package dev.silencebound.maxtech.lib.api.registry;

import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * A registered object. {@link #get()} throws until the registry has been populated; {@link #id()} and {@link #key()}
 * are available immediately.
 *
 * @param <R>
 *            the registry's element type
 * @param <T>
 *            the concrete type of this entry
 */
public interface RegistryEntry<R, T extends R> extends Supplier<T> {
    /** The entry's id, {@code <modid>:<name>}. */
    Identifier id();

    /** The entry's key in its registry. */
    ResourceKey<R> key();

    /** The registry holder, for vanilla APIs that take a {@code Holder<R>}. */
    Holder<R> holder();
}
