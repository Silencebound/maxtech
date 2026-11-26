package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.terminal.NetworkTerminal;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.terminal.TerminalStatus;
import dev.silencebound.maxtech.storage.terminal.filter.CarriedViewFilters;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: the terminal's charge is journaled in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A wireless terminal opened in the player's hand or another carried slot ({@link CarriedSlot}): the network's
 * {@link NetworkTerminal} over the bound core, with every insert and extract also paying
 * {@code signal.wireless.energyPerOperation} FE from the terminal's own charge, in the transaction. Once the charge
 * cannot pay an operation the GUI stays open but moves nothing (it shows "no power"); a charger topping the item up
 * while the GUI is open is seen at once. Opened from another dimension (through the network's dimension bridge), every
 * operation also pays the bridge's surcharge from the network's FE, in the same transaction. One source per opened GUI.
 * Server thread.
 */
public final class WirelessTerminalSource implements TerminalSource {
    private final NetworkTerminal network;
    private final ServerPlayer player;
    private final CarriedSlot slot;
    private final GlobalPos link;
    private final ControllerBlockEntity core;
    private final Spending spending = new Spending();
    /** The filter strip, read from the item when a menu first asks; sources without a menu never read it. */
    private @Nullable CarriedViewFilters filters;

    WirelessTerminalSource(NetworkTerminal network, ServerPlayer player, CarriedSlot slot, GlobalPos link,
            ControllerBlockEntity core) {
        this.network = network;
        this.player = player;
        this.slot = slot;
        this.link = link;
        this.core = core;
    }

    /** FE the terminal can still pay: what the item holds now less what the open transaction has spent. */
    public long energy() {
        ItemStack stack = held();
        return stack.isEmpty() ? 0 : Math.max(0, WirelessTerminalItem.energy(stack) - spending.spent);
    }

    /** The stack in the slot while it is still a terminal bound to this network; empty once it was moved away. */
    private ItemStack held() {
        ItemStack stack = slot.get();
        return stack.getItem() instanceof WirelessTerminalItem
                && link.equals(stack.get(ModDataComponents.WIRELESS_LINK.get())) ? stack : ItemStack.EMPTY;
    }

    /**
     * The bound network, so the terminal's crafting session (requests, the jobs tab) works over the wireless terminal
     * too (B09 item 23); without it the session saw no network and refused every request.
     */
    @Override
    public @Nullable NetworkState network() {
        return network.network();
    }

    @Override
    public boolean stillValid(Player viewer) {
        CarriedViewFilters strip = filters;
        return network.stillValid(viewer) && (strip == null || strip.usable());
    }

    /**
     * The view filters kept in the terminal item (#322). Once they were read, the menu stays valid only while the very
     * stack they were read from stays in its slot, so a filter cannot be moved while the item is elsewhere.
     */
    @Override
    public CarriedViewFilters viewFilters() {
        CarriedViewFilters strip = filters;
        if (strip == null) {
            strip = new CarriedViewFilters(slot);
            filters = strip;
        }
        return strip;
    }

    @Override
    public boolean canAccess(Player viewer) {
        return network.canAccess(viewer);
    }

    @Override
    public boolean canDeposit(Player viewer) {
        return network.canDeposit(viewer);
    }

    @Override
    public boolean canTake(Player viewer) {
        return network.canTake(viewer);
    }

    @Override
    public Int2LongMap contents() {
        return network.contents();
    }

    @Override
    public @Nullable StackKey key(int id) {
        return network.key(id);
    }

    @Override
    public long amount(int id) {
        return network.amount(id);
    }

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        return operate(true, key, amount, transaction);
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        return operate(false, key, amount, transaction);
    }

    @SuppressWarnings("ReferenceEquality") // levels are compared by identity
    private long operate(boolean insert, StackKey key, long amount, TransactionContext transaction) {
        long cost = WirelessTerminalItem.energyPerOperation();
        if (amount <= 0 || cost > energy()) {
            return 0;
        }
        try (Transaction nested = Transaction.open(transaction)) {
            long moved = insert ? network.insert(key, amount, nested) : network.extract(key, amount, nested);
            if (moved <= 0 || !spending.tryPay(cost, nested)) {
                return 0;
            }
            NetworkState bridged = core.network();
            if (core.getLevel() != player.level() && (bridged == null || !DimensionBridge.pay(bridged, nested))) {
                return 0;
            }
            nested.commit();
            return moved;
        }
    }

    /**
     * One operation's FE from the terminal's charge, and the dimension bridge's surcharge from another dimension, as
     * for an insert or extract (B09 item 23: crafting requests, locate and overview changes through the wireless
     * terminal).
     */
    @Override
    @SuppressWarnings("ReferenceEquality") // levels are compared by identity
    public boolean payAction(TransactionContext transaction) {
        if (!spending.tryPay(WirelessTerminalItem.energyPerOperation(), transaction)) {
            return false;
        }
        NetworkState bridged = core.network();
        return core.getLevel() == player.level() || (bridged != null && DimensionBridge.pay(bridged, transaction));
    }

    @Override
    public void watch(TerminalMenu menu) {
        network.watch(menu);
    }

    @Override
    public void unwatch(TerminalMenu menu) {
        network.unwatch(menu);
    }

    @Override
    public void closed(TerminalMenu menu) {
        network.closed(menu);
    }

    @Override
    public int status(int field) {
        if (field == TerminalMenu.STATUS && energy() < WirelessTerminalItem.energyPerOperation()) {
            return TerminalStatus.NO_POWER.index();
        }
        return network.status(field);
    }

    /**
     * FE spent in the open transaction, journaled so a rollback gives it back; the root commit takes it off whatever
     * the item holds then, so FE a charger added meanwhile is kept.
     */
    private final class Spending extends SnapshotJournal<long[]> {
        long spent;

        boolean tryPay(long cost, TransactionContext transaction) {
            if (cost > energy()) {
                return false;
            }
            if (cost > 0) {
                updateSnapshots(transaction);
                spent += cost;
            }
            return true;
        }

        @Override
        protected long[] createSnapshot() {
            return new long[]{spent};
        }

        @Override
        protected void revertToSnapshot(long[] snapshot) {
            spent = snapshot[0];
        }

        @Override
        protected void onRootCommit(long[] originalState) {
            ItemStack stack = held();
            if (!stack.isEmpty() && spent > 0) {
                ItemEnergy.setInt(stack, ModDataComponents.WIRELESS_ENERGY.get(),
                        (int) Math.max(0, WirelessTerminalItem.energy(stack) - spent));
            }
            spent = 0;
        }
    }
}
