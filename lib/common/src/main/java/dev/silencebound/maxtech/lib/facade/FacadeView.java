package dev.silencebound.maxtech.lib.facade;

import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import org.jspecify.annotations.Nullable;

/**
 * What a facaded conduit draws on this client. By default the facade hides the conduits (EnderIO's default); with the
 * client option {@code showConduitsThroughFacade} on, holding a wrench or a conduit swaps that round, so the conduits
 * show and the facade doesn't. The flag is written on the client thread and read by chunk meshing threads.
 */
public final class FacadeView {
    private static volatile boolean conduitsShown;

    private FacadeView() {
    }

    /** Whether facaded conduits are currently drawn instead of their facade. Any thread. */
    public static boolean conduitsShown() {
        return conduitsShown;
    }

    /**
     * Sets {@link #conduitsShown()}; returns whether it changed, in which case the caller re-meshes the sections
     * holding facaded conduits. Client thread.
     */
    public static boolean setConduitsShown(boolean shown) {
        if (conduitsShown == shown) {
            return false;
        }
        conduitsShown = shown;
        return true;
    }

    /**
     * The conduit model's geometry key: equal keys mean equal geometry. {@code covered} says whether the conduit has a
     * facade ({@code data.facade() != null}; a parameter so tests need no block state). Uncovered, the render data.
     * Covered with the facade drawn, only {@code facadeGeometry}, the facade block model's own key, since the conduits
     * behind it are not drawn; {@code null} if that model gives no key. With the conduits shown through it, the
     * conduits' data without the facade, which is exactly the geometry of the same conduit uncovered.
     */
    public static @Nullable Object geometryKey(ConduitRenderData data, boolean covered, boolean shown,
            @Nullable Object facadeGeometry) {
        if (!covered) {
            return data;
        }
        if (shown) {
            return data.withoutFacade();
        }
        return facadeGeometry == null ? null : new FacadeOnly(facadeGeometry);
    }

    /** Wraps the facade's key so it can never equal a key of the facade block's own model elsewhere. */
    private record FacadeOnly(Object facadeGeometry) {
    }
}
