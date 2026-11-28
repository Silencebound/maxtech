/**
 * NeoForge side of transfer ports (ADR-0018): {@link dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridge}
 * turns a resource kind's capability into {@link dev.silencebound.maxtech.lib.neoforge.api.transfer.KindHandler}s,
 * registered per kind in {@link dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridges}. Built from
 * {@code lib/neoforge/src/api} and published as {@code maxtech_lib-neoforge-api}.
 * <p>
 * Experimental until Farms (FM1) settles it.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.neoforge.api.transfer;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
