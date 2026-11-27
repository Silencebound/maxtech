package dev.silencebound.maxtech.lib.api.client.gui;

import org.jspecify.annotations.Nullable;

/** Holds the look {@link FluidLook#install} sets; an interface cannot hold a field that changes. */
final class FluidLookSource {
    static volatile @Nullable FluidLook look;

    private FluidLookSource() {
    }
}
