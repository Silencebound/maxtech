/**
 * Lib's conduit framework: the block, block entity, item and per-conduit data behind every cable; its pure helpers
 * (look, shapes, connections, face modes, colours) are in {@code lib.api.conduit}. A conduit is Lib's generic framework
 * for blocks that connect to their neighbours (cables now, later pipes and wires), not vanilla's Conduit block. Name
 * clash: {@code ConduitBlock} and {@code ConduitBlockEntity} here share their simple names with
 * {@code net.minecraft.world.level.block.ConduitBlock} and {@code ...block.entity.ConduitBlockEntity}; ours are never
 * imported next to the vanilla ones.
 */
@NullMarked
package dev.silencebound.maxtech.lib.conduit;

import org.jspecify.annotations.NullMarked;
