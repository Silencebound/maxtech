package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.LogicNoticePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Shows a logic notice as a toast (M7L area E), unless the player turned them off. Client only. */
public final class LogicToasts {
    private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId();

    private LogicToasts() {
    }

    public static void show(LogicNoticePayload payload) {
        if (!StorageClientConfig.logicToasts()) {
            return;
        }
        SystemToast.add(Minecraft.getInstance().getToastManager(), ID, Component.translatable(ControllerTexts.TOAST),
                Component.literal(payload.text()));
    }
}
