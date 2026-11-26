package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

/**
 * The cell type registry, {@code maxtech_storage:cell_type}. Storage's NeoForge glue creates it during registry setup;
 * Storage and other mods add entries to {@link #REGISTRY_KEY} with their loader's deferred registration like any other
 * registry. Synced, so a client with other types is refused on login. Lookups are safe on any thread once it exists.
 */
public final class CellTypes {
    /** Key of the registry. */
    public static final ResourceKey<Registry<CellType>> REGISTRY_KEY = ResourceKey
            .createRegistryKey(MaxtechStorage.id("cell_type"));

    private static volatile @Nullable Registry<CellType> registry;

    private CellTypes() {
    }

    /** The registry; throws {@link IllegalStateException} before it has been created. */
    @SuppressWarnings("unchecked") // the root registry maps REGISTRY_KEY to a Registry<CellType>
    public static Registry<CellType> registry() {
        Registry<CellType> result = registry;
        if (result == null) {
            Registry<?> found = BuiltInRegistries.REGISTRY.getValue(REGISTRY_KEY.identifier());
            if (found == null) {
                throw new IllegalStateException(
                        "Cell type registry " + REGISTRY_KEY.identifier() + " has not been created");
            }
            result = (Registry<CellType>) found;
            registry = result;
        }
        return result;
    }

    /** The type registered under {@code id}; {@code null} for an unknown id (a type of a mod that was removed). */
    public static @Nullable CellType byId(Identifier id) {
        return registry().getValue(id);
    }

    /** The lang key of a type's name: {@code cell_type.<namespace>.<path>}. */
    public static String translationKey(Identifier id) {
        return id.toLanguageKey("cell_type");
    }
}
