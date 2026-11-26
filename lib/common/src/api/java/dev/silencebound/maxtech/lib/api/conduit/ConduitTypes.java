package dev.silencebound.maxtech.lib.api.conduit;

import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

/**
 * The conduit type registry. Lib creates it during registry setup; addons add entries to {@link #REGISTRY_KEY} with
 * their loader's deferred registration (on NeoForge a {@code DeferredRegister} for this key) like any other registry.
 * The lookups below are safe from any thread once the registry exists.
 */
public final class ConduitTypes {
    /** Key of the conduit type registry, {@code maxtech_lib:conduit_type}. */
    public static final ResourceKey<Registry<ConduitType>> REGISTRY_KEY = ResourceKey
            .createRegistryKey(Identifier.fromNamespaceAndPath("maxtech_lib", "conduit_type"));

    private static volatile @Nullable Registry<ConduitType> registry;

    private ConduitTypes() {
    }

    /**
     * The registry; throws {@link IllegalStateException} if called before it has been created (before registration).
     */
    @SuppressWarnings("unchecked") // the root registry maps REGISTRY_KEY to a Registry<ConduitType>
    public static Registry<ConduitType> registry() {
        Registry<ConduitType> result = registry;
        if (result == null) {
            Registry<?> found = BuiltInRegistries.REGISTRY.getValue(REGISTRY_KEY.identifier());
            if (found == null) {
                throw new IllegalStateException(
                        "Conduit type registry " + REGISTRY_KEY.identifier() + " has not been created");
            }
            result = (Registry<ConduitType>) found;
            registry = result;
        }
        return result;
    }

    /** The conduit type registered under {@code id}, or empty if there is none. */
    public static Optional<ConduitType> byId(Identifier id) {
        return registry().getOptional(id);
    }

    /** The id {@code type} is registered under; throws {@link IllegalArgumentException} if it is not registered. */
    public static Identifier idOf(ConduitType type) {
        Identifier id = registry().getKey(type);
        if (id == null) {
            throw new IllegalArgumentException(type + " is not registered");
        }
        return id;
    }
}
