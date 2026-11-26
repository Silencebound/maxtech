package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyReadout;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.storage.energy.FeView;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * What a sensor last read of its target, kept between reads and refilled in place. Reading only asks: it never inserts,
 * extracts or changes anything. Per-item and per-fluid counts are grouped by item and fluid, whatever their components.
 * Server thread only.
 */
final class SensorReading {
    private static final Comparator<Item> ITEM_ORDER = Comparator
            .comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString());
    private static final Comparator<Fluid> FLUID_ORDER = Comparator
            .comparing(fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString());
    private static final Comparator<Property<?>> PROPERTY_ORDER = Comparator.comparing(Property::getName);

    /** Whether the target was read at least once. */
    boolean read;
    BlockState state = Blocks.AIR.defaultBlockState();
    int redstone;
    boolean hasComparator;
    int comparator;
    boolean hasEnergy;
    long energy;
    long energyCapacity;
    /** Whether the target shows a MaxTech energy endpoint that is in a network (MLE). */
    boolean hasMu;
    /** The readout of that network at the last read; {@link EnergyReadout#NONE} without one. */
    EnergyReadout mu = EnergyReadout.NONE;
    boolean hasItems;
    long itemTotal;
    long itemCapacity;
    long slotsUsed;
    long slotsFree;
    /**
     * How full the item handler's slots are, in millionths: each slot's count against its item's own max stack size (an
     * empty slot 0), averaged over the slots, so a full chest of any stackable item reads 1,000,000.
     */
    long itemFill;
    final Reference2LongOpenHashMap<Item> items = new Reference2LongOpenHashMap<>();
    boolean hasFluids;
    long fluidTotal;
    long fluidCapacity;
    final Reference2LongOpenHashMap<Fluid> fluids = new Reference2LongOpenHashMap<>();
    /** Tanks of the fluid handler, at most {@link SensorRef#MAX_TANKS}; listed one by one only when there are two. */
    int tanks;
    final long[] tankAmount = new long[SensorRef.MAX_TANKS];
    final long[] tankCapacity = new long[SensorRef.MAX_TANKS];
    /** The pack reader values of the target's block, read from its saved data by the worker. */
    final ReaderValues data = new ReaderValues();

    /**
     * Reads the block at {@code target}, which lies {@code towards} of the sensor, with the handlers it shows the
     * sensor (null where it has none). The energy network's values come from its readout, which never wakes it.
     */
    void read(ServerLevel level, BlockPos target, Direction towards,
            @Nullable ResourceHandler<ItemResource> itemHandler, @Nullable ResourceHandler<FluidResource> fluidHandler,
            @Nullable EnergyHandler energyHandler, @Nullable EnergyEndpoint endpoint) {
        read = true;
        state = level.getBlockState(target);
        // Signals are asked of the target with the direction from the sensor to it, as a neighbour's power is.
        redstone = level.getSignal(target, towards);
        hasComparator = state.hasAnalogOutputSignal();
        comparator = hasComparator ? state.getAnalogOutputSignal(level, target, towards.getOpposite()) : 0;
        hasEnergy = energyHandler != null;
        // MU (MLE item 4): a Storage block's own, another mod's FE at Lib's rate.
        if (energyHandler instanceof FeView view) {
            energy = view.store().getAmountAsLong();
            energyCapacity = view.store().getCapacityAsLong();
        } else if (energyHandler != null) {
            FeRate rate = ServerConfig.feRate();
            energy = rate.toMu(energyHandler.getAmountAsLong());
            energyCapacity = rate.toMu(energyHandler.getCapacityAsLong());
        } else {
            energy = 0;
            energyCapacity = 0;
        }
        Optional<EnergyNetwork> network = endpoint == null ? Optional.empty() : endpoint.network();
        hasMu = network.isPresent();
        mu = network.isPresent() ? network.get().readout() : EnergyReadout.NONE;
        readItems(itemHandler);
        readFluids(fluidHandler);
    }

    /** Forgets everything read: values read 0 and the list is empty, as before the first read. */
    void clear() {
        read = false;
        state = Blocks.AIR.defaultBlockState();
        redstone = 0;
        hasComparator = false;
        comparator = 0;
        hasEnergy = false;
        energy = 0;
        energyCapacity = 0;
        hasMu = false;
        mu = EnergyReadout.NONE;
        readItems(null);
        readFluids(null);
        data.clear();
    }

    private void readItems(@Nullable ResourceHandler<ItemResource> handler) {
        hasItems = handler != null;
        itemTotal = 0;
        itemCapacity = 0;
        slotsUsed = 0;
        slotsFree = 0;
        itemFill = 0;
        items.clear();
        if (handler == null) {
            return;
        }
        int size = handler.size();
        double fill = 0;
        for (int i = 0; i < size; i++) {
            ItemResource resource = handler.getResource(i);
            long amount = handler.getAmountAsLong(i);
            itemCapacity += handler.getCapacityAsLong(i, resource);
            if (!resource.isEmpty() && amount > 0) {
                itemTotal += amount;
                slotsUsed++;
                items.addTo(resource.getItem(), amount);
                fill += Math.min(1.0, (double) amount / Math.max(1, resource.getMaxStackSize()));
            } else {
                slotsFree++;
            }
        }
        itemFill = size == 0 ? 0 : Math.round(fill / size * MachineReader.FILL_SCALE);
    }

    private void readFluids(@Nullable ResourceHandler<FluidResource> handler) {
        hasFluids = handler != null;
        fluidTotal = 0;
        fluidCapacity = 0;
        tanks = 0;
        fluids.clear();
        if (handler == null) {
            return;
        }
        int size = handler.size();
        tanks = Math.min(size, SensorRef.MAX_TANKS);
        for (int i = 0; i < size; i++) {
            FluidResource resource = handler.getResource(i);
            long amount = handler.getAmountAsLong(i);
            long capacity = handler.getCapacityAsLong(i, resource);
            fluidCapacity += capacity;
            if (i < tanks) {
                tankAmount[i] = resource.isEmpty() ? 0 : Math.max(0, amount);
                tankCapacity[i] = capacity;
            }
            if (!resource.isEmpty() && amount > 0) {
                fluidTotal += amount;
                fluids.addTo(resource.getFluid(), amount);
            }
        }
    }

    /** The value {@code ref} names at the last read; 0 for one the target does not have. */
    long value(SensorRef ref) {
        return switch (ref.kind()) {
            case REDSTONE -> redstone;
            case COMPARATOR -> comparator;
            case STATE -> {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(ref.name());
                yield property == null || !state.hasProperty(property) ? 0 : stateValue(state, property);
            }
            case ENERGY_STORED -> energy;
            case ENERGY_CAPACITY -> energyCapacity;
            case MU_SUPPLY -> mu.supply();
            case MU_DEMAND -> mu.demand();
            case MU_RECEIVED -> mu.received();
            case MU_SUPPLIED -> suppliedPercent(mu);
            case MU_STORED -> mu.stored();
            case MU_CAPACITY -> mu.capacity();
            case MU_TICKS_TO_FULL -> hasMu ? mu.ticksToFull().orElse(-1) : 0;
            case MU_TICKS_TO_EMPTY -> hasMu ? mu.ticksToEmpty().orElse(-1) : 0;
            case MU_PASS_NANOS -> mu.lastPassNanos();
            case ITEMS_TOTAL -> itemTotal;
            case ITEMS_CAPACITY -> itemCapacity;
            case ITEMS_SLOTS_USED -> slotsUsed;
            case ITEMS_SLOTS_FREE -> slotsFree;
            case ITEM -> ref.item() == null ? 0 : items.getLong(ref.item());
            case FLUIDS_TOTAL -> fluidTotal;
            case FLUIDS_CAPACITY -> fluidCapacity;
            case FLUID -> ref.fluid() == null ? 0 : fluids.getLong(ref.fluid());
            case TANK -> {
                int tank = ref.number() - 1;
                if (tank < 0 || tank >= tanks) {
                    yield 0;
                }
                yield ref.name().equals(SensorRef.TANK_CAPACITY) ? tankCapacity[tank] : tankAmount[tank];
            }
            case DATA -> data.value(ref.name());
        };
    }

    /**
     * Whether the value {@code ref} names was found at the last read: false only for a pack reader value
     * ({@code data.}) that no reader of the target gives or whose path was missing; the values every block gets read 0
     * when absent.
     */
    boolean found(SensorRef ref) {
        return ref.kind() != SensorRef.Kind.DATA || data.found(ref.name());
    }

    /**
     * The percentage of demand served, rounded down so any brownout reads below 100; 100 without one, 0 without a
     * network.
     */
    static long suppliedPercent(EnergyReadout readout) {
        if (!readout.brownout()) {
            return readout.passCount() == 0 ? 0 : 100;
        }
        return Math.min(99, (long) Math.floor(readout.suppliedFraction() * 100));
    }

    /** A property's value as a number: 1 or 0 for yes/no, the number itself, else its index among the values. */
    private static <T extends Comparable<T>> long stateValue(BlockState state, Property<T> property) {
        T value = state.getValue(property);
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Integer number) {
            return number;
        }
        return property.getPossibleValues().indexOf(value);
    }

    private static <T extends Comparable<T>> String stateText(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * Every value the last read offers, in a stable order: redstone and comparator, the block state's properties by
     * name, energy, items (totals and slots, then each item by id), fluids (totals, each fluid by id, then each tank
     * when there are several), then pack reader values grouped by reader. At most {@code max} entries, the reader
     * values always among them; empty before the first read. Allocates; for screens and the editor, not for evaluation.
     */
    List<SensorValue> values(int max) {
        List<SensorValue> values = new ArrayList<>();
        if (!read) {
            return values;
        }
        add(values, SensorRef.REDSTONE);
        if (hasComparator) {
            add(values, SensorRef.COMPARATOR);
        }
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(PROPERTY_ORDER);
        for (Property<?> property : properties) {
            SensorRef ref = SensorRef.state(property.getName());
            values.add(SensorValue.of(ref.id(), Component.translatable(SensorTexts.STATE, property.getName()),
                    value(ref), SensorUnit.STATE, stateText(state, property)));
        }
        if (hasEnergy) {
            add(values, SensorRef.ENERGY_STORED);
            add(values, SensorRef.ENERGY_CAPACITY);
        }
        if (hasMu) {
            add(values, SensorRef.MU_SUPPLY);
            add(values, SensorRef.MU_DEMAND);
            add(values, SensorRef.MU_RECEIVED);
            add(values, SensorRef.MU_SUPPLIED);
            add(values, SensorRef.MU_STORED);
            add(values, SensorRef.MU_CAPACITY);
            add(values, SensorRef.MU_TICKS_TO_FULL);
            add(values, SensorRef.MU_TICKS_TO_EMPTY);
            add(values, SensorRef.MU_PASS_NANOS);
        }
        if (hasItems) {
            add(values, SensorRef.ITEMS_TOTAL);
            add(values, SensorRef.ITEMS_CAPACITY);
            add(values, SensorRef.ITEMS_SLOTS_USED);
            add(values, SensorRef.ITEMS_SLOTS_FREE);
            List<Item> kinds = new ArrayList<>(items.keySet());
            kinds.sort(ITEM_ORDER);
            for (Item item : kinds) {
                SensorRef ref = SensorRef.item(item);
                values.add(SensorValue.of(ref.id(), item.getDefaultInstance().getHoverName(), value(ref),
                        SensorUnit.ITEMS, ""));
            }
        }
        if (hasFluids) {
            add(values, SensorRef.FLUIDS_TOTAL);
            add(values, SensorRef.FLUIDS_CAPACITY);
            List<Fluid> kinds = new ArrayList<>(fluids.keySet());
            kinds.sort(FLUID_ORDER);
            for (Fluid fluid : kinds) {
                SensorRef ref = SensorRef.fluid(fluid);
                values.add(SensorValue.of(ref.id(), fluid.getFluidType().getDescription(), value(ref),
                        SensorUnit.MILLIBUCKETS, ""));
            }
            // One tank is what the totals already say; tanks are listed one by one only when there are several.
            if (tanks > 1) {
                for (int tank = 1; tank <= tanks; tank++) {
                    SensorRef amount = SensorRef.tank(tank, false);
                    values.add(SensorValue.of(amount.id(),
                            Component.translatable(SensorTexts.TANK_AMOUNT, Integer.toString(tank)), value(amount),
                            SensorUnit.MILLIBUCKETS, ""));
                    SensorRef capacity = SensorRef.tank(tank, true);
                    values.add(SensorValue.of(capacity.id(),
                            Component.translatable(SensorTexts.TANK_CAPACITY, Integer.toString(tank)), value(capacity),
                            SensorUnit.MILLIBUCKETS, ""));
                }
            }
        }
        // Pack reader values keep their room: a target with very many item kinds loses item rows, not reader rows.
        int room = Math.max(0, max - data.size());
        if (values.size() > room) {
            values = new ArrayList<>(values.subList(0, room));
        }
        data.list(values, max);
        return values;
    }

    private void add(List<SensorValue> values, SensorRef ref) {
        values.add(SensorValue.of(ref.id(), Component.translatable(SensorTexts.valueKey(ref.kind())), value(ref),
                ref.unit(), ""));
    }
}
