package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.LongValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * A {@link MenuValues} declaration read into arrays for {@link MenuValueTable}: what each value is, where its fields
 * are, how they are read and how a tank's content travels. Kept here so the api shows none of it. Immutable once built;
 * built on any one thread.
 */
final class MenuLayout<H> {
    /** What a value is; decides its fields and its encoding. */
    enum Kind {
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

    private static final Enum<?>[] NO_CONSTANTS = new Enum<?>[0];

    final Kind[] kinds;
    /** First int, long and object field of each value. */
    final int[] firstInt;
    final int[] firstLong;
    final int[] firstObject;
    final Enum<?>[][] enumConstants;
    final ToIntFunction<? super H>[] intReaders;
    final ToLongFunction<? super H>[] longReaders;
    final Function<? super H, ?>[] objectReaders;
    final StreamCodec<? super RegistryFriendlyByteBuf, Object>[] objectCodecs;
    final Object[] objectInitials;
    /** Per object field: true if it holds an {@code Optional<FluidKey>} (a tank declared through the api). */
    final boolean[] fluid;

    @SuppressWarnings("unchecked") // generic arrays of the declaration's own readers and codecs
    private MenuLayout(Collector<H> collector) {
        kinds = collector.kinds.toArray(new Kind[0]);
        firstInt = toInts(collector.firstInt);
        firstLong = toInts(collector.firstLong);
        firstObject = toInts(collector.firstObject);
        enumConstants = collector.enumConstants.toArray(new Enum<?>[0][]);
        intReaders = collector.intReaders.toArray(new ToIntFunction[0]);
        longReaders = collector.longReaders.toArray(new ToLongFunction[0]);
        objectReaders = collector.objectReaders.toArray(new Function[0]);
        objectCodecs = collector.objectCodecs.toArray(new StreamCodec[0]);
        objectInitials = collector.objectInitials.toArray();
        fluid = new boolean[collector.fluid.size()];
        for (int i = 0; i < fluid.length; i++) {
            fluid[i] = collector.fluid.get(i);
        }
    }

    /** The layout of {@code values}. */
    static <H> MenuLayout<H> of(MenuValues<H> values) {
        Collector<H> collector = new Collector<>(null);
        values.read(collector);
        return new MenuLayout<>(collector);
    }

    /**
     * The layout of {@code values} with {@code tank}'s fluid replaced by any {@code O} with its own codec, for tests of
     * the encoding that cannot build fluid keys (no registries in plain JUnit). A {@code fluid} read of that tank
     * throws {@link IllegalArgumentException}.
     */
    static <H, O> MenuLayout<H> withTankContent(MenuValues<H> values, TankValue tank, Function<? super H, O> content,
            StreamCodec<? super RegistryFriendlyByteBuf, O> codec, O empty) {
        Collector<H> collector = new Collector<>(new Replacement<>(tank.index(), content, codec, empty));
        values.read(collector);
        return new MenuLayout<>(collector);
    }

    int size() {
        return kinds.length;
    }

    private static int[] toInts(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private record Replacement<H, O>(int index, Function<? super H, O> content,
            StreamCodec<? super RegistryFriendlyByteBuf, O> codec, O empty) {
    }

    private static final class Collector<H> implements MenuValues.Reader<H> {
        private final @Nullable Replacement<H, ?> replacement;
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
        private final List<Boolean> fluid = new ArrayList<>();

        Collector(@Nullable Replacement<H, ?> replacement) {
            this.replacement = replacement;
        }

        private void add(Kind kind, Enum<?>[] constants) {
            kinds.add(kind);
            firstInt.add(intReaders.size());
            firstLong.add(longReaders.size());
            firstObject.add(objectReaders.size());
            enumConstants.add(constants);
        }

        @Override
        public void intValue(IntValue handle, ToIntFunction<? super H> getter) {
            add(Kind.INT, NO_CONSTANTS);
            intReaders.add(getter);
        }

        @Override
        public void longValue(LongValue handle, ToLongFunction<? super H> getter) {
            add(Kind.LONG, NO_CONSTANTS);
            longReaders.add(getter);
        }

        @Override
        public void enumValue(EnumValue<?> handle, Enum<?>[] constants, ToIntFunction<? super H> ordinal) {
            add(Kind.ENUM, constants);
            intReaders.add(ordinal);
        }

        @Override
        public void energy(EnergyValue handle, ToLongFunction<? super H> stored, ToLongFunction<? super H> capacity) {
            add(Kind.ENERGY, NO_CONSTANTS);
            longReaders.add(stored);
            longReaders.add(capacity);
        }

        @Override
        public void progress(ProgressValue handle, ToLongFunction<? super H> current, ToLongFunction<? super H> total) {
            add(Kind.PROGRESS, NO_CONSTANTS);
            longReaders.add(current);
            longReaders.add(total);
        }

        @Override
        public void tank(TankValue handle, Function<? super H, Optional<FluidKey>> fluidGetter,
                ToLongFunction<? super H> amount, ToLongFunction<? super H> capacity) {
            add(Kind.TANK, NO_CONSTANTS);
            Replacement<H, ?> other = replacement;
            if (other != null && other.index() == handle.index()) {
                addReplacement(other);
            } else {
                addContent(fluidGetter, FluidKey.OPTIONAL_STREAM_CODEC, Optional.empty(), true);
            }
            longReaders.add(amount);
            longReaders.add(capacity);
        }

        private <O> void addReplacement(Replacement<H, O> other) {
            addContent(other.content(), other.codec(), other.empty(), false);
        }

        @SuppressWarnings("unchecked") // the codec only ever sees values from its own reader
        private <O> void addContent(Function<? super H, O> content,
                StreamCodec<? super RegistryFriendlyByteBuf, O> codec, O empty, boolean isFluid) {
            objectReaders.add(content);
            objectCodecs.add((StreamCodec<? super RegistryFriendlyByteBuf, Object>) (StreamCodec<?, ?>) codec);
            objectInitials.add(empty);
            fluid.add(isFluid);
        }
    }
}
