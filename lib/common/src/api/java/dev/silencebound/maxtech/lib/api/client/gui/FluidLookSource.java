package dev.silencebound.maxtech.lib.api.client.gui;

import org.jspecify.annotations.Nullable;

/** Holds the look {@link FluidLook#install} sets; an interface cannot hold a field that changes. */
final class FluidLookSource {
    static volatile @Nullable FluidLook look;

    static synchronized void setOnce(FluidLook installed) {
        if (look != null) {
            throw new IllegalStateException("A fluid look is already installed");
        }
        look = installed;
    }

    private FluidLookSource() {
    }
}
