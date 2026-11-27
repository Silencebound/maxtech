package dev.silencebound.maxtech.lib.api.conduit;

import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * The single place that decides whether two conduits connect (PRD 5.2). Every conduit type uses it; don't add per-type
 * colour logic elsewhere.
 * <p>
 * Experimental (Lib 0.14.0, moved from {@code lib.conduit} for Farms, FM1) until Farms, its first addon user, settles
 * it.
 */
@ApiStatus.Experimental
public final class ConduitConnections {
    /** Connection mask with every side set. */
    public static final int ALL_SIDES = (1 << 6) - 1;

    private ConduitConnections() {
    }

    /**
     * Conduits connect when they belong to the same network type (tiers of one kind join) and their colours match or at
     * least one of them is uncoloured.
     */
    public static boolean canConnect(ConduitType a, @Nullable DyeColor colourA, ConduitType b,
            @Nullable DyeColor colourB) {
        return a.networkType().equals(b.networkType()) && (colourA == null || colourB == null || colourA == colourB);
    }

    /**
     * Whether two conduits that {@link #canConnect} actually link, given the modes of the two faces that meet: a
     * disabled face on either side cuts the link (ADR-0005).
     */
    public static boolean linked(ConduitFaceMode ownFace, ConduitFaceMode otherFace) {
        return ownFace != ConduitFaceMode.DISABLED && otherFace != ConduitFaceMode.DISABLED;
    }

    /** The bit of {@code side} in a connection mask: {@code 1 << side.get3DDataValue()}. */
    public static int bit(Direction side) {
        return 1 << side.get3DDataValue();
    }

    /** Whether {@code mask} has {@code side}'s {@link #bit} set. */
    public static boolean isConnected(int mask, Direction side) {
        return (mask & bit(side)) != 0;
    }
}
