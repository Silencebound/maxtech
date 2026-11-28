package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.lib.neoforge.api.guide.Guides;
import dev.silencebound.maxtech.storage.crafting.overview.client.CrafterManagerScreen;
import dev.silencebound.maxtech.storage.crafting.stock.client.StockKeeperScreen;
import dev.silencebound.maxtech.storage.crafting.stock.client.StockTab;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.bulk.client.BulkDriveScreen;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockContent;
import dev.silencebound.maxtech.storage.storageblock.client.StorageBlockScreen;
import dev.silencebound.maxtech.storage.ioport.IoPortContent;
import dev.silencebound.maxtech.storage.ioport.client.IoPortScreen;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.drive.client.DriveScreen;
import dev.silencebound.maxtech.storage.board.client.BoardScreen;
import dev.silencebound.maxtech.storage.mail.client.MailScreen;
import dev.silencebound.maxtech.lib.neoforge.client.ConfigScreens;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.List;
import dev.silencebound.maxtech.storage.condition.client.ConditionScreen;
import dev.silencebound.maxtech.storage.logic.controller.client.LogicControllerScreen;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.client.SensorScreen;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.client.ControllerScreen;
import dev.silencebound.maxtech.storage.controller.recorder.client.RecordsScreen;
import dev.silencebound.maxtech.storage.subnet.client.SubnetScreen;
import dev.silencebound.maxtech.storage.controller.module.client.SecurityScreen;
import dev.silencebound.maxtech.storage.display.client.DisplayScreen;
import dev.silencebound.maxtech.storage.crafting.client.CraftingScreens;
import dev.silencebound.maxtech.storage.crafting.generator.client.GeneratorScreen;
import dev.silencebound.maxtech.storage.crafting.provider.client.ProviderScreen;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.logic.remote.client.LogicRemoteScreen;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.client.CraftingTabletScreen;
import dev.silencebound.maxtech.storage.iface.client.InterfaceScreen;
import dev.silencebound.maxtech.storage.machine.client.StorageMachineScreen;
import dev.silencebound.maxtech.storage.port.client.PortScreen;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.research.client.ResearchScreen;
import dev.silencebound.maxtech.storage.research.puzzle.client.PuzzleScreens;
import dev.silencebound.maxtech.storage.terminal.client.NetworkLogScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.terminal.export.client.ExportScreen;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.belt.client.BeltScreen;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterContent;
import dev.silencebound.maxtech.storage.terminal.filter.client.ViewFilterScreen;
import dev.silencebound.maxtech.storage.tool.probe.client.ProbeScreen;
import dev.silencebound.maxtech.storage.workbench.client.CellWorkbenchScreen;
import dev.silencebound.maxtech.storage.world.client.WorldScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Storage's client-only entrypoint. FML constructs it after the common one, still during mod construction, which is
 * when screens must be bound (the screen event fires before client setup).
 */
@Mod(value = MaxtechStorage.MOD_ID, dist = Dist.CLIENT)
public final class MaxtechStorageClient {
    /** Top sections of Storage's config files, most often changed first; the rest follow as declared. */
    private static final List<String> CONFIG_ORDER = List.of("terminal", "visuals", "modules", "network", "cells",
            "controller", "ports", "crafting", "energy", "signal", "worldgen", "machines", "tools", "devices", "world",
            "interface", "bulkDrive", "p2p", "display", "research", "materials", "logic", "sensor", "condition",
            "alarms", "claims", "caps", "loadShedding", "history", "recovery", "setup", "snapshots", "services",
            "bridges");

    public MaxtechStorageClient(ModContainer container) {
        ConfigScreens.register(container, CONFIG_ORDER);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.MACHINE, StorageMachineScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.TERMINAL, TerminalScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, CraftingTerminalContent.MENU, TerminalScreen::new);
        CraftingScreens.init();
        // Storage's guide (maxtech_storage:guide), drawn by Lib's GuideME; its pages load on the first resource reload.
        Guides.register(MaxtechStorage.MOD_ID);
        StockTab.init();
        NetworkLogScreen.registerTab();
        RecordsScreen.registerTab();
        ExportScreen.registerTab();
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.PORT, PortScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.CONDITION, ConditionScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.LOGIC_CONTROLLER,
                LogicControllerScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, SensorContent.MENU, SensorScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.STOCK_KEEPER, StockKeeperScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.RESEARCH, ResearchScreen::new);
        PuzzleScreens.install();
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.WORKBENCH, CellWorkbenchScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.DISPLAY, DisplayScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.INTERFACE, InterfaceScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.WORLD, WorldScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.SECURITY, SecurityScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.CONTROLLER, ControllerScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.SUBNET, SubnetScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, BeltContent.MENU, BeltScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ViewFilterContent.MENU, ViewFilterScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.PROBE, ProbeScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.MAIL_PORT, MailScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.PATTERN_PROVIDER, ProviderScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.CRAFTER_MANAGER,
                CrafterManagerScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.GENERATOR_PROVIDER, GeneratorScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.PATTERN_TERMINAL, TerminalScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, ModMenus.REQUEST_BOARD, BoardScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, BulkContent.MENU, BulkDriveScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, StorageBlockContent.MENU, StorageBlockScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, DriveContent.MENU, DriveScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, IoPortContent.MENU, IoPortScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, CraftingTabletContent.MENU,
                CraftingTabletScreen::new);
        Platform.get().menus().registerScreen(MaxtechStorage.MOD_ID, RemoteContent.MENU, LogicRemoteScreen::new);
    }
}
