package dev.silencebound.maxtech.storage.research.puzzle;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * The puzzle types by id. Research nodes name a puzzle by this id; a node whose id is not registered falls back to its
 * tasks or points. Types are registered during mod construction and read on the server and client threads afterwards,
 * so the map is never written once the game runs.
 */
public final class PuzzleTypes {
    private static final Map<Identifier, PuzzleType<?>> TYPES = new LinkedHashMap<>();

    private PuzzleTypes() {
    }

    /** Adds {@code type}; call during mod construction. Throws when its id is taken. */
    public static synchronized <S> PuzzleType<S> register(PuzzleType<S> type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate puzzle type " + type.id());
        }
        return type;
    }

    /** The type with {@code id}, or empty when none is registered. */
    public static Optional<PuzzleType<?>> get(Identifier id) {
        return Optional.ofNullable(TYPES.get(id));
    }

    /** Every registered type in registration order. */
    public static Collection<PuzzleType<?>> all() {
        return Collections.unmodifiableCollection(TYPES.values());
    }
}
