package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.storage.logic.controller.CardPayloads;
import dev.silencebound.maxtech.storage.logic.controller.SharePayloads;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Hands the sharing answers to the open {@link LogicControllerScreen} or its {@link LogicTemplateScreen}: an exported
 * text goes to the system clipboard or the in-game clipboard, an added graph's name to the list, and the line to the
 * screen. Answers for a screen that is no longer open are dropped (an export is then not copied). Also keeps the
 * in-game clipboard: one graph as text, copied with Copy and added with Paste on any controller during this game
 * session, without the system clipboard. Client thread only; loaded only on the client.
 */
public final class LogicShareClient {
    /** The in-game clipboard's text; null until something is copied. */
    private static @Nullable String clipboard;

    private LogicShareClient() {
    }

    /** The in-game clipboard's text; null while nothing was copied this session. */
    static @Nullable String clipboard() {
        return clipboard;
    }

    /** The open controller screen for {@code containerId}, itself or under the template list; else null. */
    private static @Nullable LogicControllerScreen screen(int containerId) {
        Object open = Minecraft.getInstance().screen;
        LogicControllerScreen screen = open instanceof LogicTemplateScreen templates
                ? templates.parent()
                : open instanceof LogicControllerScreen controller ? controller : null;
        return screen != null && screen.getMenu().containerId == containerId ? screen : null;
    }

    public static void exported(SharePayloads.Exported payload) {
        LogicControllerScreen screen = screen(payload.containerId());
        if (screen == null) {
            return;
        }
        if (!payload.text().isEmpty()) {
            if (payload.target() == SharePayloads.TO_GAME) {
                clipboard = payload.text();
            } else if (payload.target() == SharePayloads.TO_CLIPBOARD) {
                Minecraft.getInstance().keyboardHandler.setClipboard(payload.text());
            }
        }
        screen.show(payload.message(), payload.args(), payload.ok());
    }

    public static void imported(SharePayloads.Imported payload) {
        LogicControllerScreen screen = screen(payload.containerId());
        if (screen == null) {
            return;
        }
        if (payload.added()) {
            screen.getMenu().setName(payload.index(), payload.name());
        }
        screen.show(payload.message(), payload.args(), payload.added());
        if (Minecraft.getInstance().screen instanceof LogicTemplateScreen templates) {
            templates.answered(payload.added(), payload.message(), payload.args());
        }
    }

    /** The answer to an edit on the card view (M7L2 chunk 11). */
    public static void cardEdited(CardPayloads.Edited payload) {
        LogicControllerScreen screen = screen(payload.containerId());
        if (screen != null) {
            screen.cardEdited(payload);
        }
    }

    public static void templates(SharePayloads.TemplateListed payload) {
        if (Minecraft.getInstance().screen instanceof LogicTemplateScreen templates
                && templates.parent().getMenu().containerId == payload.containerId()) {
            templates.listed(payload.templates());
        }
    }
}
