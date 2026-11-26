package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.display.DisplayListSave;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.display.material.MaterialFeed;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The build material list display (M7C item 17): rows count each listed entry from the network's cached counts and
 * follow only those keys, and "craft missing" plans just the missing craftable amounts as one job for a player who may
 * craft.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class MaterialListGameTests {
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final CraftingPattern STICKS = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
            List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
            List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    private static final String LIST = "mtlist1:minecraft:stick*8,minecraft:cobblestone*4,minecraft:oak_planks*10";
    private static final String[] NAMES = {"material_list_counts", "material_list_craft_missing", "material_list_save",
            "material_list_craft_twice", "material_list_take_all", "material_list_take_full",
            "material_list_take_cooldown"};
    /** The "take all" row: after the three entries and craft missing. */
    private static final int TAKE_ROW = 4;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        StorageTests.function(NAMES[0], StorageTestEnvironment.clearing(MaterialListGameTests::counts));
        StorageTests.function(NAMES[1], StorageTestEnvironment.clearing(MaterialListGameTests::craftMissing));
        StorageTests.function(NAMES[2], StorageTestEnvironment.clearing(MaterialListGameTests::save));
        StorageTests.function(NAMES[3], StorageTestEnvironment.clearing(MaterialListGameTests::craftTwice));
        StorageTests.function(NAMES[4], StorageTestEnvironment.clearing(MaterialListGameTests::takeAll));
        StorageTests.function(NAMES[5], StorageTestEnvironment.clearing(MaterialListGameTests::takeFull));
        StorageTests.function(NAMES[6], StorageTestEnvironment.clearing(MaterialListGameTests::takeCooldown));
    }

    private MaterialListGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("material_list"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    /** A controller owned by {@code owner} with a device and a material list screen showing {@link #LIST}. */
    private static DisplayPanelBlockEntity setUp(GameTestHelper helper, ServerPlayer owner) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        BlockState state = helper.getBlockState(core);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(core), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(DisplaySettings.DEFAULT.withSource(DisplaySourceKind.MATERIAL_LIST).withList(LIST));
        panel.setOwner(OwnerRecord.of(owner));
        return panel;
    }

    private static NetworkState network(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        NetworkState network = panel.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the panel is in no network"));
        }
        return network;
    }

    private static MaterialFeed feed(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        if (panel.worker().feed() instanceof MaterialFeed feed) {
            return feed;
        }
        throw helper.assertionException(Component.literal("the panel has no material feed"));
    }

    private static ScreenRows rows(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        return feed(helper, panel).rows(network(helper, panel), helper.getLevel().getGameTime());
    }

    /**
     * Each entry shows its stored count out of its need in the list's order, followed by the two action rows; a stored
     * amount changes the row after a move; only the three listed keys are listened to, and none after the source
     * changes.
     */
    private static void counts(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "material_owner");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        helper.startSequence().thenWaitUntil(() -> network(helper, panel)).thenIdle(5).thenExecute(() -> {
            NetworkState network = network(helper, panel);
            helper.assertValueEqual(feed(helper, panel).entries().size(), 3, "three entries");
            helper.assertValueEqual(network.listenedKeyCount(), 3, "only the listed keys are listened to");
            NetworkGameTests.insert(network, STICK, 3);
            NetworkGameTests.insert(network, PLANKS, 10);
            ScreenRows rows = rows(helper, panel);
            helper.assertValueEqual(rows.rows().size(), 6, "three entries and three actions");
            helper.assertTrue(rows.rows().get(0).tone() == ScreenRows.WARN, "sticks are short");
            helper.assertTrue(rows.rows().get(0).icon().isPresent(), "an item entry has an icon");
            helper.assertValueEqual(rows.rows().get(0).fill(), 375, "3 of 8 sticks");
            helper.assertTrue(rows.rows().get(2).tone() == ScreenRows.GOOD, "planks are complete");
            helper.assertTrue(rows.rows().get(3).has(ScreenRows.BAND), "craft missing is on offer");
            helper.assertValueEqual(rows.status().getString(),
                    Component.translatable("gui.maxtech_storage.material_list.status", 1, 3).getString(),
                    "one of three complete");
            NetworkGameTests.insert(network, STICK, 5);
            NetworkGameTests.insert(network, COBBLE, 4);
            ScreenRows done = rows(helper, panel);
            helper.assertTrue(done.rows().get(0).tone() == ScreenRows.GOOD, "sticks are complete now");
            helper.assertTrue(done.rows().get(3).tone() == ScreenRows.DIM, "nothing left to craft");
            panel.applyToWall(DisplaySettings.DEFAULT);
        }).thenIdle(5).thenExecute(() -> helper.assertValueEqual(network(helper, panel).listenedKeyCount(), 0,
                "the listeners went with the source")).thenSucceed();
    }

    /**
     * Craft missing plans the missing sticks (not the stock the list is already satisfied with, not the cobblestone
     * nothing can make) as one job of the clicking player; a stranger without craft access starts nothing.
     */
    private static void craftMissing(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "material_crafter");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "material_stranger");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        helper.setBlock(NetworkGameTests.at(4), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(4), CraftingTestContent.Host.class).setPatterns(List.of(STICKS));
        helper.setBlock(NetworkGameTests.at(5), CpuContent.CORE_1.get());
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(network(helper, panel).owner().isOwner(owner.getUUID()), "the network is not owned yet"))
                .thenIdle(30).thenExecute(() -> {
                    NetworkState network = network(helper, panel);
                    NetworkGameTests.insert(network, PLANKS, 40);
                    NetworkGameTests.insert(network, STICK, 4);
                    List<?> wanted = MaterialFeed.craftable(network, feed(helper, panel).entries());
                    helper.assertValueEqual(wanted.size(), 1, "only the sticks can be crafted");
                    helper.assertTrue(feed(helper, panel).click(stranger, network, 3), "the click is taken");
                    // The job store is level-wide and other tests run in it at once, so look at this test's players
                    // only.
                    helper.assertTrue(jobsOf(helper, stranger).isEmpty() && jobsOf(helper, owner).isEmpty(),
                            "a stranger starts nothing");
                    helper.assertTrue(feed(helper, panel).click(owner, network, 3), "the click is taken");
                }).thenWaitUntil(() -> {
                    List<CraftingJob> found = jobsOf(helper, owner);
                    helper.assertValueEqual(found.size(), 1, "one job queued");
                    helper.assertValueEqual(found.getFirst().key(), STICK, "for sticks");
                    helper.assertValueEqual(found.getFirst().amount(), 4L, "for the 4 missing, not the 8 listed");
                }).thenSucceed();
    }

    /**
     * A second click after the first plan was queued starts no second job; once that job is cancelled the display
     * crafts again.
     */
    private static void craftTwice(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "material_twice");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        helper.setBlock(NetworkGameTests.at(4), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(4), CraftingTestContent.Host.class).setPatterns(List.of(STICKS));
        helper.setBlock(NetworkGameTests.at(5), CpuContent.CORE_1.get());
        UUID[] first = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(network(helper, panel).owner().isOwner(owner.getUUID()), "the network is not owned yet"))
                .thenIdle(30).thenExecute(() -> {
                    NetworkState network = network(helper, panel);
                    NetworkGameTests.insert(network, PLANKS, 40);
                    NetworkGameTests.insert(network, STICK, 4);
                    helper.assertTrue(feed(helper, panel).click(owner, network, 3), "the click is taken");
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(jobsOf(helper, owner).size(), 1, "one job queued");
                    first[0] = jobsOf(helper, owner).getFirst().id();
                }).thenExecute(() -> {
                    feed(helper, panel).click(owner, network(helper, panel), 3);
                }).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(jobsOf(helper, owner).size(), 1, "the second click queued nothing");
                    helper.assertTrue(JobQueue.cancel(helper.getLevel(), first[0]), "the job is cancelled");
                    feed(helper, panel).click(owner, network(helper, panel), 3);
                }).thenWaitUntil(() -> {
                    List<CraftingJob> found = jobsOf(helper, owner);
                    helper.assertValueEqual(found.size(), 1, "a new job after the first ended");
                    helper.assertTrue(!found.getFirst().id().equals(first[0]), "it is a new job");
                }).thenSucceed();
    }

    /**
     * Take all moves exactly the listed amounts (8 of the 20 sticks, the only 2 cobblestone, the 10 planks) into the
     * owner's inventory and nothing more; a second click right after takes nothing; a stranger takes nothing. Storage
     * and inventories always add up to what was stored.
     */
    private static void takeAll(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "material_taker");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "material_take_stranger");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(network(helper, panel).owner().isOwner(owner.getUUID()), "the network is not owned yet"))
                .thenIdle(5).thenExecute(() -> {
                    NetworkState network = network(helper, panel);
                    NetworkGameTests.insert(network, STICK, 20);
                    NetworkGameTests.insert(network, COBBLE, 2);
                    NetworkGameTests.insert(network, PLANKS, 10);
                    helper.assertTrue(feed(helper, panel).click(stranger, network, TAKE_ROW), "the click is taken");
                    helper.assertValueEqual(
                            carried(stranger, STICK) + carried(stranger, COBBLE) + carried(stranger, PLANKS), 0,
                            "the stranger's take");
                    helper.assertValueEqual(network.contents().amount(STICK), 20L, "sticks after the stranger");
                    helper.assertTrue(feed(helper, panel).click(owner, network, TAKE_ROW), "the click is taken");
                    helper.assertValueEqual(carried(owner, STICK), 8, "sticks taken");
                    helper.assertValueEqual(carried(owner, COBBLE), 2, "cobblestone taken");
                    helper.assertValueEqual(carried(owner, PLANKS), 10, "planks taken");
                    helper.assertValueEqual(network.contents().amount(STICK), 12L, "sticks left");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "cobblestone left");
                    helper.assertValueEqual(network.contents().amount(PLANKS), 0L, "planks left");
                    feed(helper, panel).click(owner, network, TAKE_ROW);
                    helper.assertValueEqual(carried(owner, STICK), 8, "sticks after a double-click");
                    helper.assertValueEqual(network.contents().amount(STICK), 12L, "sticks left after a double-click");
                }).thenSucceed();
    }

    /**
     * With room for only 4 sticks, take all moves those 4 and nothing of the rest; everything that did not fit stays in
     * storage.
     */
    private static void takeFull(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "material_full");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(network(helper, panel).owner().isOwner(owner.getUUID()), "the network is not owned yet"))
                .thenIdle(5).thenExecute(() -> {
                    NetworkState network = network(helper, panel);
                    NetworkGameTests.insert(network, STICK, 20);
                    NetworkGameTests.insert(network, COBBLE, 4);
                    NetworkGameTests.insert(network, PLANKS, 10);
                    for (int i = 0; i < owner.getInventory().getNonEquipmentItems().size(); i++) {
                        owner.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
                    }
                    owner.getInventory().setItem(5, new ItemStack(Items.STICK, 60));
                    owner.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.DIRT, 64));
                    helper.assertTrue(feed(helper, panel).click(owner, network, TAKE_ROW), "the click is taken");
                    helper.assertValueEqual(carried(owner, STICK), 64, "sticks up to the room");
                    helper.assertValueEqual(carried(owner, COBBLE), 0, "no room for cobblestone");
                    helper.assertValueEqual(network.contents().amount(STICK), 16L, "sticks left");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 4L, "cobblestone kept");
                    helper.assertValueEqual(network.contents().amount(PLANKS), 10L, "planks kept");
                    helper.assertValueEqual(network.contents().amount(STICK) + carried(owner, STICK), 80L,
                            "sticks stored plus carried are conserved");
                    helper.assertValueEqual(network.contents().amount(COBBLE) + carried(owner, COBBLE), 4L,
                            "cobblestone stored plus carried is conserved");
                    helper.assertValueEqual(network.contents().amount(PLANKS) + carried(owner, PLANKS), 10L,
                            "planks stored plus carried are conserved");
                }).thenSucceed();
    }

    /**
     * The take-all cooldown is per player: a second player right after the first is not blocked, the first is blocked
     * inside the window and takes again once it has passed.
     */
    private static void takeCooldown(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "material_cool_a");
        ServerPlayer other = NetworkGameTests.player(helper.getLevel(), "material_cool_b");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(network(helper, panel).owner().isOwner(owner.getUUID()), "the network is not owned yet"))
                .thenExecute(() -> {
                    var controller = NetworkGameTests.controllerEntity(helper, NetworkGameTests.at(1));
                    controller.setOwner(controller.ownerRecord().withTrusted(other.getUUID()));
                }).thenWaitUntil(() -> helper.assertTrue(network(helper, panel).canAccess(other), "trusted"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, panel);
                    NetworkGameTests.insert(network, STICK, 40);
                    feed(helper, panel).click(owner, network, TAKE_ROW);
                    helper.assertValueEqual(carried(owner, STICK), 8, "first take");
                    feed(helper, panel).click(other, network, TAKE_ROW);
                    helper.assertValueEqual(carried(other, STICK), 8, "a second player is not blocked");
                    feed(helper, panel).click(owner, network, TAKE_ROW);
                    helper.assertValueEqual(carried(owner, STICK), 8, "the first player is blocked in the window");
                }).thenIdle(MaterialFeed.TAKE_COOLDOWN_TICKS + 1).thenExecute(() -> {
                    NetworkState network = network(helper, panel);
                    feed(helper, panel).click(owner, network, TAKE_ROW);
                    helper.assertValueEqual(carried(owner, STICK), 16, "taking again after the window");
                }).thenSucceed();
    }

    /** {@code key} in the player's main inventory and offhand. */
    private static int carried(ServerPlayer player, StackKey key) {
        ItemStack offhand = player.getOffhandItem();
        int count = !offhand.isEmpty() && ItemKey.of(offhand).equals(key) ? offhand.getCount() : 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && ItemKey.of(stack).equals(key)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static List<CraftingJob> jobsOf(GameTestHelper helper, ServerPlayer player) {
        return CraftingJobs.get(helper.getLevel()).all().stream()
                .filter(job -> job.requester().equals(Optional.of(player.getUUID()))).toList();
    }

    /** A typed list is stored as the canonical code; text with nothing readable is refused and a blank one clears. */
    private static void save(GameTestHelper helper) {
        helper.assertValueEqual(DisplayListSave.canonical("stick*8; torch, stick*2"),
                "mtlist1:minecraft:stick*10,minecraft:torch*1", "merged and written as a code");
        helper.assertValueEqual(DisplayListSave.canonical("no such !!"), "", "nothing readable");
        helper.assertValueEqual(DisplayListSave.canonical("mtlist1:"), "", "an empty code");
        helper.succeed();
    }
}
