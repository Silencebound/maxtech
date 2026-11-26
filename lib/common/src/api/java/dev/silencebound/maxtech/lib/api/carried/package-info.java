/**
 * Finding items a player carries (Storage's wireless gadgets, belts and the like):
 * {@link dev.silencebound.maxtech.lib.api.carried.CarriedItems} looks through the player's inventory and then every
 * registered {@link dev.silencebound.maxtech.lib.api.carried.CarriedItemSource}, such as accessory slots of another
 * mod, and names where a found stack sits with a {@link dev.silencebound.maxtech.lib.api.carried.CarriedSlot}.
 * <p>
 * Experimental: Storage's gadgets are the one user so far, so every type here may change in any Lib minor version, also
 * after 1.0.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.carried;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
