package dev.silencebound.maxtech.storage.neoforge.compat.ae2;

import dev.silencebound.maxtech.storage.bridge.ForeignNetworks;

/** Installs the AE2 side of the network bridge. Call only with AE2 loaded. */
public final class Ae2Compat {
    private Ae2Compat() {
    }

    /**
     * Registers the finder that turns an AE2 grid node in front of a bridge into a foreign network, and the dev
     * showcase's AE2 grid (only built by the dev-only showcase command).
     */
    public static void install() {
        ForeignNetworks.register(Ae2Network::find);
        Ae2Showcase.register();
    }
}
