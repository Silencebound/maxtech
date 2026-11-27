/**
 * Network topology shared by cables and storage (PRD 5.2): which nodes form a connected network, per level and
 * {@link dev.silencebound.maxtech.lib.api.network.NetworkType}, reported through the level's
 * {@link dev.silencebound.maxtech.lib.api.network.NetworkManager}. Transport semantics belong to each type's
 * {@link dev.silencebound.maxtech.lib.api.network.NetworkLogic}; nothing here knows what flows through a network.
 * {@link dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode} is stable; the engine types (moved from
 * {@code lib.network} in Lib 0.14.0 for Farms, FM1) are experimental, each marked {@code @ApiStatus.Experimental}.
 */
@NullMarked
package dev.silencebound.maxtech.lib.api.network;

import org.jspecify.annotations.NullMarked;
