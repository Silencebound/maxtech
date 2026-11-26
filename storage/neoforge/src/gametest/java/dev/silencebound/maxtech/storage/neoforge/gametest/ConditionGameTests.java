package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchPart;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.condition.ConditionWorker;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The condition switch (M6c area C): each condition drives a lamp next to the block form, the part lights only the
 * block its face points at, evaluations are throttled and run only on events, research gates the output, and the
 * settings travel in the item and through the GUI's buttons.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ConditionGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("condition_item_count", ConditionGameTests::itemCount);
        register("condition_tag_count", ConditionGameTests::tagCount);
        register("condition_power", ConditionGameTests::power);
        register("condition_overload", ConditionGameTests::overload);
        register("condition_research", ConditionGameTests::research);
        register("condition_needs_research", ConditionGameTests::needsResearch);
        register("condition_hysteresis_and_no_polling", ConditionGameTests::hysteresis);
        register("condition_part_signal", ConditionGameTests::partSignal);
        register("condition_settings_in_item_and_gui", ConditionGameTests::settingsAndGui);
    }

    private ConditionGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("condition"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    /** A powered controller at x 1, a test device at x 2 and the switch at x 3 with a lamp on top. */
    private static ConditionSwitchBlockEntity setUp(GameTestHelper helper, ConditionSettings settings) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), ConditionContent.SWITCH.get());
        helper.setBlock(NetworkGameTests.at(3).above(), Blocks.REDSTONE_LAMP);
        NetworkGameTests.power(helper, controller, 1_000_000);
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(NetworkGameTests.at(3),
                ConditionSwitchBlockEntity.class);
        entity.worker().setSettings(settings);
        return entity;
    }

    private static void assertLamp(GameTestHelper helper, BlockPos pos, boolean lit, String message) {
        helper.assertValueEqual(helper.getBlockState(pos).getValue(RedstoneLampBlock.LIT), lit, message);
    }

    private static NetworkState network(GameTestHelper helper, ConditionSwitchBlockEntity entity) {
        NetworkState network = entity.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the switch is in no network"));
        }
        return network;
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static ConditionSettings count(StackKey key, boolean above, long threshold) {
        return ConditionSettings.DEFAULT.withItem(Optional.of((ItemKey) key)).withAbove(above).withThreshold(threshold);
    }

    /** Cobblestone above 10 lights the lamp at 11 and puts it out again below; other items change nothing. */
    private static void itemCount(GameTestHelper helper) {
        ConditionSwitchBlockEntity entity = setUp(helper, count(COBBLE, true, 10));
        BlockPos lamp = NetworkGameTests.at(3).above();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> NetworkGameTests.insert(network(helper, entity), COBBLE, 10)).thenIdle(15)
                .thenExecute(() -> {
                    helper.assertFalse(entity.worker().output(), "on at the threshold");
                    NetworkGameTests.insert(network(helper, entity), ItemKey.of(Items.DIRT), 100);
                }).thenIdle(15).thenExecute(() -> {
                    helper.assertFalse(entity.worker().output(), "on for dirt");
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 1);
                }).thenWaitUntil(() -> assertLamp(helper, lamp, true, "lamp after 11 cobblestone"))
                .thenExecute(() -> extract(network(helper, entity), COBBLE, 5))
                .thenWaitUntil(() -> assertLamp(helper, lamp, false, "lamp after taking some out")).thenSucceed();
    }

    /** Logs above 3 of any kind: two oak and two birch together light the lamp, stone does not count. */
    private static void tagCount(GameTestHelper helper) {
        ConditionSwitchBlockEntity entity = setUp(helper, ConditionSettings.DEFAULT.withType(ConditionType.TAG_COUNT)
                .withTag(Optional.of(ItemTags.LOGS)).withThreshold(3));
        BlockPos lamp = NetworkGameTests.at(3).above();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), ItemKey.of(Items.OAK_LOG), 2);
            NetworkGameTests.insert(network(helper, entity), ItemKey.of(Items.STONE), 50);
        }).thenIdle(15).thenExecute(() -> {
            helper.assertFalse(entity.worker().output(), "on with two logs");
            NetworkGameTests.insert(network(helper, entity), ItemKey.of(Items.BIRCH_LOG), 2);
        }).thenWaitUntil(() -> assertLamp(helper, lamp, true, "lamp after four logs"))
                .thenExecute(() -> extract(network(helper, entity), ItemKey.of(Items.OAK_LOG), 2))
                .thenWaitUntil(() -> assertLamp(helper, lamp, false, "lamp after two logs left")).thenSucceed();
    }

    /** Power below 50 %, inverted: on while the controller is full, off once it is drained to a fifth. */
    private static void power(GameTestHelper helper) {
        setUp(helper, ConditionSettings.DEFAULT.withType(ConditionType.POWER).withAbove(false).withThreshold(50)
                .withInverted(true));
        BlockPos controller = NetworkGameTests.at(1);
        BlockPos lamp = NetworkGameTests.at(3).above();
        helper.startSequence().thenWaitUntil(() -> assertLamp(helper, lamp, true, "inverted: lamp while full"))
                .thenExecute(() -> {
                    var buffer = NetworkGameTests.controllerEntity(helper, controller).buffer();
                    buffer.tryConsume(buffer.stored() * 4 / 5);
                }).thenWaitUntil(() -> assertLamp(helper, lamp, false, "lamp at a fifth")).thenSucceed();
    }

    /**
     * Link load above 50 %: the switch sits between the controller and its devices, so their channels (M7N) load the
     * switch's link to the controller; once two of them are gone the load falls under the threshold and the switch
     * notices on its own.
     */
    private static void overload(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        helper.setBlock(NetworkGameTests.at(2), ConditionContent.SWITCH.get());
        helper.setBlock(NetworkGameTests.at(2).above(), Blocks.REDSTONE_LAMP);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        NetworkGameTests.power(helper, controller, 1_000_000);
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(NetworkGameTests.at(2),
                ConditionSwitchBlockEntity.class);
        entity.worker().setSettings(ConditionSettings.DEFAULT.withType(ConditionType.OVERLOAD).withThreshold(50));
        BlockPos lamp = NetworkGameTests.at(2).above();
        List<BlockPos> more = List.of(new BlockPos(3, 1, 0), new BlockPos(3, 1, 2), new BlockPos(3, 2, 1),
                new BlockPos(3, 0, 1));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(device.deviceLink().transfer() != null, "no net"))
                .thenIdle(5).thenExecute(() -> {
                    // The switch's tier-1 link carries 8 channels: five devices use 5 (62 %); the switch uses none.
                    for (BlockPos pos : more) {
                        NetworkGameTests.device(helper, pos);
                    }
                }).thenWaitUntil(() -> assertLamp(helper, lamp, true, "lamp while loaded")).thenExecute(() -> {
                    helper.destroyBlock(more.get(0));
                    helper.destroyBlock(more.get(1));
                }).thenWaitUntil(() -> assertLamp(helper, lamp, false, "lamp after the load fell")).thenSucceed();
    }

    /** Research done: lit once the owner's team completes Family Masks. */
    private static void research(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "condition_research");
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
        ConditionSwitchBlockEntity entity = setUp(helper, ConditionSettings.DEFAULT.withType(ConditionType.RESEARCH)
                .withNode(Optional.of(MaxtechStorage.id("family_masks"))));
        entity.setOwner(OwnerRecord.of(player));
        BlockPos lamp = NetworkGameTests.at(3).above();
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            helper.assertFalse(entity.worker().output(), "on before the node is done");
            Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("family_masks"));
        }).thenWaitUntil(() -> assertLamp(helper, lamp, true, "lamp after the node")).thenSucceed();
    }

    /** A switch whose owner's team lacks the Condition Switch node gives no signal until the node is done. */
    private static void needsResearch(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "condition_locked");
        ResearchTeam team = Research.team(player, true);
        ConditionSwitchBlockEntity entity = setUp(helper, count(COBBLE, false, 10));
        entity.setOwner(OwnerRecord.of(player));
        BlockPos lamp = NetworkGameTests.at(3).above();
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            assertLamp(helper, lamp, false, "lamp without research");
            Research.addPoints(helper.getLevel().getServer(), team, 100);
            Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
        }).thenWaitUntil(() -> assertLamp(helper, lamp, true, "lamp after research")).thenSucceed();
    }

    /**
     * Flipping the count across the threshold every third tick (so the 10 tick evaluations see both sides) changes the
     * output at most once per minimum interval, and a settled switch runs no evaluation at all while nothing happens.
     */
    private static void hysteresis(GameTestHelper helper) {
        ConditionSwitchBlockEntity entity = setUp(helper, count(COBBLE, true, 0));
        ConditionWorker worker = entity.worker();
        int[] flips = new int[1];
        boolean[] last = new boolean[1];
        int[] evaluations = new int[1];
        int[] tick = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenIdle(20).thenExecuteFor(40, () -> {
            NetworkState network = network(helper, entity);
            if (tick[0]++ % 3 == 0) {
                if (network.contents().amount(COBBLE) > 0) {
                    extract(network, COBBLE, 1);
                } else {
                    NetworkGameTests.insert(network, COBBLE, 1);
                }
            }
            if (worker.output() != last[0]) {
                last[0] = worker.output();
                flips[0]++;
            }
        }).thenExecute(() -> {
            helper.assertTrue(flips[0] > 0, "the output never changed");
            helper.assertTrue(flips[0] <= 5, flips[0] + " output changes in 40 ticks with a 10 tick interval");
        }).thenIdle(20).thenExecute(() -> evaluations[0] = worker.evaluations()).thenIdle(100).thenExecute(() -> helper
                .assertValueEqual(worker.evaluations(), evaluations[0], "evaluations while nothing happened"))
                .thenSucceed();
    }

    /** A part on a cable's east face lights the lamp east of the cable, not the one west of it; no ticker anywhere. */
    private static void partSignal(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos cable = NetworkGameTests.at(3);
        helper.setBlock(cable.east(), Blocks.REDSTONE_LAMP);
        helper.setBlock(cable.above(), Blocks.REDSTONE_LAMP);
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), helper.absolutePos(cable));
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        ItemStack stack = new ItemStack(ConditionContent.SWITCH_PART_ITEM.get());
        stack.set(ModDataComponents.CONDITION.get(), count(COBBLE, false, 5));
        helper.assertTrue(parts.place(Direction.EAST, ConditionContent.SWITCH_PART, null, stack), "not placed");
        helper.assertTrue(helper.getBlockState(NetworkGameTests.at(2)).getTicker(helper.getLevel(),
                BlockEntityType.FURNACE) == null, "a ticker");
        helper.startSequence().thenWaitUntil(() -> assertLamp(helper, cable.east(), true, "faced lamp"))
                .thenExecute(() -> {
                    assertLamp(helper, cable.above(), false, "lamp beside the part");
                    if (parts.part(Direction.EAST) instanceof ConditionSwitchPart part) {
                        helper.assertValueEqual(part.lookVariant(), 1, "look");
                        helper.assertValueEqual(part.worker().settings().threshold(), 5L, "settings from the item");
                    } else {
                        throw helper.assertionException(Component.literal("no switch part"));
                    }
                }).thenSucceed();
    }

    /**
     * The dropped block carries its settings and names them; a fresh switch carries none. GUI buttons cycle the type,
     * step the threshold (clamped to 100 % for percentages), toggle side and inversion, and the ghost slot sets the
     * watched item and its first tag; a stranger's clicks change nothing.
     */
    private static void settingsAndGui(GameTestHelper helper) {
        ConditionSwitchBlockEntity entity = setUp(helper, count(COBBLE, true, 64).withInverted(true));
        BlockPos pos = NetworkGameTests.at(3);
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(pos), helper.getLevel(), helper.absolutePos(pos),
                entity);
        helper.assertValueEqual(drops.size(), 1, "drops");
        ConditionSettings carried = drops.get(0).get(ModDataComponents.CONDITION.get());
        helper.assertTrue(entity.worker().settings().equals(carried), "settings in the item: " + carried);
        helper.assertTrue(
                new ItemStack(ConditionContent.SWITCH.item().get()).get(ModDataComponents.CONDITION.get()) == null,
                "a fresh switch carries settings");

        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "condition_owner");
        entity.setOwner(OwnerRecord.of(owner));
        entity.worker().setSettings(ConditionSettings.DEFAULT);
        ConditionMenu menu = ConditionMenu.forDevice(1, owner.getInventory(), entity);
        menu.clickMenuButton(owner, ConditionMenu.FIRST_STEP + 5);
        menu.clickMenuButton(owner, ConditionMenu.FIRST_STEP + 7);
        helper.assertValueEqual(entity.worker().settings().threshold(), 1000L, "+100 then x10");
        menu.clickMenuButton(owner, ConditionMenu.CYCLE_TYPE);
        menu.clickMenuButton(owner, ConditionMenu.CYCLE_TYPE);
        helper.assertValueEqual(entity.worker().settings().type(), ConditionType.POWER, "type cycled twice");
        helper.assertValueEqual(entity.worker().settings().threshold(), 100L, "clamped to 100 %");
        menu.clickMenuButton(owner, ConditionMenu.TOGGLE_SIDE);
        menu.clickMenuButton(owner, ConditionMenu.TOGGLE_INVERTED);
        helper.assertFalse(entity.worker().settings().above(), "side");
        helper.assertTrue(entity.worker().settings().inverted(), "inverted");
        menu.setCarried(new ItemStack(Items.OAK_LOG));
        menu.clicked(0, 0, ContainerInput.PICKUP, owner);
        menu.setCarried(ItemStack.EMPTY);
        helper.assertTrue(entity.worker().settings().item().isPresent(), "no item from the ghost slot");
        helper.assertTrue(
                entity.worker().settings().tag().equals(Optional.of(ConditionMenu.tags(Items.OAK_LOG).get(0))),
                "first tag");
        menu.clickMenuButton(owner, ConditionMenu.NEXT_TAG);
        helper.assertTrue(
                entity.worker().settings().tag().equals(Optional.of(ConditionMenu.tags(Items.OAK_LOG).get(1))),
                "next tag");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "condition_stranger");
        ConditionSettings before = entity.worker().settings();
        helper.assertFalse(ConditionMenu.forDevice(2, stranger.getInventory(), entity).clickMenuButton(stranger,
                ConditionMenu.CYCLE_TYPE), "a stranger's click");
        helper.assertTrue(entity.worker().settings().equals(before), "changed by a stranger");
        helper.succeed();
    }
}
