package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

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
    private final List<Consumer<Reader<H>>> values;

    private MenuValues(Builder<H> builder) {
        values = List.copyOf(builder.values);
    }

    /** A new, empty declaration; values are added in the order they are sent. */
    public static <H> Builder<H> builder() {
        return new Builder<>();
    }

    /** Number of declared values. */
    public int size() {
        return values.size();
    }

    /** Hands every value to {@code reader} in declaration order; Lib's menu lays out its fields from this. */
    @ApiStatus.Internal
    public void read(Reader<H> reader) {
        for (Consumer<Reader<H>> value : values) {
            value.accept(reader);
        }
    }

    /**
     * What a declaration is made of, one call per value in declaration order. Lib's menu implements it to lay out its
     * fields; addons have no use for it. Called on the thread that calls {@link #read}.
     */
    @ApiStatus.Internal
    public interface Reader<H> {
        /** An int value and its getter. */
        void intValue(IntValue handle, ToIntFunction<? super H> getter);

        /** A long value and its getter. */
        void longValue(LongValue handle, ToLongFunction<? super H> getter);

        /** An enum value: the enum's constants (not to be changed) and a getter of the current ordinal. */
        void enumValue(EnumValue<?> handle, Enum<?>[] constants, ToIntFunction<? super H> ordinal);

        /** An energy value: getters of stored and capacity. */
        void energy(EnergyValue handle, ToLongFunction<? super H> stored, ToLongFunction<? super H> capacity);

        /** A progress value: getters of current and total. */
        void progress(ProgressValue handle, ToLongFunction<? super H> current, ToLongFunction<? super H> total);

        /** A fluid tank: getters of the fluid (never null), amount and capacity. */
        void tank(TankValue handle, Function<? super H, Optional<FluidKey>> fluid, ToLongFunction<? super H> amount,
                ToLongFunction<? super H> capacity);

        /**
         * A tank of {@code kind}, or of any kind when null: {@code changeKey} is read every tick and compared with
         * {@code equals} (it must not allocate), and {@code content} is read only when the key changed; a null
         * {@code content} sends the change key itself, which then is an {@code Optional<KindKey<?>>}.
         */
        void kindTank(TankValue handle, @Nullable ResourceKind<?> kind, Function<? super H, ?> changeKey,
                @Nullable Function<? super H, Optional<KindKey<?>>> content, ToLongFunction<? super H> amount,
                ToLongFunction<? super H> capacity);

        /** A tank of {@code kind} read from a Lib {@link ResourceTank}, unknown contents included. */
        <K> void resourceTank(TankValue handle, ResourceKind<K> kind,
                Function<? super H, ? extends ResourceTank<K>> tank);
    }

    /**
     * Collects values in declaration order. Each method returns the new value's handle; keep the handles (static
     * fields) for the screen. Single use: {@link #build()} freezes it, and adding afterwards throws
     * {@link IllegalStateException}. Not thread-safe; declare during class initialisation.
     */
    public static final class Builder<H> {
        private final List<Consumer<Reader<H>>> values = new ArrayList<>();
        private boolean built;

        private Builder() {
        }

        /** An int, sent as a var-int. */
        public IntValue intValue(ToIntFunction<? super H> getter) {
            IntValue handle = new IntValue(next());
            values.add(reader -> reader.intValue(handle, getter));
            return handle;
        }

        /** A long, sent as a var-long. */
        public LongValue longValue(ToLongFunction<? super H> getter) {
            LongValue handle = new LongValue(next());
            values.add(reader -> reader.longValue(handle, getter));
            return handle;
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
            EnumValue<E> handle = new EnumValue<>(next());
            ToIntFunction<H> ordinal = host -> getter.apply(host).ordinal();
            values.add(reader -> reader.enumValue(handle, constants, ordinal));
            return handle;
        }

        /** Stored energy and capacity in MU. */
        public EnergyValue energy(ToLongFunction<? super H> stored, ToLongFunction<? super H> capacity) {
            EnergyValue handle = new EnergyValue(next());
            values.add(reader -> reader.energy(handle, stored, capacity));
            return handle;
        }

        /** Work done and work in total, in the host's own unit (usually ticks). */
        public ProgressValue progress(ToLongFunction<? super H> current, ToLongFunction<? super H> total) {
            ProgressValue handle = new ProgressValue(next());
            values.add(reader -> reader.progress(handle, current, total));
            return handle;
        }

        /**
         * A fluid tank: its fluid with components (empty for none; {@code fluid} must not return null), amount and
         * capacity in mB. The fluid travels with {@code FluidKey.OPTIONAL_STREAM_CODEC}; tanks of other kinds are
         * {@link #kindTank} and {@link #tank(ResourceKind, Function, ToLongFunction, ToLongFunction)}.
         */
        public TankValue tank(Function<? super H, Optional<FluidKey>> fluid, ToLongFunction<? super H> amount,
                ToLongFunction<? super H> capacity) {
            TankValue handle = new TankValue(next());
            values.add(reader -> reader.tank(handle, fluid, amount, capacity));
            return handle;
        }

        /**
         * A tank of any {@link ResourceKind}: its resource as a {@link KindKey} (empty for none; {@code resource} must
         * not return null), amount and capacity in the resource's {@linkplain ResourceKind#unit() unit}. Reads with
         * {@link MenuValueView#resource(TankValue)}; {@link MenuValueView#fluid(TankValue)} refuses it, since its kind
         * can change. The resource travels with {@link KindKey#CLIENTBOUND_STREAM_CODEC}, so a client without the kind
         * gets an {@link dev.silencebound.maxtech.lib.api.resource.UnknownResource UnknownResource}, as does one sent
         * an unknown resource. As with fluids, the getter should return a kept {@code Optional}: it is compared with
         * {@code equals} every tick.
         */
        public TankValue kindTank(Function<? super H, Optional<KindKey<?>>> resource, ToLongFunction<? super H> amount,
                ToLongFunction<? super H> capacity) {
            TankValue handle = new TankValue(next());
            values.add(reader -> reader.kindTank(handle, null, resource, null, amount, capacity));
            return handle;
        }

        /**
         * A tank of one {@code kind}: its key (empty for none; {@code resource} must not return null), amount and
         * capacity in the kind's unit. Sent like {@link #kindTank}; a tank of
         * {@link dev.silencebound.maxtech.lib.api.resource.ResourceKinds#FLUID ResourceKinds.FLUID} also reads with
         * {@link MenuValueView#fluid(TankValue)}. A {@link ResourceTank}'s {@code resource()} is a kept
         * {@code Optional} and fits as the getter; {@link #tank(ResourceKind, Function)} also shows its unknown
         * contents.
         */
        public <K> TankValue tank(ResourceKind<K> kind, Function<? super H, Optional<K>> resource,
                ToLongFunction<? super H> amount, ToLongFunction<? super H> capacity) {
            Objects.requireNonNull(kind, "kind");
            TankValue handle = new TankValue(next());
            Function<H, Optional<KindKey<?>>> content = host -> resource.apply(host)
                    .<KindKey<?>>map(key -> new KindKey<>(kind, key));
            values.add(reader -> reader.kindTank(handle, kind, resource, content, amount, capacity));
            return handle;
        }

        /**
         * A Lib {@link ResourceTank} of {@code kind}, with its resource, amount and capacity. Unknown contents (a saved
         * resource that no longer loads) show as an {@link dev.silencebound.maxtech.lib.api.resource.UnknownResource
         * UnknownResource} of the tank's kind with the amount saved, not as empty. Sent like {@link #kindTank}; a fluid
         * tank also reads with {@link MenuValueView#fluid(TankValue)}. {@code tank} must not return null, and the
         * tank's kind must be {@code kind}.
         */
        public <K> TankValue tank(ResourceKind<K> kind, Function<? super H, ? extends ResourceTank<K>> tank) {
            Objects.requireNonNull(kind, "kind");
            TankValue handle = new TankValue(next());
            values.add(reader -> reader.resourceTank(handle, kind, tank));
            return handle;
        }

        /** The declaration; call once, after every value. */
        public MenuValues<H> build() {
            checkOpen();
            built = true;
            return new MenuValues<>(this);
        }

        private int next() {
            checkOpen();
            return values.size();
        }

        private void checkOpen() {
            if (built) {
                throw new IllegalStateException("Menu values already built");
            }
        }
    }
}
