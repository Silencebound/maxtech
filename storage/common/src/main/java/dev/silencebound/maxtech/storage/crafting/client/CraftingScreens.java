package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.overview.client.PatternOverviewScreen;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.crafting.order.client.OrderScreen;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import java.util.List;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTab;
import dev.silencebound.maxtech.storage.crafting.cpu.client.JobToasts;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Entry points to the crafting screens: the jobs tab and opening the planner from anywhere. Client only. */
public final class CraftingScreens {
    private CraftingScreens() {
    }

    /** Registers the crafting jobs, pattern overview and order list tabs; called once from client setup. */
    public static void init() {
        TerminalTabs.register(new TerminalTab(MaxtechStorage.id("crafting_jobs"), TerminalTab.sprite("tab_jobs"),
                Component.translatable(RequestContent.JOBS_TITLE_KEY), TerminalMenu::networked,
                (parent, menu) -> new CraftingJobsScreen(menu, parent, parent.getTitle()).focus(JobToasts.badgeJob()),
                JobToasts::jobsBadge));
        PatternOverviewScreen.registerTab();
        OrderScreen.registerTab();
    }

    /**
     * Shows the crafting jobs over the open terminal menu {@code containerId} with no terminal behind it, for the
     * crafting tablet's jobs button (B09 item 23); does nothing when that menu is not open.
     */
    public static void openJobs(int containerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof TerminalMenu menu)
                || menu.containerId != containerId) {
            return;
        }
        // The session runs once the menu was asked for its contents, which a terminal screen does when it opens.
        if (minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(containerId,
                    new TerminalAction(TerminalAction.Kind.REFRESH, -1).buttonId());
        }
        minecraft.setScreen(CraftingJobsScreen.standalone(menu, Component.translatable(RequestContent.JOBS_TITLE_KEY)));
    }

    /**
     * Plans the order list {@code roots} (M7C item 6) over the player's open terminal menu, as {@link #openRequest}
     * does for one item; does nothing when no network terminal is open or the list is empty.
     */
    public static void openOrder(List<CraftingPlanner.Root> roots) {
        Minecraft minecraft = Minecraft.getInstance();
        if (roots.isEmpty() || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof TerminalMenu menu) || !menu.networked()) {
            return;
        }
        Screen current = minecraft.screen;
        TerminalScreen parent = current instanceof TerminalScreen terminal ? terminal : null;
        Component title = parent != null ? parent.getTitle() : TerminalScreen.lastTitle();
        minecraft.setScreen(CraftRequestScreen.order(menu, parent, title, roots, OrderList.encode(roots)));
    }

    /**
     * Opens the planner for {@code amount} of {@code key} over the player's open terminal menu, planning at once when
     * {@code autoPlan}; does nothing when no network terminal is open. The terminal screen it returns to is the current
     * screen when that is one, else a new one.
     */
    public static void openRequest(StackKey key, long amount, boolean autoPlan) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof TerminalMenu menu)
                || !menu.networked()) {
            return;
        }
        Screen current = minecraft.screen;
        TerminalScreen parent = current instanceof TerminalScreen terminal ? terminal : null;
        Component title = parent != null ? parent.getTitle() : TerminalScreen.lastTitle();
        minecraft.setScreen(new CraftRequestScreen(menu, parent, title, key, amount, autoPlan));
    }
}
