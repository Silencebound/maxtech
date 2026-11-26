package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.OperationCost;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.terminal.TerminalStatus;
import dev.silencebound.maxtech.storage.terminal.filter.CarriedViewFilters;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: the battery is journaled in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: player transfers run in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A portable cell opened in the player's hand or another carried slot ({@link CarriedSlot}): the {@link TerminalSource}
 * of the item terminal's GUI over the cell's own contents (PRD 7.2, the cell builder). Opening mounts the cell from
 * {@link CellStorage}, as a drive would, so a copy of the item cannot be used at the same time; closing the GUI
 * unmounts it and writes its stats into the item. Every insert and extract costs FE from the cell's battery, the same
 * amounts as a network operation, paid in the transaction. One source per opened GUI. Server thread.
 */
public final class PortableCellSource implements TerminalSource {
    private final ServerPlayer player;
    private final CarriedSlot slot;
    private final CellStorage storage;
    private final CellInventory cell;
    private final Spending spending = new Spending();
    private final ObjectArrayList<TerminalMenu> menus = new ObjectArrayList<>();
    private boolean closed;
    /** The filter strip, read from the item when the menu first asks. */
    private @Nullable CarriedViewFilters filters;

    private PortableCellSource(ServerPlayer player, CarriedSlot slot, CellStorage storage, CellInventory cell) {
        this.player = player;
        this.slot = slot;
        this.storage = storage;
        this.cell = cell;
        cell.setListener((key, delta) -> {
            int id = cell.keyId(key);
            long total = cell.amount(id);
            for (int i = 0; i < menus.size(); i++) {
                menus.get(i).changed(id, total);
            }
        });
    }

    /**
     * Mounts the portable cell in {@code player}'s {@code hand}, giving it an id first if it never had one. Returns
     * {@code null}, changing nothing, when the hand holds no portable cell, its type is unknown, or its contents are in
     * use elsewhere (a copy of the item in a drive or open in another hand).
     */
    public static @Nullable PortableCellSource open(ServerPlayer player, InteractionHand hand) {
        return open(player, CarriedSlot.hand(player, hand));
    }

    /** Mounts the portable cell in {@code slot}; as {@link #open(ServerPlayer, InteractionHand)} for a hand. */
    public static @Nullable PortableCellSource open(ServerPlayer player, CarriedSlot slot) {
        ItemStack stack = slot.get();
        CellData data = StorageCellItem.data(stack);
        CellType type = data == null ? null : CellTypes.byId(data.type());
        if (!(stack.getItem() instanceof PortableCellItem item) || data == null || type == null) {
            return null;
        }
        CellStorage storage = CellStorage.get(player.level().getServer());
        if (!data.hasId()) {
            data = data.withId(storage.newId());
            stack.set(ModDataComponents.CELL.get(), data);
        }
        CellInventory inventory = storage.mount(data.id(), type, item.casing(), data, () -> {
        });
        if (inventory == null) {
            return null;
        }
        return new PortableCellSource(player, slot, storage, inventory);
    }

    /** The mounted cell; tests use it. */
    public CellInventory cell() {
        return cell;
    }

    /**
     * FE the battery can still pay: what the item holds now (a charger may have added some while the GUI is open) less
     * what the open transaction has spent.
     */
    public long energy() {
        ItemStack stack = held();
        return stack.isEmpty() ? 0 : Math.max(0, PortableCellItem.energy(stack) - spending.spent);
    }

    /** The stack in its slot while it is still this cell; empty once it was moved away. */
    private ItemStack held() {
        ItemStack stack = slot.get();
        CellData data = StorageCellItem.data(stack);
        return stack.getItem() instanceof PortableCellItem && data != null && data.id() == cell.id()
                ? stack
                : ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player viewer) {
        CarriedViewFilters strip = filters;
        return !closed && viewer.getUUID().equals(player.getUUID()) && player.isAlive() && !held().isEmpty()
                && (strip == null || strip.usable());
    }

    /**
     * The view filters kept in the cell item (#322). Once they were read, the menu stays valid only while the very
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
        return viewer.getUUID().equals(player.getUUID());
    }

    @Override
    public Int2LongMap contents() {
        return cell.contents();
    }

    @Override
    public @Nullable StackKey key(int id) {
        return cell.key(id);
    }

    @Override
    public long amount(int id) {
        return cell.amount(id);
    }

    /** Stores into the cell; a portable cell never goes into one (it would hold itself). */
    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        if (key instanceof ItemKey item && item.item() instanceof PortableCellItem) {
            return 0;
        }
        return operate(true, key, amount, transaction);
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        return operate(false, key, amount, transaction);
    }

    /** One operation paid from the battery, as {@code NetworkState}'s player operations are paid from the network. */
    private long operate(boolean insert, StackKey key, long amount, TransactionContext transaction) {
        if (closed || amount <= 0) {
            return 0;
        }
        boolean fluid = key.kind() == StackKey.Kind.FLUID;
        long perOperation = StorageServerConfig.ENERGY_PER_OPERATION.getAsLong();
        long perUnit = StorageServerConfig.ENERGY_PER_UNIT.getAsLong();
        int mbPerUnit = StorageServerConfig.ENERGY_FLUID_MB_PER_UNIT.getAsInt();
        long limit = Math.min(amount, OperationCost.affordable(fluid, energy(), perOperation, perUnit, mbPerUnit));
        if (limit <= 0) {
            return 0;
        }
        try (Transaction nested = Transaction.open(transaction)) {
            long moved = insert ? cell.insert(key, limit, nested) : cell.extract(key, limit, nested);
            if (moved <= 0 || !spending.tryPay(OperationCost.energyCost(fluid, moved, perOperation, perUnit, mbPerUnit),
                    nested)) {
                return 0;
            }
            nested.commit();
            return moved;
        }
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // an open menu is one object
    public void watch(TerminalMenu menu) {
        for (int i = 0; i < menus.size(); i++) {
            if (menus.get(i) == menu) {
                return;
            }
        }
        menus.add(menu);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // as in watch
    public void unwatch(TerminalMenu menu) {
        menus.removeIf(open -> open == menu);
    }

    /** The GUI closed: the cell leaves use and the item gets its current stats. */
    @Override
    public void closed(TerminalMenu menu) {
        unwatch(menu);
        release();
    }

    /**
     * Unmounts the cell and writes its stats into the item, once; after that the source refuses everything. Also for a
     * GUI that never opened.
     */
    public void release() {
        if (closed) {
            return;
        }
        closed = true;
        ItemStack stack = held();
        CellData data = StorageCellItem.data(stack);
        if (data != null) {
            stack.set(ModDataComponents.CELL.get(),
                    data.withStats(cell.usedBytes(), cell.totalBytes(), cell.kinds(), cell.keptKinds()));
        }
        storage.unmount(cell);
    }

    @Override
    public int status(int field) {
        return switch (field) {
            case TerminalMenu.STATUS -> (energy() > 0 ? TerminalStatus.ONLINE : TerminalStatus.NO_POWER).index();
            case TerminalMenu.ENERGY -> (int) Math.min(Integer.MAX_VALUE, energy());
            case TerminalMenu.CAPACITY -> PortableCellItem.capacity();
            case TerminalMenu.KINDS -> cell.kinds();
            default -> 0;
        };
    }

    /**
     * FE spent in the open transaction, journaled so a rollback gives it back. Only the spending is kept here; the
     * charge stays in the item, and the root commit takes the spent FE off whatever the item holds then, so FE a
     * charger added meanwhile is kept.
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
                ItemEnergy.setInt(stack, ModDataComponents.PORTABLE_ENERGY.get(),
                        (int) Math.max(0, PortableCellItem.energy(stack) - spent));
            }
            spent = 0;
        }
    }
}
