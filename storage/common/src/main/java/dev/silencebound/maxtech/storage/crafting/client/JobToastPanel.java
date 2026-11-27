package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.api.client.gui.AccentToast;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's in-screen toast for crafting job notices (B09 item 21d): drawn over the panel's bottom right corner; a
 * left click on it opens the crafting jobs view on the job (the first job when the job is gone). Client only.
 */
public final class JobToastPanel {
    /** How long the in-screen toast shows, long enough to reach it with the mouse. */
    public static final int MILLIS = 5000;
    /** Room between the toast and the panel's edges. */
    private static final int INSET = 4;

    private final AccentToast toast = new AccentToast();
    private @Nullable UUID job;

    /** Shows a notice; {@code openJob} is the job a click opens, null for the jobs view as it opens. */
    public void show(Component title, Component detail, AccentToast.Kind kind, @Nullable UUID openJob) {
        this.job = openJob;
        toast.show(title, detail, kind, Util.getMillis(), MILLIS);
    }

    /**
     * Draws the toast, while it shows, in the bottom right corner of the panel at ({@code left}, {@code top}),
     * {@code width} by {@code height}, in a new stratum above the slots.
     */
    public void draw(GuiGraphicsExtractor graphics, Font font, int left, int top, int width, int height) {
        long now = Util.getMillis();
        if (toast.visible(now)) {
            // Above the slots and their items.
            graphics.nextStratum();
            toast.draw(graphics, font, left + width - INSET, top + height - INSET, now);
        }
    }

    /**
     * A click at ({@code x}, {@code y}) on the toast of {@code screen}'s panel opens the jobs view over its network
     * terminal {@code menu} and returns true; anything else returns false.
     */
    public boolean clicked(TerminalScreen screen, TerminalMenu menu, Font font, int left, int top, int width,
            int height, double x, double y) {
        if (!toast.contains(font, left + width - INSET, top + height - INSET, Util.getMillis(), x, y)
                || !menu.networked()) {
            return false;
        }
        toast.hide();
        Minecraft.getInstance().setScreen(new CraftingJobsScreen(menu, screen, screen.getTitle()).focus(job));
        return true;
    }
}
