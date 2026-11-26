package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchTerminalBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * One controller per network (M7N item 3, PRD 7.2 Controller): when two networks with a controller each are joined by a
 * cable, the controller placed first stays in charge and the network keeps working on its stats; the other goes on
 * standby (no buffer, no cluster, an error look and a reason line) and takes over when the first is removed. Which one
 * is in charge survives a reload in either order. A core touching a running cluster stays idle instead
 * ({@code controller_cluster_second_core_idle}).
 *
 * <p>
 * Layout along x at y 1, z 1: controller A at x 1, cables x 2 to 6 (x 4 joins the two halves), controller B at x 7 with
 * a research terminal at x 8 (a network member without a channel), a test device beside x 2 and one beside x 6.
 */
// Networks and controllers are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ControllerStandbyGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String JOIN = "controller_standby_join_keeps_older";
    private static final String HANDOVER = "controller_standby_handover";
    private static final String RELOAD = "controller_standby_order_after_reload";
    private static final String SUBNET = "controller_standby_second_subnet_shows_nothing";
    private static final String CORE_OVER_SUBNET = "controller_standby_core_over_older_subnet";
    private static final String LEGACY = "controller_standby_loaded_without_placed_at";
    private static final List<String> PLAIN = List.of(JOIN, HANDOVER, RELOAD, SUBNET, CORE_OVER_SUBNET, LEGACY);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final BlockPos A = new BlockPos(1, 1, 1);
    private static final BlockPos B = new BlockPos(7, 1, 1);
    private static final BlockPos TERMINAL = new BlockPos(8, 1, 1);
    private static final BlockPos JOINT = new BlockPos(4, 1, 1);
    private static final BlockPos DEVICE_A = new BlockPos(2, 1, 2);
    private static final BlockPos DEVICE_B = new BlockPos(6, 1, 2);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(JOIN, StorageTestEnvironment.clearing(ControllerStandbyGameTests::joinKeepsOlder));
        StorageTests.function(HANDOVER, StorageTestEnvironment.clearing(ControllerStandbyGameTests::handover));
        StorageTests.function(RELOAD, StorageTestEnvironment.clearing(ControllerStandbyGameTests::orderAfterReload));
        StorageTests.function(SUBNET, StorageTestEnvironment.clearing(ControllerStandbyGameTests::secondSubnet));
        StorageTests.function(CORE_OVER_SUBNET,
                StorageTestEnvironment.clearing(ControllerStandbyGameTests::coreOverSubnet));
        StorageTests.function(LEGACY,
                StorageTestEnvironment.clearing(ControllerStandbyGameTests::loadedWithoutPlacedAt));
    }

    private ControllerStandbyGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("controller_standby"),
                StorageTestEnvironment.plain());
        for (String name : PLAIN) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
    }

    /**
     * Joining the two networks keeps A, placed first, in charge: the network works on A's buffer and stats, both
     * devices are members, B shows an error and the standby line, keeps its own FE, and the terminal stays a member of
     * the joined network; the overview names B.
     */
    private static void joinKeepsOlder(GameTestHelper helper) {
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        twoNetworks(helper, devices).thenExecute(() -> cable(helper, JOINT))
                .thenWaitUntil(() -> standbyAfterJoin(helper, devices)).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, devices[0]);
                    ControllerBlockEntity a = core(helper, A);
                    ControllerBlockEntity b = core(helper, B);
                    helper.assertTrue(a.placedAt() < b.placedAt(), "A placed first");
                    helper.assertTrue(network.controllerDevice() == a, "A in charge");
                    helper.assertFalse(a.isStandby(), "A on standby");
                    helper.assertValueEqual(network.controllerCount(), 2, "controllers");
                    helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status");
                    helper.assertValueEqual(network.deviceCount(), 5, "two controllers, two devices and the terminal");
                    helper.assertValueEqual(network.channelsHeld(), 2, "the standby core holds no channel");
                    helper.assertValueEqual(network.energyCapacity(), 10_000L, "A's buffer without B's");
                    helper.assertValueEqual(network.storedEnergy(), 1_000L, "A's FE only");
                    helper.assertValueEqual(b.buffer().stored(), 700L, "B keeps its FE");
                    helper.assertValueEqual(b.members().size(), 0, "B's cluster");
                    helper.assertValueEqual(NetworkGameTests.status(helper, A), ControllerStatus.ONLINE, "A's look");
                    helper.assertValueEqual(NetworkGameTests.status(helper, B), ControllerStatus.ERROR, "B's look");
                    List<String> lines = NetworkGameTests.keys(helper, B, BlockInfoRequest.Detail.BRIEF);
                    helper.assertTrue(lines.contains(ControllerContent.STANDBY_KEY), "standby line: " + lines);
                    helper.assertFalse(NetworkGameTests.keys(helper, A, BlockInfoRequest.Detail.BRIEF)
                            .contains(ControllerContent.STANDBY_KEY), "standby line on A");
                    NetworkOverview overview = NetworkOverview.of(network, NetworkOverview.Cluster.NO_CLUSTER);
                    helper.assertTrue(
                            overview.warnings().contains(new NetworkOverview.Warning(
                                    NetworkOverview.WarningKind.CONTROLLER_STANDBY, 1, helper.absolutePos(B).asLong())),
                            "standby warning: " + overview.warnings());
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 4), 4L, "insert");
                    // FE fed into B stays in B's own buffer.
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(b.energyInput().insert(100, transaction), 100, "FE into B");
                        transaction.commit();
                    }
                    helper.assertValueEqual(b.buffer().stored(), 800L, "B's FE after feeding it");
                    helper.assertValueEqual(network.storedEnergy(), 996L, "A's FE after one operation");
                }).thenSucceed();
    }

    /**
     * Removing A hands the network over to B: B is in charge, the network runs on B's buffer and both devices and the
     * terminal stay members.
     */
    private static void handover(GameTestHelper helper) {
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        twoNetworks(helper, devices).thenExecute(() -> cable(helper, JOINT))
                .thenWaitUntil(() -> standbyAfterJoin(helper, devices)).thenExecute(() -> helper.destroyBlock(A))
                .thenWaitUntil(() -> {
                    ControllerBlockEntity b = core(helper, B);
                    helper.assertFalse(b.isStandby(), "B still on standby");
                    NetworkState network = NetworkGameTests.net(helper, devices[0]);
                    helper.assertTrue(terminal(helper).network() == network, "the terminal stays a member under B");
                    helper.assertTrue(network.controllerDevice() == b, "B in charge");
                    helper.assertValueEqual(network.energyCapacity(), 10_000L, "B's buffer");
                    helper.assertValueEqual(NetworkGameTests.status(helper, B), ControllerStatus.ONLINE, "B's look");
                }).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, devices[0]);
                    helper.assertTrue(devices[1].network() == network, "B's device in the network");
                    helper.assertValueEqual(network.controllerCount(), 1, "controllers");
                    helper.assertValueEqual(network.storedEnergy(), 700L, "B's FE");
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 4), 4L, "insert");
                }).thenSucceed();
    }

    /**
     * A, placed first, stays in charge after both cores are unloaded and B loads back before A: the placement times are
     * saved, so neither the load order nor the join order decides.
     */
    private static void orderAfterReload(GameTestHelper helper) {
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        long[] placed = new long[2];
        CompoundTag[] saved = new CompoundTag[2];
        twoNetworks(helper, devices).thenExecute(() -> cable(helper, JOINT))
                .thenWaitUntil(() -> standbyAfterJoin(helper, devices)).thenExecute(() -> {
                    placed[0] = core(helper, A).placedAt();
                    placed[1] = core(helper, B).placedAt();
                    saved[0] = unload(helper, A);
                    saved[1] = unload(helper, B);
                }).thenIdle(5).thenExecute(() -> load(helper, B, saved[1]))
                .thenWaitUntil(() -> helper.assertTrue(
                        NetworkGameTests.net(helper, devices[0]).controllerDevice() == core(helper, B),
                        "B in charge while A is unloaded"))
                .thenExecute(() -> load(helper, A, saved[0])).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.net(helper, devices[0]);
                    helper.assertValueEqual(network.controllerCount(), 2, "both controllers back");
                    helper.assertTrue(network.controllerDevice() == core(helper, A), "A in charge again");
                    helper.assertTrue(core(helper, B).isStandby(), "B on standby again");
                }).thenExecute(() -> {
                    helper.assertValueEqual(core(helper, A).placedAt(), placed[0], "A's placement time");
                    helper.assertValueEqual(core(helper, B).placedAt(), placed[1], "B's placement time");
                    NetworkState network = NetworkGameTests.net(helper, devices[0]);
                    helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status");
                    helper.assertValueEqual(network.energyCapacity(), 10_000L, "A's buffer");
                }).thenSucceed();
    }

    /**
     * Two subnet controllers on one subnet, both facing the same parent: the second placed is on standby and its views
     * show nothing, so the parent sees the subnet's 10 cobblestone once, every tick from the second's placement on;
     * removing the first hands the views over. Layout: the parent's controller at x 1 z 1 with cables x 2 to 4 on z 1
     * and z 3 (joined at x 4 z 2), the subnet controllers at x 5 on z 1 and z 3 facing west, the subnet's cables at x 6
     * z 1 to 3 and its device at x 7 z 2.
     */
    private static void secondSubnet(GameTestHelper helper) {
        BlockPos parentCore = NetworkGameTests.controller(helper, 1);
        for (int x = 2; x <= 4; x++) {
            cable(helper, new BlockPos(x, 1, 1));
            cable(helper, new BlockPos(x, 1, 3));
        }
        cable(helper, new BlockPos(4, 1, 2));
        BlockPos firstPos = new BlockPos(5, 1, 1);
        BlockPos secondPos = new BlockPos(5, 1, 3);
        SubnetBlockEntity first = SubnetGameTests.subnet(helper, firstPos, Direction.WEST);
        for (int z = 1; z <= 3; z++) {
            cable(helper, new BlockPos(6, 1, z));
        }
        TestStorage.TestDevice subnetDevice = NetworkGameTests.device(helper, new BlockPos(7, 1, 2));
        NetworkGameTests.power(helper, parentCore, 10_000);
        first.buffer().receive(10_000);
        subnetDevice.provider.setDirect(COBBLE, 10);
        GameTestSequence sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(parentCobble(helper, parentCore), 10L,
                        "the parent sees the cobble through the first"))
                .thenExecute(() -> {
                    SubnetGameTests.subnet(helper, secondPos, Direction.WEST);
                    helper.assertValueEqual(parentCobble(helper, parentCore), 10L, "twice as the second is placed");
                });
        // Every tick while the second joins: the parent never counts the subnet's storage twice.
        for (int tick = 0; tick < 10; tick++) {
            int at = tick;
            sequence = sequence.thenIdle(1).thenExecute(() -> helper.assertValueEqual(parentCobble(helper, parentCore),
                    10L, "twice " + (at + 1) + " ticks later"));
        }
        sequence.thenWaitUntil(() -> {
            SubnetBlockEntity second = helper.getBlockEntity(secondPos, SubnetBlockEntity.class);
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            helper.assertValueEqual(child.controllerCount(), 2, "two subnet controllers");
            helper.assertTrue(child.controllerDevice() == first, "the first in charge");
            helper.assertTrue(second.isStandby(), "the second on standby");
            helper.assertTrue(second.parent() == NetworkGameTests.state(helper, parentCore),
                    "the second's uplink in the parent");
        }).thenExecute(() -> {
            List<String> lines = NetworkGameTests.keys(helper, secondPos, BlockInfoRequest.Detail.BRIEF);
            helper.assertTrue(lines.contains(ControllerContent.STANDBY_KEY), "standby line: " + lines);
            helper.destroyBlock(firstPos);
        }).thenWaitUntil(() -> {
            SubnetBlockEntity second = helper.getBlockEntity(secondPos, SubnetBlockEntity.class);
            helper.assertFalse(second.isStandby(), "the second took over");
            helper.assertValueEqual(parentCobble(helper, parentCore), 10L,
                    "the parent sees the cobble through the second");
        }).thenSucceed();
    }

    private static long parentCobble(GameTestHelper helper, BlockPos parentCore) {
        return NetworkGameTests.state(helper, parentCore).contents().amount(COBBLE);
    }

    /**
     * A controller core placed after a subnet controller in the subnet's network is in charge all the same: an older
     * subnet controller never puts a core on standby. Layout on z 3: the subnet controller at x 5 facing west (no
     * parent), a cable at x 6 with a device beside it, the core at x 7.
     */
    private static void coreOverSubnet(GameTestHelper helper) {
        BlockPos subnetPos = new BlockPos(5, 1, 3);
        BlockPos corePos = new BlockPos(7, 1, 3);
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, subnetPos, Direction.WEST);
        cable(helper, new BlockPos(6, 1, 3));
        TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(6, 1, 4));
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertTrue(NetworkGameTests.net(helper, device).controllerDevice() == subnet,
                                "the subnet controller runs it"))
                .thenIdle(3).thenExecute(() -> helper.setBlock(corePos, ControllerContent.CONTROLLER.get()))
                .thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.net(helper, device);
                    ControllerBlockEntity core = core(helper, corePos);
                    helper.assertValueEqual(network.controllerCount(), 2, "controllers");
                    helper.assertTrue(subnet.placedAt() < core.placedAt(), "the subnet controller is older");
                    helper.assertTrue(network.controllerDevice() == core, "the core in charge");
                    helper.assertFalse(core.isStandby(), "the core on standby");
                    helper.assertTrue(subnet.isStandby(), "the subnet controller on standby");
                }).thenSucceed();
    }

    /**
     * A core saved before placement times existed (no {@code placed_at}) gets the game time of its load: A, loaded back
     * without one, is now newer than B and goes on standby, and saves its new time.
     */
    private static void loadedWithoutPlacedAt(GameTestHelper helper) {
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        long[] loadedAt = new long[1];
        CompoundTag[] saved = new CompoundTag[1];
        twoNetworks(helper, devices).thenExecute(() -> cable(helper, JOINT))
                .thenWaitUntil(() -> standbyAfterJoin(helper, devices)).thenExecute(() -> {
                    saved[0] = unload(helper, A);
                    saved[0].remove("placed_at");
                }).thenIdle(3).thenExecute(() -> {
                    loadedAt[0] = helper.getLevel().getGameTime();
                    load(helper, A, saved[0]);
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity a = core(helper, A);
                    helper.assertTrue(a.placedAt() >= loadedAt[0], "A's time from its load: " + a.placedAt());
                    NetworkState network = NetworkGameTests.net(helper, devices[0]);
                    helper.assertValueEqual(network.controllerCount(), 2, "both controllers");
                    helper.assertTrue(network.controllerDevice() == core(helper, B), "B in charge");
                    helper.assertTrue(a.isStandby(), "A on standby");
                }).thenExecute(() -> {
                    CompoundTag tag = core(helper, A).saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.assertValueEqual(tag.getLongOr("placed_at", Long.MIN_VALUE), core(helper, A).placedAt(),
                            "A saves its new time");
                }).thenSucceed();
    }

    /**
     * Builds A's network, then a few ticks later B's with its terminal, two separate networks with one device and FE
     * each (A 1,000, B 700); the sequence continues once both work.
     */
    private static GameTestSequence twoNetworks(GameTestHelper helper, TestStorage.TestDevice[] devices) {
        helper.setBlock(A, ControllerContent.CONTROLLER.get());
        cable(helper, new BlockPos(2, 1, 1));
        cable(helper, new BlockPos(3, 1, 1));
        devices[0] = NetworkGameTests.device(helper, DEVICE_A);
        return helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.setBlock(B, ControllerContent.CONTROLLER.get());
            // A research terminal touching B: a network member without a channel.
            helper.setBlock(TERMINAL, ResearchContent.TERMINAL.get());
            cable(helper, new BlockPos(5, 1, 1));
            cable(helper, new BlockPos(6, 1, 1));
            devices[1] = NetworkGameTests.device(helper, DEVICE_B);
        }).thenWaitUntil(() -> {
            NetworkState first = NetworkGameTests.net(helper, devices[0]);
            NetworkState second = NetworkGameTests.net(helper, devices[1]);
            helper.assertTrue(first != second, "two networks");
            helper.assertTrue(second.controllerDevice() == core(helper, B), "B runs its own network");
            helper.assertTrue(terminal(helper).network() == second, "B's terminal on its network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, A, 1_000);
            NetworkGameTests.power(helper, B, 700);
        });
    }

    /**
     * Passes once the cable joined both networks, the joined network counted both devices' channels (the standby core
     * holds none, nor does the terminal) and B went on standby.
     */
    private static void standbyAfterJoin(GameTestHelper helper, TestStorage.TestDevice[] devices) {
        NetworkState network = NetworkGameTests.net(helper, devices[0]);
        helper.assertTrue(network == devices[1].network(), "networks not joined");
        helper.assertValueEqual(network.channelsHeld(), 2, "channels of the two devices");
        helper.assertTrue(core(helper, B).isStandby(), "B not on standby");
        // The terminal holds no channel and stays a member of the joined network.
        helper.assertTrue(terminal(helper).network() == network, "B's terminal in the joined network");
    }

    private static ControllerBlockEntity core(GameTestHelper helper, BlockPos pos) {
        return NetworkGameTests.controllerEntity(helper, pos);
    }

    private static ResearchTerminalBlockEntity terminal(GameTestHelper helper) {
        return helper.getBlockEntity(TERMINAL, ResearchTerminalBlockEntity.class);
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
    }

    /** Saves the block entity at {@code pos} and takes it out of its chunk, as an unload does. */
    private static CompoundTag unload(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        BlockEntity entity = helper.getLevel().getBlockEntity(abs);
        if (entity == null) {
            throw helper.assertionException(Component.literal("Nothing to unload at " + pos.toShortString()));
        }
        CompoundTag tag = entity.saveWithFullMetadata(helper.getLevel().registryAccess());
        entity.onChunkUnloaded();
        helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
        return tag;
    }

    /** Loads the block entity at {@code pos} from {@code tag} into its chunk, as a chunk load does. */
    private static void load(GameTestHelper helper, BlockPos pos, CompoundTag tag) {
        BlockPos abs = helper.absolutePos(pos);
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), tag,
                helper.getLevel().registryAccess());
        if (loaded == null) {
            throw helper.assertionException(Component.literal("Nothing loaded at " + pos.toShortString()));
        }
        helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(loaded);
    }
}
