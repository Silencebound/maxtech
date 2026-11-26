package dev.silencebound.maxtech.lib.content;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;

/**
 * Content that a mod above Lib adds to every mod's datagen and content index checks, such as materials (ADR-0014):
 * their form items are declared in builders of their own, listed in the index's {@value ContentIndex#MATERIALS} section
 * and checked against the material registry, none of which Lib knows. Register during mod construction; the methods are
 * called during datagen and from GameTests on the server thread.
 */
public interface ContentExtension {
    /**
     * Builders besides {@code modId}'s own whose declarations {@code modId}'s datagen writes, such as what the
     * materials an addon's file declares bring with them. Empty by default.
     */
    default List<Content> declarations(String modId) {
        return List.of();
    }

    /**
     * Fills {@code modId}'s {@value ContentIndex#MATERIALS} section and its form folders
     * ({@value ContentIndex#MATERIAL_FORMS}), and adds the ids it listed to {@code listed}, so the item and block
     * sections leave them out. Called on the datagen thread once registries are frozen.
     */
    default void indexMaterials(String modId, JsonObject materials, Map<String, String> formFolders,
            Set<Identifier> listed) {
    }

    /** Whether the index's {@value ContentIndex#FLUIDS} section leaves {@code fluid} to {@link #indexMaterials}. */
    default boolean listsFluid(Fluid fluid) {
        return false;
    }

    /** Adds to {@code problems} where the running game disagrees with what {@link #indexMaterials} wrote. */
    default void registryProblems(ContentIndex index, List<String> problems) {
    }

    /** Adds an extension; call during mod construction. */
    static void register(ContentExtension extension) {
        Registered.ALL.add(extension);
    }

    /** Every registered extension, in registration order. */
    static List<ContentExtension> all() {
        return Registered.ALL;
    }

    /** Holder for the registered extensions; interfaces cannot have private static fields. */
    final class Registered {
        private static final List<ContentExtension> ALL = new CopyOnWriteArrayList<>();

        private Registered() {
        }
    }
}
