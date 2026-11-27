/**
 * Conduit model geometry for addons that draw their own conduit model: the quads of a model part before baking
 * ({@link dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad}, with its sprite
 * {@link dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture} and chunk layer
 * {@link dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer}), the key of a conduit's body
 * ({@link dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey}) and Lib's own body and face quads
 * ({@link dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts}). Plain values on vanilla maths types with no
 * client classes, so they load on a dedicated server too; the loader's conduit model bakes them into quads. Pixel boxes
 * follow {@link dev.silencebound.maxtech.lib.api.conduit.ConduitShapes}, so a model and the hit shape agree.
 * <p>
 * Everything here is experimental since Lib 0.14.0 (for Farms, FM1).
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.conduit.model;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
