package dev.silencebound.maxtech.lib.platform;

/**
 * A registered object, as Lib's own registrars return it; addons see the api's
 * {@link dev.silencebound.maxtech.lib.api.registry.RegistryEntry}.
 *
 * @param <R>
 *            the registry's element type
 * @param <T>
 *            the concrete type of this entry
 */
public interface RegistryEntry<R, T extends R> extends dev.silencebound.maxtech.lib.api.registry.RegistryEntry<R, T> {
}
