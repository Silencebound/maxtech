package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: NeoForge's transfer types; Lib's handlers of the tank and slots are NeoForge handlers.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;

/**
 * Everything the lab hub holds besides energy, on Lib's inventory api: the nutrient tank ({@link #tank()}) and one
 * {@link ItemSlots} with the fuel slot ({@link #FUEL}), the output buffer ({@link #OUTPUT_FIRST} on,
 * {@link #OUTPUT_SLOTS} slots, take-only) and the sampler charging slot ({@link #SAMPLER}), with their save format and
 * the views other mods get. Server thread only.
 *
 * <p>
 * Stacks a save held for no slot (the overflow of {@link #slots()}) stay in the hub's save and its dropped item and are
 * never handed out in production; unknown items and an unknown tank are kept the same way. The tank's capacity is fixed
 * when the storage is made: a changed {@code hub.tankCapacity} applies to hubs placed afterwards (a screen shows
 * {@link FluidTank#capacity()}, never the config).
 */
public final class HubStorage {
    /** The fuel slot: any furnace fuel. */
    public static final int FUEL = 0;
    /** The first output slot. */
    public static final int OUTPUT_FIRST = 1;
    /** Output slots: three rows. */
    public static final int OUTPUT_SLOTS = 27;
    /** The sampler charging slot: one sampler. */
    public static final int SAMPLER = OUTPUT_FIRST + OUTPUT_SLOTS;
    /** All slots. */
    public static final int SIZE = SAMPLER + 1;

    private static volatile Supplier<@Nullable FuelValues> clientFuels = () -> null;
    /**
     * The slots' shape, from which a client menu builds its empty copy; its fuel filter asks the client's furnace fuels
     * ({@link #setClientFuels}). The hub's own slots replace that filter with one over its level.
     */
    public static final ItemSlots.Builder SHAPE = shape(stack -> {
        FuelValues fuels = clientFuels.get();
        return fuels != null && burnTime(fuels, stack) > 0;
    });

    /** Told when the contents change; fuel, output and sampler changes separately, since they can start work. */
    public interface Listener {
        /** Anything changed (after the root transaction committed, or at once for the machine's own changes). */
        void changed();

        /** The tank changed. */
        void tankChanged();

        /** The fuel slot changed. */
        void fuelChanged();

        /** The output buffer changed: room may have opened for a fuel remainder. */
        void outputChanged();

        /** The sampler slot changed. */
        void samplerChanged();
    }

    private final ItemSlots slots;
    private final FluidTank tank;
    private @Nullable ResourceHandler<FluidResource> fluidHandler;
    private @Nullable HubItemHandler itemHandler;

    /** Storage for a hub in {@code level} (null while the block entity has none); public for GameTests. */
    @VisibleForTesting
    public HubStorage(Supplier<@Nullable Level> level, Listener listener) {
        slots = shape(stack -> {
            Level current = level.get();
            return current != null && burnTime(current.fuelValues(), stack) > 0;
        }).build(slot -> slotChanged(listener, slot));
        tank = FluidTank.create(FarmsServerConfig.hubTankCapacity(), () -> {
            listener.changed();
            listener.tankChanged();
        });
    }

    private static ItemSlots.Builder shape(Predicate<ItemStack> fuel) {
        return ItemSlots.builder(SIZE).input(FUEL, FUEL + 1, fuel).output(OUTPUT_FIRST, SAMPLER)
                .input(SAMPLER, SIZE, stack -> stack.getItem() instanceof SamplerItem).limit(SAMPLER, SIZE, 1);
    }

    private static void slotChanged(Listener listener, int slot) {
        listener.changed();
        // -1 is the overflow list (stacks a save held for no slot): nothing there starts work.
        if (slot == FUEL) {
            listener.fuelChanged();
        } else if (slot == SAMPLER) {
            listener.samplerChanged();
        } else if (slot >= OUTPUT_FIRST) {
            listener.outputChanged();
        }
    }

    /**
     * Where the fuel filter of {@link #SHAPE} finds the client's furnace fuels; Farms' client glue sets it once. Until
     * then (and on a dedicated server, where the shape builds no menu) the client copy takes no fuel by itself, which
     * only costs a click its client-side prediction.
     */
    public static void setClientFuels(Supplier<@Nullable FuelValues> source) {
        clientFuels = Objects.requireNonNull(source, "source");
    }

    /** The nutrient tank, for any fluid; capacity {@code hub.tankCapacity} when the hub was made. */
    public FluidTank tank() {
        return tank;
    }

    /** The fuel slot, output buffer and sampler slot; see the slot constants. */
    public ItemSlots slots() {
        return slots;
    }

    /** Lib's handler of the tank: what the fluid capability, hand use and the fluid ports move fluid through. */
    public ResourceHandler<FluidResource> fluidHandler() {
        ResourceHandler<FluidResource> handler = fluidHandler;
        if (handler == null) {
            handler = HubHandlers.fluids(tank);
            fluidHandler = handler;
        }
        return handler;
    }

    /** The item capability: fuel goes in, output comes out; the sampler slot is not part of it. */
    public HubItemHandler itemHandler() {
        HubItemHandler handler = itemHandler;
        if (handler == null) {
            handler = new HubItemHandler(HubHandlers.items(slots));
            itemHandler = handler;
        }
        return handler;
    }

    /** Whether tank, slots and the slots' overflow are all empty. */
    public boolean isEmpty() {
        if (tank.amount() > 0 || tank.unknown() || !slots.overflow().isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < SIZE; slot++) {
            if (!slots.stack(slot).isEmpty() || slots.unknown(slot)) {
                return false;
            }
        }
        return true;
    }

    /** The sampler in the charging slot, possibly empty; live, read only. */
    public ItemStack sampler() {
        return slots.stack(SAMPLER);
    }

    /** Whether the fuel slot holds something that burns. */
    boolean hasFuel(Level current) {
        ItemStack stack = slots.stack(FUEL);
        return !stack.isEmpty() && burnTime(current.fuelValues(), stack) > 0;
    }

    /** Ticks {@code stack} burns in a furnace, from NeoForge's burn time hook (data map and item overrides). */
    static int burnTime(FuelValues fuels, ItemStack stack) {
        // PLATFORM-LEAK: NeoForge's patched ItemStack#getBurnTime, which vanilla's deprecated FuelValues lookup skips.
        return stack.getBurnTime(RecipeType.SMELTING, fuels);
    }

    /**
     * Takes one fuel item out of the fuel slot and returns its burn time, or 0 when nothing burns. Its remainder (an
     * empty bucket) goes into the output buffer, so pipes can take it; when it does not fit, nothing is taken: if the
     * buffer changed under the extraction and the remainder no longer fits, the fuel is put back and nothing is lost.
     */
    int takeFuel(Level current) {
        return takeFuel(current, () -> {
        });
    }

    /**
     * {@link #takeFuel(Level)} running {@code afterExtract} between taking the fuel and inserting its remainder: a hook
     * for tests that change the output buffer right there.
     */
    @VisibleForTesting
    public int takeFuel(Level current, Runnable afterExtract) {
        ItemStack stack = slots.stack(FUEL);
        int time = stack.isEmpty() ? 0 : burnTime(current.fuelValues(), stack);
        if (time <= 0) {
            return 0;
        }
        ItemStackTemplate remainder = stack.getCraftingRemainder();
        ItemStack left = remainder == null ? ItemStack.EMPTY : remainder.create();
        if (!left.isEmpty() && insertOutput(left, true) < left.getCount()) {
            return 0;
        }
        ItemStack fuel = stack.copyWithCount(1);
        if (slots.extract(FUEL, 1, false) != 1) {
            return 0;
        }
        afterExtract.run();
        if (!left.isEmpty()) {
            int inserted = insertOutput(left, false);
            if (inserted < left.getCount()) {
                // The listener of the extraction changed the buffer in between: undo, so neither fuel nor remainder is
                // lost or duplicated. The fuel goes back onto what the slot holds now, which may have changed too.
                if (slots.insert(FUEL, fuel, false) == 1) {
                    extractOutput(left, inserted);
                    return 0;
                }
                // The fuel slot filled up or changed item meanwhile too: the fuel burns after all and the part of its
                // remainder that did not fit is lost. Only a test hook changes both inside this call.
            }
        }
        return time;
    }

    private void extractOutput(ItemStack stack, int amount) {
        int left = amount;
        for (int slot = OUTPUT_FIRST; slot < SAMPLER && left > 0; slot++) {
            if (ItemStack.isSameItemSameComponents(slots.stack(slot), stack)) {
                left -= slots.extract(slot, left, false);
            }
        }
    }

    /**
     * Puts up to {@code stack.getCount()} of {@code stack} into the output buffer, onto matching stacks first, then
     * into empty slots; returns how many went in (would go in, when simulating). The machine's own access: never inside
     * a transfer transaction. {@code stack} is not changed.
     */
    public int insertOutput(ItemStack stack, boolean simulate) {
        int left = stack.getCount();
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int slot = OUTPUT_FIRST; slot < SAMPLER && left > 0; slot++) {
                ItemStack here = slots.stack(slot);
                boolean fits = pass == 0
                        ? !here.isEmpty() && ItemStack.isSameItemSameComponents(here, stack)
                        : here.isEmpty() && !slots.unknown(slot);
                if (fits) {
                    left -= slots.insert(slot, stack.copyWithCount(left), simulate);
                }
            }
        }
        return stack.getCount() - left;
    }

    void save(ValueOutput out) {
        tank.save(out.child("nutrient"));
        slots.save(out.child("items"));
    }

    void load(ValueInput in) {
        in.child("nutrient").ifPresent(tank::load);
        in.child("items").ifPresent(slots::load);
    }
}
