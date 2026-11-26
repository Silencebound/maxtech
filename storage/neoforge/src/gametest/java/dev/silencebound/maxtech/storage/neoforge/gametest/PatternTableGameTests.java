package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.assembler.CraftingAssemblerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDispatcher;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternDraft;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternEncoder;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalBlockEntity;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel.Action;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTransfer;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Pattern amounts and table patterns (B09 item 16): x2 / /2 (x8 / /8 with shift) on a processing draft and the refused
 * cases, stonecutting and smithing drafts filled from a recipe viewer and encoded, and both kinds crafted by the
 * assembler through a job. Jobs run on a controller at x = 1, a storage device at x = 2 with a CPU above it, a provider
 * at x = 3 facing east and its assembler at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PatternTableGameTests {
    private static final String[] NAMES = {"pattern_scale_amounts", "pattern_stonecutting_transfer",
            "pattern_smithing_transfer", "pattern_stonecutting_job", "pattern_smithing_job",
            "pattern_table_recipe_changed"};
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey SLAB = ItemKey.of(Items.STONE_SLAB);
    private static final StackKey TEMPLATE = ItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    private static final StackKey DIAMOND_SWORD = ItemKey.of(Items.DIAMOND_SWORD);
    private static final StackKey NETHERITE = ItemKey.of(Items.NETHERITE_INGOT);
    private static final StackKey NETHERITE_SWORD = ItemKey.of(Items.NETHERITE_SWORD);
    private static final Identifier STONECUTTING = Identifier.withDefaultNamespace("stonecutting");
    private static final Identifier SMITHING = Identifier.withDefaultNamespace("smithing");
    private static final Identifier SLAB_RECIPE = Identifier.withDefaultNamespace("stone_slab_from_stone_stonecutting");
    private static final Identifier SWORD_RECIPE = Identifier.withDefaultNamespace("netherite_sword_smithing");
    private static final Identifier NETHERITE_TAG = Identifier.withDefaultNamespace("netherite_tool_materials");

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(NAMES[0], PatternTableGameTests::scaleAmounts);
        register(NAMES[1], PatternTableGameTests::stonecuttingTransfer);
        register(NAMES[2], PatternTableGameTests::smithingTransfer);
        register(NAMES[3], helper -> job(helper, stonecuttingPattern(), SLAB, 6, List.of(STONE), 3));
        register(NAMES[5], PatternTableGameTests::recipeChanged);
        register(NAMES[4], helper -> job(helper, smithingPattern(), NETHERITE_SWORD, 1,
                List.of(TEMPLATE, DIAMOND_SWORD, NETHERITE), 1));
    }

    private PatternTableGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("pattern_table"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    /** What the terminal encodes for the stone slab stonecutter recipe: one stone to two slabs. */
    static CraftingPattern stonecuttingPattern() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING, List.of(exact(STONE)),
                List.of(new PatternOutput(SLAB, 2, 1f)), Optional.of(STONECUTTING), Optional.of(SLAB_RECIPE));
    }

    /** What the terminal encodes for the netherite sword: template, diamond sword, a netherite ingot (by tag). */
    static CraftingPattern smithingPattern() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(exact(TEMPLATE), exact(DIAMOND_SWORD),
                        new PatternInput(new PatternIngredient.ItemTag(TagKey.create(Registries.ITEM, NETHERITE_TAG)),
                                1, Optional.empty(), false)),
                List.of(new PatternOutput(NETHERITE_SWORD, 1, 1f)), Optional.of(SMITHING), Optional.of(SWORD_RECIPE));
    }

    private static PatternInput exact(StackKey key) {
        return new PatternInput(new PatternIngredient.Exact(key), 1, Optional.empty(), false);
    }

    private static TerminalMenu menu(GameTestHelper helper, ServerPlayer player) {
        BlockPos pos = NetworkGameTests.at(2);
        helper.setBlock(pos, PatternTerminalContent.TERMINAL.get());
        return TerminalMenu.server(1, player.getInventory(),
                helper.getBlockEntity(pos, PatternTerminalBlockEntity.class), update -> {
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

    private static void set(TerminalMenu menu, ServerPlayer player, int slot, Item item, int count) {
        menu.setCarried(new ItemStack(item, count));
        click(menu, player, Action.SET, slot);
        menu.setCarried(ItemStack.EMPTY);
    }

    private static long input(TerminalMenu menu, int slot) {
        return panel(menu).draft().inputs().get(slot).orElseThrow().amount();
    }

    private static long output(TerminalMenu menu, int slot) {
        return panel(menu).draft().outputs().get(slot).orElseThrow().amount();
    }

    /** Encodes the menu's draft onto a fresh blank and returns the pattern. */
    private static CraftingPattern encode(GameTestHelper helper, TerminalMenu menu, ServerPlayer player) {
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        click(menu, player, Action.ENCODE, 0);
        helper.assertTrue(panel(menu).result() == PatternEncoder.Result.ENCODED, "encoded");
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            CraftingPattern pattern = PatternItem.pattern(player.getInventory().getItem(i));
            if (pattern != null) {
                return pattern;
            }
        }
        throw helper.assertionException(Component.literal("no encoded pattern"));
    }

    /**
     * Processing amounts: x2 doubles every input and output (items and mB), /2 halves them back, x8 and /8 are the
     * shift buttons; /2 is refused with nothing changed while an amount is odd, x2 while an amount would pass the
     * largest, and both on a crafting draft.
     */
    private static void scaleAmounts(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "pattern_scale");
        TerminalMenu menu = menu(helper, player);
        click(menu, player, Action.MODE_PROCESSING, 0);
        set(menu, player, 0, Items.IRON_ORE, 4);
        menu.setCarried(new ItemStack(Items.WATER_BUCKET));
        click(menu, player, Action.SET_FLUID, 1);
        set(menu, player, PatternDraft.INPUTS, Items.IRON_INGOT, 2);
        set(menu, player, PatternDraft.INPUTS + 1, Items.COBBLESTONE, 6);

        click(menu, player, Action.MULTIPLY, 0);
        helper.assertValueEqual(input(menu, 0), 8L, "ore doubled");
        helper.assertValueEqual(input(menu, 1), 2000L, "water doubled");
        helper.assertValueEqual(output(menu, 0), 4L, "ingots doubled");
        helper.assertValueEqual(output(menu, 1), 12L, "byproduct doubled");
        click(menu, player, Action.DIVIDE, 0);
        click(menu, player, Action.DIVIDE, 0);
        helper.assertValueEqual(input(menu, 0), 2L, "ore halved twice");
        helper.assertValueEqual(input(menu, 1), 500L, "water halved twice");
        helper.assertValueEqual(output(menu, 0), 1L, "ingots halved twice");
        helper.assertValueEqual(output(menu, 1), 3L, "byproduct halved twice");

        PatternDraft odd = panel(menu).draft();
        helper.assertTrue(odd.scaleBlock(2, false) == PatternDraft.ScaleBlock.NOT_WHOLE, "an odd amount");
        click(menu, player, Action.DIVIDE, 0);
        helper.assertValueEqual(panel(menu).draft(), odd, "a refused /2 changes nothing");

        click(menu, player, Action.MULTIPLY, 1);
        helper.assertValueEqual(input(menu, 0), 16L, "ore x8");
        helper.assertValueEqual(output(menu, 1), 24L, "byproduct x8");
        click(menu, player, Action.DIVIDE, 1);
        helper.assertValueEqual(input(menu, 0), 2L, "ore /8");
        helper.assertValueEqual(input(menu, 1), 500L, "water /8");

        PatternDraft large = panel(menu).draft().withOutput(0,
                new PatternDraft.Output(ItemKey.of(Items.IRON_INGOT), PatternCodecs.MAX_AMOUNT, 1f));
        helper.assertTrue(large.scaleBlock(2, true) == PatternDraft.ScaleBlock.TOO_LARGE, "past the largest");
        helper.assertValueEqual(large.scaled(2, true), large, "a refused x2 changes nothing");
        helper.assertTrue(PatternDraft.EMPTY.withMode(PatternDraft.Mode.PROCESSING).scaleBlock(2,
                true) == PatternDraft.ScaleBlock.EMPTY, "nothing to scale");

        click(menu, player, Action.MODE_CRAFTING, 0);
        PatternDraft crafting = panel(menu).draft();
        helper.assertTrue(crafting.scaleBlock(2, true) == PatternDraft.ScaleBlock.NOT_PROCESSING,
                "crafting has no amounts");
        click(menu, player, Action.MULTIPLY, 0);
        helper.assertValueEqual(panel(menu).draft(), crafting, "x2 on crafting changes nothing");
        helper.assertTrue(
                !menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.PATTERN, 31 * 16).buttonId()),
                "an unknown action");
        helper.succeed();
    }

    /**
     * A recipe viewer's stonecutter recipe becomes a stonecutting draft whose output and count come from the recipe;
     * encoding writes a crafting-kind pattern naming the stonecutting type, the same record the job test runs. Clicking
     * the output steps through the stone's other stonecutter recipes and back. A processing transfer of another type
     * stays processing.
     */
    private static void stonecuttingTransfer(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "pattern_stonecut");
        TerminalMenu menu = menu(helper, player);
        panel(menu).receiveProcessing(player,
                new PatternTransfer(menu.containerId, Optional.of(STONECUTTING), Optional.of(SLAB_RECIPE),
                        List.of(new PatternTransfer.Entry(STONE, 1, Optional.empty(), 1f)),
                        List.of(new PatternTransfer.Entry(SLAB, 2, Optional.empty(), 1f))));
        helper.assertValueEqual(panel(menu).draft().mode(), PatternDraft.Mode.STONECUTTING, "a stonecutting draft");
        helper.assertValueEqual(panel(menu).draft().outputs().getFirst().orElseThrow().key(), SLAB, "slabs");
        helper.assertValueEqual(output(menu, 0), 2L, "two per stone");
        helper.assertValueEqual(encode(helper, menu, player), stonecuttingPattern(), "the encoded pattern");

        click(menu, player, Action.MORE, PatternDraft.INPUTS);
        StackKey other = panel(menu).draft().outputs().getFirst().orElseThrow().key();
        helper.assertTrue(!other.equals(SLAB), "another stonecutter recipe for stone");
        helper.assertTrue(!panel(menu).draft().recipe().equals(Optional.of(SLAB_RECIPE)), "its recipe named");
        click(menu, player, Action.LESS, PatternDraft.INPUTS);
        helper.assertValueEqual(panel(menu).draft().outputs().getFirst().orElseThrow().key(), SLAB, "back to slabs");

        menu.setCarried(PatternItem.encode(stonecuttingPattern()));
        click(menu, player, Action.MODE_PROCESSING, 0);
        click(menu, player, Action.LOAD, 0);
        menu.setCarried(ItemStack.EMPTY);
        helper.assertValueEqual(panel(menu).draft().mode(), PatternDraft.Mode.STONECUTTING, "loads as stonecutting");

        helper.startSequence().thenIdle(1).thenExecute(() -> {
            panel(menu).receiveProcessing(player,
                    new PatternTransfer(menu.containerId, Optional.of(Identifier.withDefaultNamespace("smelting")),
                            Optional.empty(), List.of(new PatternTransfer.Entry(STONE, 1, Optional.empty(), 1f)),
                            List.of(new PatternTransfer.Entry(FluidKey.of(Fluids.LAVA), 100, Optional.empty(), 1f))));
            helper.assertValueEqual(panel(menu).draft().mode(), PatternDraft.Mode.PROCESSING,
                    "smelting stays processing");
        }).thenSucceed();
    }

    /**
     * A recipe viewer's smithing recipe, its inputs in any order, lands as template, base and addition by the recipe's
     * ingredients and encodes to the smithing pattern; the addition keeps the tag it came from. By hand, the smithing
     * tab fills the output as soon as the three inputs match.
     */
    private static void smithingTransfer(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "pattern_smith");
        TerminalMenu menu = menu(helper, player);
        panel(menu).receiveProcessing(player,
                new PatternTransfer(menu.containerId, Optional.of(SMITHING), Optional.of(SWORD_RECIPE),
                        List.of(new PatternTransfer.Entry(DIAMOND_SWORD, 1, Optional.empty(), 1f),
                                new PatternTransfer.Entry(NETHERITE, 1, Optional.of(NETHERITE_TAG), 1f),
                                new PatternTransfer.Entry(TEMPLATE, 1, Optional.empty(), 1f)),
                        List.of(new PatternTransfer.Entry(NETHERITE_SWORD, 1, Optional.empty(), 1f))));
        PatternDraft draft = panel(menu).draft();
        helper.assertValueEqual(draft.mode(), PatternDraft.Mode.SMITHING, "a smithing draft");
        helper.assertValueEqual(draft.inputs().get(0).orElseThrow().key(), TEMPLATE, "template first");
        helper.assertValueEqual(draft.inputs().get(1).orElseThrow().key(), DIAMOND_SWORD, "base second");
        helper.assertValueEqual(draft.inputs().get(2).orElseThrow().key(), NETHERITE, "addition third");
        helper.assertValueEqual(encode(helper, menu, player), smithingPattern(), "the encoded pattern");

        click(menu, player, Action.CLEAR, 0);
        helper.assertValueEqual(panel(menu).draft().mode(), PatternDraft.Mode.SMITHING, "clear keeps the mode");
        click(menu, player, Action.MODE_CRAFTING, 0);
        click(menu, player, Action.MODE_SMITHING, 0);
        set(menu, player, 0, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1);
        set(menu, player, 1, Items.DIAMOND_SWORD, 1);
        helper.assertTrue(panel(menu).draft().outputs().getFirst().isEmpty(), "no output before the addition");
        set(menu, player, 2, Items.NETHERITE_INGOT, 1);
        helper.assertValueEqual(panel(menu).draft().outputs().getFirst().orElseThrow().key(), NETHERITE_SWORD,
                "the output from the recipe");
        CraftingPattern byHand = encode(helper, menu, player);
        helper.assertValueEqual(byHand.recipe(), Optional.of(SWORD_RECIPE), "the recipe named");
        helper.assertValueEqual(byHand.recipeType(), Optional.of(SMITHING), "the smithing type");

        menu.setCarried(PatternItem.encode(smithingPattern()));
        click(menu, player, Action.LOAD, 0);
        menu.setCarried(ItemStack.EMPTY);
        helper.assertValueEqual(panel(menu).draft().inputs().get(2).orElseThrow().key(), NETHERITE, "loads in place");
        helper.succeed();
    }

    /**
     * A job for {@code count} of {@code output} with {@code pattern} on the provider: the assembler crafts it from
     * exactly the {@code crafts} sets of {@code inputs} in storage, and the job ends with the outputs stored and the
     * inputs used up.
     */
    private static void job(GameTestHelper helper, CraftingPattern pattern, StackKey output, long count,
            List<StackKey> inputs, long crafts) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(4), AssemblerContent.ASSEMBLER.get());
        helper.setBlock(NetworkGameTests.at(3), ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(NetworkGameTests.at(3),
                PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(pattern));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, output)).thenExecute(() -> {
            NetworkState network = network(helper, provider, output);
            for (StackKey input : inputs) {
                NetworkGameTests.insert(network, input, crafts);
            }
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, output, count);
            helper.assertTrue(plan.tree().complete(), "the plan is complete");
            JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                    JobQueue.Request.player(UUID.randomUUID()));
            id[0] = submission.job()
                    .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())))
                    .id();
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, provider, output);
            CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id[0]);
            helper.assertTrue(job == null, "the job finished");
            helper.assertValueEqual(network.contents().amount(output), count, "the outputs stored");
            for (StackKey input : inputs) {
                helper.assertValueEqual(network.contents().amount(input), 0L, input + " used up");
            }
        }).thenSucceed();
    }

    /**
     * A table pattern whose recipe no longer gives its output (as after a datapack change) is refused whole: a smithing
     * pattern promising another item than every matching recipe makes, and a stonecutting pattern promising another
     * count. Nothing is crafted or taken, and the provider names the reason; the real pattern still runs.
     */
    private static void recipeChanged(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(4), AssemblerContent.ASSEMBLER.get());
        helper.setBlock(NetworkGameTests.at(3), ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(NetworkGameTests.at(3),
                PatternProviderBlockEntity.class);
        CraftingPattern smithing = smithingPattern();
        CraftingPattern wrongItem = new CraftingPattern(CraftingPattern.Kind.CRAFTING, smithing.inputs(),
                List.of(new PatternOutput(ItemKey.of(Items.NETHERITE_AXE), 1, 1f)), smithing.recipeType(),
                smithing.recipe());
        CraftingPattern wrongCount = new CraftingPattern(CraftingPattern.Kind.CRAFTING, stonecuttingPattern().inputs(),
                List.of(new PatternOutput(SLAB, 3, 1f)), Optional.of(STONECUTTING), Optional.of(SLAB_RECIPE));
        provider.logic().slots().setItem(0, PatternItem.encode(wrongItem));
        provider.logic().slots().setItem(1, PatternItem.encode(wrongCount));
        provider.logic().slots().setItem(2, PatternItem.encode(stonecuttingPattern()));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, SLAB)).thenExecute(() -> {
            NetworkState network = network(helper, provider, SLAB);
            CraftingAssemblerBlockEntity assembler = helper.getBlockEntity(NetworkGameTests.at(4),
                    CraftingAssemblerBlockEntity.class);
            Object2LongOpenHashMap<StackKey> swordInputs = new Object2LongOpenHashMap<>();
            swordInputs.put(TEMPLATE, 1);
            swordInputs.put(DIAMOND_SWORD, 1);
            swordInputs.put(NETHERITE, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, wrongItem, 1, swordInputs), 0,
                    "a smithing pattern whose recipe makes another item");
            helper.assertTrue(provider.logic().stuck() == StuckReason.RECIPE_CHANGED, "the reason is named");
            helper.assertTrue(assembler.contents().isEmpty(), "no sword crafted");
            Object2LongOpenHashMap<StackKey> stone = new Object2LongOpenHashMap<>();
            stone.put(STONE, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, wrongCount, 1, stone), 0,
                    "a stonecutting pattern whose recipe makes another count");
            helper.assertTrue(provider.logic().stuck() == StuckReason.RECIPE_CHANGED, "the reason again");
            helper.assertTrue(assembler.contents().isEmpty(), "no slabs crafted");
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, stonecuttingPattern(), 1, stone), 1,
                    "the real pattern runs");
            helper.assertTrue(provider.logic().stuck() == null, "no longer stuck");
        }).thenSucceed();
    }

    private static NetworkState network(GameTestHelper helper, PatternProviderBlockEntity provider, StackKey output) {
        NetworkState network = provider.network();
        if (network == null || !network.patterns().isCraftable(output)) {
            throw helper.assertionException(Component.literal("the pattern is not on the network"));
        }
        return network;
    }
}
