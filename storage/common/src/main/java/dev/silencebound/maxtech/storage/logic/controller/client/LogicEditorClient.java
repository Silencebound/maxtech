package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import net.minecraft.client.Minecraft;

/**
 * Hands the node editor's server packets to the open {@link LogicEditorScreen}; packets for a screen that is no longer
 * open are dropped. Client thread only; loaded only when a packet arrives on the client.
 */
public final class LogicEditorClient {
    private LogicEditorClient() {
    }

    public static void graph(EditorPayloads.Graph payload) {
        if (Minecraft.getInstance().screen instanceof LogicEditorScreen screen) {
            screen.receive(payload);
        }
    }

    public static void saved(EditorPayloads.Saved payload) {
        if (Minecraft.getInstance().screen instanceof LogicEditorScreen screen) {
            screen.receive(payload);
        }
    }

    public static void live(EditorPayloads.Live payload) {
        if (Minecraft.getInstance().screen instanceof LogicEditorScreen screen) {
            screen.receive(payload);
        }
    }
}
