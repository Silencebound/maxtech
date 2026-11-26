package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.NetworkActor;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
// PLATFORM-LEAK: the pick runs in a NeoForge transaction, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Pick block from the network (M7C item 7): a survival player who middle-clicks a block they do not carry, while
 * carrying a bound wireless terminal, gets one stack of that block's item from the terminal's network into the hotbar.
 * The client sends {@link Pick} with the aimed block when it sees the pick key on a block and a wireless terminal among
 * the carried items; vanilla's own pick packet still goes and does what it does (it selects the stack once it is
 * there). The server checks everything again: the block within interaction reach and loaded, the item not carried
 * already, the terminal reaching its network by the wireless terminal's rules (research, coverage, charge, dimension
 * bridge), and the player's right to take out; then it takes the stack in one transaction through the terminal's
 * source, which pays the terminal's FE, and puts what came out into a free hotbar slot (or the suitable one, moving its
 * stack to a free inventory slot), selecting it. With no room for that, nothing is taken. One pick per
 * {@link #PICK_TICKS} per player. Server thread.
 */
public final class NetworkPick {
    /** Fewest ticks between two picks of one player; a bound against a client that keeps sending. */
    static final int PICK_TICKS = 4;
    private static final Map<Player, Long> LAST = new WeakHashMap<>();

    /** What a pick did. */
    public enum Outcome {
        /** A stack came from the network. */
        PICKED,
        /** Not for this player now: creative, spectator, too soon, out of reach, unloaded or an item-less block. */
        IGNORED,
        /** The player carries the item already; vanilla's pick selects it. */
        CARRIED,
        /** No wireless terminal carried, or it does not reach its network now. */
        NO_TERMINAL,
        /** The network's security does not let the player take out. */
        DENIED,
        /** The hotbar has no slot to put it in. */
        NO_ROOM,
        /** The network holds none of the item (or the terminal could not pay). */
        NOT_STORED
    }

    /** Client to server: pick the block at {@code pos} from the network. */
    public record Pick(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(MaxtechStorage.id("network_pick"));
        public static final StreamCodec<ByteBuf, Pick> STREAM_CODEC = BlockPos.STREAM_CODEC.map(Pick::new, Pick::pos);

        @Override
        public Type<Pick> type() {
            return TYPE;
        }
    }

    private NetworkPick() {
    }

    /** Registers the payload; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Pick.TYPE, Pick.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer serverPlayer) {
                        pick(serverPlayer, payload.pos());
                    }
                });
    }

    /** Client side: sends a pick of the block at {@code pos}. */
    public static void send(BlockPos pos) {
        Platform.get().network().sendToServer(new Pick(pos));
    }

    /** Picks the block at {@code pos} from the network for {@code player}; see the class comment. */
    public static Outcome pick(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        // The server tick count, not the level's time, so a change of dimension cannot reset or extend the wait.
        long now = level.getServer().getTickCount();
        Long last = LAST.get(player);
        if (player.hasInfiniteMaterials() || player.isSpectator() || (last != null && now - last < PICK_TICKS)
                || !player.isWithinBlockInteractionRange(pos, 1.0) || !level.isLoaded(pos)) {
            return Outcome.IGNORED;
        }
        LAST.put(player, now);
        BlockState state = level.getBlockState(pos);
        ItemStack clone = state.getCloneItemStack(pos, level, false, player);
        if (clone.isEmpty()) {
            return Outcome.IGNORED;
        }
        Inventory inventory = player.getInventory();
        if (inventory.findSlotMatchingItem(clone) != -1) {
            return Outcome.CARRIED;
        }
        Optional<CarriedSlot> slot = CarriedItems.locate(player, WirelessTerminalItem.class);
        WirelessTerminalSource source = slot.isEmpty() ? null : WirelessTerminalItem.source(player, slot.get());
        if (source == null) {
            return Outcome.NO_TERMINAL;
        }
        if (!source.canAccess(player) || !source.canTake(player)) {
            return Outcome.DENIED;
        }
        int target = inventory.getSuitableHotbarSlot();
        int free = inventory.getItem(target).isEmpty() ? -1 : inventory.getFreeSlot();
        if (!inventory.getItem(target).isEmpty() && free < 0) {
            return Outcome.NO_ROOM;
        }
        ItemKey key = ItemKey.of(clone);
        long taken;
        UUID previous = NetworkActor.begin(player);
        try (Transaction transaction = Transaction.openRoot()) {
            taken = source.extract(key, clone.getMaxStackSize(), transaction);
            transaction.commit();
        } finally {
            NetworkActor.end(previous);
        }
        if (taken <= 0) {
            return Outcome.NOT_STORED;
        }
        if (free >= 0) {
            inventory.setItem(free, inventory.getItem(target));
        }
        inventory.setItem(target, key.toStack((int) taken));
        inventory.setSelectedSlot(target);
        if (player.connection != null) {
            player.connection.send(new ClientboundSetHeldSlotPacket(target));
        }
        player.inventoryMenu.broadcastChanges();
        return Outcome.PICKED;
    }
}
