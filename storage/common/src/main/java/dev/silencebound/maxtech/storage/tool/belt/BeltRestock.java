package dev.silencebound.maxtech.storage.tool.belt;

import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkReach;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: refills run in a NeoForge transaction, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The restock belt's refills (M6e): once every {@code tools.belt.intervalTicks} (20 by default), at the end of the
 * server tick, each online player's first restock belt tops its marked slots up to their counts from the bound network.
 * A slot holding another item is left alone. Across dimensions each refilled slot pays the bridge's surcharge. Needs
 * full access, as taking out at a terminal does. Between passes the handler costs one modulo per tick; a pass walks
 * each player's inventory (and accessory slots, {@link CarriedItems}) once to find the belt. Server thread.
 */
public final class BeltRestock {
    private BeltRestock() {
    }

    /** Registers the pass on the server tick; once, during mod construction. */
    public static void init() {
        Platform.get().lifecycle().onServerTickEnd(BeltRestock::tick);
    }

    private static void tick(MinecraftServer server) {
        int interval = StorageServerConfig.BELT_INTERVAL_TICKS.getAsInt();
        if (server.getTickCount() % interval != 0) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            ItemStack belt = beltOf(player);
            if (!belt.isEmpty()) {
                restock(player, belt);
            }
        }
    }

    /**
     * The first restock belt {@code player} carries: main slots, then armour and offhand, then accessory slots of other
     * mods ({@link CarriedItems}); empty for none.
     */
    public static ItemStack beltOf(Player player) {
        return CarriedItems.find(player, RestockBeltItem.class);
    }

    /**
     * Tops up every slot {@code belt} marks in {@code player}'s inventory from the belt's network; returns the items
     * moved, 0 when the network is out of reach or nothing was missing.
     */
    public static int restock(ServerPlayer player, ItemStack belt) {
        BeltMarks marks = RestockBeltItem.marks(belt);
        if (marks.marks().isEmpty() || player.isSpectator()) {
            return 0;
        }
        NetworkState network = UplinkReach.network(player, belt, AccessLevel.FULL);
        if (network == null) {
            return 0;
        }
        boolean crossing = UplinkReach.crossing(player, belt);
        Inventory inventory = player.getInventory();
        int moved = 0;
        List<BeltMarks.Mark> list = marks.marks();
        for (int i = 0; i < list.size(); i++) {
            BeltMarks.Mark mark = list.get(i);
            ItemStack current = inventory.getItem(mark.slot());
            if (!current.isEmpty() && !(current.is(mark.key().item())
                    && current.getComponentsPatch().equals(mark.key().components()))) {
                continue;
            }
            int limit = Math.min(mark.count(),
                    current.isEmpty() ? mark.key().item().getDefaultMaxStackSize() : current.getMaxStackSize());
            int need = limit - current.getCount();
            if (need <= 0) {
                continue;
            }
            long taken;
            try (Transaction transaction = Transaction.openRoot()) {
                taken = network.extract(mark.key(), need, transaction, player);
                if (taken > 0 && crossing && !DimensionBridge.pay(network, transaction)) {
                    // The bridge's surcharge is unpaid: the slot stays as it is (the transaction rolls back).
                    taken = 0;
                } else {
                    transaction.commit();
                }
            }
            if (taken <= 0) {
                continue;
            }
            if (current.isEmpty()) {
                inventory.setItem(mark.slot(), mark.key().toStack((int) taken));
            } else {
                current.grow((int) taken);
            }
            moved += (int) taken;
        }
        if (moved > 0) {
            inventory.setChanged();
        }
        return moved;
    }
}
