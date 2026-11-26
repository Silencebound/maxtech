package dev.silencebound.maxtech.storage.terminal.client;

import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A screen over a terminal's open menu that is not the terminal grid itself: the crafting screens and the screens of
 * {@link TerminalTab}s. It never closes the menu, so the server keeps the session and payloads can name the menu's
 * container id; Escape and {@link #back()} return to the terminal screen. If the server closes the menu, the screen
 * goes away with it. Subclasses lay out inside the panel at ({@link #left}, {@link #top}). Client only.
 */
public abstract class TerminalSubScreen extends Screen {
    protected final TerminalMenu menu;
    private final @Nullable TerminalScreen parent;
    private final Component terminalTitle;
    protected final int panelWidth;
    private final int preferredHeight;
    /** The panel's height for the current window; see {@link #panelHeight(int)}. */
    protected int panelHeight;
    protected int left;
    protected int top;

    /**
     * @param parent
     *            the terminal screen to go back to; null builds a new one (when opened from a recipe viewer's screen)
     */
    protected TerminalSubScreen(Component title, TerminalMenu menu, @Nullable TerminalScreen parent,
            Component terminalTitle, int panelWidth, int panelHeight) {
        super(title);
        this.menu = menu;
        this.parent = parent;
        this.terminalTitle = terminalTitle;
        this.panelWidth = panelWidth;
        this.preferredHeight = panelHeight;
        this.panelHeight = panelHeight;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelHeight = panelHeight(height);
        left = (width - panelWidth) / 2;
        top = AccentFrame.top(height, panelHeight);
    }

    /**
     * The panel's height on a screen {@code screenHeight} tall: the height the screen was made with, unless a subclass
     * gives up room (fewer rows of a grid) on a short window.
     */
    protected int panelHeight(int screenHeight) {
        return preferredHeight;
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft != null && (minecraft.player == null || minecraft.player.containerMenu != menu)) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, panelWidth, panelHeight, title, mouseX, mouseY);
    }

    /** The terminal screen this one goes back to, null when it builds a new one. */
    protected @Nullable TerminalScreen parentTerminal() {
        return parent;
    }

    /** The terminal's title, for a terminal screen built on the way back. */
    protected Component terminalTitle() {
        return terminalTitle;
    }

    /** Escape goes back to the terminal instead of closing the menu. */
    @Override
    public void onClose() {
        back();
    }

    /** Shows the terminal screen again; the menu stayed open the whole time. */
    protected void back() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        TerminalScreen terminal = parent;
        minecraft.setScreen(
                terminal != null ? terminal : new TerminalScreen(menu, minecraft.player.getInventory(), terminalTitle));
    }
}
