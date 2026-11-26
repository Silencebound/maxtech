package dev.silencebound.maxtech.storage.registry;

import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorMenu;
import dev.silencebound.maxtech.storage.crafting.overview.CrafterManagerMenu;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderMenu;

import dev.silencebound.maxtech.storage.board.BoardMenu;
import dev.silencebound.maxtech.storage.mail.MailMenu;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.module.SecurityMenu;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.iface.InterfaceMenu;
import dev.silencebound.maxtech.storage.machine.StorageMachineMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.research.ResearchMenu;
import dev.silencebound.maxtech.storage.workbench.CellWorkbenchMenu;
import dev.silencebound.maxtech.storage.world.WorldMenu;
import dev.silencebound.maxtech.storage.subnet.SubnetMenu;
import dev.silencebound.maxtech.storage.tool.probe.ProbeMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;

/** Storage's menu types. Shared by every Storage area; add entries, don't reorder them. */
public final class ModMenus {
    private static final RegistryHelper.Registrar<MenuType<?>> MENUS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU);

    /** The GUI of the fuel generator, lithograph, fabricator and assembler. */
    public static final RegistryEntry<MenuType<?>, MenuType<StorageMachineMenu>> MACHINE = MENUS.register("machine",
            () -> Platform.get().menus().menuType(StorageMachineMenu::fromNetwork));
    /** The item terminal's and the storage chest's GUI. */
    public static final RegistryEntry<MenuType<?>, MenuType<TerminalMenu>> TERMINAL = MENUS.register("terminal",
            () -> Platform.get().menus().menuType(TerminalMenu::fromNetwork));
    /** The port's filter and mode. */
    public static final RegistryEntry<MenuType<?>, MenuType<PortMenu>> PORT = MENUS.register("port",
            () -> Platform.get().menus().menuType(PortMenu::fromNetwork));
    /** The cell workbench. */
    public static final RegistryEntry<MenuType<?>, MenuType<CellWorkbenchMenu>> WORKBENCH = MENUS
            .register("cell_workbench", () -> Platform.get().menus().menuType(CellWorkbenchMenu::fromNetwork));

    /** The research screen of the research terminal. */
    public static final RegistryEntry<MenuType<?>, MenuType<ResearchMenu>> RESEARCH = MENUS.register("research",
            () -> Platform.get().menus().menuType(ResearchMenu::fromNetwork));

    /** The condition switch's settings. */
    public static final RegistryEntry<MenuType<?>, MenuType<ConditionMenu>> CONDITION = MENUS
            .register("condition_switch", () -> Platform.get().menus().menuType(ConditionMenu::fromNetwork));
    /** The interface's stock slots. */
    public static final RegistryEntry<MenuType<?>, MenuType<InterfaceMenu>> INTERFACE = MENUS.register("interface",
            () -> Platform.get().menus().menuType(InterfaceMenu::fromNetwork));
    /** A world device's filter, mode and tool (network chest, collector, placer, breaker). */
    public static final RegistryEntry<MenuType<?>, MenuType<WorldMenu>> WORLD = MENUS.register("world_device",
            () -> Platform.get().menus().menuType(WorldMenu::fromNetwork));

    /** A display panel's or display part's screen settings. */
    public static final RegistryEntry<MenuType<?>, MenuType<DisplayMenu>> DISPLAY = MENUS.register("display",
            () -> Platform.get().menus().menuType(DisplayMenu::fromNetwork));

    /** A controller security module's access rules. */
    public static final RegistryEntry<MenuType<?>, MenuType<SecurityMenu>> SECURITY = MENUS.register("network_security",
            () -> Platform.get().menus().menuType(SecurityMenu::fromNetwork));

    /** A subnet controller's mode, priority and filter. */
    public static final RegistryEntry<MenuType<?>, MenuType<SubnetMenu>> SUBNET = MENUS.register("subnet_controller",
            () -> Platform.get().menus().menuType(SubnetMenu::fromNetwork));

    /** The controller core's screen with its Network tab. */
    public static final RegistryEntry<MenuType<?>, MenuType<ControllerMenu>> CONTROLLER = MENUS
            .register("storage_controller", () -> Platform.get().menus().menuType(ControllerMenu::fromNetwork));

    /** The signal probe's graph. */
    public static final RegistryEntry<MenuType<?>, MenuType<ProbeMenu>> PROBE = MENUS.register("signal_probe",
            () -> Platform.get().menus().menuType(ProbeMenu::fromNetwork));

    /** A mail port's outbox and inbox. */
    public static final RegistryEntry<MenuType<?>, MenuType<MailMenu>> MAIL_PORT = MENUS.register("mail_port",
            () -> Platform.get().menus().menuType(MailMenu::fromNetwork));

    /** A request board's list. */
    public static final RegistryEntry<MenuType<?>, MenuType<BoardMenu>> REQUEST_BOARD = MENUS.register("request_board",
            () -> Platform.get().menus().menuType(BoardMenu::fromNetwork));

    /** The pattern terminal: the terminal with the pattern panel (M7a; a terminal menu since B09 item 30). */
    public static final RegistryEntry<MenuType<?>, MenuType<TerminalMenu>> PATTERN_TERMINAL = MENUS
            .register("pattern_terminal", () -> Platform.get().menus().menuType(TerminalMenu::fromNetworkPattern));

    /** The pattern provider's slots and blocking toggle (M7c). */
    public static final RegistryEntry<MenuType<?>, MenuType<ProviderMenu>> PATTERN_PROVIDER = MENUS
            .register("pattern_provider", () -> Platform.get().menus().menuType(ProviderMenu::fromNetwork));

    /** The crafter manager: every provider of a network and one provider's pattern slots (B09 item 23). */
    public static final RegistryEntry<MenuType<?>, MenuType<CrafterManagerMenu>> CRAFTER_MANAGER = MENUS
            .register("crafter_manager", () -> Platform.get().menus().menuType(CrafterManagerMenu::fromNetwork));

    /** The generator provider's output and signal settings (M7C item 11). */
    public static final RegistryEntry<MenuType<?>, MenuType<GeneratorMenu>> GENERATOR_PROVIDER = MENUS
            .register("generator_provider", () -> Platform.get().menus().menuType(GeneratorMenu::fromNetwork));

    private ModMenus() {
    }

    /** The stock keeper's rules (M7c area D). */
    public static final RegistryEntry<MenuType<?>, MenuType<StockKeeperMenu>> STOCK_KEEPER = MENUS
            .register("stock_keeper", () -> Platform.get().menus().menuType(StockKeeperMenu::fromNetwork));

    /** The Logic Controller's graph list (M7L area B). */
    public static final RegistryEntry<MenuType<?>, MenuType<LogicControllerMenu>> LOGIC_CONTROLLER = MENUS
            .register("logic_controller", () -> Platform.get().menus().menuType(LogicControllerMenu::fromNetwork));

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
