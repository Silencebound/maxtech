package dev.silencebound.maxtech.lib.neoforge.api.inventory;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.inventory.SlotRole;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * The NeoForge handlers of Lib's inventories, for capability registration:
 *
 * <pre>{@code
 * event.registerBlockEntity(Capabilities.Item.BLOCK, LAB_HUB.get(), (hub, side) -> Handlers.items(hub.slots()));
 * event.registerBlockEntity(Capabilities.Fluid.BLOCK, LAB_HUB.get(), (hub, side) -> Handlers.fluids(hub.tank()));
 * }</pre>
 *
 * The handlers are Lib's own and take part in transfer transactions: what a pipe or hopper moves is journaled and
 * rolled back with the transaction, and the inventory's change listener runs once the root transaction commits.
 * {@link #items} applies the slots' {@link SlotRole}s (inputs take what their filter accepts, outputs give, display
 * slots do neither; every slot reports its real capacity, so comparators read a full output slot as full, and one that
 * takes nothing refuses in {@code isValid} and {@code insert}; slots holding an unknown item take nothing and give
 * nothing). {@link #fluids} takes and gives what the tank's filter allows. Each returns the same handler for the same
 * inventory every time. Server thread, like the inventories.
 * <p>
 * A machine's own step (take the inputs, put out the results) uses {@link #machineItems} and {@link #machineTank}
 * instead, inside one transaction, so it commits or rolls back as a whole. The direct methods of {@code ItemSlots} and
 * {@code ResourceTank} are each their own committed step and throw while a transaction is open; the machine views do
 * not, and like those methods they ignore the roles (a machine puts results into its output slots) but keep every
 * slot's filter and limit and a tank's filter and capacity. Their changes are journaled like a pipe's: a rolled-back
 * transaction restores every stack and tank exactly, and the change listener runs only when the root commits, once for
 * each slot or tank the transaction touched (NeoForge reports every touched one, even one left as it was). A slot
 * holding an unknown item takes and gives nothing, an unknown tank takes nothing and reads as empty, and
 * {@code ItemSlots.overflow()} is never reached, so nothing Lib keeps for a removed mod is overwritten or lost.
 */
public final class Handlers {
    private static volatile @Nullable Bridge bridge;

    private Handlers() {
    }

    /** The item handler of {@code slots}, which must come from {@link ItemSlots#builder}. */
    public static ResourceHandler<ItemResource> items(ItemSlots slots) {
        return bridge().items(slots);
    }

    /** The fluid handler of {@code tank}, which must come from {@link FluidTank#create}. */
    public static ResourceHandler<FluidResource> fluids(FluidTank tank) {
        return bridge().fluids(tank);
    }

    /**
     * The machine's item handler over {@code slots}, which must come from {@link ItemSlots#builder}: every slot by its
     * index, roles not applied, filters and limits kept; display slots are reachable too, overflow is not. The same
     * instance for the same slots. Experimental until Lib's machine core ({@code MachineProcess}, ADR-0019) settles it.
     */
    @ApiStatus.Experimental
    public static ResourceHandler<ItemResource> machineItems(ItemSlots slots) {
        Objects.requireNonNull(slots, "slots");
        return bridge().machineItems(slots);
    }

    /**
     * The machine's transactional view of {@code tank}, which must come from Lib's tank factories, keeping its filter
     * and capacity. The same instance for the same tank. Experimental, like {@link #machineItems}.
     */
    @ApiStatus.Experimental
    public static <K> MachineTank<K> machineTank(ResourceTank<K> tank) {
        Objects.requireNonNull(tank, "tank");
        return bridge().machineTank(tank);
    }

    /**
     * Puts every stack of {@code stacks} into the slots {@code from} (inclusive) to {@code to} (exclusive) of
     * {@code handler}, each first onto slots already holding the same item and components, then into empty ones, in
     * slot order. All or nothing: runs in a transaction nested in {@code transaction} and returns false, with nothing
     * moved, if any stack does not fit whole (a filter refuses it, limits leave too little room); true once all went
     * in. Empty stacks are skipped, so an empty list returns true. The stacks themselves are not changed. Throws
     * {@link IndexOutOfBoundsException} for a range outside the handler. Experimental, like {@link #machineItems}.
     */
    @ApiStatus.Experimental
    public static boolean insertAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
            TransactionContext transaction) {
        return bridge().insertAll(handler, from, to, stacks, transaction);
    }

    /**
     * Takes every stack of {@code stacks} (its item, components and count) out of the slots {@code from} (inclusive) to
     * {@code to} (exclusive) of {@code handler}, from the first slot holding it on, summing over slots. All or nothing,
     * like {@link #insertAll}: false with nothing taken if any stack is short. Empty stacks are skipped. The stacks
     * themselves are not changed. Throws {@link IndexOutOfBoundsException} for a range outside the handler.
     * Experimental, like {@link #machineItems}.
     */
    @ApiStatus.Experimental
    public static boolean extractAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
            TransactionContext transaction) {
        return bridge().extractAll(handler, from, to, stacks, transaction);
    }

    /** Sets Lib's bridge; Lib calls it once during its construction, and a second call throws. */
    @ApiStatus.Internal
    public static synchronized void install(Bridge source) {
        if (bridge != null) {
            throw new IllegalStateException("A handler bridge is already installed");
        }
        bridge = source;
    }

    private static Bridge bridge() {
        Bridge source = bridge;
        if (source == null) {
            throw new IllegalStateException("MaxTech Lib has not set up its handlers yet");
        }
        return source;
    }

    /** Lib's side of {@link Handlers}. */
    @ApiStatus.Internal
    public interface Bridge {
        /** See {@link Handlers#items}. */
        ResourceHandler<ItemResource> items(ItemSlots slots);

        /** See {@link Handlers#fluids}. */
        ResourceHandler<FluidResource> fluids(FluidTank tank);

        /** See {@link Handlers#machineItems}. */
        ResourceHandler<ItemResource> machineItems(ItemSlots slots);

        /** See {@link Handlers#machineTank}. */
        <K> MachineTank<K> machineTank(ResourceTank<K> tank);

        /** See {@link Handlers#insertAll}. */
        boolean insertAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
                TransactionContext transaction);

        /** See {@link Handlers#extractAll}. */
        boolean extractAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
                TransactionContext transaction);
    }

    /**
     * A machine's transactional view of one {@link ResourceTank} ({@link #machineTank}): moves take part in the given
     * transaction, keep the tank's filter and capacity, and are reported to the tank's change listener when the root
     * commits. Read the contents through the tank itself. Implemented by Lib only; server thread only.
     *
     * @param <K>
     *            the key type of the tank's kind
     */
    @ApiStatus.Experimental
    @ApiStatus.NonExtendable
    public interface MachineTank<K> {
        /**
         * Puts up to {@code amount} of {@code key} in if the filter takes it and the tank is empty or holds
         * {@code key}; returns how much went in (0 for an amount of 0 or less, and while the tank is unknown or over
         * its capacity).
         */
        long insert(K key, long amount, TransactionContext transaction);

        /**
         * Takes up to {@code amount} of {@code key} out if the tank holds it; returns how much came out (0 for an
         * amount of 0 or less, and while the tank is unknown, which reads as empty).
         */
        long extract(K key, long amount, TransactionContext transaction);

        /**
         * Puts exactly {@code amount} of {@code key} in, or nothing: false, in a nested transaction rolled back, if
         * less fits. True for an amount of 0 or less.
         */
        boolean insertExactly(K key, long amount, TransactionContext transaction);

        /**
         * Takes exactly {@code amount} of {@code key} out, or nothing: false, in a nested transaction rolled back, if
         * the tank holds less. True for an amount of 0 or less.
         */
        boolean extractExactly(K key, long amount, TransactionContext transaction);
    }
}
