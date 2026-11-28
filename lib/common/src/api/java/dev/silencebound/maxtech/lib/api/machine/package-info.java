/**
 * Building blocks for machines (ADR-0019), composed into a mod's own block entity: the working loop
 * ({@link dev.silencebound.maxtech.lib.api.machine.MachineProcess}, which reports a
 * {@link dev.silencebound.maxtech.lib.api.machine.MachineStatus}) and the redstone control
 * ({@link dev.silencebound.maxtech.lib.api.machine.RedstoneControl} with its
 * {@link dev.silencebound.maxtech.lib.api.machine.RedstoneMode}), which a menu sets through a cycle button
 * ({@code MenuDefinition.Builder#cycle}). Experimental until Farms ships.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.machine;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
