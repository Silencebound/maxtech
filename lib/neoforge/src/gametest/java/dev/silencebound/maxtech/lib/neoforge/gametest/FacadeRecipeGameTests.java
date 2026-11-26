package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.facade.FacadeContent;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import java.util.List;
import java.util.Objects;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Lib's facade recipes: exactly one of the plate recipe and the ingot fallback loads, chosen by whether any mod tags an
 * iron plate, and the loaded one turns a block and its binder into four facades. Lib's own run has no plates, so it
 * checks the fallback.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class FacadeRecipeGameTests {
    private static final String NAME = "facade_recipe_binder";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final TagKey<Item> IRON_PLATES = ItemTags
            .create(Identifier.fromNamespaceAndPath("c", "plates/iron"));

    static {
        FUNCTIONS.register(NAME, () -> FacadeRecipeGameTests::binder);
    }

    private FacadeRecipeGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("facade_recipes"));
        Identifier id = MaxtechLib.id(NAME);
        event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
    }

    private static void binder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean plates = BuiltInRegistries.ITEM.getTagOrEmpty(IRON_PLATES).iterator().hasNext();
        helper.assertValueEqual(loaded(level, "facade"), plates, "plate recipe loaded");
        helper.assertValueEqual(loaded(level, "facade_from_ingot"), !plates, "ingot recipe loaded");

        ItemStack binder = plates
                ? new ItemStack(BuiltInRegistries.ITEM.getTagOrEmpty(IRON_PLATES).iterator().next())
                : new ItemStack(Items.IRON_INGOT);
        ItemStack result = craft(level, List.of(new ItemStack(Blocks.STONE_BRICKS), binder));
        helper.assertTrue(result.is(FacadeContent.FACADE.get()), "result " + result);
        helper.assertValueEqual(result.getCount(), 4, "facades per craft");
        helper.assertTrue(
                Objects.equals(result.get(ModDataComponents.FACADE.get()), Blocks.STONE_BRICKS.defaultBlockState()),
                "mimicked block");
        helper.succeed();
    }

    private static boolean loaded(ServerLevel level, String path) {
        return level.getServer().getRecipeManager()
                .byKey(ResourceKey.<Recipe<?>>create(Registries.RECIPE, MaxtechLib.id(path))).isPresent();
    }

    private static ItemStack craft(ServerLevel level, List<ItemStack> items) {
        CraftingInput input = CraftingInput.of(items.size(), 1, items);
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.value().assemble(input)).orElse(ItemStack.EMPTY);
    }
}
