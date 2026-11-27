/**
 * Client-side conduit api on NeoForge, for addons whose conduits draw their own model. Built from
 * {@code lib/neoforge/src/api} and published as {@code maxtech_lib-neoforge-api}; it names NeoForge and client types,
 * which Lib's common api cannot. A conduit marked {@code customModel} on its builder is drawn by a
 * {@link ConduitModelHook}, registered in {@link RegisterConduitModelsEvent}.
 * <p>
 * Experimental: may change in any Lib minor version, also after 1.0.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.neoforge.api.client.conduit;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
