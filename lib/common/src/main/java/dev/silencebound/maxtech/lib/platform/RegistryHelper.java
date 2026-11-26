package dev.silencebound.maxtech.lib.platform;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public interface RegistryHelper {
    /**
     * Creates a deferred registrar for one registry and mod and attaches it to that mod's lifecycle. Must be called
     * during mod construction (typically from the static initialiser of a {@code ModBlocks}-style class that the
     * entrypoint touches), because registration events fire right after construction.
     */
    <R> Registrar<R> registrar(String modId, ResourceKey<? extends Registry<R>> registry);

    interface Registrar<R> {
        /** Queues {@code factory} for registration under {@code modId:name}. */
        <T extends R> RegistryEntry<R, T> register(String name, Supplier<? extends T> factory);

        /**
         * Like {@link #register(String, Supplier)}, but the factory receives the entry's id; blocks and items need it
         * for {@code Properties.setId(...)} before they are constructed.
         */
        <T extends R> RegistryEntry<R, T> register(String name, Function<Identifier, ? extends T> factory);
    }
}
