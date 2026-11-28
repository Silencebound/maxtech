package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.LongValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.resource.UnknownResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
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
    /** How a kind tank's resource travels: kind id and length-framed key, so unknown kinds are skipped. */
    private static final StreamCodec<RegistryFriendlyByteBuf, Optional<KindKey<?>>> OPTIONAL_KIND_CODEC = ByteBufCodecs
            .optional(KindKey.CLIENTBOUND_STREAM_CODEC);
    /**
     * The fluid kind's id. Kind tanks are told apart from fluid tanks by id, so a layout without fluids never loads
     * {@link ResourceKinds}, whose built-in kinds need registries that JUnit tests lack.
     */
    private static final Identifier FLUID_ID = Identifier.fromNamespaceAndPath("maxtech_lib", "fluid");
    /** A {@link ResourceTank} getter's change key while the tank holds unknown contents. */
    private static final Object UNKNOWN = new Object();

    final Kind[] kinds;
    /** First int, long and object field of each value. */
    final int[] firstInt;
    final int[] firstLong;
    final int[] firstObject;
    final Enum<?>[][] enumConstants;
    final ToIntFunction<? super H>[] intReaders;
    final ToLongFunction<? super H>[] longReaders;
    /** Per object field: what is read every tick and compared with {@code equals} to find a change. */
    final Function<? super H, ?>[] objectReaders;
    /** Per object field: what is sent once the change key changed; null to send the change key itself. */
    final @Nullable Function<? super H, ?>[] objectContents;
    final StreamCodec<? super RegistryFriendlyByteBuf, Object>[] objectCodecs;
    final Object[] objectInitials;
    /** Per object field: the sent content as a resource of any kind. */
    final Function<Object, Optional<KindKey<?>>>[] toResource;
    /** Per object field: the sent content as a fluid, or null for a tank not declared as a fluid tank. */
    final @Nullable Function<Object, Optional<FluidKey>>[] toFluid;
    /** Per object field: the kind the tank was declared with, null for a tank of any kind or a fluid tank. */
    final @Nullable ResourceKind<?>[] declaredKinds;

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
        objectContents = collector.objectContents.toArray(new Function[0]);
        objectCodecs = collector.objectCodecs.toArray(new StreamCodec[0]);
        objectInitials = collector.objectInitials.toArray();
        toResource = collector.toResource.toArray(new Function[0]);
        toFluid = collector.toFluid.toArray(new Function[0]);
        declaredKinds = collector.declaredKinds.toArray(new ResourceKind<?>[0]);
    }

    /** The layout of {@code values}. */
    static <H> MenuLayout<H> of(MenuValues<H> values) {
        Collector<H> collector = new Collector<>(null, OPTIONAL_KIND_CODEC);
        values.read(collector);
        return new MenuLayout<>(collector);
    }

    /** The layout of {@code values} with kind tanks sent through {@code kindCodec}, for tests with their own kinds. */
    static <H> MenuLayout<H> withKindCodec(MenuValues<H> values,
            StreamCodec<? super RegistryFriendlyByteBuf, Optional<KindKey<?>>> kindCodec) {
        Collector<H> collector = new Collector<>(null, kindCodec);
        values.read(collector);
        return new MenuLayout<>(collector);
    }

    /**
     * The layout of {@code values} with {@code tank}'s fluid replaced by any {@code O} with its own codec, for tests of
     * the encoding that cannot build fluid keys (no registries in plain JUnit). A {@code fluid} read of that tank
     * throws {@link IllegalArgumentException}, and its {@code resource} is empty.
     */
    static <H, O> MenuLayout<H> withTankContent(MenuValues<H> values, TankValue tank, Function<? super H, O> content,
            StreamCodec<? super RegistryFriendlyByteBuf, O> codec, O empty) {
        Collector<H> collector = new Collector<>(new Replacement<>(tank.index(), content, codec, empty),
                OPTIONAL_KIND_CODEC);
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
        private final StreamCodec<? super RegistryFriendlyByteBuf, Optional<KindKey<?>>> kindCodec;
        private final List<Kind> kinds = new ArrayList<>();
        private final List<Integer> firstInt = new ArrayList<>();
        private final List<Integer> firstLong = new ArrayList<>();
        private final List<Integer> firstObject = new ArrayList<>();
        private final List<Enum<?>[]> enumConstants = new ArrayList<>();
        private final List<ToIntFunction<? super H>> intReaders = new ArrayList<>();
        private final List<ToLongFunction<? super H>> longReaders = new ArrayList<>();
        private final List<Function<? super H, ?>> objectReaders = new ArrayList<>();
        private final List<@Nullable Function<? super H, ?>> objectContents = new ArrayList<>();
        private final List<StreamCodec<? super RegistryFriendlyByteBuf, Object>> objectCodecs = new ArrayList<>();
        private final List<Object> objectInitials = new ArrayList<>();
        private final List<Function<Object, Optional<KindKey<?>>>> toResource = new ArrayList<>();
        private final List<@Nullable Function<Object, Optional<FluidKey>>> toFluid = new ArrayList<>();
        private final List<@Nullable ResourceKind<?>> declaredKinds = new ArrayList<>();

        Collector(@Nullable Replacement<H, ?> replacement,
                StreamCodec<? super RegistryFriendlyByteBuf, Optional<KindKey<?>>> kindCodec) {
            this.replacement = replacement;
            this.kindCodec = kindCodec;
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
        @SuppressWarnings("unchecked") // the fluid getter's own values
        public void tank(TankValue handle, Function<? super H, Optional<FluidKey>> fluidGetter,
                ToLongFunction<? super H> amount, ToLongFunction<? super H> capacity) {
            add(Kind.TANK, NO_CONSTANTS);
            Replacement<H, ?> other = replacement;
            if (other != null && other.index() == handle.index()) {
                addReplacement(other);
            } else {
                addContent(null, fluidGetter, null, FluidKey.OPTIONAL_STREAM_CODEC, Optional.empty(),
                        fluid -> ((Optional<FluidKey>) fluid)
                                .<KindKey<?>>map(key -> new KindKey<>(ResourceKinds.FLUID, key)),
                        fluid -> (Optional<FluidKey>) fluid);
            }
            longReaders.add(amount);
            longReaders.add(capacity);
        }

        @Override
        public void kindTank(TankValue handle, @Nullable ResourceKind<?> kind, Function<? super H, ?> changeKey,
                @Nullable Function<? super H, Optional<KindKey<?>>> content, ToLongFunction<? super H> amount,
                ToLongFunction<? super H> capacity) {
            add(Kind.TANK, NO_CONSTANTS);
            addKindContent(kind, changeKey, content);
            longReaders.add(amount);
            longReaders.add(capacity);
        }

        @Override
        public <K> void resourceTank(TankValue handle, ResourceKind<K> kind,
                Function<? super H, ? extends ResourceTank<K>> tank) {
            add(Kind.TANK, NO_CONSTANTS);
            Function<H, Object> changeKey = host -> {
                ResourceTank<K> held = tank.apply(host);
                return held.unknown() ? UNKNOWN : held.resource();
            };
            // The kind check needs a host, so it runs when the content is first built, not at declaration.
            Function<H, Optional<KindKey<?>>> content = host -> {
                ResourceTank<K> held = tank.apply(host);
                if (held.kind() != kind) {
                    throw new IllegalStateException("Menu tank of " + kind.id() + " got a tank of " + held.kind().id());
                }
                return held.unknown()
                        ? Optional.of(unknown(kind))
                        : held.resource().<KindKey<?>>map(key -> new KindKey<>(kind, key));
            };
            addKindContent(kind, changeKey, content);
            longReaders.add(host -> {
                ResourceTank<K> held = tank.apply(host);
                return held.unknown() ? held.unknownAmount() : held.amount();
            });
            longReaders.add(host -> tank.apply(host).capacity());
        }

        @SuppressWarnings("unchecked") // the codec's own values
        private void addKindContent(@Nullable ResourceKind<?> kind, Function<? super H, ?> changeKey,
                @Nullable Function<? super H, ?> content) {
            addContent(kind, changeKey, content, kindCodec, Optional.empty(),
                    resource -> (Optional<KindKey<?>>) resource,
                    kind != null && kind.id().equals(FLUID_ID)
                            ? resource -> ((Optional<KindKey<?>>) resource).flatMap(key -> key.as(ResourceKinds.FLUID))
                            : null);
        }

        private <O> void addReplacement(Replacement<H, O> other) {
            addContent(null, other.content(), null, other.codec(), other.empty(), content -> Optional.empty(), null);
        }

        @SuppressWarnings("unchecked") // the codec only ever sees values from its own reader
        private <O> void addContent(@Nullable ResourceKind<?> kind, Function<? super H, ?> changeKey,
                @Nullable Function<? super H, ?> content, StreamCodec<? super RegistryFriendlyByteBuf, O> codec,
                O empty, Function<Object, Optional<KindKey<?>>> resource,
                @Nullable Function<Object, Optional<FluidKey>> fluid) {
            objectReaders.add(changeKey);
            objectContents.add(content);
            objectCodecs.add((StreamCodec<? super RegistryFriendlyByteBuf, Object>) (StreamCodec<?, ?>) codec);
            objectInitials.add(empty);
            toResource.add(resource);
            toFluid.add(fluid);
            declaredKinds.add(kind);
        }
    }

    /** Unknown contents of a tank of {@code kind}: only the kind id, which is all the client gets anyway. */
    private static KindKey<UnknownResource> unknown(ResourceKind<?> kind) {
        CompoundTag raw = new CompoundTag();
        raw.putString(KindKey.KIND_FIELD, kind.id().toString());
        return UnknownResource.key(kind.id(), raw);
    }
}
