/**
 * Ghost blocks: translucent or outlined block cells drawn in the world to show where something can be built, for
 * example where a cluster can grow or where a pasted blueprint goes. Client code fills
 * {@link dev.silencebound.maxtech.lib.client.ghost.GhostBlocks} with
 * {@link dev.silencebound.maxtech.lib.client.ghost.GhostSet}s per channel; Lib's client glue draws them each frame in
 * two batches. Not public api. Holds no client-only types, but only the client fills it.
 */
@NullMarked
package dev.silencebound.maxtech.lib.client.ghost;

import org.jspecify.annotations.NullMarked;
