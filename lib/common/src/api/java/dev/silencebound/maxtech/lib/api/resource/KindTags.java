package dev.silencebound.maxtech.lib.api.resource;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Tags for a {@link ResourceKind}, which knows nothing of tags itself: a kind whose resources sit in a registry with
 * tags registers how a tag id ({@code c:water}) becomes a test of its keys. Port filters and machine recipe inputs
 * match tags through it. Lib registers the item and fluid kinds; a compat module registers its kind's during mod
 * construction, like the kind. A kind without a test has tags that match nothing in port filters and cannot be a recipe
 * input by tag. Lookups are safe from any thread.
 */
@ApiStatus.Experimental
public final class KindTags {
    private static final Map<ResourceKind<?>, Function<Identifier, ? extends Predicate<?>>> TESTS = new ConcurrentHashMap<>();

    private KindTags() {
    }

    /**
     * Gives {@code kind} tags: {@code resolver} turns a tag id into a test of keys. It runs once per tag where one is
     * used (a filter made, a recipe loaded), the test on every match, so the test should resolve the tag up front and
     * allocate nothing.
     *
     * @throws IllegalArgumentException
     *             if {@code kind} already has tags
     */
    public static <K> void register(ResourceKind<K> kind, Function<Identifier, ? extends Predicate<K>> resolver) {
        if (TESTS.putIfAbsent(kind, resolver) != null) {
            throw new IllegalArgumentException(kind + " already has tag support");
        }
    }

    /** Whether {@code kind} has tags. */
    public static boolean supports(ResourceKind<?> kind) {
        return TESTS.containsKey(kind);
    }

    /** The test of whether a key of {@code kind} is in {@code tag}; empty when the kind has no tags. */
    @SuppressWarnings("unchecked") // register pairs each kind with a resolver of its own key type
    public static <K> Optional<Predicate<K>> test(ResourceKind<K> kind, Identifier tag) {
        Function<Identifier, ? extends Predicate<?>> resolver = TESTS.get(kind);
        return resolver == null ? Optional.empty() : Optional.of((Predicate<K>) resolver.apply(tag));
    }
}
