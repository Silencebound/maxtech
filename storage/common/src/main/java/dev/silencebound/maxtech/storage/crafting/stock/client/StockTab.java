package dev.silencebound.maxtech.storage.crafting.stock.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.stock.StockTexts;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTab;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTabs;
import net.minecraft.network.chat.Component;

/** Registers the terminal's stock tab ({@link StockTabScreen}) on network terminals. Client only. */
public final class StockTab {
    private StockTab() {
    }

    /** Called once from client setup, after the crafting jobs tab. */
    public static void init() {
        TerminalTabs.register(new TerminalTab(MaxtechStorage.id("stock_keepers"), TerminalTab.sprite("tab_stock"),
                Component.translatable(StockTexts.TAB_TITLE), TerminalMenu::networked,
                (parent, menu) -> new StockTabScreen(menu, parent, parent.getTitle())));
    }
}
