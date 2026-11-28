package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A {@code ResourceHandler<ItemResource>} over a 1.21.1 {@link IItemHandler}, which has no transactions. The
 * transaction state lives in a {@link LegacyLedger} shared by all wrappers over the same inventory: insertion is
 * simulated (combined per slot) and applied on the root commit; extraction is real and restored with
 * {@code setStackInSlot} on rollback for an {@link IItemHandlerModifiable}, else simulated and applied on commit too.
 * Items the handler no longer accepts on commit go into its other slots, then drop at the block.
 *
 * <p>Wrappers over one inventory share a ledger: an {@link InvWrapper} or {@link SidedInvWrapper} (NeoForge makes a
 * new one per query) by its real container and container slot, a double chest's {@link CompoundContainer} by each
 * half; any other handler by the block it was found at ({@link LegacyLedger.Location}), or by the handler object when
 * there is no block.
 *
 * <p>Server or client thread only, like the wrapped handler.
 */
public final class LegacyItemHandler implements ResourceHandler<ItemResource> {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final LegacyLedger.Registry<ItemResource> LEDGERS = new LegacyLedger.Registry<>();
    private static final @Nullable VarHandle SIDED_INV = field(SidedInvWrapper.class, "inv", WorldlyContainer.class);
    private static final @Nullable VarHandle SIDED_SIDE = field(SidedInvWrapper.class, "side", Direction.class);
    private static final @Nullable VarHandle FIRST_HALF = field(CompoundContainer.class, "container1", Container.class);
    private static final @Nullable VarHandle SECOND_HALF = field(CompoundContainer.class, "container2", Container.class);

    private final IItemHandler handler;
    private final @Nullable Level level;
    private final @Nullable BlockPos pos;
    private final @Nullable WorldlyContainer sided;
    private final @Nullable Direction side;
    /** The ledger, or the first half's for a double chest. */
    private final LegacyLedger<ItemResource> ledger;
    /** The second half's ledger for a double chest, else null. */
    private final @Nullable LegacyLedger<ItemResource> secondLedger;
    private final int firstSize;
    private final Real real = new Real();

    /** A wrapper whose overflow drops at {@code pos}; without a level the overflow is logged as lost. */
    public LegacyItemHandler(IItemHandler handler, @Nullable Level level, @Nullable BlockPos pos) {
        this(handler, level, pos, level == null || pos == null ? handler
                : new LegacyLedger.Location(level, pos.asLong()));
    }

    public LegacyItemHandler(IItemHandler handler) {
        this(handler, null, null);
    }

    /** {@code ledgerKey}: what a handler that is not a NeoForge container wrapper shares its ledger by. */
    LegacyItemHandler(IItemHandler handler, @Nullable Level level, @Nullable BlockPos pos, Object ledgerKey) {
        this.handler = handler;
        this.level = level;
        this.pos = pos == null ? null : pos.immutable();
        Container container = null;
        WorldlyContainer sidedInv = null;
        Direction sidedSide = null;
        if (handler instanceof InvWrapper wrapper) {
            container = wrapper.getInv();
        } else if (handler instanceof SidedInvWrapper && SIDED_INV != null && SIDED_SIDE != null) {
            sidedInv = (WorldlyContainer) SIDED_INV.get(handler);
            sidedSide = (Direction) SIDED_SIDE.get(handler);
            container = sidedInv;
        }
        this.sided = sidedInv;
        this.side = sidedSide;
        if (container instanceof CompoundContainer compound && FIRST_HALF != null && SECOND_HALF != null) {
            Container first = (Container) FIRST_HALF.get(compound);
            this.ledger = LEDGERS.of(first);
            this.secondLedger = LEDGERS.of((Container) SECOND_HALF.get(compound));
            this.firstSize = first.getContainerSize();
        } else {
            this.ledger = LEDGERS.of(container != null ? container : ledgerKey);
            this.secondLedger = null;
            this.firstSize = 0;
        }
    }

    /** {@return the wrapped 1.21.1 handler} */
    public IItemHandler handler() {
        return handler;
    }

    /** The inventory's own slot behind {@code index}: the container slot for a sided wrapper, else the index. */
    private int slotOf(int index) {
        return sided != null ? SidedInvWrapper.getSlot(sided, index, side) : index;
    }

    /** The ledger slot behind {@code index}: the slot within its half for a double chest. */
    private int key(int index) {
        int slot = slotOf(index);
        return secondLedger != null && slot >= firstSize ? slot - firstSize : slot;
    }

    private LegacyLedger<ItemResource> ledger(int index) {
        return secondLedger != null && slotOf(index) >= firstSize ? secondLedger : ledger;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public ItemResource getResource(int index) {
        ItemStack stack = handler.getStackInSlot(index);
        ItemResource pending = ledger(index).pendingResource(key(index));
        if (pending == null || (!stack.isEmpty() && !pending.matches(stack))) {
            return ItemResource.of(stack);
        }
        return getAmountAsLong(index) <= 0 ? ItemResource.EMPTY : pending;
    }

    @Override
    public long getAmountAsLong(int index) {
        ItemStack stack = handler.getStackInSlot(index);
        int key = key(index);
        LegacyLedger<ItemResource> ledger = ledger(index);
        ItemResource pending = ledger.pendingResource(key);
        if (pending == null || (!stack.isEmpty() && !pending.matches(stack))) {
            return stack.getCount();
        }
        return Math.max(0, (long) stack.getCount() + ledger.pendingDelta(key, pending));
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        int limit = handler.getSlotLimit(index);
        // Same as ItemStackHandler's stack limit: the slot limit, capped by the item's own max stack size.
        return resource.isEmpty() ? limit : Math.min(limit, resource.getMaxStackSize());
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return !resource.isEmpty() && handler.isItemValid(index, resource.toStack());
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return ledger(index).insert(real, index, key(index), resource, amount, transaction);
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return ledger(index).extract(real, index, key(index), resource, amount, transaction);
    }

    /** Real, non-transactional moves on the handler, called by the ledger. */
    private final class Real implements LegacyTarget<ItemResource> {
        @Override
        public Object handler() {
            return handler;
        }

        @Override
        public boolean extractsImmediately() {
            return handler instanceof IItemHandlerModifiable;
        }

        @Override
        public int simulateExtract(int slot, ItemResource resource, int amount) {
            if (!resource.matches(handler.getStackInSlot(slot))) {
                return 0;
            }
            ItemStack extracted = handler.extractItem(slot, amount, true);
            return resource.matches(extracted) ? extracted.getCount() : 0;
        }

        @Override
        public int restore(int slot, ItemResource resource, int amount) {
            // Only reached for IItemHandlerModifiable; setStackInSlot ignores insert rules, so output slots work.
            ItemStack current = handler.getStackInSlot(slot);
            if (!current.isEmpty() && !resource.matches(current)) {
                return 0;
            }
            int limit = Math.min(handler.getSlotLimit(slot), resource.getMaxStackSize());
            int restored = Math.min(amount, limit - current.getCount());
            if (restored <= 0) {
                return 0;
            }
            ItemStack stack = resource.toStack(current.getCount() + restored);
            if (handler instanceof InvWrapper wrapper && wrapper.getInv() instanceof SimpleContainer simple) {
                // A rollback is not a change: skip SimpleContainer's setChanged listeners where we can.
                simple.getItems().set(slot, stack);
            } else {
                ((IItemHandlerModifiable) handler).setStackInSlot(slot, stack);
            }
            return restored;
        }

        @Override
        public int simulateInsert(int slot, ItemResource resource, int amount) {
            ItemStack remainder = handler.insertItem(slot, resource.toStack(amount), true);
            return amount - remainder.getCount();
        }

        @Override
        public int insert(int slot, ItemResource resource, int amount) {
            int left = amount;
            while (left > 0) {
                int chunk = Math.min(left, resource.getMaxStackSize());
                ItemStack remainder = handler.insertItem(slot, resource.toStack(chunk), false);
                int accepted = chunk - remainder.getCount();
                left -= accepted;
                if (accepted < chunk) {
                    break;
                }
            }
            return amount - left;
        }

        @Override
        public int insertAnywhere(ItemResource resource, int amount) {
            int inserted = 0;
            for (int slot = 0, n = handler.getSlots(); slot < n && inserted < amount; slot++) {
                inserted += insert(slot, resource, amount - inserted);
            }
            return inserted;
        }

        @Override
        public int extract(int slot, ItemResource resource, int amount) {
            int taken = 0;
            while (taken < amount && resource.matches(handler.getStackInSlot(slot))) {
                ItemStack simulated = handler.extractItem(slot, amount - taken, true);
                if (simulated.isEmpty() || !resource.matches(simulated)) {
                    break;
                }
                ItemStack extracted = handler.extractItem(slot, simulated.getCount(), false);
                if (extracted.isEmpty()) {
                    break;
                }
                taken += extracted.getCount();
            }
            return taken;
        }

        @Override
        public void overflow(ItemResource resource, int amount) {
            if (level == null || pos == null || level.isClientSide()) {
                LOGGER.error("{} x {} could not go back into {} and there is no block to drop them at; lost", amount,
                        resource, handler);
                return;
            }
            int left = amount;
            while (left > 0) {
                int chunk = Math.min(left, resource.getMaxStackSize());
                Block.popResource(level, pos, resource.toStack(chunk));
                left -= chunk;
            }
        }
    }

    @Override
    public String toString() {
        return "LegacyItemHandler{" + handler + "}";
    }

    private static @Nullable VarHandle field(Class<?> owner, String name, Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, name, type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Cannot read {}.{}; those wrappers share a ledger only by block", owner.getSimpleName(), name,
                    e);
            return null;
        }
    }
}
