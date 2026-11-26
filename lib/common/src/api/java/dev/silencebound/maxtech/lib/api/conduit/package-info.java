/**
 * A conduit is Lib's generic framework for blocks that connect to their neighbours: cables now, later pipes and wires.
 * One {@link dev.silencebound.maxtech.lib.api.conduit.ConduitType} per block, per-face modes, shapes and colours, and
 * membership in a network of the type's network type. It has nothing to do with vanilla's Conduit block (the underwater
 * beacon), and the player never sees the word: in text it is a "cable".
 * <p>
 * Conduit types: every cable kind (PRD 5.2, ADR-0012). Each type gets a block of its own, built from Lib's conduit
 * block, holding one conduit. MaxTech mods register {@link dev.silencebound.maxtech.lib.api.conduit.ConduitType}
 * instances (or subclasses) in {@link dev.silencebound.maxtech.lib.api.conduit.ConduitTypes#REGISTRY_KEY} through Lib's
 * content builder, which registers the block and item with them; they never write a cable block of their own.
 * <p>
 * Other mods adding their own conduit types is not supported in Lib 1.0: the content builder and the network framework
 * a conduit needs to do anything are internal (ADR-0004), so a third-party type would join no network. This package
 * stays stable because Storage's cable parts rely on {@link dev.silencebound.maxtech.lib.api.conduit.ConduitState}.
 */
@NullMarked
package dev.silencebound.maxtech.lib.api.conduit;

import org.jspecify.annotations.NullMarked;
