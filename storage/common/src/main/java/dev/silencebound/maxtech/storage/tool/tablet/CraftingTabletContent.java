package dev.silencebound.maxtech.storage.tool.tablet;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Locale;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The crafting tablet (M7 C10): item, menu type, texts and the recipe-viewer registration of its grid. It depends on
 * the wireless module: {@code StorageModules} hides it and its recipe is gated with that switch.
 */
public final class CraftingTabletContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String DENIED_KEY = "message." + PREFIX + "crafting_tablet.denied";
    public static final String NOTHING_MISSING_KEY = "message." + PREFIX + "crafting_tablet.nothing_missing";
    public static final String NO_PATTERN_KEY = "message." + PREFIX + "crafting_tablet.no_pattern";
    public static final String QUEUED_KEY = "message." + PREFIX + "crafting_tablet.queued";
    public static final String PLAN_MISSING_KEY = "message." + PREFIX + "crafting_tablet.plan_missing";
    public static final String TOO_MANY_JOBS_KEY = "message." + PREFIX + "crafting_tablet.too_many_jobs";
    public static final String PLAN_FAILED_KEY = "message." + PREFIX + "crafting_tablet.plan_failed";
    public static final String REQUEST_KEY = "gui." + PREFIX + "crafting_tablet.request";
    public static final String REQUEST_TOOLTIP_KEY = "gui." + PREFIX + "crafting_tablet.request_tooltip";
    public static final String ENERGY_KEY = "gui." + PREFIX + "crafting_tablet.energy";

    /** The tablet's grid; its screen is registered by the loader's client setup. */
    public static final RegistryEntry<MenuType<?>, MenuType<CraftingTabletMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("crafting_tablet", () -> Platform.get().menus().menuType(CraftingTabletMenu::fromNetwork));

    // A crafting table behind display glass, powered like the wireless terminal and linked by an I/O processor.
    public static final RegistryEntry<Item, CraftingTabletItem> TABLET = ModContent.CONTENT
            .item("crafting_tablet", CraftingTabletItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/crafting_tablet"))).lang("Crafting Tablet")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PDP").pattern("OCO")
                    .pattern("PIP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('O', MaterialTags.component("oscillator")).define('C', Items.CRAFTING_TABLE)
                    .define('I', MaterialTags.component("advanced_io_processor"))
                    .unlockedBy("has_oscillator", recipes.has(MaterialTags.component("oscillator")))
                    .save(StorageModules.gate(recipes.output(), StorageModules.WIRELESS)))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(DENIED_KEY, "You need access to this network to use the tablet");
        ModContent.CONTENT.translation(NOTHING_MISSING_KEY, "Nothing is missing from the grid");
        ModContent.CONTENT.translation(NO_PATTERN_KEY, "Nothing missing can be crafted: no pattern makes it");
        ModContent.CONTENT.translation(QUEUED_KEY, "Queued a job for %s %s");
        ModContent.CONTENT.translation(PLAN_MISSING_KEY, "Cannot craft %s: %s %s is missing");
        ModContent.CONTENT.translation(TOO_MANY_JOBS_KEY,
                "You already have %s planned jobs waiting; wait for a crafting CPU to take them");
        ModContent.CONTENT.translation(PLAN_FAILED_KEY, "Cannot craft: %s");
        ModContent.CONTENT.translation(REQUEST_KEY, "Request missing");
        ModContent.CONTENT.translation(REQUEST_TOOLTIP_KEY,
                "Orders a crafting job for the ingredients of the last recipe the grid still lacks");
        ModContent.CONTENT.translation(ENERGY_KEY, "Energy: %s / %s MU");
        for (PlanTree.Failure failure : PlanTree.Failure.values()) {
            ModContent.CONTENT.translation(failureKey(failure), switch (failure) {
                case TOO_MANY_NODES -> "the request is too large";
                case TIME_LIMIT -> "planning took too long";
                case ERROR -> "planning failed, see the server log";
            });
        }
    }

    private CraftingTabletContent() {
    }

    /** The lang key of a planner failure's reason. */
    public static String failureKey(PlanTree.Failure failure) {
        return "message." + PREFIX + "crafting_tablet.failure." + failure.name().toLowerCase(Locale.ROOT);
    }

    /** Loads the class during mod construction and lists the grid for recipe viewers. */
    public static void init() {
        // The supplier is read when JEI starts, after registration.
        CraftingGridMenus.register(CraftingTabletMenu.class, () -> MENU.get());
        TabletJobs.init();
    }
}
