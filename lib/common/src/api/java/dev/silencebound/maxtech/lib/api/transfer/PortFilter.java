package dev.silencebound.maxtech.lib.api.transfer;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.KindTags;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Which resources of one kind a {@link TransferPort} moves (ADR-0018): an allow or deny list of keys and tags. A port
 * has at most one filter per kind, for all its faces (owner: per port, not per face); a kind without one moves
 * everything. An empty allow list moves nothing, an empty deny list everything. Keys match exactly, components
 * included. Tags match through the kind's tag support ({@link #registerTags}); a kind without one has filters whose
 * tags match nothing.
 * <p>
 * Lib's port screen lets a player add up to {@code transfer.filterSize} entries (keys and tags together); a saved
 * filter loads whole whatever its size. A saved key that no longer loads (its mod or a component's mod was removed) is
 * kept, saved back as saved (after the keys that load, so the list's order can change) and matches nothing, so the
 * entry returns with its mod.
 * <p>
 * Immutable. {@link #test} allocates nothing and is safe from any thread; tags resolve once, when the filter is made,
 * so tag support registered later ({@link #registerTags}) does not reach filters that already exist; register it during
 * mod construction, before any filter loads.
 *
 * @param <K>
 *            the key type of the filter's kind
 */
@ApiStatus.Experimental
public final class PortFilter<K> {
    private final ResourceKind<K> kind;
    private final Mode mode;
    private final List<K> keys;
    private final List<Tag> unknownKeys;
    private final List<Identifier> tags;
    private final List<Predicate<K>> tagTests;

    private PortFilter(ResourceKind<K> kind, Mode mode, List<K> keys, List<Tag> unknownKeys, List<Identifier> tags) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.keys = List.copyOf(keys);
        this.unknownKeys = List.copyOf(unknownKeys);
        this.tags = List.copyOf(tags);
        List<Predicate<K>> tests = new ArrayList<>(this.tags.size());
        for (Identifier tag : this.tags) {
            tests.add(KindTags.test(kind, tag).orElse(key -> false));
        }
        this.tagTests = List.copyOf(tests);
    }

    /** A filter of {@code kind} that allows or denies {@code keys} and members of {@code tags}. */
    public static <K> PortFilter<K> of(ResourceKind<K> kind, Mode mode, List<K> keys, List<Identifier> tags) {
        return new PortFilter<>(kind, mode, keys, List.of(), tags);
    }

    /**
     * Gives {@code kind} tags in filters: {@code resolver} turns a tag id into a test of keys, called once per tag when
     * a filter is made. Lib registers item and fluid tags; a compat module registers its kind's during mod
     * construction. The same as {@link KindTags#register}, which recipe inputs share. Throws
     * {@link IllegalArgumentException} if {@code kind} already has one. Any thread.
     */
    public static <K> void registerTags(ResourceKind<K> kind, Function<Identifier, ? extends Predicate<K>> resolver) {
        KindTags.register(kind, resolver);
    }

    /** Whether the port may move {@code key}: listed or in a listed tag for an allow list, neither for a deny list. */
    public boolean test(K key) {
        boolean listed = false;
        for (int i = 0; i < keys.size() && !listed; i++) {
            listed = keys.get(i).equals(key);
        }
        for (int i = 0; i < tagTests.size() && !listed; i++) {
            listed = tagTests.get(i).test(key);
        }
        return listed == (mode == Mode.ALLOW);
    }

    /** The filter's kind. */
    public ResourceKind<K> kind() {
        return kind;
    }

    /** Allow or deny. */
    public Mode mode() {
        return mode;
    }

    /** The listed keys, without saved keys that no longer load. */
    public List<K> keys() {
        return keys;
    }

    /** Saved keys that no longer load, as saved; kept and saved back, never matched. */
    public List<Tag> unknownKeys() {
        return unknownKeys;
    }

    /** The listed tag ids. */
    public List<Identifier> tags() {
        return tags;
    }

    /** Entries in all: keys, unknown keys and tags. */
    public int size() {
        return keys.size() + unknownKeys.size() + tags.size();
    }

    /** This filter with {@code newMode}, keeping its entries. */
    public PortFilter<K> withMode(Mode newMode) {
        return new PortFilter<>(kind, newMode, keys, unknownKeys, tags);
    }

    /**
     * Saves {@code {"mode": "allow" | "deny", "keys": [...], "tags": [...]}}, keys in the kind's own
     * {@link ResourceKind#codec()}; give registry-aware ops for kinds whose keys carry components.
     */
    public static <K> Codec<PortFilter<K>> codec(ResourceKind<K> kind) {
        return RecordCodecBuilder.create(filter -> filter.group(Mode.CODEC.fieldOf("mode").forGetter(PortFilter::mode),
                keysCodec(kind.codec()).optionalFieldOf("keys", Pair.of(List.of(), List.of()))
                        .forGetter(f -> Pair.of(f.keys, f.unknownKeys)),
                Identifier.CODEC.listOf().optionalFieldOf("tags", List.of()).forGetter(PortFilter::tags)).apply(filter,
                        (mode, keys, tags) -> new PortFilter<>(kind, mode, keys.getFirst(), keys.getSecond(), tags)));
    }

    /** A list of keys where each element that fails to decode is kept as its saved tag. */
    private static <K> Codec<Pair<List<K>, List<Tag>>> keysCodec(Codec<K> codec) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<Pair<List<K>, List<Tag>>, T>> decode(DynamicOps<T> ops, T input) {
                return ops.getList(input).map(elements -> {
                    List<K> known = new ArrayList<>();
                    List<Tag> unknown = new ArrayList<>();
                    elements.accept(element -> codec.parse(ops, element).ifSuccess(known::add)
                            .ifError(error -> unknown.add(ops.convertTo(NbtOps.INSTANCE, element))));
                    return Pair.of(Pair.of(known, unknown), ops.empty());
                });
            }

            @Override
            public <T> DataResult<T> encode(Pair<List<K>, List<Tag>> keys, DynamicOps<T> ops, T prefix) {
                ListBuilder<T> list = ops.listBuilder();
                for (K key : keys.getFirst()) {
                    list.add(codec.encodeStart(ops, key));
                }
                for (Tag raw : keys.getSecond()) {
                    list.add(NbtOps.INSTANCE.convertTo(ops, raw));
                }
                return list.build(prefix);
            }
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PortFilter<?> filter && kind == filter.kind && mode == filter.mode
                && keys.equals(filter.keys) && unknownKeys.equals(filter.unknownKeys) && tags.equals(filter.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, mode, keys, unknownKeys, tags);
    }

    @Override
    public String toString() {
        return "PortFilter[" + kind.id() + " " + mode + " " + keys + " " + tags + " +" + unknownKeys.size() + "]";
    }

    /** Whether a filter lists what may move or what may not. */
    public enum Mode {
        /** Only listed resources move. */
        ALLOW("allow"),
        /** Everything but listed resources moves. */
        DENY("deny");

        /** Saves the mode by its {@link #id()}. */
        public static final Codec<Mode> CODEC = Codec.STRING.comapFlatMap(
                id -> id.equals("allow")
                        ? DataResult.success(ALLOW)
                        : id.equals("deny") ? DataResult.success(DENY) : DataResult.error(() -> "No filter mode " + id),
                Mode::id);

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        /** The stable saved name, {@code allow} or {@code deny}. */
        public String id() {
            return id;
        }
    }
}
