package dev.silencebound.maxtech.storage.logic.remote.client;

import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.logic.remote.RemotePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Shows a logic remote's ping as a toast in the top corner, the same box as the notify node's toasts, unless the player
 * turned remote pings off. Client only.
 */
public final class RemoteClient {
    private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId();

    private RemoteClient() {
    }

    public static void ping(RemotePayloads.Ping payload) {
        if (!StorageClientConfig.remotePings()) {
            return;
        }
        SystemToast.add(Minecraft.getInstance().getToastManager(), ID, Component.translatable(RemoteContent.PING_KEY),
                payload.notice().line());
    }
}
