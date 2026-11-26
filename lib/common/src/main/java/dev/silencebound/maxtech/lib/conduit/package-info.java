/**
 * Lib's conduit framework: the block, block entity, item, shapes, connections and face modes behind every cable. A
 * conduit is Lib's generic framework for blocks that connect to their neighbours (cables now, later pipes and wires),
 * not vanilla's Conduit block. Name clash: {@code ConduitBlock} and {@code ConduitBlockEntity} here share their simple
 * names with {@code net.minecraft.world.level.block.ConduitBlock} and {@code ...block.entity.ConduitBlockEntity}; ours
 * are never imported next to the vanilla ones.
 */
@NullMarked
package dev.silencebound.maxtech.lib.conduit;

import org.jspecify.annotations.NullMarked;
