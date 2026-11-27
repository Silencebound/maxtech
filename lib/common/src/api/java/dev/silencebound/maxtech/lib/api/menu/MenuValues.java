package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.ApiStatus;

/**
 * The values one kind of menu syncs to its screen, declared once (usually in a static field) and shared by the server
 * and client menus of that kind. Each value has getters over the menu's server-side host {@code H}; the client never
 * calls them. Values are sent by position, so both sides must declare the same values in the same order, which a shared
 * static declaration guarantees.
 *
 * <pre>{@code
 * static final MenuValues.Builder<LabHub> VALUES = MenuValues.builder();
 * static final EnergyValue ENERGY = VALUES.energy(LabHub::stored, LabHub::capacity);
 * static final TankValue NUTRIENT = VALUES.tank(LabHub::fluid, LabHub::amount, LabHub::tankCapacity);
 * static final MenuValues<LabHub> DECLARED = VALUES.build();
 * }</pre>
 *
 * Getters run on the server thread every tick while the menu is open, so they should only read fields: no lookups, no
 * allocation (a tank getter returns a kept {@code Optional} where it can). Immutable; any thread.
 *
 * @param <H>
 *            the server-side object the getters read
 */
public final class MenuValues<H> {
    /** What a value is; decides its fields and its encoding. */
    @ApiStatus.Internal
    public enum Kind {
        /** One int field. */
        INT,
        /** One long field. */
        LONG,
        /** One int field holding the ordinal. */
        ENUM,
        /** Two long fields: stored, capacity. */
        ENERGY,
        /** Two long fields: current, total. */
        PROGRESS,
        /** One object field (the content) and two long fields: amount, capacity. */
        TANK
    }

    private final Kind[] kinds;
    private final int[] firstInt;
    private final int[] firstLong;
    private final int[] firstObject;
    private final Enum<?>[][] enumConstants;
    private final List<ToIntFunction<? super H>> intReaders;
    private final List<ToLongFunction<? super H>> longReaders;
    private final List<Function<? super H, ?>> objectReaders;
    private final List<StreamCodec<? super RegistryFriendlyByteBuf, Object>> objectCodecs;
    private final List<Object> objectInitials;

    private MenuValues(Builder<H> builder) {
        int size = builder.kinds.size();
        kinds = builder.kinds.toArray(new Kind[0]);
        firstInt = new int[size];
        firstLong = new int[size];
        firstObject = new int[size];
        enumConstants = new Enum<?>[size][];
        for (int i = 0; i < size; i++) {
            firstInt[i] = builder.firstInt.get(i);
            firstLong[i] = builder.firstLong.get(i);
            firstObject[i] = builder.firstObject.get(i);
            enumConstants[i] = builder.enumConstants.get(i);
        }
        intReaders = List.copyOf(builder.intReaders);
        longReaders = List.copyOf(builder.longReaders);
        objectReaders = List.copyOf(builder.objectReaders);
        objectCodecs = List.copyOf(builder.objectCodecs);
        objectInitials = List.copyOf(builder.objectInitials);
    }

    /** A new, empty declaration; values are added in the order they are sent. */
    public static <H> Builder<H> builder() {
        return new Builder<>();
    }

    /** Number of declared values. */
    public int size() {
        return kinds.length;
    }

    /** Kind of value {@code index}. */
    @ApiStatus.Internal
    public Kind kind(int index) {
        return kinds[index];
    }

    /** First int field of value {@code index}; ints hold {@code INT} values and {@code ENUM} ordinals. */
    @ApiStatus.Internal
    public int firstInt(int index) {
        return firstInt[index];
    }

    /** First long field of value {@code index}: one for {@code LONG}, two for energy, progress and tanks. */
    @ApiStatus.Internal
    public int firstLong(int index) {
        return firstLong[index];
    }

    /** Object field of value {@code index}: a tank's fluid. */
    @ApiStatus.Internal
    public int firstObject(int index) {
        return firstObject[index];
    }

    /** Number of int fields over all values. */
    @ApiStatus.Internal
    public int intFields() {
        return intReaders.size();
    }

    /** Number of long fields over all values. */
    @ApiStatus.Internal
    public int longFields() {
        return longReaders.size();
    }

    /** Number of object fields over all values. */
    @ApiStatus.Internal
    public int objectFields() {
        return objectReaders.size();
    }

    /** Getter of int field {@code field}. */
    @ApiStatus.Internal
    public ToIntFunction<? super H> intReader(int field) {
        return intReaders.get(field);
    }

    /** Getter of long field {@code field}. */
    @ApiStatus.Internal
    public ToLongFunction<? super H> longReader(int field) {
        return longReaders.get(field);
    }

    /** Getter of object field {@code field}; never returns null. */
    @ApiStatus.Internal
    public Function<? super H, ?> objectReader(int field) {
        return objectReaders.get(field);
    }

    /** Codec of object field {@code field}. */
    @ApiStatus.Internal
    public StreamCodec<? super RegistryFriendlyByteBuf, Object> objectCodec(int field) {
        return objectCodecs.get(field);
    }

    /** What an object field holds before anything was read or received. */
    @ApiStatus.Internal
    public Object objectInitial(int field) {
        return objectInitials.get(field);
    }

    /** Number of constants of the {@code ENUM} value {@code index}. */
    @ApiStatus.Internal
    public int enumCount(int index) {
        return enumConstants[index].length;
    }

    /** The constant of {@code ENUM} value {@code index} at {@code ordinal}, which must be in range. */
    @ApiStatus.Internal
    public Enum<?> enumConstant(int index, int ordinal) {
        return enumConstants[index][ordinal];
    }

    /**
     * Collects values in declaration order. Each method returns the new value's handle; keep the handles (static
     * fields) for the screen. Single use: {@link #build()} freezes it, and adding afterwards throws
     * {@link IllegalStateException}. Not thread-safe; declare during class initialisation.
     */
    public static final class Builder<H> {
        private static final Enum<?>[] NO_CONSTANTS = new Enum<?>[0];

        private final List<Kind> kinds = new ArrayList<>();
        private final List<Integer> firstInt = new ArrayList<>();
        private final List<Integer> firstLong = new ArrayList<>();
        private final List<Integer> firstObject = new ArrayList<>();
        private final List<Enum<?>[]> enumConstants = new ArrayList<>();
        private final List<ToIntFunction<? super H>> intReaders = new ArrayList<>();
        private final List<ToLongFunction<? super H>> longReaders = new ArrayList<>();
        private final List<Function<? super H, ?>> objectReaders = new ArrayList<>();
        private final List<StreamCodec<? super RegistryFriendlyByteBuf, Object>> objectCodecs = new ArrayList<>();
        private final List<Object> objectInitials = new ArrayList<>();
        private boolean built;

        private Builder() {
        }

        /** An int, sent as a var-int. */
        public IntValue intValue(ToIntFunction<? super H> getter) {
            int index = add(Kind.INT, NO_CONSTANTS);
            intReaders.add(getter);
            return new IntValue(index);
        }

        /** A long, sent as a var-long. */
        public LongValue longValue(ToLongFunction<? super H> getter) {
            int index = add(Kind.LONG, NO_CONSTANTS);
            longReaders.add(getter);
            return new LongValue(index);
        }

        /**
         * {@code getter} must not return null. Throws {@link IllegalArgumentException} for an enum without constants.
         */
        @SuppressWarnings("EnumOrdinal") // the ordinal only travels to a client of the same build
        public <E extends Enum<E>> EnumValue<E> enumValue(Class<E> type, Function<? super H, E> getter) {
            E[] constants = type.getEnumConstants();
            if (constants.length == 0) {
                throw new IllegalArgumentException("Enum without constants: " + type.getName());
            }
            int index = add(Kind.ENUM, constants);
            intReaders.add(host -> getter.apply(host).ordinal());
            return new EnumValue<>(index);
        }

        /** Stored energy and capacity in MU. */
        public EnergyValue energy(ToLongFunction<? super H> stored, ToLongFunction<? super H> capacity) {
            int index = add(Kind.ENERGY, NO_CONSTANTS);
            longReaders.add(stored);
            longReaders.add(capacity);
            return new EnergyValue(index);
        }

        /** Work done and work in total, in the host's own unit (usually ticks). */
        public ProgressValue progress(ToLongFunction<? super H> current, ToLongFunction<? super H> total) {
            int index = add(Kind.PROGRESS, NO_CONSTANTS);
            longReaders.add(current);
            longReaders.add(total);
            return new ProgressValue(index);
        }

        /** A tank: its fluid with components (empty for none), amount and capacity in mB. */
        public TankValue tank(Function<? super H, Optional<FluidKey>> fluid, ToLongFunction<? super H> amount,
                ToLongFunction<? super H> capacity) {
            return tank(fluid, FluidKey.OPTIONAL_STREAM_CODEC, Optional.empty(), amount, capacity);
        }

        /**
         * A tank whose content is any {@code O} with its own codec, for tests of the encoding that cannot build fluid
         * keys (no registries in plain JUnit). A view's {@code fluid} read of such a tank fails.
         */
        @ApiStatus.Internal
        @SuppressWarnings("unchecked") // the codec only ever sees values from its own reader
        public <O> TankValue tank(Function<? super H, O> content, StreamCodec<? super RegistryFriendlyByteBuf, O> codec,
                O empty, ToLongFunction<? super H> amount, ToLongFunction<? super H> capacity) {
            int index = add(Kind.TANK, NO_CONSTANTS);
            objectReaders.add(content);
            objectCodecs.add((StreamCodec<? super RegistryFriendlyByteBuf, Object>) (StreamCodec<?, ?>) codec);
            objectInitials.add(empty);
            longReaders.add(amount);
            longReaders.add(capacity);
            return new TankValue(index);
        }

        /** The declaration; call once, after every value. */
        public MenuValues<H> build() {
            checkOpen();
            built = true;
            return new MenuValues<>(this);
        }

        private int add(Kind kind, Enum<?>[] constants) {
            checkOpen();
            kinds.add(kind);
            firstInt.add(intReaders.size());
            firstLong.add(longReaders.size());
            firstObject.add(objectReaders.size());
            enumConstants.add(constants);
            return kinds.size() - 1;
        }

        private void checkOpen() {
            if (built) {
                throw new IllegalStateException("Menu values already built");
            }
        }
    }
}
