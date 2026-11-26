package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.recorder.AlarmWorker;
import dev.silencebound.maxtech.storage.controller.recorder.ControllerRecorders;
import dev.silencebound.maxtech.storage.controller.recorder.RecordsPayloads;
import dev.silencebound.maxtech.storage.controller.recorder.RecordsView;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchTerminalBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The controller's redstone alarm rules (M7R item 3; the alarm module of M6c2 area G before): a rule makes the
 * controller give redstone for each of its conditions, woken by the network's events and evaluated at most once per
 * interval (nothing while the value stays put); the rules go with the item of a controller's last block and are changed
 * only by players with configure access whose team has the research. Channel-free blocks touching the controller take
 * no cluster slot.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ControllerAlarmGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String LOW_POWER = "controller_alarm_low_power";
    private static final String STORAGE_FULL = "controller_alarm_storage_full";
    private static final String LINK = "controller_alarm_link_saturated";
    private static final String DEVICES = "controller_alarm_device_limit";
    private static final String SCREEN = "controller_alarm_rules_edit_and_item";
    private static final String BESIDE = "controller_channel_free_beside";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(LOW_POWER, ControllerAlarmGameTests::lowPower);
        register(STORAGE_FULL, ControllerAlarmGameTests::storageFull);
        register(LINK, ControllerAlarmGameTests::linkSaturated);
        register(DEVICES, ControllerAlarmGameTests::deviceLimit);
        register(SCREEN, ControllerAlarmGameTests::screenAndItem);
        register(BESIDE, ControllerAlarmGameTests::channelFreeBeside);
    }

    private ControllerAlarmGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("controller_alarm"),
                StorageTestEnvironment.plain());
        for (String name : List.of(LOW_POWER, STORAGE_FULL, LINK, SCREEN, BESIDE)) {
            StorageTests.test(event, plain, name, STRUCTURE, 600);
        }
        // The controller sets no device limit any more (M7R item 1): the alarm reads the team's device cap.
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(DEVICES),
                new StorageTestEnvironment("caps.devicesPerTeam", "32", false)), DEVICES, STRUCTURE, 600);
    }

    /**
     * Seven devices beside the tier-1 trunk north of {@code core}, which with the one at its end use all eight channels
     * of its root link. Returns where they are.
     */
    private static List<BlockPos> fillTrunk(GameTestHelper helper, BlockPos core) {
        List<BlockPos> placed = new ArrayList<>();
        for (int i = 2; i <= 3; i++) {
            placed.add(core.north(i).east());
            placed.add(core.north(i).west());
            placed.add(core.north(i).below());
        }
        placed.add(core.north(3).above());
        for (BlockPos pos : placed) {
            NetworkGameTests.device(helper, pos);
        }
        return placed;
    }

    /**
     * A controller with a redstone lamp on top and, once its network formed, one alarm rule of {@code settings};
     * returns the lamp's position.
     */
    private static BlockPos alarmOnCore(GameTestHelper helper, BlockPos core) {
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        BlockPos lamp = core.above();
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
        return lamp;
    }

    /** Adds a rule of {@code settings} to the controller at {@code core}; fails when it is refused. */
    static void addRule(GameTestHelper helper, BlockPos core, AlarmSettings settings) {
        helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).recorders().addRule(settings), "rule added");
    }

    /** Rule {@code index} of the controller at {@code core}. */
    static AlarmWorker alarm(GameTestHelper helper, BlockPos core, int index) {
        List<AlarmWorker> rules = NetworkGameTests.controllerEntity(helper, core).recorders().rules();
        if (index >= rules.size()) {
            throw helper.assertionException(Component.literal("no rule " + index + " at " + core.toShortString()));
        }
        return rules.get(index);
    }

    private static AlarmWorker alarm(GameTestHelper helper, BlockPos core) {
        return alarm(helper, core, 0);
    }

    private static boolean lamp(GameTestHelper helper, BlockPos lamp) {
        return helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
    }

    /**
     * Low power at 10 %: off at 50 % FE, on (and the lamp lit) at 5 %, off again at 50 %; with the FE steady nothing is
     * evaluated.
     */
    private static void lowPower(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos lamp = alarmOnCore(helper, core);
        int[] evaluations = {0};
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core)).thenExecute(() -> {
            addRule(helper, core, AlarmSettings.DEFAULT);
            NetworkGameTests.controllerEntity(helper, core).buffer().load(5_000);
        }).thenIdle(15).thenExecute(() -> {
            helper.assertTrue(!alarm(helper, core).output(), "off at 50 %");
            NetworkGameTests.controllerEntity(helper, core).buffer().load(500);
        }).thenWaitUntil(() -> {
            helper.assertTrue(alarm(helper, core).output(), "on at 5 %");
            helper.assertTrue(lamp(helper, lamp), "lamp lit");
        }).thenExecute(() -> evaluations[0] = alarm(helper, core).evaluations()).thenIdle(60).thenExecute(() -> {
            helper.assertValueEqual(alarm(helper, core).evaluations(), evaluations[0],
                    "evaluations while the FE stays put");
            NetworkGameTests.controllerEntity(helper, core).buffer().load(5_000);
        }).thenWaitUntil(() -> helper.assertTrue(!alarm(helper, core).output() && !lamp(helper, lamp), "off again"))
                .thenSucceed();
    }

    /** Storage nearly full at 1 %: on once 2,000 of 100,000 bytes are used, off once they are taken out. */
    private static void storageFull(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos lamp = alarmOnCore(helper, core);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        device.provider.countBytes();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(device.network() == NetworkGameTests.state(helper, core), "network formed");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            addRule(helper, core, new AlarmSettings(AlarmSettings.Type.STORAGE_FULL, 1));
        }).thenIdle(15).thenExecute(() -> {
            helper.assertTrue(!alarm(helper, core).output(), "off while empty");
            helper.assertValueEqual(NetworkGameTests.insert(NetworkGameTests.state(helper, core), COBBLE, 2_000),
                    2_000L, "insert");
        }).thenWaitUntil(() -> helper.assertTrue(alarm(helper, core).output() && lamp(helper, lamp), "on at 2 %"))
                .thenExecute(() -> {
                    try (Transaction transaction = Transaction.openRoot()) {
                        NetworkGameTests.state(helper, core).extract(COBBLE, 2_000, transaction);
                        transaction.commit();
                    }
                }).thenWaitUntil(() -> helper.assertTrue(!alarm(helper, core).output(), "off once emptied"))
                .thenSucceed();
    }

    /**
     * A root link saturated at 90 %: eight devices on a tier-1 trunk use all its channels (M7N) and turn the alarm on;
     * once they are gone, the alarm, looking again each interval, goes off by itself.
     */
    private static void linkSaturated(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos lamp = alarmOnCore(helper, core);
        for (int i = 1; i <= 3; i++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(core.north(i)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north(4));
        List<BlockPos> trunk = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(device.network() == NetworkGameTests.state(helper, core), "network formed");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            addRule(helper, core, new AlarmSettings(AlarmSettings.Type.LINK_SATURATED, 90));
            trunk.addAll(fillTrunk(helper, core));
        }).thenWaitUntil(() -> helper.assertTrue(alarm(helper, core).output(), "on while the trunk is full"))
                .thenExecute(() -> {
                    for (BlockPos pos : trunk) {
                        helper.destroyBlock(pos);
                    }
                    helper.destroyBlock(core.north(4));
                }).thenWaitUntil(() -> helper.assertTrue(!alarm(helper, core).output() && !lamp(helper, lamp),
                        "off once the load fell"))
                .thenSucceed();
    }

    /**
     * Device limit at 5 %: with the team's device cap at 32 (the controller sets none since M7R item 1), two devices
     * turn it on, one turns it off.
     */
    private static void deviceLimit(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        alarmOnCore(helper, core);
        NetworkGameTests.controllerEntity(helper, core)
                .setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "alarm_cap_owner")));
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core)).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            addRule(helper, core, new AlarmSettings(AlarmSettings.Type.DEVICE_LIMIT, 5));
            NetworkGameTests.device(helper, core.north());
        }).thenIdle(15).thenExecute(() -> {
            helper.assertTrue(!alarm(helper, core).output(), "off at 1 of 32");
            NetworkGameTests.device(helper, core.south());
        }).thenWaitUntil(() -> helper.assertTrue(alarm(helper, core).output(), "on at 2 of 32"))
                .thenExecute(() -> helper.destroyBlock(core.south()))
                .thenWaitUntil(() -> helper.assertTrue(!alarm(helper, core).output(), "off at 1 of 32 again"))
                .thenSucceed();
    }

    /**
     * The rules through the views' edits ({@link RecordsPayloads#edit}, as from the History tab): a stranger changes
     * nothing; the owner, once the team has Network Tuning, adds a rule, cycles its condition to the next with its
     * default threshold and steps the threshold within 0 to 100, past the most rules nothing is added, and a rule is
     * removed again. The rules go with the item of the controller's last block and come back when it is placed.
     */
    private static void screenAndItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "alarm_owner");
        ServerPlayer stranger = NetworkGameTests.player(level, "alarm_stranger");
        BlockPos core = new BlockPos(5, 1, 5);
        alarmOnCore(helper, core);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core)).thenExecute(() -> {
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            controller.setOwner(OwnerRecord.of(owner));
            open(helper, owner, controller, 7);
            open(helper, stranger, controller, 8);
            helper.assertTrue(!RecordsPayloads.edit(owner, 7, -1, ControllerRecorders.OP_ADD),
                    "no rules without the research");
            ControllerRecorderGameTests.research(helper, owner, "port_tier_2", "network_telemetry", "network_tuning");
            helper.assertTrue(!RecordsPayloads.edit(stranger, 8, -1, ControllerRecorders.OP_ADD), "stranger's add");
            helper.assertTrue(RecordsPayloads.edit(owner, 7, -1, ControllerRecorders.OP_ADD), "owner's add");
            helper.assertTrue(RecordsPayloads.edit(owner, 7, 0, ControllerRecorders.OP_CYCLE), "owner's cycle");
            AlarmWorker worker = alarm(helper, core);
            helper.assertValueEqual(worker.settings(),
                    new AlarmSettings(AlarmSettings.Type.STORAGE_FULL,
                            AlarmSettings.Type.STORAGE_FULL.defaultThreshold()),
                    "next condition with its default threshold");
            helper.assertTrue(RecordsPayloads.edit(owner, 7, 0, ControllerRecorders.OP_FIRST_STEP + 3), "+10");
            helper.assertValueEqual(worker.settings().threshold(), 100, "capped at 100");
            helper.assertTrue(RecordsPayloads.edit(owner, 7, 0, ControllerRecorders.OP_FIRST_STEP), "-10");
            helper.assertValueEqual(worker.settings().threshold(), 90, "90");
            helper.assertTrue(!RecordsPayloads.edit(owner, 7, 3, ControllerRecorders.OP_CYCLE), "no rule 3");
            RecordsView view = RecordsView.build(controller, owner, RecordsView.ALARMS);
            helper.assertValueEqual(view.alarms().rules().size(), 1, "the view lists the rule");
            helper.assertTrue(view.has(RecordsView.MAY_CONFIGURE), "the owner may change it");
            for (int i = 1; i < 4; i++) {
                helper.assertTrue(RecordsPayloads.edit(owner, 7, -1, ControllerRecorders.OP_ADD), "rule " + i);
            }
            helper.assertTrue(!RecordsPayloads.edit(owner, 7, -1, ControllerRecorders.OP_ADD), "four at most");
            helper.assertTrue(RecordsPayloads.edit(owner, 7, 3, ControllerRecorders.OP_REMOVE), "remove");
            helper.assertValueEqual(controller.recorders().rules().size(), 3, "three left");
            List<ItemStack> drops = Block.getDrops(helper.getBlockState(core), level, helper.absolutePos(core),
                    controller);
            List<AlarmSettings> kept = drops.getFirst().get(ModDataComponents.ALARM_RULES.get());
            helper.assertTrue(kept != null && kept.equals(controller.recorders().ruleSettings()), "rules in the item");
            BlockPos again = core.east(3);
            helper.setBlock(again, ControllerContent.CONTROLLER.get());
            ControllerBlockEntity placed = NetworkGameTests.controllerEntity(helper, again);
            placed.applyComponentsFromItemStack(drops.getFirst());
            helper.assertValueEqual(placed.recorders().ruleSettings(), controller.recorders().ruleSettings(),
                    "rules back from the item");
            owner.containerMenu = owner.inventoryMenu;
            stranger.containerMenu = stranger.inventoryMenu;
        }).thenSucceed();
    }

    /** Opens the controller's screen for {@code player} as container {@code id}, standing next to it. */
    static void open(GameTestHelper helper, ServerPlayer player, ControllerBlockEntity controller, int id) {
        BlockPos at = controller.getBlockPos();
        player.setPos(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
        player.containerMenu = ControllerMenu.server(id, controller, player, payload -> {
        });
    }

    /**
     * A core with a research terminal on top and another beside it, and seven more controller blocks: the terminals are
     * network members without a channel, not part of the multiblock, so the cap of 8 is not exceeded and nothing
     * attaches. Only the device on the cable holds a channel.
     */
    private static void channelFreeBeside(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "beside_owner");
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        BlockPos top = core.above();
        BlockPos side = core.west();
        helper.setBlock(top, ResearchContent.TERMINAL.get());
        helper.setBlock(side, ResearchContent.TERMINAL.get());
        for (int i = 1; i <= 7; i++) {
            helper.setBlock(core.east(i), ControllerContent.CONTROLLER.get());
        }
        for (int i = 1; i <= 3; i++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(core.north(i)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north(4));
        helper.startSequence().thenWaitUntil(() -> {
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(controller.blockCount(), 8, "eight blocks within the size cap of 8");
            helper.assertValueEqual(controller.members().size(), 0, "the terminals do not attach");
            helper.assertTrue(!controller.overCapacity(), "not over the size cap");
            helper.assertTrue(helper.getBlockEntity(top, ResearchTerminalBlockEntity.class).network() == network,
                    "the terminal on top joined");
            helper.assertTrue(helper.getBlockEntity(side, ResearchTerminalBlockEntity.class).network() == network,
                    "the terminal beside joined");
            helper.assertValueEqual(network.channelsHeld(), 1, "only the device's channel");
            helper.assertTrue(device.network() == network, "network formed");
        }).thenSucceed();
    }
}
