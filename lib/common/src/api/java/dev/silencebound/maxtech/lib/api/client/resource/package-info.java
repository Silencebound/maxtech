/**
 * How resources look on the client, per {@linkplain dev.silencebound.maxtech.lib.api.resource.ResourceKind kind}:
 * {@link dev.silencebound.maxtech.lib.api.client.resource.ResourceLook} (sprites, tint, name, light) and the
 * {@link dev.silencebound.maxtech.lib.api.client.resource.ResourceLooks} registry. Conduit contents and Lib's screens
 * draw a resource with its kind's look; Lib registers the fluid kind's. Client side only: never load these classes on a
 * dedicated server.
 * <p>
 * Experimental until Farms (FM1) settles it.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.client.resource;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
