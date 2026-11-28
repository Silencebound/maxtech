package dev.silencebound.maxtech.lib.api.transfer;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TransferPort}'s player settings (ADR-0018), per resource kind: the six face modes ({@link PortFaces}), an
 * optional {@link PortFilter} for the whole port, and a rate cap per operation (0 for no player cap; the port then
 * moves up to its owner's cap and the {@code transfer.*PerOp} config). A kind with no settings has every face off, no
 * filter and no cap. Which modes a face may take follows from the buffers the port owner added
 * ({@link TransferPorts.Builder}); those buffer roles are code, not saved here, and the port puts loaded settings back
 * within them.
 * <p>
 * Saved by kind id ({@link #CODEC}). Settings of a kind no loaded mod registers, or that no longer load, are kept as
 * saved and saved back unchanged ({@link #unknownKinds()}), like {@code UnknownResource}, after the entries of known
 * kinds (so the order of the saved list can change). Such a raw entry stays, even beside a valid entry of the same
 * kind, until settings of that kind are set again through a {@code with} method, which replaces it. A known kind's
 * entry is saved from what it decodes to: fields in it that this version does not know are dropped on the next save.
 * <p>
 * Immutable; the {@code with} methods return changed copies. Reads allocate nothing except {@link #filter} and
 * {@link #kinds()}; any thread.
 */
@ApiStatus.Experimental
public final class PortSettings {
    private static final String KINDS = "kinds";
    private static final String KIND = "kind";
    private static final Set<PortFaceMode> ANY_MODE = EnumSet.allOf(PortFaceMode.class);

    /** No settings for any kind. */
    public static final PortSettings EMPTY = new PortSettings(new ResourceKind<?>[0], new int[0], new long[0],
            new PortFilter<?>[0], List.of());

    /**
     * {@code {"kinds": [{"kind": <kind id>, "faces": <packed PortFaces>, "rate": <cap>, "filter": {...}}, ...]}},
     * leaving out fields at their default; the filter as {@link PortFilter#codec}. Give registry-aware ops for item and
     * fluid filters.
     */
    public static final Codec<PortSettings> CODEC = codec(ResourceKinds::byId);

    private final ResourceKind<?>[] kinds;
    private final int[] faces;
    private final long[] rates;
    private final @Nullable PortFilter<?>[] filters;
    private final List<Tag> unknown;

    private PortSettings(ResourceKind<?>[] kinds, int[] faces, long[] rates, @Nullable PortFilter<?>[] filters,
            List<Tag> unknown) {
        this.kinds = kinds;
        this.faces = faces;
        this.rates = rates;
        this.filters = filters;
        this.unknown = List.copyOf(unknown);
    }

    /** The packed face modes of {@code kind}; {@link PortFaces#NONE} without settings. */
    public int faces(ResourceKind<?> kind) {
        int index = indexOf(kind);
        return index < 0 ? PortFaces.NONE : faces[index];
    }

    /** The mode of {@code face} for {@code kind}. */
    public PortFaceMode mode(ResourceKind<?> kind, Direction face) {
        return PortFaces.get(faces(kind), face);
    }

    /** The player's rate cap per operation of {@code kind} in its unit, or 0 for no player cap. */
    public long rate(ResourceKind<?> kind) {
        int index = indexOf(kind);
        return index < 0 ? 0 : rates[index];
    }

    /** The filter of {@code kind}, or empty when the port moves every resource of it. */
    @SuppressWarnings("unchecked") // filters[i] is of kinds[i]
    public <K> Optional<PortFilter<K>> filter(ResourceKind<K> kind) {
        int index = indexOf(kind);
        return index < 0 ? Optional.empty() : Optional.ofNullable((PortFilter<K>) filters[index]);
    }

    /** Whether the port may move {@code key} of {@code kind}: true without a filter. Allocates nothing. */
    @SuppressWarnings("unchecked") // filters[i] is of kinds[i]
    public <K> boolean allows(ResourceKind<K> kind, K key) {
        int index = indexOf(kind);
        PortFilter<K> filter = index < 0 ? null : (PortFilter<K>) filters[index];
        return filter == null || filter.test(key);
    }

    /** The kinds with settings, in the order they were first set or loaded. */
    public List<ResourceKind<?>> kinds() {
        return List.of(kinds);
    }

    /** Saved settings of kinds that are not loaded or did not load, as saved. */
    public List<Tag> unknownKinds() {
        return unknown;
    }

    /** These settings with {@code kind}'s faces set to {@code packed} ({@link PortFaces}). */
    public PortSettings withFaces(ResourceKind<?> kind, int packed) {
        int index = indexOf(kind);
        return with(kind, packed & PortFaces.ALL_BITS, index < 0 ? 0 : rates[index], index < 0 ? null : filters[index]);
    }

    /** These settings with {@code face} of {@code kind} set to {@code mode}. */
    public PortSettings withMode(ResourceKind<?> kind, Direction face, PortFaceMode mode) {
        return withFaces(kind, PortFaces.with(faces(kind), face, mode));
    }

    /** These settings with {@code kind}'s rate cap set to {@code cap} (0 for none); throws for a negative cap. */
    public PortSettings withRate(ResourceKind<?> kind, long cap) {
        if (cap < 0) {
            throw new IllegalArgumentException("Negative rate cap " + cap);
        }
        int index = indexOf(kind);
        return with(kind, faces(kind), cap, index < 0 ? null : filters[index]);
    }

    /** These settings with {@code kind}'s filter set, or removed for an empty {@code filter}. */
    public <K> PortSettings withFilter(ResourceKind<K> kind, Optional<PortFilter<K>> filter) {
        filter.ifPresent(f -> {
            if (f.kind() != kind) {
                throw new IllegalArgumentException("A filter of " + f.kind() + " set for " + kind);
            }
        });
        return with(kind, faces(kind), rate(kind), filter.orElse(null));
    }

    private PortSettings with(ResourceKind<?> kind, int packed, long rate, @Nullable PortFilter<?> filter) {
        Identifier id = kind.id();
        List<Tag> keptUnknown = new ArrayList<>();
        for (Tag raw : unknown) {
            if (!Objects.equals(id, kindOf(raw))) {
                keptUnknown.add(raw);
            }
        }
        int index = indexOf(kind);
        boolean empty = packed == PortFaces.NONE && rate == 0 && filter == null;
        int size = kinds.length + (index < 0 && !empty ? 1 : 0) - (index >= 0 && empty ? 1 : 0);
        ResourceKind<?>[] newKinds = new ResourceKind<?>[size];
        int[] newFaces = new int[size];
        long[] newRates = new long[size];
        @Nullable
        PortFilter<?>[] newFilters = new PortFilter<?>[size];
        int at = 0;
        for (int i = 0; i <= kinds.length; i++) {
            boolean target = i == (index < 0 ? kinds.length : index);
            if (target && !empty) {
                newKinds[at] = kind;
                newFaces[at] = packed;
                newRates[at] = rate;
                newFilters[at++] = filter;
            } else if (!target && i < kinds.length) {
                newKinds[at] = kinds[i];
                newFaces[at] = faces[i];
                newRates[at] = rates[i];
                newFilters[at++] = filters[i];
            }
        }
        return new PortSettings(newKinds, newFaces, newRates, newFilters, keptUnknown);
    }

    private int indexOf(ResourceKind<?> kind) {
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == kind) {
                return i;
            }
        }
        return -1;
    }

    private static @Nullable Identifier kindOf(Tag raw) {
        return raw instanceof CompoundTag compound ? Identifier.tryParse(compound.getStringOr(KIND, "")) : null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PortSettings settings && Arrays.equals(kinds, settings.kinds)
                && Arrays.equals(faces, settings.faces) && Arrays.equals(rates, settings.rates)
                && Arrays.equals(filters, settings.filters) && unknown.equals(settings.unknown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(kinds), Arrays.hashCode(faces), Arrays.hashCode(rates),
                Arrays.hashCode(filters), unknown);
    }

    @Override
    public String toString() {
        return "PortSettings[" + Arrays.toString(kinds) + " faces " + Arrays.toString(faces) + " rates "
                + Arrays.toString(rates) + " filters " + Arrays.toString(filters) + " +" + unknown.size() + "]";
    }

    /** {@link #CODEC} with kinds looked up through {@code lookup}; for tests over their own kinds. */
    static Codec<PortSettings> codec(Function<Identifier, Optional<ResourceKind<?>>> lookup) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<PortSettings, T>> decode(DynamicOps<T> ops, T input) {
                return ops.getMap(input).flatMap(map -> {
                    T list = map.get(KINDS);
                    if (list == null) {
                        return DataResult.success(Pair.of(PortSettings.EMPTY, ops.empty()));
                    }
                    return ops.getList(list).map(elements -> {
                        PortSettings[] settings = {PortSettings.EMPTY};
                        elements.accept(element -> settings[0] = settings[0].load(ops, element, lookup));
                        return Pair.of(settings[0], ops.empty());
                    });
                });
            }

            @Override
            public <T> DataResult<T> encode(PortSettings settings, DynamicOps<T> ops, T prefix) {
                ListBuilder<T> list = ops.listBuilder();
                for (int i = 0; i < settings.kinds.length; i++) {
                    list.add(settings.encodeEntry(i, ops));
                }
                for (Tag raw : settings.unknown) {
                    list.add(NbtOps.INSTANCE.convertTo(ops, raw));
                }
                return list.build(ops.empty()).flatMap(kinds -> ops.mapBuilder().add(KINDS, kinds).build(prefix));
            }
        };
    }

    /** These settings with one saved entry added: decoded if its kind is known and not set yet, else kept raw. */
    private <T> PortSettings load(DynamicOps<T> ops, T element,
            Function<Identifier, Optional<ResourceKind<?>>> lookup) {
        Optional<PortSettings> loaded = ops.getMap(element).result().flatMap(map -> {
            T kindValue = map.get(KIND);
            return kindValue == null
                    ? Optional.empty()
                    : Identifier.CODEC.parse(ops, kindValue).result().flatMap(lookup).filter(kind -> indexOf(kind) < 0)
                            .flatMap(kind -> loadEntry(kind, ops, map));
        });
        if (loaded.isPresent()) {
            PortSettings with = loaded.get();
            return new PortSettings(with.kinds, with.faces, with.rates, with.filters, unknown); // a valid entry keeps
                                                                                                // broken raw ones
        }
        List<Tag> kept = new ArrayList<>(unknown);
        kept.add(ops.convertTo(NbtOps.INSTANCE, element));
        return new PortSettings(kinds, faces, rates, filters, kept);
    }

    private <K, T> Optional<PortSettings> loadEntry(ResourceKind<K> kind, DynamicOps<T> ops, MapLike<T> map) {
        return entryCodec(kind).decode(ops, map).result().map(entry -> with(kind,
                PortFaces.sanitise(entry.faces(), ANY_MODE), Math.max(0, entry.rate()), entry.filter().orElse(null)));
    }

    @SuppressWarnings("unchecked") // filters[i] is of kinds[i]
    private <K, T> DataResult<T> encodeEntry(int index, DynamicOps<T> ops) {
        ResourceKind<K> kind = (ResourceKind<K>) kinds[index];
        Entry<K> entry = new Entry<>(faces[index], rates[index], Optional.ofNullable((PortFilter<K>) filters[index]));
        return entryCodec(kind).encode(entry, ops, ops.mapBuilder()).add(KIND, ops.createString(kind.id().toString()))
                .build(ops.empty());
    }

    private static <K> MapCodec<Entry<K>> entryCodec(ResourceKind<K> kind) {
        return RecordCodecBuilder.mapCodec(entry -> entry
                .group(Codec.INT.optionalFieldOf("faces", PortFaces.NONE).forGetter(Entry::faces),
                        Codec.LONG.optionalFieldOf("rate", 0L).forGetter(Entry::rate),
                        PortFilter.codec(kind).optionalFieldOf("filter").forGetter(Entry::filter))
                .apply(entry, Entry::new));
    }

    private record Entry<K>(int faces, long rate, Optional<PortFilter<K>> filter) {
    }
}
