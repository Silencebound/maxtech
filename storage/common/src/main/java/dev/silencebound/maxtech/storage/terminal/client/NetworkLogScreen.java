package dev.silencebound.maxtech.storage.terminal.client;

import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.terminal.NetworkLogPayloads;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's network log tab (M7L): the same logic log as the network controller's Network tab, newest at the
 * bottom, scrolled with the mouse wheel. The server sends the log when the tab opens and again every
 * {@link #REFRESH_TICKS} ticks while it stays open; it answers only players with view access to the terminal's network.
 * Client only.
 */
public final class NetworkLogScreen extends TerminalSubScreen {
    public static final int WIDTH = 300;
    public static final int HEIGHT = 222;
    private static final int MARGIN = 8;
    private static final int LIST_TOP = 34;
    private static final int ROW = 10;
    private static final int ROWS = (HEIGHT - LIST_TOP - MARGIN - 24) / ROW;
    private static final int REFRESH_TICKS = 40;

    /** Lines scrolled up from the newest. */
    private int scroll;
    private int ticks;

    public NetworkLogScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle) {
        super(Component.translatable(TerminalContent.LOG_TAB_KEY), menu, parent, terminalTitle, WIDTH, HEIGHT);
    }

    /** Adds the tab to network terminals; called once from client setup. */
    public static void registerTab() {
        TerminalTabs.register(new TerminalTab(MaxtechStorage.id("network_log"), TerminalTab.sprite("tab_log"),
                Component.translatable(TerminalContent.LOG_TAB_KEY), TerminalMenu::networked,
                (parent, menu) -> new NetworkLogScreen(menu, parent, parent.getTitle())));
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new AccentButton(left + MARGIN, top + HEIGHT - MARGIN - 16, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        ask();
    }

    private void ask() {
        Platform.get().network().sendToServer(new NetworkLogPayloads.Ask(menu.containerId));
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) {
            ask();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        NetworkLogPayloads.Log log = NetworkLogPayloads.received(menu.containerId);
        int lines = log == null ? 0 : log.lines().size();
        scroll = Math.clamp(scroll + (scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0), 0, Math.max(0, lines - ROWS));
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        AccentFrame.text(graphics, font, Component.translatable(TerminalContent.LOG_HINT_KEY), left + MARGIN, top + 20,
                WIDTH - 2 * MARGIN, AccentFrame.LABEL, mouseX, mouseY);
        NetworkLogPayloads.Log log = NetworkLogPayloads.received(menu.containerId);
        List<NetworkNotices.Notice> lines = log == null ? List.of() : log.lines();
        if (lines.isEmpty()) {
            TextScale.text(graphics, font, Component.translatable(TerminalContent.LOG_EMPTY_KEY), left + MARGIN,
                    top + LIST_TOP, AccentFrame.TEXT, false);
            return;
        }
        int end = lines.size() - Math.min(scroll, Math.max(0, lines.size() - ROWS));
        int start = Math.max(0, end - ROWS);
        for (int i = start; i < end; i++) {
            AccentFrame.text(graphics, font, lines.get(i).line(), left + MARGIN, top + LIST_TOP + (i - start) * ROW,
                    WIDTH - 2 * MARGIN, AccentFrame.TEXT, mouseX, mouseY);
        }
    }
}
