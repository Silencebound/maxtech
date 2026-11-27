package dev.silencebound.maxtech.lib.api.registry;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where a mod gets its {@link Registrar}s. Lib sets this up in its own construction, so a mod must declare its
 * {@code maxtech_lib} dependency with {@code ordering = "AFTER"} in {@code neoforge.mods.toml}.
 */
public final class Registrars {
    private static volatile Factory factory = new Factory() {
        @Override
        public <R> Registrar<R> create(String modId, ResourceKey<? extends Registry<R>> registry) {
            throw new IllegalStateException("MaxTech Lib has not set up registrars yet");
        }
    };

    private Registrars() {
    }

    /**
     * A deferred registrar for {@code registry} and {@code modId}, attached to that mod's lifecycle. Call during mod
     * construction (typically from the static initialiser of a class the entrypoint touches), because registration runs
     * right after construction.
     */
    public static <R> Registrar<R> create(String modId, ResourceKey<? extends Registry<R>> registry) {
        return factory.create(modId, registry);
    }

    /** Sets where {@link #create} gets its registrars; Lib calls it once during init. Unit tests may set a fake. */
    @ApiStatus.Internal
    public static void setFactory(Factory source) {
        factory = source;
    }

    /** Lib's side of {@link Registrars}. */
    @ApiStatus.Internal
    public interface Factory {
        /** See {@link Registrars#create}. */
        <R> Registrar<R> create(String modId, ResourceKey<? extends Registry<R>> registry);
    }
}
