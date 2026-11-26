package dev.silencebound.maxtech.storage.terminal.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import net.minecraft.resources.Identifier;

/**
 * A button on the terminal screen's right edge that opens another screen over the same menu, such as the crafting jobs
 * or the stock keeper's rules. Register tabs with {@link TerminalTabs#register} from client setup; the terminal shows
 * the visible ones in registration order. The screen a tab opens usually extends {@link TerminalSubScreen}, which keeps
 * the terminal's menu open and goes back to the terminal on Escape, so its payloads can name the menu's container id.
 * Client only.
 *
 * @param id
 *            a unique id, for ordering and tests
 * @param icon
 *            a 16x16 GUI sprite drawn on the button, relative to {@code textures/gui/sprites/} (Storage's own are under
 *            {@code terminal/}, drawn by {@code storage/neoforge/src/textures/generate_gui.py})
 * @param title
 *            the tooltip
 * @param visible
 *            whether the tab shows for a menu (for example only for network terminals)
 * @param opener
 *            builds the screen; {@code parent} is the terminal screen to go back to
 * @param badge
 *            the tab's red or amber badge with its reason, null for none (B09 item 21d); read every frame, so it must
 *            hand out a cached badge
 */
public record TerminalTab(Identifier id, Identifier icon, Component title, Predicate<TerminalMenu> visible,
        Opener opener, Supplier<SideTabs.@Nullable Badge> badge) {
    /** A tab without a badge. */
    public TerminalTab(Identifier id, Identifier icon, Component title, Predicate<TerminalMenu> visible,
            Opener opener) {
        this(id, icon, title, visible, opener, () -> null);
    }

    /** Builds a tab's screen. */
    @FunctionalInterface
    public interface Opener {
        Screen open(TerminalScreen parent, TerminalMenu menu);
    }

    /** The id of Storage's GUI sprite {@code terminal/<name>}. */
    public static Identifier sprite(String name) {
        return MaxtechStorage.id("terminal/" + name);
    }
}
