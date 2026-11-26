package dev.silencebound.maxtech.storage.controller.recorder.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.recorder.RecordTexts;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSubScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTab;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTabs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's network history tab (M7R item 3): the same recorder views as the controller's History tab
 * ({@link RecordsPanel}: telemetry graphs, the ledger and the redstone alarm rules) for the controller in charge of the
 * terminal's network, each behind its research. Client only.
 */
public final class RecordsScreen extends TerminalSubScreen {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 222;
    private static final int MARGIN = 8;
    private static final int PANEL_TOP = 20;

    private final RecordsPanel panel;

    public RecordsScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle) {
        super(Component.translatable(RecordTexts.TAB), menu, parent, terminalTitle, WIDTH, HEIGHT);
        this.panel = new RecordsPanel(menu.containerId);
    }

    /** Adds the tab to network terminals; called once from client setup. */
    public static void registerTab() {
        TerminalTabs.register(new TerminalTab(MaxtechStorage.id("network_history"), TerminalTab.sprite("tab_history"),
                Component.translatable(RecordTexts.TAB), TerminalMenu::networked,
                (parent, menu) -> new RecordsScreen(menu, parent, parent.getTitle())));
    }

    @Override
    protected void init() {
        super.init();
        panel.layout(left + MARGIN, top + PANEL_TOP, WIDTH - 2 * MARGIN);
        addRenderableWidget(new AccentButton(left + MARGIN, top + HEIGHT - MARGIN - 16, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        panel.ask();
    }

    @Override
    public void tick() {
        super.tick();
        panel.tick();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && panel.click(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return panel.scroll(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        panel.draw(graphics, font, mouseX, mouseY);
    }
}
