package dev.silencebound.maxtech.storage.logic.remote;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;

/**
 * The logic remote (M7L2 chunk 12): item, menu type, texts and payloads, and the hook that pushes new notices to the
 * remotes in range ({@link RemoteNotices}). It does not depend on the wireless module or its research
 * ({@link LogicRemoteItem#reach}).
 */
public final class RemoteContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String BOUND_KEY = "message." + PREFIX + "logic_remote.bound";
    public static final String NO_CONTROLLER_KEY = "message." + PREFIX + "logic_remote.no_controller";
    public static final String DENIED_KEY = "message." + PREFIX + "logic_remote.denied";
    public static final String PING_KEY = "message." + PREFIX + "logic_remote.ping";
    public static final String TAB_NOTICES = gui("tab.notices");
    public static final String TAB_RULES = gui("tab.rules");
    public static final String KIND_NOTICES = gui("kind.notices");
    public static final String KIND_WARNINGS = gui("kind.warnings");
    public static final String SUBTITLE_ONLINE = gui("subtitle.online");
    public static final String NO_NOTICES = gui("no_notices");
    public static final String NO_RULES = gui("no_rules");
    public static final String NEEDS_LOGIC = gui("needs_logic");
    public static final String READ_ONLY = gui("read_only");
    public static final String AGO = gui("ago");
    public static final String PAUSE = gui("pause");
    public static final String RESUME = gui("resume");
    public static final String PAUSE_TIP = gui("pause_tip");
    public static final String RULE_AT = gui("rule_at");

    /** The remote's screen; registered by the loader's client setup. */
    public static final RegistryEntry<MenuType<?>, MenuType<LogicRemoteMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("logic_remote", () -> Platform.get().menus().menuType(LogicRemoteMenu::fromNetwork));

    // A display over a logic processor, sent and received through an oscillator like the wireless terminal. Only parts
    // the Logic Controller's tier needs no research for, and no module gate: it is craftable as soon as the controller
    // is (owner decision 2026-10-09).
    public static final RegistryEntry<Item, LogicRemoteItem> REMOTE = ModContent.CONTENT
            .item("logic_remote", LogicRemoteItem::new).model(ItemModel.flat(MaxtechStorage.id("item/logic_remote")))
            .lang("Logic Remote")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PDP").pattern("OLO")
                    .pattern("PRP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('O', MaterialTags.component("oscillator"))
                    .define('L', MaterialTags.component("basic_logic_processor")).define('R', MaterialTags.REDSTONE)
                    .unlockedBy("has_logic_controller", recipes.has(LogicContent.CONTROLLER.get()))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(BOUND_KEY, "Bound to the network of the storage controller at %s %s %s");
        ModContent.CONTENT.translation(NO_CONTROLLER_KEY, "This network has no single storage controller to bind to");
        ModContent.CONTENT.translation(DENIED_KEY, "You need access to this network to use the remote");
        ModContent.CONTENT.translation(PING_KEY, "Logic Remote");
        ModContent.CONTENT.translation(TAB_NOTICES, "NOTICES");
        ModContent.CONTENT.translation(TAB_RULES, "RULES");
        ModContent.CONTENT.translation(KIND_NOTICES, "Notices");
        ModContent.CONTENT.translation(KIND_WARNINGS, "Warnings");
        ModContent.CONTENT.translation(SUBTITLE_ONLINE, "IN RANGE · %s MU");
        ModContent.CONTENT.translation(NO_NOTICES, "No notices yet");
        ModContent.CONTENT.translation(NO_RULES, "No logic controller on this network has rules");
        ModContent.CONTENT.translation(NEEDS_LOGIC, "You need logic access to see and change the rules");
        ModContent.CONTENT.translation(READ_ONLY, "Last known notices, read only");
        ModContent.CONTENT.translation(AGO, "%s s ago");
        ModContent.CONTENT.translation(PAUSE, "Pause");
        ModContent.CONTENT.translation(RESUME, "Resume");
        ModContent.CONTENT.translation(PAUSE_TIP, "Switches the selected rule off or on again, as at its controller");
        ModContent.CONTENT.translation(RULE_AT, "Controller at %s %s %s");
        for (Reach reach : Reach.values()) {
            if (reach != Reach.OK) {
                ModContent.CONTENT.translation(subtitleKey(reach), switch (reach) {
                    case OK -> "";
                    case DISABLED -> "WIRELESS OFF";
                    case LOCKED -> "NEEDS RESEARCH";
                    case UNBOUND -> "NOT BOUND";
                    case OTHER_DIMENSION -> "OTHER DIMENSION";
                    case NO_NETWORK -> "NO NETWORK";
                    case OUT_OF_RANGE -> "OUT OF RANGE";
                    case NO_POWER -> "NO POWER";
                });
            }
        }
        for (RemoteResult result : RemoteResult.values()) {
            ModContent.CONTENT.translation(result.key(), switch (result) {
                case DONE -> "Done";
                case OUT_OF_REACH -> "The remote does not reach the network";
                case NO_POWER -> "The remote is out of power; charge it";
                case DENIED -> "You need logic access to that controller";
                case NO_SUCH_RULE -> "That rule is gone";
            });
        }
    }

    private RemoteContent() {
    }

    /** The screen's subtitle for a remote that does not reach its network for {@code reach}. */
    public static String subtitleKey(Reach reach) {
        return gui("subtitle." + reach.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String gui(String path) {
        return "gui." + PREFIX + "logic_remote." + path;
    }

    /** Loads the class during mod construction, registers the payloads and pushes new notices to remotes. */
    public static void init() {
        RemotePayloads.register();
        NetworkState.listenNotices(RemoteNotices::posted);
        Platform.get().lifecycle().onServerStopped(server -> RemoteNotices.clear());
    }
}
