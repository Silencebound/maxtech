/**
 * A conduit is Lib's generic framework for blocks that connect to their neighbours: cables now, later pipes and wires.
 * One {@link dev.silencebound.maxtech.lib.api.conduit.ConduitType} per block, per-face modes, shapes and colours, and
 * membership in a network of the type's network type. It has nothing to do with vanilla's Conduit block (the underwater
 * beacon), and the player never sees the word: in text it is a "cable".
 * <p>
 * Each conduit type gets a block of its own, built from Lib's conduit block, holding one conduit (ADR-0012). Lib owns
 * that block and its block entity: it saves, syncs and draws them, takes dyes and facades, and adds each conduit to the
 * network of its type's {@link dev.silencebound.maxtech.lib.api.conduit.ConduitType#networkType()}.
 * <p>
 * An addon on the api jar alone adds a conduit like this (ADR-0015; {@code docs/api/conduit-addon.md} has a sketch):
 * <ol>
 * <li>Register a network type with {@link dev.silencebound.maxtech.lib.api.network.NetworkType#register
 * NetworkType.register}, passing {@link dev.silencebound.maxtech.lib.api.conduit.Conduits#connectivity
 * Conduits.connectivity} as its connectivity and its own {@link dev.silencebound.maxtech.lib.api.network.NetworkLogic},
 * which does the transport.</li>
 * <li>Register the conduit with {@link dev.silencebound.maxtech.lib.api.conduit.Conduits#builder Conduits.builder},
 * giving a {@link dev.silencebound.maxtech.lib.api.conduit.ConduitType} on that network type, then
 * {@link dev.silencebound.maxtech.lib.api.conduit.Conduits.Builder#register register()}; the returned
 * {@link dev.silencebound.maxtech.lib.api.conduit.ConduitEntry} holds the type, block and item.</li>
 * <li>Change behaviour only through the builder's hooks
 * ({@link dev.silencebound.maxtech.lib.api.conduit.ConduitWrenchHook},
 * {@link dev.silencebound.maxtech.lib.api.conduit.ConduitConnectionRule}), never by subclassing the block. The conduit
 * in the world is a {@link dev.silencebound.maxtech.lib.api.conduit.ConduitHost} from
 * {@link dev.silencebound.maxtech.lib.api.conduit.Conduits#host Conduits.host}.</li>
 * <li>Set a {@link dev.silencebound.maxtech.lib.api.conduit.ConduitLook} with
 * {@link dev.silencebound.maxtech.lib.api.conduit.Conduits.Builder#look look()}: Lib then supplies the block state file
 * and item model definition. The addon ships the item model ({@code models/item/<name>.json}), the textures its look
 * names, the lang entry {@code block.<modid>.<name>}, and a loot table only if the conduit should drop something other
 * than its own item.</li>
 * </ol>
 * Custom conduit models are not supported yet: Lib's conduit model draws every conduit from its look.
 * <p>
 * Storage registers its cables through Lib's internal content builder instead. {@code ConduitType},
 * {@code ConduitTypes}, {@code ConduitFaceMode} and {@code ConduitState} are stable; everything else here (the addon
 * entry points, and the helpers for look, shapes, connections, face modes and colours moved from {@code lib.conduit})
 * is experimental since Lib 0.14.0 (for Farms, FM1), each type marked {@code @ApiStatus.Experimental}.
 */
@NullMarked
package dev.silencebound.maxtech.lib.api.conduit;

import org.jspecify.annotations.NullMarked;
