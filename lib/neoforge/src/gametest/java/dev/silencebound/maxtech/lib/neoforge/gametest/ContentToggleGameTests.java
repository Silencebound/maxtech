package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.content.ContentToggle;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * {@link ContentToggle}: hiding items from creative tabs and the {@code maxtech_lib:content_enabled} recipe condition.
 * Lib's own tests for it, so they do not depend on Storage's modules.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ContentToggleGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");

    private static final AtomicBoolean TOGGLE = new AtomicBoolean(true);
    private static final Identifier TOGGLE_ID = MaxtechLib.id("gametest_toggle_on");
    private static final ContentToggle TEST_TOGGLE = ContentToggle.register(TOGGLE_ID, TOGGLE::get)
            .hides(() -> Items.DEAD_BUSH);
    private static final ContentToggle OFF_TOGGLE = ContentToggle.register(MaxtechLib.id("gametest_toggle_off"),
            () -> false);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        test("content_toggle_hides_items", ContentToggleGameTests::toggleHidesItems);
        test("content_toggle_recipe_condition", ContentToggleGameTests::toggleRecipeCondition);
    }

    private ContentToggleGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> function);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("content_toggle"));
        for (String name : TESTS) {
            event.registerTest(MaxtechLib.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(name)),
                            new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    /** A disabled toggle takes its items out of every creative tab, search included, until it is on again. */
    private static void toggleHidesItems(GameTestHelper helper) {
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(CreativeModeTabs.NATURAL_BLOCKS);
        CreativeModeTab.ItemDisplayParameters parameters = new CreativeModeTab.ItemDisplayParameters(
                helper.getLevel().enabledFeatures(), false, helper.getLevel().registryAccess());
        try {
            TOGGLE.set(false);
            helper.assertTrue(ContentToggle.hiddenItems().contains(Items.DEAD_BUSH), "dead bush not hidden");
            helper.assertFalse(TEST_TOGGLE.isEnabled(), "toggle still on");
            tab.buildContents(parameters);
            helper.assertFalse(contains(tab.getDisplayItems(), Items.DEAD_BUSH), "dead bush in the tab");
            helper.assertFalse(contains(tab.getSearchTabDisplayItems(), Items.DEAD_BUSH),
                    "dead bush in the search tab");
            TOGGLE.set(true);
            helper.assertFalse(ContentToggle.hiddenItems().contains(Items.DEAD_BUSH), "dead bush still hidden");
            tab.buildContents(parameters);
            helper.assertTrue(contains(tab.getDisplayItems(), Items.DEAD_BUSH), "dead bush back in the tab");
        } finally {
            TOGGLE.set(true);
        }
        helper.succeed();
    }

    /**
     * The condition reads the toggle: the GameTest recipe under an always-off toggle did not load, its twin under a
     * toggle that was on did, and a condition parsed now follows the toggle.
     */
    private static void toggleRecipeCondition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.recipeAccess().byKey(recipe("test_toggle/disabled")).isEmpty(),
                "recipe of a disabled toggle loaded");
        helper.assertTrue(level.recipeAccess().byKey(recipe("test_toggle/enabled")).isPresent(),
                "recipe of an enabled toggle did not load");
        helper.assertFalse(OFF_TOGGLE.isEnabled(), "the off toggle is on");
        JsonObject json = new JsonObject();
        JsonArray conditions = new JsonArray();
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "maxtech_lib:content_enabled");
        condition.addProperty("toggle", TOGGLE_ID.toString());
        conditions.add(condition);
        json.add("neoforge:conditions", conditions);
        try {
            helper.assertTrue(ICondition.conditionsMatched(JsonOps.INSTANCE, json), "condition false while on");
            TOGGLE.set(false);
            helper.assertFalse(ICondition.conditionsMatched(JsonOps.INSTANCE, json), "condition true while off");
        } finally {
            TOGGLE.set(true);
        }
        condition.addProperty("toggle", "maxtech_lib:no_such_toggle");
        helper.assertTrue(ICondition.conditionsMatched(JsonOps.INSTANCE, json), "an unknown toggle reads as off");
        helper.succeed();
    }

    private static ResourceKey<Recipe<?>> recipe(String path) {
        return ResourceKey.create(Registries.RECIPE, MaxtechLib.id(path));
    }

    private static boolean contains(Iterable<ItemStack> stacks, Item item) {
        for (ItemStack stack : stacks) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }
}
