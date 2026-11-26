package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The api pattern records reject amounts below one, chances outside (0, 1], empty patterns and crafting patterns with
 * more than nine inputs, and ingredients match by key or tag. Needs real items, hence a GameTest.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CraftingApiGameTests {
    private static final String NAME = "crafting_api_pattern_records";
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> CraftingApiGameTests::records);
    }

    private CraftingApiGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("crafting_api"));
        event.registerTest(MaxtechStorage.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    private static PatternInput input(long amount) {
        return new PatternInput(new PatternIngredient.Exact(ItemKey.of(Items.COBBLESTONE)), amount, Optional.empty(),
                false);
    }

    private static PatternOutput output(float chance) {
        return new PatternOutput(ItemKey.of(Items.STONE), 1, chance);
    }

    private static void rejects(GameTestHelper helper, String what, Runnable construct) {
        try {
            construct.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        helper.fail("accepted " + what);
    }

    private static void records(GameTestHelper helper) {
        rejects(helper, "input amount 0", () -> input(0));
        rejects(helper, "output amount 0", () -> new PatternOutput(ItemKey.of(Items.STONE), 0, 1f));
        rejects(helper, "chance 0", () -> output(0f));
        rejects(helper, "chance 1.5", () -> output(1.5f));
        rejects(helper, "chance NaN", () -> output(Float.NaN));
        helper.assertTrue(output(1f).guaranteed(), "chance 1 is guaranteed");
        helper.assertFalse(output(0.5f).guaranteed(), "chance 0.5 is not guaranteed");

        rejects(helper, "no inputs", () -> new CraftingPattern(CraftingPattern.Kind.PROCESSING, List.of(),
                List.of(output(1f)), Optional.empty(), Optional.empty()));
        rejects(helper, "no outputs", () -> new CraftingPattern(CraftingPattern.Kind.PROCESSING, List.of(input(1)),
                List.of(), Optional.empty(), Optional.empty()));
        List<PatternInput> ten = Collections.nCopies(10, input(1));
        rejects(helper, "ten crafting inputs", () -> new CraftingPattern(CraftingPattern.Kind.CRAFTING, ten,
                List.of(output(1f)), Optional.empty(), Optional.empty()));
        CraftingPattern processing = new CraftingPattern(CraftingPattern.Kind.PROCESSING, ten,
                List.of(output(1f), output(0.25f)), Optional.empty(), Optional.empty());
        helper.assertValueEqual(output(1f), processing.mainOutput(), "main output");

        PatternIngredient planks = new PatternIngredient.ItemTag(ItemTags.PLANKS);
        helper.assertTrue(planks.matches(ItemKey.of(Items.OAK_PLANKS)), "oak planks match #planks");
        helper.assertFalse(planks.matches(ItemKey.of(Items.STONE)), "stone does not match #planks");
        helper.assertFalse(new PatternIngredient.Exact(ItemKey.of(Items.STONE)).matches(ItemKey.of(Items.COBBLESTONE)),
                "exact key");
        helper.succeed();
    }
}
