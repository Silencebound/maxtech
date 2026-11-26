package dev.silencebound.maxtech.storage.machine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.client.gui.LayoutCheck;
import dev.silencebound.maxtech.lib.client.gui.LayoutCheck.Box;
import dev.silencebound.maxtech.storage.board.BoardMenu;
import dev.silencebound.maxtech.storage.bulk.BulkDriveMenu;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockMenu;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.recorder.client.RecordsScreen;
import dev.silencebound.maxtech.storage.controller.module.SecurityMenu;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderMenu;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperMenu;
import dev.silencebound.maxtech.storage.crafting.stock.client.StockTabScreen;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.client.DisplayTextScreen;
import dev.silencebound.maxtech.storage.drive.DriveMenu;
import dev.silencebound.maxtech.storage.iface.InterfaceMenu;
import dev.silencebound.maxtech.storage.label.client.LabelScreen;
import dev.silencebound.maxtech.storage.logic.controller.client.LogicTemplateScreen;
import dev.silencebound.maxtech.storage.machine.MachineLayout;
import dev.silencebound.maxtech.storage.mail.MailMenu;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.research.client.ResearchScreen;
import dev.silencebound.maxtech.storage.sensor.SensorMenu;
import dev.silencebound.maxtech.storage.subnet.SubnetMenu;
import dev.silencebound.maxtech.storage.terminal.client.NetworkLogScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.tool.belt.BeltMenu;
import dev.silencebound.maxtech.storage.tool.probe.ProbeMenu;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletMenu;
import dev.silencebound.maxtech.storage.workbench.CellWorkbenchMenu;
import dev.silencebound.maxtech.storage.world.WorldMenu;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every fixed-size Storage panel, with its title tab above and the label and priority tabs on its right edge, fits the
 * smallest GUI Minecraft lays out (320 x 240). The sizes are compile-time constants, so no screen class loads. The
 * pattern terminal is a terminal screen with its panel in the item grid's place ({@code TerminalLayoutTest}).
 */
class PanelFitTest {
    /** Wider than any Storage title, so a long title's tab counts too (titles longer still end with an ellipsis). */
    private static final int TITLE_TAB = 100;
    /** The label and priority tabs stick out this far right of a container screen's panel. */
    private static final int SIDE_TAB = 19;

    private record Panel(String name, int width, int height, boolean container) {
    }

    private static final List<Panel> PANELS = List.of(new Panel("board", BoardMenu.WIDTH, BoardMenu.HEIGHT, true),
            new Panel("bulk drive", BulkDriveMenu.WIDTH, BulkDriveMenu.HEIGHT, true),
            new Panel("storage block", StorageBlockMenu.WIDTH, StorageBlockMenu.HEIGHT, true),
            new Panel("condition switch", ConditionMenu.WIDTH, ConditionMenu.HEIGHT, true),
            new Panel("controller", ControllerMenu.WIDTH, ControllerMenu.HEIGHT, true),
            new Panel("security", SecurityMenu.WIDTH, SecurityMenu.HEIGHT, true),
            new Panel("provider", ProviderMenu.WIDTH, ProviderMenu.HEIGHT, true),
            new Panel("stock keeper", StockKeeperMenu.WIDTH, StockKeeperMenu.HEIGHT, true),
            new Panel("display", DisplayMenu.WIDTH, DisplayMenu.HEIGHT, true),
            new Panel("drive", DriveMenu.WIDTH, DriveMenu.HEIGHT, true),
            new Panel("interface", InterfaceMenu.WIDTH, InterfaceMenu.HEIGHT, true),
            new Panel("machine", MachineLayout.WIDTH, MachineLayout.HEIGHT, true),
            new Panel("mail", MailMenu.WIDTH, MailMenu.HEIGHT, true),
            new Panel("port", PortMenu.WIDTH, PortMenu.HEIGHT, true),
            new Panel("sensor", SensorMenu.WIDTH, SensorMenu.HEIGHT, true),
            new Panel("subnet", SubnetMenu.WIDTH, SubnetMenu.HEIGHT, true),
            new Panel("belt", BeltMenu.WIDTH, BeltMenu.HEIGHT, true),
            new Panel("probe", ProbeMenu.WIDTH, ProbeMenu.HEIGHT, true),
            new Panel("crafting tablet", CraftingTabletMenu.WIDTH, CraftingTabletMenu.HEIGHT, true),
            new Panel("cell workbench", CellWorkbenchMenu.WIDTH, CellWorkbenchMenu.HEIGHT, true),
            new Panel("world device", WorldMenu.WIDTH, WorldMenu.HEIGHT, true),
            new Panel("research", ResearchScreen.WIDTH, ResearchScreen.HEIGHT, true),
            new Panel("display text", DisplayTextScreen.WIDTH, DisplayTextScreen.HEIGHT, false),
            new Panel("label", LabelScreen.WIDTH, LabelScreen.HEIGHT, false),
            new Panel("logic templates", LogicTemplateScreen.WIDTH, LogicTemplateScreen.HEIGHT, false),
            new Panel("network log", NetworkLogScreen.WIDTH, NetworkLogScreen.HEIGHT, false),
            new Panel("network history", RecordsScreen.WIDTH, RecordsScreen.HEIGHT, false),
            new Panel("stock keepers tab", StockTabScreen.WIDTH, StockTabScreen.HEIGHT, false));

    @Test
    void everyPanelFitsTheSmallestGuiWithItsTabs() {
        List<String> problems = new ArrayList<>();
        for (Panel panel : PANELS) {
            problems.addAll(check(panel, true));
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void theTerminalFitsButItsTitleTabDoesNot() {
        // 240 high fills the smallest GUI: the title tab is cut there, the grid and the hotbar are not.
        assertEquals(List.of(), check(new Panel("terminal", TerminalScreen.WIDTH, TerminalScreen.HEIGHT, true), false));
    }

    @Test
    void buttonLabelsShrinkALittleOrEndWithAnEllipsis() {
        assertEquals(1.0F, AccentFrame.labelScale(30, 40), "fits");
        // "-100" (24) on a 19 wide step button: drawn smaller rather than "-1...".
        int room = AccentFrame.labelRoom(19);
        assertEquals(15, room);
        float scale = AccentFrame.labelScale(24, room);
        assertTrue(scale > 0 && scale < 1, "scale " + scale);
        assertTrue(24 * scale <= room);
        // "Ignores redstone" (90) on a 46 wide button would be too small to read: cut instead.
        assertEquals(0.0F, AccentFrame.labelScale(90, AccentFrame.labelRoom(46)));
        assertEquals(0, AccentFrame.labelRoom(3), "never negative");
    }

    private static List<String> check(Panel panel, boolean titleTab) {
        int screenWidth = LayoutCheck.MIN_GUI_WIDTH;
        int screenHeight = LayoutCheck.MIN_GUI_HEIGHT;
        int left = (screenWidth - panel.width()) / 2;
        // Container screens centre like vanilla; the others place themselves with the Accent Frame's rule.
        int top = panel.container()
                ? (screenHeight - panel.height()) / 2
                : AccentFrame.top(screenHeight, panel.height());
        List<Box> boxes = new ArrayList<>();
        boxes.add(new Box(panel.name(), left, top, panel.width() + (panel.container() ? SIDE_TAB : 0), panel.height()));
        if (titleTab) {
            int tab = Math.min(TITLE_TAB, panel.width() - 24) + 12;
            boxes.add(new Box(panel.name() + " title tab", left + 6, top - AccentFrame.TAB_HEIGHT + 1, tab,
                    AccentFrame.TAB_HEIGHT));
        }
        return LayoutCheck.problems(screenWidth, screenHeight, boxes, List.of());
    }
}
