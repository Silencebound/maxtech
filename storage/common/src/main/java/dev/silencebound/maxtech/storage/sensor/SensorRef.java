package dev.silencebound.maxtech.storage.sensor;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * One readable value of a sensor, resolved from its id once so reading it later costs no parsing. Ids are saved in
 * logic graphs and must not change:
 *
 * <ul>
 * <li>{@code redstone}: the redstone signal the target gives towards the sensor, 0 to 15;
 * <li>{@code comparator}: the target's comparator output towards the sensor, 0 to 15 (only for blocks that have one);
 * <li>{@code state.<property>}: a block state property of the target, such as {@code state.facing} or
 * {@code state.lit};
 * <li>{@code energy.stored}, {@code energy.capacity}: energy in the target's energy handler in MU (since MLE item 4; a
 * Storage block's own MU, another mod's FE converted at Lib's rate);
 * <li>{@code mu.supply}, {@code mu.demand}, {@code mu.received} (MU per tick), {@code mu.supplied} (percent of demand
 * served, 100 without a brownout), {@code mu.stored}, {@code mu.capacity} (MU in batteries), {@code mu.ticks_to_full},
 * {@code mu.ticks_to_empty} (-1 while the batteries do not fill or empty) and {@code mu.pass_nanos} (the last pass's
 * cost): the readout of the MaxTech energy network the target's energy endpoint is in (MLE), only for targets that show
 * one;
 * <li>{@code items.total}, {@code items.capacity}: items in the target's item handler, and the room for them;
 * <li>{@code items.slots_used}, {@code items.slots_free}: slots of the item handler that hold something, and that are
 * empty;
 * <li>{@code item.<item id>}: the count of one item, such as {@code item.minecraft:cobblestone}, whatever its
 * components;
 * <li>{@code fluids.total}, {@code fluids.capacity}: millibuckets in the target's fluid handler, and the room;
 * <li>{@code fluid.<fluid id>}: the millibuckets of one fluid;
 * <li>{@code tank.<n>.amount}, {@code tank.<n>.capacity}: one tank of a fluid handler with several, counted from 1;
 * <li>{@code data.<name>}: a value a pack reader names
 * ({@link dev.silencebound.maxtech.storage.sensor.reader.PackReader}), read from the target's saved data.
 * </ul>
 *
 * Immutable.
 *
 * @param kind
 *            which value
 * @param name
 *            the property name for {@link Kind#STATE}, the value name for {@link Kind#DATA}, {@link #TANK_AMOUNT} or
 *            {@link #TANK_CAPACITY} for {@link Kind#TANK}; empty otherwise
 * @param item
 *            the item for {@link Kind#ITEM}
 * @param fluid
 *            the fluid for {@link Kind#FLUID}
 * @param number
 *            the tank, from 1, for {@link Kind#TANK}; 0 otherwise
 */
public record SensorRef(Kind kind, String name, @Nullable Item item, @Nullable Fluid fluid, int number) {
    /** Most tanks listed one by one. */
    public static final int MAX_TANKS = 16;
    /** The name of a tank's amount ({@code tank.<n>.amount}). */
    public static final String TANK_AMOUNT = "amount";
    /** The name of a tank's capacity ({@code tank.<n>.capacity}). */
    public static final String TANK_CAPACITY = "capacity";

    public static final SensorRef REDSTONE = fixed(Kind.REDSTONE);
    public static final SensorRef COMPARATOR = fixed(Kind.COMPARATOR);
    public static final SensorRef ENERGY_STORED = fixed(Kind.ENERGY_STORED);
    public static final SensorRef ENERGY_CAPACITY = fixed(Kind.ENERGY_CAPACITY);
    public static final SensorRef MU_SUPPLY = fixed(Kind.MU_SUPPLY);
    public static final SensorRef MU_DEMAND = fixed(Kind.MU_DEMAND);
    public static final SensorRef MU_RECEIVED = fixed(Kind.MU_RECEIVED);
    public static final SensorRef MU_SUPPLIED = fixed(Kind.MU_SUPPLIED);
    public static final SensorRef MU_STORED = fixed(Kind.MU_STORED);
    public static final SensorRef MU_CAPACITY = fixed(Kind.MU_CAPACITY);
    public static final SensorRef MU_TICKS_TO_FULL = fixed(Kind.MU_TICKS_TO_FULL);
    public static final SensorRef MU_TICKS_TO_EMPTY = fixed(Kind.MU_TICKS_TO_EMPTY);
    public static final SensorRef MU_PASS_NANOS = fixed(Kind.MU_PASS_NANOS);
    public static final SensorRef ITEMS_TOTAL = fixed(Kind.ITEMS_TOTAL);
    public static final SensorRef ITEMS_CAPACITY = fixed(Kind.ITEMS_CAPACITY);
    public static final SensorRef ITEMS_SLOTS_USED = fixed(Kind.ITEMS_SLOTS_USED);
    public static final SensorRef ITEMS_SLOTS_FREE = fixed(Kind.ITEMS_SLOTS_FREE);
    public static final SensorRef FLUIDS_TOTAL = fixed(Kind.FLUIDS_TOTAL);
    public static final SensorRef FLUIDS_CAPACITY = fixed(Kind.FLUIDS_CAPACITY);

    /**
     * The kinds of value, in the order the list shows them: signals, block state, FE, MaxTech energy, items, fluids,
     * then pack reader values. The id of a fixed kind is its {@link #id}; the others append a name to it.
     */
    public enum Kind {
        REDSTONE("redstone", false, SensorUnit.SIGNAL), COMPARATOR("comparator", false, SensorUnit.SIGNAL), STATE(
                "state.", true,
                SensorUnit.STATE), ENERGY_STORED("energy.stored", false, SensorUnit.ENERGY), ENERGY_CAPACITY(
                        "energy.capacity", false,
                        SensorUnit.ENERGY), MU_SUPPLY("mu.supply", false, SensorUnit.MU_PER_TICK), MU_DEMAND(
                                "mu.demand", false, SensorUnit.MU_PER_TICK), MU_RECEIVED("mu.received", false,
                                        SensorUnit.MU_PER_TICK), MU_SUPPLIED("mu.supplied", false,
                                                SensorUnit.PERCENT), MU_STORED("mu.stored", false,
                                                        SensorUnit.MU), MU_CAPACITY("mu.capacity", false,
                                                                SensorUnit.MU), MU_TICKS_TO_FULL("mu.ticks_to_full",
                                                                        false, SensorUnit.TICKS), MU_TICKS_TO_EMPTY(
                                                                                "mu.ticks_to_empty", false,
                                                                                SensorUnit.TICKS), MU_PASS_NANOS(
                                                                                        "mu.pass_nanos", false,
                                                                                        SensorUnit.NANOSECONDS), ITEMS_TOTAL(
                                                                                                "items.total", false,
                                                                                                SensorUnit.ITEMS), ITEMS_CAPACITY(
                                                                                                        "items.capacity",
                                                                                                        false,
                                                                                                        SensorUnit.ITEMS), ITEMS_SLOTS_USED(
                                                                                                                "items.slots_used",
                                                                                                                false,
                                                                                                                SensorUnit.SLOTS), ITEMS_SLOTS_FREE(
                                                                                                                        "items.slots_free",
                                                                                                                        false,
                                                                                                                        SensorUnit.SLOTS), ITEM(
                                                                                                                                "item.",
                                                                                                                                true,
                                                                                                                                SensorUnit.ITEMS), FLUIDS_TOTAL(
                                                                                                                                        "fluids.total",
                                                                                                                                        false,
                                                                                                                                        SensorUnit.MILLIBUCKETS), FLUIDS_CAPACITY(
                                                                                                                                                "fluids.capacity",
                                                                                                                                                false,
                                                                                                                                                SensorUnit.MILLIBUCKETS), FLUID(
                                                                                                                                                        "fluid.",
                                                                                                                                                        true,
                                                                                                                                                        SensorUnit.MILLIBUCKETS), TANK(
                                                                                                                                                                "tank.",
                                                                                                                                                                true,
                                                                                                                                                                SensorUnit.MILLIBUCKETS), DATA(
                                                                                                                                                                        "data.",
                                                                                                                                                                        true,
                                                                                                                                                                        SensorUnit.CUSTOM);

        private static final Kind[] VALUES = values();

        private final String id;
        private final boolean prefix;
        private final SensorUnit unit;

        Kind(String id, boolean prefix, SensorUnit unit) {
            this.id = id;
            this.prefix = prefix;
            this.unit = unit;
        }

        /** The whole id of a fixed kind, or the prefix of a named one. */
        public String id() {
            return id;
        }

        public SensorUnit unit() {
            return unit;
        }

        /** Whether the kind's ids append a name to {@link #id()}. */
        public boolean named() {
            return prefix;
        }
    }

    private static SensorRef fixed(Kind kind) {
        return new SensorRef(kind, "", null, null, 0);
    }

    /** A block state property by name. */
    public static SensorRef state(String property) {
        return new SensorRef(Kind.STATE, property, null, null, 0);
    }

    /** One item's count. */
    public static SensorRef item(Item item) {
        return new SensorRef(Kind.ITEM, "", item, null, 0);
    }

    /** One fluid's amount. */
    public static SensorRef fluid(Fluid fluid) {
        return new SensorRef(Kind.FLUID, "", null, fluid, 0);
    }

    /** Tank {@code tank} (from 1): its amount, or its capacity when {@code capacity}. */
    public static SensorRef tank(int tank, boolean capacity) {
        return new SensorRef(Kind.TANK, capacity ? TANK_CAPACITY : TANK_AMOUNT, null, null, tank);
    }

    /** A pack reader's value by name. */
    public static SensorRef data(String name) {
        return new SensorRef(Kind.DATA, name, null, null, 0);
    }

    /** The id the value is saved and listed by. */
    public String id() {
        return switch (kind) {
            case STATE, DATA -> kind.id + name;
            case ITEM -> kind.id + (item == null ? "" : BuiltInRegistries.ITEM.getKey(item));
            case FLUID -> kind.id + (fluid == null ? "" : BuiltInRegistries.FLUID.getKey(fluid));
            case TANK -> kind.id + number + "." + name;
            default -> kind.id;
        };
    }

    public SensorUnit unit() {
        return kind.unit;
    }

    /**
     * The value {@code id} names, or empty when it names none: an unknown id, an empty property or value name, a tank
     * outside 1 to {@link #MAX_TANKS}, or an item or fluid that is not registered (air and empty fluid included).
     */
    @SuppressWarnings("ReferenceEquality") // registry entries are singletons
    public static Optional<SensorRef> parse(String id) {
        for (Kind kind : Kind.VALUES) {
            if (!kind.prefix) {
                if (id.equals(kind.id)) {
                    return Optional.of(fixed(kind));
                }
                continue;
            }
            if (!id.startsWith(kind.id) || id.length() == kind.id.length()) {
                continue;
            }
            String rest = id.substring(kind.id.length());
            switch (kind) {
                case STATE -> {
                    return Optional.of(state(rest));
                }
                case DATA -> {
                    return Optional.of(data(rest));
                }
                case TANK -> {
                    return tank(rest);
                }
                default -> {
                }
            }
            Identifier key = Identifier.tryParse(rest);
            if (key == null) {
                return Optional.empty();
            }
            if (kind == Kind.ITEM) {
                Item found = BuiltInRegistries.ITEM.getValue(key);
                return found == Items.AIR ? Optional.empty() : Optional.of(item(found));
            }
            Fluid found = BuiltInRegistries.FLUID.getValue(key);
            return found == Fluids.EMPTY ? Optional.empty() : Optional.of(fluid(found));
        }
        return Optional.empty();
    }

    /** {@code <n>.amount} or {@code <n>.capacity}. */
    private static Optional<SensorRef> tank(String rest) {
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot > 2) {
            return Optional.empty();
        }
        int tank = 0;
        for (int i = 0; i < dot; i++) {
            char c = rest.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
            tank = tank * 10 + (c - '0');
        }
        String name = rest.substring(dot + 1);
        if (tank < 1 || tank > MAX_TANKS || !(name.equals(TANK_AMOUNT) || name.equals(TANK_CAPACITY))) {
            return Optional.empty();
        }
        return Optional.of(tank(tank, name.equals(TANK_CAPACITY)));
    }
}
