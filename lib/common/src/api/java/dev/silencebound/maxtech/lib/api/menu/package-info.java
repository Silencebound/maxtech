/**
 * Menu values synced from server to client for MaxTech screens (ADR-0016), on both sides. A menu declares its values
 * once ({@link dev.silencebound.maxtech.lib.api.menu.MenuValues#builder()}) with getters over a server-side host (a
 * block entity, say) and gets a handle per value: {@link dev.silencebound.maxtech.lib.api.menu.IntValue},
 * {@link dev.silencebound.maxtech.lib.api.menu.LongValue}, {@link dev.silencebound.maxtech.lib.api.menu.EnumValue},
 * {@link dev.silencebound.maxtech.lib.api.menu.EnergyValue},
 * {@link dev.silencebound.maxtech.lib.api.menu.ProgressValue} and
 * {@link dev.silencebound.maxtech.lib.api.menu.TankValue}. Screens read them through a
 * {@link dev.silencebound.maxtech.lib.api.menu.MenuValueView}.
 * <p>
 * Lib's menu reads every getter each server tick without boxing or allocating, and sends the values that changed in one
 * payload, at most once a tick and only when something changed; nothing goes through vanilla data slots, so longs and
 * fluids with components arrive whole. Both sides must run the same build: values travel by position in the declaration
 * and enums by ordinal. Experimental until Farms (FM1) settles it.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.menu;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
