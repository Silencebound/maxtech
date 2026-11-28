package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.lib.api.recipe.KindInput;
import dev.silencebound.maxtech.lib.api.recipe.KindOutput;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipe;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeInput;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipePlan;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeShape;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeType;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Lib's machine recipes with registries, which the JUnit tests of {@code lib.api.recipe} cannot build: codecs, shape
 * and unknown-id refusals, matching of real items and fluids, and the demo recipe in the GameTest resources loading and
 * being found. The test machine type is registered here, so it exists in dev runs only.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MachineRecipeGameTests {
    /** Two input slots, one catalyst, two outputs, two input tanks and one output tank, modes fine and coarse. */
    static final MachineRecipeType TEST_MACHINE = MachineRecipeType.define(MaxtechLib.MOD_ID, "test_machine",
            MachineRecipeShape.items(2, 2).withCatalysts(1).withTanks(2, 1).withModes("fine", "coarse"));

    private static final String ROUND_TRIP = "machine_recipes_round_trip";
    private static final String REFUSED = "machine_recipes_refused";
    private static final String MATCHING = "machine_recipes_matching";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(ROUND_TRIP, () -> MachineRecipeGameTests::roundTrip);
        FUNCTIONS.register(REFUSED, () -> MachineRecipeGameTests::refused);
        FUNCTIONS.register(MATCHING, () -> MachineRecipeGameTests::matching);
    }

    private MachineRecipeGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("machine_recipes"));
        for (String name : List.of(ROUND_TRIP, REFUSED, MATCHING)) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    /** Water named "Holy", an exact resource with components. */
    private static KindKey<FluidKey> holyWater() {
        return new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.WATER,
                DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal("Holy")).build()));
    }

    /**
     * Two iron ingots and a diamond catalyst, 1000 mB of any water and 100 mB of holy water, in fine mode, make a gold
     * ingot (half the time) and 250 mB of lava.
     */
    private static MachineRecipe demo(HolderGetter<Item> items) {
        return new MachineRecipe(TEST_MACHINE, Optional.of("fine"),
                List.of(new ItemInput(Ingredient.of(items.getOrThrow(Tags.Items.INGOTS_IRON)), 2),
                        new ItemInput(Ingredient.of(items.getOrThrow(Tags.Items.GEMS_DIAMOND)), 1, false)),
                List.of(KindInput.tag(ResourceKinds.FLUID, Tags.Fluids.WATER.location(), 1000),
                        KindInput.of(holyWater(), 100)),
                List.of(new ItemOutput(new ItemStackTemplate(Items.GOLD_INGOT), 5000)),
                List.of(new KindOutput(new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.LAVA)), 250)), 20, 100);
    }

    private static RegistryOps<JsonElement> json(ServerLevel level) {
        return level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
    }

    private static JsonElement encode(ServerLevel level, Recipe<?> recipe) {
        return Recipe.CODEC.encodeStart(json(level), recipe).getOrThrow();
    }

    /**
     * The demo recipe saves as JSON with its type and loads back the same, through the vanilla dispatch codec; the
     * network form round trips too.
     */
    private static void roundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MachineRecipe recipe = demo(level.registryAccess().lookupOrThrow(Registries.ITEM));
        JsonElement saved = encode(level, recipe);
        helper.assertValueEqual(saved.getAsJsonObject().get("type").getAsString(), "maxtech_lib:test_machine", "type");
        Recipe<?> loaded = Recipe.CODEC.parse(json(level), saved).getOrThrow();
        helper.assertTrue(loaded instanceof MachineRecipe machine && machine.type() == TEST_MACHINE, "loaded type");
        helper.assertValueEqual(encode(level, loaded), saved, "saved again");
        MachineRecipe machine = (MachineRecipe) loaded;
        helper.assertValueEqual(machine.tankInputs(), recipe.tankInputs(), "tank inputs");
        helper.assertValueEqual(machine.tankOutputs(), recipe.tankOutputs(), "tank outputs");
        helper.assertValueEqual(machine.outputs().getFirst().chance(), 5000, "chance");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        TEST_MACHINE.streamCodec().encode(buffer, recipe);
        MachineRecipe streamed = TEST_MACHINE.streamCodec().decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "bytes left");
        helper.assertValueEqual(encode(level, streamed), saved, "streamed");
        helper.succeed();
    }

    /** A valid recipe of the test machine, which {@link #refused} breaks one field at a time. */
    private static final String VALID = """
            {"type": "maxtech_lib:test_machine", "mode": "fine",
             "inputs": [{"ingredient": "#c:ingots/iron", "count": 2}],
             "tank_inputs": [{"kind": "maxtech_lib:fluid", "tag": "c:water", "amount": 1000}],
             "outputs": [{"item": {"id": "minecraft:gold_ingot"}, "chance": 5000}],
             "tank_outputs": [{"resource": {"kind": "maxtech_lib:fluid", "id": "minecraft:lava"}, "amount": 250}],
             "duration": 100}""";

    /**
     * Recipes that do not fit the shape, have chances or amounts out of bounds, or name an item, fluid or kind no mod
     * has fail to decode, which makes the game log and skip them; the valid one they are made from decodes.
     */
    private static void refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(parse(level, VALID).isSuccess(), "valid recipe: " + parse(level, VALID));
        Map<String, String[]> broken = Map.ofEntries(
                Map.entry("three inputs", new String[]{"\"count\": 2}]",
                        "\"count\": 2}, {\"ingredient\": \"minecraft:stone\"}, {\"ingredient\": \"minecraft:dirt\"}]"}),
                Map.entry("no mode", new String[]{"\"mode\": \"fine\",", ""}),
                Map.entry("unknown mode", new String[]{"\"fine\"", "\"rough\""}),
                Map.entry("no duration", new String[]{"\"duration\": 100", "\"energy_per_tick\": 1"}),
                Map.entry("chance 0", new String[]{"\"chance\": 5000", "\"chance\": 0"}),
                Map.entry("chance above always", new String[]{"\"chance\": 5000", "\"chance\": 10001"}),
                Map.entry("tank amount 0", new String[]{"\"amount\": 1000", "\"amount\": 0"}),
                Map.entry("unknown output item", new String[]{"minecraft:gold_ingot", "nomod:nothing"}),
                Map.entry("unknown input item", new String[]{"#c:ingots/iron", "nomod:nothing"}),
                Map.entry("unknown tag kind",
                        new String[]{"\"kind\": \"maxtech_lib:fluid\", \"tag\"", "\"kind\": \"nomod:gas\", \"tag\""}),
                Map.entry("kind without tags",
                        new String[]{"\"kind\": \"maxtech_lib:fluid\", \"tag\"",
                                "\"kind\": \"maxtech_lib:energy\", \"tag\""}),
                Map.entry("unknown output kind",
                        new String[]{"\"kind\": \"maxtech_lib:fluid\", \"id\"", "\"kind\": \"nomod:gas\", \"id\""}),
                Map.entry("unknown output fluid", new String[]{"minecraft:lava", "nomod:goo"}),
                Map.entry("resource and tag", new String[]{"\"tag\": \"c:water\"",
                        "\"tag\": \"c:water\", \"resource\": {\"kind\": \"maxtech_lib:fluid\", \"id\": \"minecraft:water\"}"}));
        for (Map.Entry<String, String[]> entry : broken.entrySet()) {
            String[] change = entry.getValue();
            helper.assertTrue(VALID.contains(change[0]), "test edit applies: " + entry.getKey());
            DataResult<Recipe<?>> result = parse(level, VALID.replace(change[0], change[1]));
            helper.assertTrue(result.isError(), "refused: " + entry.getKey());
        }
        helper.succeed();
    }

    private static DataResult<Recipe<?>> parse(ServerLevel level, String text) {
        return Recipe.CODEC.parse(json(level), JsonParser.parseString(text));
    }

    /**
     * Counts, tags, catalysts, components, fluid amounts and modes decide a match, and the plan says where each input
     * comes from.
     */
    private static void matching(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MachineRecipe recipe = demo(level.registryAccess().lookupOrThrow(Registries.ITEM));
        KindKey<FluidKey> water = new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.WATER));
        KindKey<FluidKey> lava = new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.LAVA));

        TestInput input = full(water);
        helper.assertTrue(recipe.matches(input, level), "everything present");
        helper.assertValueEqual(input.plan().items(0), 2, "iron taken");
        helper.assertValueEqual(input.plan().items(2), 1, "catalyst reserved");
        helper.assertValueEqual(input.plan().drained(0), 1000L, "water drained");
        helper.assertValueEqual(input.plan().drained(1), 100L, "holy water drained");

        TestInput spread = full(water);
        spread.slots[0] = new ItemStack(Items.IRON_INGOT);
        spread.slots[1] = new ItemStack(Items.IRON_INGOT);
        helper.assertTrue(recipe.matches(spread, level), "iron over two slots");

        TestInput short1 = full(water);
        short1.slots[0] = new ItemStack(Items.IRON_INGOT);
        helper.assertFalse(recipe.matches(short1, level), "one iron short");
        TestInput gold = full(water);
        gold.slots[0] = new ItemStack(Items.GOLD_INGOT, 2);
        helper.assertFalse(recipe.matches(gold, level), "gold is not in c:ingots/iron");
        TestInput catalystInInput = full(water);
        catalystInInput.slots[1] = new ItemStack(Items.DIAMOND);
        catalystInInput.slots[2] = ItemStack.EMPTY;
        helper.assertFalse(recipe.matches(catalystInInput, level), "catalyst in an input slot");
        TestInput lowWater = full(water);
        lowWater.amounts[0] = 999;
        helper.assertFalse(recipe.matches(lowWater, level), "999 mB of water");
        helper.assertFalse(recipe.matches(full(lava), level), "lava is not in c:water");
        TestInput plainWater = full(water);
        plainWater.tanks[1] = Optional.of(water);
        plainWater.amounts[0] = 0;
        plainWater.amounts[1] = 2000;
        helper.assertFalse(recipe.matches(plainWater, level), "plain water for holy water");
        TestInput coarse = full(water);
        coarse.mode = "coarse";
        helper.assertFalse(recipe.matches(coarse, level), "other mode");

        // An item input with components: a stone named "Keystone", not plain stone.
        ItemStack keystone = new ItemStack(Items.STONE);
        keystone.set(DataComponents.CUSTOM_NAME, Component.literal("Keystone"));
        MachineRecipe named = new MachineRecipe(TEST_MACHINE, Optional.of("coarse"),
                List.of(new ItemInput(DataComponentIngredient.of(false,
                        DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal("Keystone"))
                                .build(),
                        Items.STONE), 1)),
                List.of(), List.of(new ItemOutput(new ItemStackTemplate(Items.DIAMOND), ItemOutput.ALWAYS)), List.of(),
                0, 1);
        TestInput withKeystone = new TestInput("coarse", keystone, ItemStack.EMPTY, ItemStack.EMPTY);
        helper.assertTrue(named.matches(withKeystone, level), "named stone");
        helper.assertFalse(named
                .matches(new TestInput("coarse", new ItemStack(Items.STONE), ItemStack.EMPTY, ItemStack.EMPTY), level),
                "plain stone");
        helper.succeed();
    }

    /** Every input of the demo recipe: 2 iron, a diamond catalyst, 1000 mB of {@code fluid} and 100 mB holy water. */
    private static TestInput full(KindKey<?> fluid) {
        TestInput input = new TestInput("fine", new ItemStack(Items.IRON_INGOT, 2), ItemStack.EMPTY,
                new ItemStack(Items.DIAMOND));
        input.tanks[0] = Optional.of(fluid);
        input.amounts[0] = 1000;
        input.tanks[1] = Optional.of(holyWater());
        input.amounts[1] = 100;
        return input;
    }

    /** Two input slots, one catalyst slot and two input tanks, set directly by the tests. */
    private static final class TestInput implements MachineRecipeInput {
        final ItemStack[] slots;
        @SuppressWarnings("unchecked") // an array of one generic type
        final Optional<KindKey<?>>[] tanks = new Optional[]{Optional.empty(), Optional.empty()};
        final long[] amounts = new long[2];
        final MachineRecipePlan plan = new MachineRecipePlan(3, 2);
        String mode;

        TestInput(String mode, ItemStack first, ItemStack second, ItemStack catalyst) {
            this.mode = mode;
            this.slots = new ItemStack[]{first, second, catalyst};
        }

        @Override
        public ItemStack getItem(int index) {
            return slots[index];
        }

        @Override
        public String mode() {
            return mode;
        }

        @Override
        public int inputSlots() {
            return 2;
        }

        @Override
        public int catalystSlots() {
            return 1;
        }

        @Override
        public int inputTanks() {
            return 2;
        }

        @Override
        public Optional<KindKey<?>> tankResource(int tank) {
            return amounts[tank] > 0 ? tanks[tank] : Optional.empty();
        }

        @Override
        public long tankAmount(int tank) {
            return amounts[tank];
        }

        @Override
        public MachineRecipePlan plan() {
            return plan;
        }
    }
}
