package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternFaces;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternDraft;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternEncoder;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminal;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalBlockEntity;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel.Action;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTransfer;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.CablePart;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Patterns and the pattern terminal (M7a): the pattern codec, encoding by hand and from a recipe viewer's crafting and
 * processing transfers, the face table, the craft access level and the network's pattern registry. Layouts run along x
 * at y = 1, z = 1.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PatternGameTests {
    private static final String[] NAMES = {"pattern_codec_round_trip", "pattern_terminal_hand_encoding",
            "pattern_terminal_crafting_transfer", "pattern_terminal_processing_transfer", "pattern_terminal_access",
            "pattern_network_registry", "pattern_terminal_part_and_load"};
    private static final StackKey IRON_ORE = ItemKey.of(Items.IRON_ORE);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final StackKey COAL = ItemKey.of(Items.COAL);

    static {
        CraftingTestContent.init();
        register(NAMES[0], PatternGameTests::codecRoundTrip);
        register(NAMES[1], PatternGameTests::handEncoding);
        register(NAMES[2], PatternGameTests::craftingTransfer);
        register(NAMES[3], PatternGameTests::processingTransfer);
        register(NAMES[4], PatternGameTests::access);
        register(NAMES[5], PatternGameTests::networkRegistry);
        register(NAMES[6], PatternGameTests::partAndLoad);
    }

    private PatternGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("pattern"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    /**
     * A pattern of another mod's machine with a tag input, a fluid input, a face, a returned input and a chance
     * byproduct.
     */
    static CraftingPattern sample() {
        return new CraftingPattern(CraftingPattern.Kind.PROCESSING, List.of(
                new PatternInput(new PatternIngredient.ItemTag(ItemTags.IRON_ORES), 2, Optional.of(Direction.UP),
                        false),
                new PatternInput(new PatternIngredient.Exact(FluidKey.of(Fluids.WATER)), 250, Optional.empty(), false),
                new PatternInput(new PatternIngredient.Exact(ItemKey.of(Items.BUCKET)), 1, Optional.empty(), true)),
                List.of(new PatternOutput(IRON, 2, 1f), new PatternOutput(COAL, 1, 0.25f)),
                Optional.of(Identifier.fromNamespaceAndPath("othermod", "mixing")), Optional.empty());
    }

    /** The codec keeps every field; ten crafting inputs, a zero amount and a chance above 1 fail to decode. */
    private static void codecRoundTrip(GameTestHelper helper) {
        RegistryOps<JsonElement> ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        CraftingPattern pattern = sample();
        JsonElement json = PatternCodecs.PATTERN.encodeStart(ops, pattern).getOrThrow();
        CraftingPattern back = PatternCodecs.PATTERN.parse(ops, json).getOrThrow();
        helper.assertValueEqual(back, pattern, "round trip");
        ItemStack stack = PatternItem.encode(pattern);
        helper.assertValueEqual(decoded(helper, stack), pattern, "the item's component");
        helper.assertTrue(PatternItem.pattern(new ItemStack(PatternContent.BLANK_PATTERN.get())) == null,
                "a blank pattern carries none");

        List<PatternInput> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new PatternInput(new PatternIngredient.Exact(COAL), 1, Optional.empty(), false));
        }
        String crafting = json.toString().replace("\"processing\"", "\"crafting\"");
        helper.assertTrue(
                PatternCodecs.PATTERN.parse(ops, com.google.gson.JsonParser.parseString(crafting)).isSuccess(),
                "three inputs are a valid crafting pattern");
        JsonElement tenJson = PatternCodecs.PATTERN
                .encodeStart(ops,
                        new CraftingPattern(CraftingPattern.Kind.PROCESSING, ten,
                                List.of(new PatternOutput(IRON, 1, 1f)), Optional.empty(), Optional.empty()))
                .getOrThrow();
        String tenCrafting = tenJson.toString().replace("\"processing\"", "\"crafting\"");
        DataResult<CraftingPattern> refused = PatternCodecs.PATTERN.parse(ops,
                com.google.gson.JsonParser.parseString(tenCrafting));
        helper.assertTrue(refused.isError(), "ten crafting inputs decode");
        helper.assertTrue(
                PatternCodecs.PATTERN
                        .parse(ops,
                                com.google.gson.JsonParser
                                        .parseString(json.toString().replace("\"amount\":2", "\"amount\":0")))
                        .isError(),
                "a zero amount decodes");
        helper.assertTrue(PatternCodecs.PATTERN
                .parse(ops, com.google.gson.JsonParser.parseString(json.toString().replace("0.25", "1.5"))).isError(),
                "a chance above 1 decodes");
        helper.succeed();
    }

    private static PatternTerminalBlockEntity terminal(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, PatternTerminalContent.TERMINAL.get());
        return helper.getBlockEntity(pos, PatternTerminalBlockEntity.class);
    }

    private static TerminalMenu menu(PatternTerminal terminal, ServerPlayer player) {
        return TerminalMenu.server(1, player.getInventory(), terminal, update -> {
        });
    }

    /** The pattern terminal menu's panel. */
    static PatternPanel panel(TerminalMenu menu) {
        PatternPanel panel = menu.patternPanel();
        if (panel == null) {
            throw new IllegalStateException("not a pattern terminal menu");
        }
        return panel;
    }

    private static void click(TerminalMenu menu, ServerPlayer player, Action action, int slot) {
        menu.clickMenuButton(player, action.button(slot));
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static CraftingPattern decoded(GameTestHelper helper, ItemStack stack) {
        CraftingPattern pattern = PatternItem.pattern(stack);
        if (pattern == null) {
            throw helper.assertionException(net.minecraft.network.chat.Component.literal("no pattern on " + stack));
        }
        return pattern;
    }

    private static ItemStack encoded(GameTestHelper helper, ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(PatternContent.ENCODED_PATTERN.get())) {
                return stack;
            }
        }
        throw helper.assertionException(net.minecraft.network.chat.Component.literal("no encoded pattern"));
    }

    /**
     * Processing by hand: items and a fluid set from the carried stack, amounts stepped, a face and the returned mark
     * set; encoding needs a blank, takes exactly one and gives one encoded pattern with those entries.
     */
    private static void handEncoding(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "pattern_hand");
        PatternTerminalBlockEntity terminal = terminal(helper, NetworkGameTests.at(2));
        TerminalMenu menu = menu(terminal, player);
        click(menu, player, Action.MODE, 0);
        helper.assertTrue(!panel(menu).draft().crafting(), "processing mode");
        menu.setCarried(new ItemStack(Items.IRON_ORE, 3));
        click(menu, player, Action.SET, 0);
        click(menu, player, Action.MORE, 0);
        menu.setCarried(new ItemStack(Items.WATER_BUCKET));
        click(menu, player, Action.SET_FLUID, 1);
        menu.setCarried(new ItemStack(Items.SHEARS));
        click(menu, player, Action.SET, 2);
        click(menu, player, Action.RETURNED, 2);
        menu.setCarried(new ItemStack(Items.IRON_INGOT, 2));
        click(menu, player, Action.SET, PatternDraft.INPUTS);
        menu.setCarried(ItemStack.EMPTY);
        click(menu, player, Action.FACE, 0);
        click(menu, player, Action.FACE, 0);
        click(menu, player, Action.LESS, PatternDraft.INPUTS);
        PatternDraft draft = panel(menu).draft();
        helper.assertValueEqual(draft.inputs().get(0).orElseThrow().amount(), 4L, "ore after one more");
        helper.assertValueEqual(draft.inputs().get(0).orElseThrow().face(), Optional.of(Direction.UP),
                "face after two steps");
        helper.assertValueEqual(draft.inputs().get(1).orElseThrow().key(), (StackKey) FluidKey.of(Fluids.WATER),
                "the bucket's fluid");
        helper.assertValueEqual(draft.inputs().get(1).orElseThrow().amount(), 1000L, "a bucket of water");
        helper.assertTrue(draft.inputs().get(2).orElseThrow().returned(), "shears returned");
        helper.assertValueEqual(draft.outputs().get(0).orElseThrow().amount(), 1L, "output after one less");

        click(menu, player, Action.ENCODE, 0);
        helper.assertTrue(panel(menu).result() == PatternEncoder.Result.NO_BLANK, "no blank pattern");
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get(), 2));
        click(menu, player, Action.ENCODE, 0);
        helper.assertTrue(panel(menu).result() == PatternEncoder.Result.ENCODED, "encoded");
        helper.assertValueEqual(count(player, PatternContent.BLANK_PATTERN.get()), 1, "one blank used");
        helper.assertValueEqual(count(player, PatternContent.ENCODED_PATTERN.get()), 1, "one encoded pattern");
        CraftingPattern pattern = decoded(helper, encoded(helper, player));
        helper.assertTrue(pattern.kind() == CraftingPattern.Kind.PROCESSING, "a processing pattern");
        helper.assertValueEqual(pattern.inputs().size(), 3, "inputs");
        helper.assertValueEqual(pattern.inputs().get(0),
                new PatternInput(new PatternIngredient.Exact(IRON_ORE), 4, Optional.of(Direction.UP), false), "ore");
        helper.assertTrue(pattern.inputs().get(2).returned(), "returned mark kept");
        helper.assertValueEqual(pattern.mainOutput(), new PatternOutput(IRON, 1, 1f), "output");

        click(menu, player, Action.CLEAR, 0);
        helper.assertTrue(panel(menu).draft().inputs().get(0).isEmpty(), "cleared");
        click(menu, player, Action.ENCODE, 0);
        helper.assertTrue(panel(menu).result() == PatternEncoder.Result.EMPTY, "an empty grid");
        helper.assertTrue(
                !menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.PATTERN, 31 * 16).buttonId()),
                "an unknown action");
        helper.assertTrue(
                !menu.clickMenuButton(player,
                        new TerminalAction(TerminalAction.Kind.PATTERN, PatternPanel.SLOTS).buttonId()),
                "a slot out of range");
        helper.succeed();
    }

    private static List<ItemStack> planks() {
        List<ItemStack> planks = new ArrayList<>();
        net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.PLANKS)
                .forEach(holder -> planks.add(new ItemStack(holder.value())));
        return planks;
    }

    /**
     * Lib's crafting transfer fills a crafting draft: cells whose stacks are a whole tag take the tag, single stacks
     * stay exact, and the output comes from the recipe. The middle-click lock makes a tag input exact.
     */
    private static void craftingTransfer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "pattern_craft");
        PatternTerminalBlockEntity terminal = terminal(helper, NetworkGameTests.at(2));
        TerminalMenu menu = menu(terminal, player);
        click(menu, player, Action.MODE, 0);
        List<List<ItemStack>> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            cells.add(i == 0 || i == 1 || i == 3 || i == 4 ? planks() : List.of());
        }
        menu.receiveRecipe(player, cells, false);
        PatternDraft draft = panel(menu).draft();
        helper.assertTrue(draft.crafting(), "a crafting draft");
        helper.assertValueEqual(draft.inputs().get(0).orElseThrow().tag(),
                Optional.<PatternIngredient>of(new PatternIngredient.ItemTag(ItemTags.PLANKS)), "planks tag");
        helper.assertValueEqual(draft.outputs().get(0).orElseThrow().key(), (StackKey) ItemKey.of(Items.CRAFTING_TABLE),
                "crafting table");
        helper.assertTrue(draft.recipe().isPresent(), "recipe id");

        List<List<ItemStack>> single = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            single.add(i == 4 ? List.of(new ItemStack(Items.OAK_LOG)) : List.of());
        }
        menu.receiveRecipe(player, single, false);
        helper.assertTrue(panel(menu).draft().inputs().get(4).orElseThrow().tag().isEmpty(), "one log stays exact");

        menu.receiveRecipe(player, cells, false);
        helper.assertTrue(panel(menu).draft().hasTags() && panel(menu).draft().substitutions(),
                "substitutions on after a transfer");
        click(menu, player, Action.SUBSTITUTE, 0);
        helper.assertTrue(!panel(menu).draft().substitutions(), "substitutions off");
        for (int i : new int[]{0, 1, 3, 4}) {
            helper.assertTrue(
                    panel(menu).draft().inputs().get(i).orElseThrow().ingredient() instanceof PatternIngredient.Exact,
                    "every tag input locked: " + i);
        }
        helper.assertTrue(panel(menu).draft().recipe().isPresent(), "the recipe id stays");
        click(menu, player, Action.SUBSTITUTE, 0);
        helper.assertTrue(panel(menu).draft().substitutions(), "substitutions on again");
        helper.assertValueEqual(panel(menu).draft().inputs().get(4).orElseThrow().ingredient(),
                (PatternIngredient) new PatternIngredient.ItemTag(ItemTags.PLANKS), "unlocked");
        click(menu, player, Action.EXACT, 0);
        helper.assertTrue(
                panel(menu).draft().inputs().get(0).orElseThrow().ingredient() instanceof PatternIngredient.Exact,
                "locked");
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        click(menu, player, Action.ENCODE, 0);
        CraftingPattern pattern = decoded(helper, encoded(helper, player));
        helper.assertTrue(pattern.kind() == CraftingPattern.Kind.CRAFTING, "a crafting pattern");
        helper.assertValueEqual(pattern.inputs().size(), 4, "four filled cells");
        helper.assertTrue(pattern.inputs().get(0).ingredient() instanceof PatternIngredient.Exact, "the locked cell");
        helper.assertValueEqual(pattern.inputs().get(1).ingredient(),
                (PatternIngredient) new PatternIngredient.ItemTag(ItemTags.PLANKS), "a tag cell");
        helper.assertValueEqual(pattern.mainOutput().amount(), 1L, "one table");

        helper.assertValueEqual(pattern.recipeType(), Optional.of(Identifier.withDefaultNamespace("crafting")),
                "a crafting pattern names the crafting type");
        helper.assertTrue(pattern.recipe().isPresent(), "and its recipe");

        // The mode tabs set a mode instead of toggling it.
        click(menu, player, Action.MODE_CRAFTING, 0);
        helper.assertTrue(panel(menu).draft().crafting(), "still crafting");
        click(menu, player, Action.MODE_PROCESSING, 0);
        click(menu, player, Action.MODE_PROCESSING, 0);
        helper.assertTrue(!panel(menu).draft().crafting(), "processing");
        click(menu, player, Action.MODE_CRAFTING, 0);
        helper.assertTrue(panel(menu).draft().crafting(), "crafting again");

        // A grid filled by hand gets the recipe id too, as machine export would write it.
        click(menu, player, Action.CLEAR, 0);
        menu.setCarried(new ItemStack(Items.OAK_PLANKS));
        click(menu, player, Action.SET, 1);
        click(menu, player, Action.SET, 4);
        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        int before = count(player, PatternContent.ENCODED_PATTERN.get());
        click(menu, player, Action.ENCODE, 0);
        helper.assertValueEqual(count(player, PatternContent.ENCODED_PATTERN.get()), before + 1, "hand encoded");
        CraftingPattern sticks = null;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            CraftingPattern found = PatternItem.pattern(player.getInventory().getItem(i));
            if (found != null && found.mainOutput().key().equals(ItemKey.of(Items.STICK))) {
                sticks = found;
            }
        }
        helper.assertTrue(
                sticks != null && sticks.recipe().isPresent()
                        && sticks.recipeType().equals(Optional.of(Identifier.withDefaultNamespace("crafting"))),
                "hand-made sticks name their recipe: " + sticks);

        List<List<ItemStack>> nonsense = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nonsense.add(i == 0 ? List.of(new ItemStack(Items.DIRT)) : List.of());
        }
        menu.receiveRecipe(player, nonsense, false);
        click(menu, player, Action.ENCODE, 0);
        helper.assertTrue(panel(menu).result() == PatternEncoder.Result.NO_RECIPE, "dirt alone crafts nothing");
        helper.succeed();
    }

    /**
     * A recipe viewer's smelting recipe becomes a processing draft; encoding fills the furnace faces (ore on top, coal
     * on the side) and keeps the tag and the recipe type. Inputs past nine are dropped.
     */
    private static void processingTransfer(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "pattern_process");
        PatternTerminalBlockEntity terminal = terminal(helper, NetworkGameTests.at(2));
        TerminalMenu menu = menu(terminal, player);
        Identifier smelting = Identifier.withDefaultNamespace("smelting");
        panel(menu).receiveProcessing(player,
                new PatternTransfer(menu.containerId, Optional.of(smelting), Optional.empty(),
                        List.of(new PatternTransfer.Entry(IRON_ORE, 1, Optional.of(ItemTags.IRON_ORES.location()), 1f),
                                new PatternTransfer.Entry(COAL, 1, Optional.empty(), 1f)),
                        List.of(new PatternTransfer.Entry(IRON, 1, Optional.empty(), 1f))));
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        click(menu, player, Action.ENCODE, 0);
        CraftingPattern pattern = decoded(helper, encoded(helper, player));
        helper.assertValueEqual(pattern.inputs().get(0).ingredient(),
                (PatternIngredient) new PatternIngredient.ItemTag(ItemTags.IRON_ORES), "ore tag");
        helper.assertValueEqual(pattern.inputs().get(0).face(), Optional.of(Direction.UP), "ore on top");
        helper.assertValueEqual(pattern.inputs().get(1).face(), Optional.of(Direction.NORTH), "fuel on the side");
        helper.assertValueEqual(pattern.recipeType(), Optional.of(smelting), "recipe type");
        helper.assertTrue(PatternFaces.forBlock(Blocks.BLAST_FURNACE) == PatternFaces.Rule.FURNACE, "blast furnace");
        helper.assertTrue(PatternFaces.forBlock(Blocks.CHEST) == null, "a chest is unknown");
        helper.assertValueEqual(PatternFaces.Rule.FURNACE.outputFace(), Optional.of(Direction.DOWN), "furnace output");
        helper.assertTrue(PatternFaces.forRecipeType(Identifier.fromNamespaceAndPath("othermod", "grinding")) == null,
                "unknown recipe types get no faces");

        List<PatternTransfer.Entry> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new PatternTransfer.Entry(COAL, i + 1, Optional.empty(), 1f));
        }
        PatternTransfer grindingTransfer = new PatternTransfer(menu.containerId,
                Optional.of(Identifier.fromNamespaceAndPath("othermod", "grinding")), Optional.empty(), many,
                List.of(new PatternTransfer.Entry(FluidKey.of(Fluids.LAVA), 500, Optional.empty(), 0.5f)));
        panel(menu).receiveProcessing(player, grindingTransfer);
        helper.assertTrue(panel(menu).draft().inputs().get(2).isEmpty(), "a second transfer in one tick is dropped");
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            panel(menu).receiveProcessing(player, grindingTransfer);
            PatternDraft draft = panel(menu).draft();
            helper.assertTrue(draft.inputs().get(8).isPresent(), "nine inputs");
            helper.assertValueEqual(draft.outputs().get(0).orElseThrow().chance(), 0.5f, "chance kept");
            player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
            player.getInventory().removeItem(encoded(helper, player));
            click(menu, player, Action.ENCODE, 0);
            CraftingPattern grinding = decoded(helper, encoded(helper, player));
            helper.assertTrue(grinding.inputs().get(0).face().isEmpty(), "unknown machine: no face");
        }).thenSucceed();
    }

    /**
     * The pattern terminal needs the craft level: with a security module, a depositor cannot use it, a player with
     * craft access can, and the cycle runs through craft between deposit and full.
     */
    private static void access(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "pat_owner");
        ServerPlayer depositor = NetworkGameTests.player(level, "pat_depositor");
        ServerPlayer crafter = NetworkGameTests.player(level, "pat_crafter");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        PatternTerminalBlockEntity terminal = terminal(helper, NetworkGameTests.at(2));
        terminal.setOwner(OwnerRecord.of(owner));
        helper.assertTrue(!terminal.canUse(depositor), "a stranger on an owned terminal");
        helper.assertTrue(terminal.canUse(owner), "the owner");
        helper.assertValueEqual(AccessLevel.DEPOSIT.next(), AccessLevel.CRAFT, "deposit then craft");
        helper.assertValueEqual(AccessLevel.CRAFT.next(), AccessLevel.LOGIC, "craft then logic");
        helper.assertTrue(
                AccessLevel.FULL.atLeast(AccessLevel.CRAFT) && !AccessLevel.DEPOSIT.atLeast(AccessLevel.CRAFT),
                "order");
        BlockPos module = core.above();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(terminal.network() != null, "joined"))
                .thenExecute(() -> {
                    ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    controller.setSecurity(SecuritySettings.DEFAULT
                            .withPlayer(depositor.getUUID(), depositor.getGameProfile().name(), AccessLevel.DEPOSIT)
                            .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT));
                }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(),
                        "module active"))
                .thenExecute(() -> {
                    helper.assertTrue(!terminal.canUse(depositor), "deposit is not enough");
                    helper.assertTrue(terminal.canUse(crafter), "craft access");
                    TerminalMenu menu = menu(terminal, depositor);
                    helper.assertTrue(!menu.clickMenuButton(depositor, Action.MODE.button(0)), "a depositor's click");
                    helper.assertTrue(!menu.stillValid(depositor), "a depositor's menu closes");
                    TerminalMenu allowed = menu(terminal, crafter);
                    helper.assertTrue(allowed.clickMenuButton(crafter, Action.MODE.button(0)), "a crafter's click");
                }).thenSucceed();
    }

    /**
     * A pattern host's patterns reach its network's registry and craftable index when it joins, follow its changes and
     * leave with it; a merge of two networks keeps them.
     */
    private static void networkRegistry(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 100_000);
        BlockPos hostPos = NetworkGameTests.at(2);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        CraftingPattern pattern = sample();
        host.setPatterns(List.of(pattern));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(host.network() != null, "joined"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertTrue(network.patterns().isCraftable(IRON), "iron craftable");
                    helper.assertTrue(!network.patterns().isCraftable(COAL), "a byproduct is not indexed");
                    helper.assertValueEqual(network.patterns().patternsFor(IRON).size(), 1, "one pattern");
                    helper.assertValueEqual(network.patterns().snapshot().patterns(), List.of(pattern), "snapshot");
                    long generation = network.patterns().generation();
                    host.setPatterns(List.of());
                    helper.assertTrue(!network.patterns().isCraftable(IRON), "pattern removed");
                    helper.assertTrue(network.patterns().generation() > generation, "generation bumped");
                    host.setPatterns(List.of(pattern));
                    helper.setBlock(hostPos, Blocks.AIR);
                })
                .thenWaitUntil(() -> helper
                        .assertTrue(!NetworkGameTests.state(helper, core).patterns().isCraftable(IRON), "host gone"))
                .thenExecute(
                        () -> helper.assertValueEqual(NetworkGameTests.state(helper, core).patterns().providerCount(),
                                0, "no providers left"))
                .thenSucceed();
    }

    /** The part form opens the same grid, keeps its draft across a save and load, and loads an encoded pattern. */
    private static void partAndLoad(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "pattern_part");
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = PartGameTests.parts(helper, 2);
        helper.assertTrue(parts.place(Direction.UP, PatternTerminalContent.TERMINAL_PART, null,
                new ItemStack(PatternTerminalContent.TERMINAL_PART_ITEM.get())), "part placed");
        CablePart part = PartGameTests.part(helper, parts, Direction.UP);
        helper.assertTrue(part instanceof PatternTerminal, "a pattern terminal part");
        PatternTerminal terminal = (PatternTerminal) part;
        TerminalMenu menu = menu(terminal, player);
        menu.setCarried(PatternItem.encode(sample()));
        click(menu, player, Action.LOAD, 0);
        PatternDraft draft = panel(menu).draft();
        helper.assertTrue(!draft.crafting(), "processing loaded");
        helper.assertValueEqual(draft.inputs().get(0).orElseThrow().tag(),
                Optional.<PatternIngredient>of(new PatternIngredient.ItemTag(ItemTags.IRON_ORES)), "tag loaded");
        helper.assertValueEqual(draft.outputs().get(1).orElseThrow().chance(), 0.25f, "chance loaded");
        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        click(menu, player, Action.ENCODE, 0);
        helper.assertValueEqual(decoded(helper, encoded(helper, player)), sample(),
                "a loaded pattern encodes to itself");

        net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput
                .createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        terminal.encoder().save(output);
        PatternEncoder copy = new PatternEncoder(() -> {
        });
        copy.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), output.buildResult()));
        helper.assertValueEqual(copy.draft(), terminal.encoder().draft(), "draft saved and loaded");
        helper.succeed();
    }
}
