package dev.silencebound.maxtech.lib.network;

import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;

/**
 * Wires the api's {@link NetworkManager} to Lib: its config values come from {@link ServerConfig}, and it ticks at the
 * end of each server level tick and forgets its levels when the server stops.
 */
public final class NetworkBootstrap {
    private NetworkBootstrap() {
    }

    /** Hooks the managers into the level tick; called once from Lib's init. */
    public static void bootstrap() {
        NetworkManager.setConfigSource(ServerConfig.UNLOADED_CHUNK_MODE, ServerConfig.ALLOW_ANCHORS);
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onServerLevelTickEnd(NetworkManager::tickLevel);
        lifecycle.onServerStopped(NetworkManager::clear);
    }
}
