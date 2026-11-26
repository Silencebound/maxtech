package dev.silencebound.maxtech.storage.part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Every {@link PartType}, by id. Device areas register their part types while their content class loads, during mod
 * construction; saves and packets name a part by its type's id. A plain static table: part types are code, not data.
 */
public final class PartTypes {
    private static final Map<Identifier, PartType> TYPES = new LinkedHashMap<>();

    private PartTypes() {
    }

    /** Registers {@code type}; throws if its id is taken. Mod construction only. */
    public static synchronized PartType register(PartType type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Part type registered twice: " + type.id());
        }
        return type;
    }

    /** The part type with {@code id}, or null for an unknown id (a part of a removed mod in an old save). */
    public static @Nullable PartType byId(Identifier id) {
        return TYPES.get(id);
    }

    /** Every part type in registration order. */
    public static List<PartType> all() {
        return Collections.unmodifiableList(new ArrayList<>(TYPES.values()));
    }
}
