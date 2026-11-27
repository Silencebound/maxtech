package dev.silencebound.maxtech.lib.api.conduit;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/**
 * Whether a conduit may link to a neighbouring conduit on one side, on top of Lib's own rules (same network type,
 * matching dye colours, face modes; {@link ConduitConnections#canConnect}). Set with
 * {@link Conduits.Builder#connectionRule}. Two conduits link only if both of their rules allow it, so the result stays
 * symmetric; a refused side is treated as if no conduit were there (no link, no stub).
 * <p>
 * Asked on the server when a conduit's links are recomputed (placement, neighbour changes, dyes, face changes,
 * {@link ConduitHost#refreshConnections()}), never per tick. Must be cheap, must not allocate, and must not change the
 * world or either conduit. Faces towards blocks are the network logic's business ({@link ConduitHost#setBlockFaces}).
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface ConduitConnectionRule {
    /** Allows every link Lib's own rules allow. */
    ConduitConnectionRule ANY = (conduit, side, neighbour) -> true;

    /**
     * Whether {@code conduit} may link through {@code side} to {@code neighbour}, the conduit next to it on that side,
     * which Lib's own rules would link with.
     */
    boolean canConnect(ConduitView conduit, Direction side, ConduitView neighbour);
}
