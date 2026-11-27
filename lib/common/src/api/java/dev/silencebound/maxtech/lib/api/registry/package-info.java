/**
 * Registering a mod's game objects (items, blocks, data components, ...) through Lib, so common code never touches the
 * loader's registration: {@code Registrars.create(modId, Registries.ITEM)} during mod construction, then
 * {@link dev.silencebound.maxtech.lib.api.registry.Registrar#register} for each entry. Experimental (Lib 0.14.0) until
 * Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.registry;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
