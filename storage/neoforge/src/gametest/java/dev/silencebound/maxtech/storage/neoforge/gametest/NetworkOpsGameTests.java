package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The network's entry points for synchronous devices (M6c area B front-load): a device's own transfer used inside
 * another device's run, key listeners (once per committed change, on first appearance, never on a rollback, following
 * merges and splits) and point-to-point operations charging only the path between two devices. Layouts along x at y =
 * 1, z = 1 unless a test says otherwise; tier-1 cables carry 32 operations a second, so one operation is 3 % of a
 * segment's load.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Transfers and networks are compared by identity: which object a device gets is the point.
@SuppressWarnings("ReferenceEquality")
public final class NetworkOpsGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        NetworkOpsTestContent.init();
        StorageTestEnvironment.init();
        register("network_nested_transfer_keeps_binding", NetworkOpsGameTests::nestedTransferKeepsBinding);
        register("network_nested_transfer_in_port_run", NetworkOpsGameTests::nestedTransferInPortRun);
        register("network_key_listener_per_change", NetworkOpsGameTests::keyListenerPerChange);
        register("network_key_listener_follows_split_merge", NetworkOpsGameTests::keyListenerFollowsSplitMerge);
        register("network_operate_between_pays_energy", NetworkOpsGameTests::operateBetweenPaysEnergy);
        register("network_rollback_moves_nothing", NetworkOpsGameTests::rollbackMovesNothing);
    }

    private NetworkOpsGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("network_ops"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * Device A's task inserts once, has device B insert once through B's own transfer, then inserts again through its
     * own: the nested operation does not rebind A's transfer, all three items arrive, and no operation walks a route or
     * changes a channel count (M7N).
     */
    private static void nestedTransferKeepsBinding(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice a = NetworkGameTests.device(helper, NetworkGameTests.at(4));
        TestStorage.TestDevice b = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
        long[] moved = new long[3];
        long[] steps = new long[2];
        boolean[] distinct = new boolean[1];
        StorageTask task = (transfer, gameTime) -> {
            NetworkState network = transfer.network();
            steps[0] = network.channelSteps();
            try (Transaction transaction = Transaction.openRoot()) {
                moved[0] = transfer.insert(COBBLE, 1, transaction);
                StorageTransfer nested = b.deviceLink().transfer();
                distinct[0] = nested != null && nested != transfer;
                moved[1] = nested == null ? -1 : nested.insert(COBBLE, 1, transaction);
                moved[2] = transfer.insert(COBBLE, 1, transaction);
                transaction.commit();
            }
            steps[1] = network.channelSteps();
            return StorageTask.DONE;
        };
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, a);
            helper.assertTrue(network == b.network(), "devices not in one network");
            helper.assertTrue(network.routeRoot() == helper.absolutePos(controller).asLong(), "no routes yet");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1000);
            a.schedule(task);
        }).thenWaitUntil(() -> helper.assertValueEqual(a.scheduledTasks(), 0, "task still running")).thenExecute(() -> {
            helper.assertTrue(distinct[0], "B's transfer is A's");
            helper.assertValueEqual(moved[0] + moved[1] + moved[2], 3L, "items moved");
            helper.assertValueEqual(steps[1], steps[0], "route steps walked by the operations");
            NetworkState network = NetworkGameTests.net(helper, a);
            helper.assertValueEqual(network.segmentChannels(helper.absolutePos(NetworkGameTests.at(2)).asLong()), 2,
                    "channels on the shared cable");
            helper.assertValueEqual(network.contents().amount(COBBLE), 3L, "stored");
            helper.assertValueEqual(NetworkGameTests.net(helper, a).contents().amount(COBBLE), 3L, "indexed");
        }).thenSucceed();
    }

    /**
     * An export port pushes cobblestone into a relay on its own network, which stores it back through its own transfer
     * inside the port's transaction (an interface's insert). Nothing is lost or made, each step pays for the port's
     * operation and the relay's (4 FE each; the relay's simulated insert is refunded), and the port keeps its rate.
     */
    private static void nestedTransferInPortRun(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        for (int x = 2; x <= 4; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 2)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        BlockPos relayPos = new BlockPos(4, 1, 1);
        helper.setBlock(relayPos, NetworkOpsTestContent.RELAY.get());
        NetworkOpsTestContent.RelayDevice relay = helper.getBlockEntity(relayPos,
                NetworkOpsTestContent.RelayDevice.class);
        long power = 10_000;
        long[] start = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, drive);
            helper.assertTrue(network == relay.network(), "relay not in the network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, power);
            drive.provider.setDirect(COBBLE, 100);
            helper.setBlock(new BlockPos(3, 1, 1), PortContent.PORT.get().defaultBlockState()
                    .setValue(PortBlock.FACING, Direction.EAST).setValue(PortBlock.MODE, PortMode.EXPORT));
            PortBlockEntity port = helper.getBlockEntity(new BlockPos(3, 1, 1), PortBlockEntity.class);
            port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        }).thenWaitUntil(() -> helper.assertTrue(relay.forwarded > 0, "the port never pushed into the relay"))
                .thenExecute(() -> start[0] = NetworkGameTests.net(helper, drive).storedEnergy()).thenIdle(100)
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 100L, "cobblestone in the drive");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 100L, "indexed cobblestone");
                    long spent = power - network.storedEnergy();
                    helper.assertTrue(spent > 0 && spent % 8 == 0, spent + " FE is not whole steps of 2 x 4");
                    long steps = (start[0] - network.storedEnergy()) / 8;
                    helper.assertTrue(steps >= 4 && steps <= 6, steps + " steps in 100 ticks at 16 a second");
                }).thenSucceed();
    }

    /**
     * A listener on cobblestone, a key the network never held, hears its first appearance, one call per committed
     * insert or extract with the new total, nothing for another key or a rolled-back insert, and nothing once it stops
     * listening.
     */
    private static void keyListenerPerChange(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        List<Long> heard = new ArrayList<>();
        KeyListener listener = (key, total) -> {
            helper.assertTrue(key.equals(COBBLE), "called for " + key);
            heard.add(total);
        };
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.net(helper, device)).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1000);
            NetworkState network = NetworkGameTests.net(helper, device);
            device.deviceLink().listen(COBBLE, listener);
            device.deviceLink().listen(COBBLE, listener);
            helper.assertValueEqual(network.listenedKeyCount(), 1, "listened keys");
            helper.assertValueEqual(heard.size(), 0, "calls on listening");
            NetworkGameTests.insert(network, COBBLE, 5);
            helper.assertValueEqual(heard, List.of(5L), "first appearance");
            NetworkGameTests.insert(network, DIRT, 5);
            try (Transaction transaction = Transaction.openRoot()) {
                network.insert(COBBLE, 3, transaction);
            }
            helper.assertValueEqual(heard, List.of(5L), "after another key and a rollback");
            NetworkGameTests.insert(network, COBBLE, 2);
            try (Transaction transaction = Transaction.openRoot()) {
                network.extract(COBBLE, 7, transaction);
                transaction.commit();
            }
            helper.assertValueEqual(heard, List.of(5L, 7L, 0L), "an insert and an extract");
            device.deviceLink().unlisten(COBBLE, listener);
            helper.assertValueEqual(network.listenedKeyCount(), 0, "listened keys after unlisten");
            NetworkGameTests.insert(network, COBBLE, 1);
            helper.assertValueEqual(heard.size(), 3, "calls after unlisten");
        }).thenSucceed();
    }

    /**
     * A far device listening to cobblestone hears its new network's total when a cut splits it off, then only that
     * network's changes, and the merged total when the cable comes back; the listener is registered in exactly one
     * network at a time.
     */
    private static void keyListenerFollowsSplitMerge(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 8, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice near = NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        TestStorage.TestDevice far = NetworkGameTests.device(helper, NetworkGameTests.at(9));
        BlockPos cut = NetworkGameTests.at(5);
        List<Long> heard = new ArrayList<>();
        KeyListener listener = (key, total) -> heard.add(total);
        NetworkState[] main = new NetworkState[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, near);
            helper.assertTrue(network == far.network(), "not one network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1000);
            near.provider.setDirect(COBBLE, 5);
            far.provider.setDirect(COBBLE, 10);
            far.deviceLink().listen(COBBLE, listener);
            main[0] = NetworkGameTests.net(helper, near);
            helper.assertValueEqual(main[0].listenedKeyCount(), 1, "listened keys before the split");
            helper.destroyBlock(cut);
        }).thenWaitUntil(() -> helper.assertTrue(far.network() != near.network(), "not split")).thenExecute(() -> {
            NetworkState split = NetworkGameTests.net(helper, far);
            NetworkState kept = NetworkGameTests.net(helper, near);
            helper.assertTrue(heard.contains(10L), "split total not heard: " + heard);
            helper.assertValueEqual(heard.get(heard.size() - 1), 10L, "last call after the split");
            helper.assertValueEqual(kept.listenedKeyCount(), 0, "listened keys left in the main network");
            helper.assertValueEqual(split.listenedKeyCount(), 1, "listened keys in the split network");
            heard.clear();
            NetworkGameTests.insert(kept, COBBLE, 1);
            helper.assertValueEqual(heard.size(), 0, "calls for the other network");
            far.provider.setDirect(COBBLE, 4);
            helper.assertValueEqual(heard, List.of(4L), "a change in its own network");
            heard.clear();
        }).thenExecute(() -> ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(cut),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null)).thenWaitUntil(() -> {
                    helper.assertTrue(far.network() == near.network(), "not merged");
                    helper.assertTrue(!heard.isEmpty(), "nothing heard on the merge");
                }).thenExecute(() -> {
                    NetworkState merged = NetworkGameTests.net(helper, far);
                    helper.assertValueEqual(heard.get(heard.size() - 1), 10L, "merged total (6 + 4)");
                    helper.assertValueEqual(merged.listenedKeyCount(), 1, "listened keys after the merge");
                }).thenSucceed();
    }

    /**
     * Devices A (end of a line) and B (end of a branch) meet at the junction cable. An operation between them is capped
     * at one batch and pays 4 FE; a rolled-back one is refunded; a device outside the network gets nothing; and no
     * operation walks a route (M7N).
     */
    private static void operateBetweenPaysEnergy(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(4, 1, 2)),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        TestStorage.TestDevice a = NetworkGameTests.device(helper, NetworkGameTests.at(7));
        TestStorage.TestDevice b = NetworkGameTests.device(helper, new BlockPos(4, 1, 3));
        TestStorage.TestDevice outside = NetworkGameTests.device(helper, new BlockPos(20, 1, 20));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, a);
            helper.assertTrue(network == b.network(), "devices not in one network");
            helper.assertTrue(network.routeRoot() == helper.absolutePos(controller).asLong(), "no routes yet");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1000);
            NetworkState network = NetworkGameTests.net(helper, a);
            long steps = network.channelSteps();
            helper.assertValueEqual(network.pathRetryTicks(a, b), 0, "retry before");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.operateBetween(a, b, StackKey.Kind.ITEM, 100, transaction), 64L,
                        "paid for");
                helper.assertValueEqual(network.operateBetween(a, outside, StackKey.Kind.ITEM, 1, transaction), 0L,
                        "to a device outside the network");
                transaction.commit();
            }
            helper.assertValueEqual(network.storedEnergy(), 1000L - 4, "FE after one operation of 64");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.operateBetween(a, b, StackKey.Kind.ITEM, 10, transaction), 10L,
                        "rolled back");
            }
            helper.assertValueEqual(network.storedEnergy(), 1000L - 4, "FE after a rolled-back operation");
            helper.assertValueEqual(network.channelSteps(), steps, "route steps walked by the operations");
            helper.assertValueEqual(network.pathRetryTicks(a, b), 0, "retry after");
        }).thenSucceed();
    }

    /**
     * A device's task runs forty one-item inserts that are each rolled back, then one committed inside a nested
     * transaction whose root rolls back: all of them pass and nothing is stored. One committed insert then stores one
     * item, and a later rollback stores nothing more.
     */
    private static void rollbackMovesNothing(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        int[] passed = new int[1];
        long[] stored = new long[3];
        StorageTask task = (transfer, gameTime) -> {
            NetworkState network = transfer.network();
            for (int i = 0; i < 40; i++) {
                try (Transaction transaction = Transaction.openRoot()) {
                    passed[0] += (int) transfer.insert(COBBLE, 1, transaction);
                }
            }
            try (Transaction root = Transaction.openRoot()) {
                try (Transaction nested = Transaction.open(root)) {
                    passed[0] += (int) transfer.insert(COBBLE, 1, nested);
                    nested.commit();
                }
            }
            stored[0] = network.contents().amount(COBBLE);
            try (Transaction transaction = Transaction.openRoot()) {
                transfer.insert(COBBLE, 1, transaction);
                transaction.commit();
            }
            stored[1] = network.contents().amount(COBBLE);
            try (Transaction transaction = Transaction.openRoot()) {
                transfer.insert(COBBLE, 1, transaction);
            }
            stored[2] = network.contents().amount(COBBLE);
            return StorageTask.DONE;
        };
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertTrue(network.routeRoot() == helper.absolutePos(controller).asLong(), "no routes yet");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1000);
            device.schedule(task);
        }).thenWaitUntil(() -> helper.assertValueEqual(device.scheduledTasks(), 0, "task still running"))
                .thenExecute(() -> {
                    helper.assertValueEqual(passed[0], 41, "rolled-back inserts that passed");
                    helper.assertValueEqual(stored[0], 0L, "stored after rollbacks");
                    helper.assertValueEqual(stored[1], 1L, "stored after one committed insert");
                    helper.assertValueEqual(stored[2], 1L, "stored after one more rollback");
                }).thenSucceed();
    }

}
