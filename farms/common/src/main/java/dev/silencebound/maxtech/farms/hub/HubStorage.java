package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: NeoForge's transfer api holds the hub's tank and slots (root CLAUDE.md: transfers).
import net.neoforged.neoforge.fluids.FluidStack;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Everything the lab hub holds besides energy: the nutrient tank, the fuel slot, the output buffer and the sampler
 * charging slot, with their save format and the view other mods get ({@link #itemHandler()}). The one place the hub's
 * storage lives, so it can move onto Lib's own slot and tank types later without touching the block entity or the
 * capability glue. Server thread only.
 */
public final class HubStorage {
    /** Output slots: three rows. */
    public static final int OUTPUT_SLOTS = 27;

    /** Told when the contents change; fuel, output and sampler changes separately, since they can start work. */
    public interface Listener {
        /** Anything changed (after the root transaction committed, or at once for direct sets). */
        void changed();

        /** The fuel slot changed. */
        void fuelChanged();

        /** The output buffer changed: room may have opened for a fuel remainder. */
        void outputChanged();

        /** The sampler slot changed. */
        void samplerChanged();
    }

    private final Supplier<@Nullable Level> level;
    private final Listener listener;
    private final Tank tank = new Tank();
    private final FuelSlot fuel = new FuelSlot();
    private final Output output = new Output();
    private final SamplerSlot sampler = new SamplerSlot();
    private final HubItemHandler itemHandler = new HubItemHandler(fuel, output);

    /** Storage for a hub in {@code level} (null while the block entity has none). */
    HubStorage(Supplier<@Nullable Level> level, Listener listener) {
        this.level = level;
        this.listener = listener;
    }

    /** The nutrient tank, for any fluid; other mods fill and drain it through the fluid capability. */
    public FluidStacksResourceHandler tank() {
        return tank;
    }

    /** The fuel slot (any furnace fuel); a remainder such as an empty bucket stays in it until taken. */
    public ItemStacksResourceHandler fuel() {
        return fuel;
    }

    /** The output buffer; the lab fills it, others only take out of it ({@link #itemHandler()}). */
    public ItemStacksResourceHandler output() {
        return output;
    }

    /** The sampler charging slot: one sampler, put in and taken out by hand; not part of {@link #itemHandler()}. */
    public ItemStacksResourceHandler sampler() {
        return sampler;
    }

    /** The item capability: fuel goes in, output (and a spent fuel remainder) comes out. */
    public HubItemHandler itemHandler() {
        return itemHandler;
    }

    /** Whether tank, fuel slot, output and sampler slot are all empty. */
    public boolean isEmpty() {
        return tank.getAmountAsLong(0) == 0 && ResourceHandlerUtil.isEmpty(fuel) && ResourceHandlerUtil.isEmpty(output)
                && sampler.getResource(0).isEmpty();
    }

    /** Whether the fuel slot holds something that burns (not just a spent remainder). */
    boolean hasFuel(Level current) {
        ItemResource resource = fuel.getResource(0);
        return !resource.isEmpty() && burnTime(current, resource.toStack(1)) > 0;
    }

    /** Ticks {@code stack} burns in a furnace, from NeoForge's burn time hook (data map and item overrides). */
    static int burnTime(Level level, ItemStack stack) {
        // PLATFORM-LEAK: NeoForge's patched ItemStack#getBurnTime, which vanilla's deprecated FuelValues lookup skips.
        return stack.getBurnTime(RecipeType.SMELTING, level.fuelValues());
    }

    /**
     * Takes one fuel item out of the fuel slot and returns its burn time, or 0 when nothing burns. Its remainder (an
     * empty bucket) replaces the last item in the slot, or goes into the output; when it fits nowhere nothing is taken.
     */
    int takeFuel(Level current) {
        ItemStack stack = fuel.getResource(0).toStack(fuel.getAmountAsInt(0));
        int time = stack.isEmpty() ? 0 : burnTime(current, stack);
        if (time <= 0) {
            return 0;
        }
        ItemStackTemplate remainder = stack.getCraftingRemainder();
        ItemStack left = remainder == null ? ItemStack.EMPTY : remainder.create();
        int count = stack.getCount();
        if (count == 1) {
            fuel.set(0, left.isEmpty() ? ItemResource.EMPTY : ItemResource.of(left), left.getCount());
            return time;
        }
        if (!left.isEmpty()) {
            try (Transaction transaction = Transaction.openRoot()) {
                int moved = ResourceHandlerUtil.insertStacking(output, ItemResource.of(left), left.getCount(),
                        transaction);
                if (moved < left.getCount()) {
                    return 0;
                }
                transaction.commit();
            }
        }
        fuel.set(0, fuel.getResource(0), count - 1);
        return time;
    }

    void save(ValueOutput out) {
        tank.serialize(out.child("tank"));
        fuel.serialize(out.child("fuel"));
        output.serialize(out.child("output"));
        sampler.serialize(out.child("sampler"));
    }

    void load(ValueInput in) {
        in.child("tank").ifPresent(tank::deserialize);
        in.child("fuel").ifPresent(fuel::deserialize);
        in.child("output").ifPresent(output::deserialize);
        in.child("sampler").ifPresent(sampler::deserialize);
    }

    /** One tank for any fluid; its capacity follows the config, and contents above a lowered capacity are kept. */
    private final class Tank extends FluidStacksResourceHandler {
        Tank() {
            super(1, FarmsServerConfig.hubTankCapacity());
        }

        @Override
        protected int getCapacity(int index, FluidResource resource) {
            return FarmsServerConfig.hubTankCapacity();
        }

        @Override
        protected void onContentsChanged(int index, FluidStack previousContents) {
            listener.changed();
        }
    }

    /** Takes only furnace fuel; what a burnt fuel leaves (an empty bucket) is set here by {@link #takeFuel}. */
    private final class FuelSlot extends ItemStacksResourceHandler {
        FuelSlot() {
            super(1);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            Level current = level.get();
            return current != null && burnTime(current, resource.toStack(1)) > 0;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            listener.changed();
            listener.fuelChanged();
        }
    }

    private final class Output extends ItemStacksResourceHandler {
        Output() {
            super(OUTPUT_SLOTS);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            listener.changed();
            listener.outputChanged();
        }
    }

    /** Holds one sampler. */
    private final class SamplerSlot extends ItemStacksResourceHandler {
        SamplerSlot() {
            super(1);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return resource.getItem() instanceof SamplerItem;
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            listener.changed();
            listener.samplerChanged();
        }
    }
}
