package dev.silencebound.maxtech.lib.api.registry;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;

/**
 * A deferred registrar for one registry and mod, made with {@link Registrars#create}. Entries are queued during mod
 * construction and registered when the loader's registration runs.
 *
 * @param <R>
 *            the registry's element type
 */
public interface Registrar<R> {
    /** Queues {@code factory} for registration under {@code modId:name}. */
    <T extends R> RegistryEntry<R, T> register(String name, Supplier<? extends T> factory);

    /**
     * Like {@link #register(String, Supplier)}, but the factory receives the entry's id; blocks and items need it for
     * {@code Properties.setId(...)} before they are constructed.
     */
    <T extends R> RegistryEntry<R, T> register(String name, Function<Identifier, ? extends T> factory);
}
