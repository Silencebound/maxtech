package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.controller.module.TelemetryRecorder;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlock;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import dev.silencebound.maxtech.storage.network.AnalyzerItem;
import dev.silencebound.maxtech.storage.network.Glance;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.registry.ModCreativeTabs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import org.jspecify.annotations.Nullable;

/**
 * The storage network in a level (M6a area 1): forming, splitting and merging with the storage index following, zones
 * under the size cap, transfers that wait for no route, the ad-hoc channel allowance, two controllers (one in charge),
 * energy per operation and none while idle, a brown-out that slows without losing anything, both unloaded-chunk modes,
 * and owner access. Layouts run along x at y = 1, z = 1 unless a test says otherwise.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity: a merge or split hands devices a different one.
@SuppressWarnings("ReferenceEquality")
public final class NetworkGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final int Y = 1;
    private static final int Z = 1;
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> PLAIN = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        plain("network_form_split_merge", NetworkGameTests::formSplitMerge);
        plain("network_transfers_wait_for_no_route", NetworkGameTests::transfersWaitForNoRoute);
        plain("network_ad_hoc_channels", NetworkGameTests::adHocChannels);
        plain("network_two_controllers_one_in_charge", NetworkGameTests::twoControllersOneInCharge);
        plain("network_energy_per_operation", NetworkGameTests::energyPerOperation);
        plain("network_brownout_slows", NetworkGameTests::brownoutSlows);
        plain("network_owner_access", NetworkGameTests::ownerAccess);
        plain("network_info_lines", NetworkGameTests::infoLines);
        plain("network_attach_without_rebuild", NetworkGameTests::attachWithoutRebuild);
        plain("network_controller_saves_state", NetworkGameTests::controllerSavesState);
        plain("network_creative_tab", NetworkGameTests::creativeTab);
        register("network_zone_cap", NetworkGameTests::zoneCap);
        register("network_routes_ignore_order", NetworkGameTests::routesIgnoreOrder);
        register("network_unloaded_truncate", helper -> unloaded(helper, UnloadedChunkMode.TRUNCATE));
        register("network_unloaded_pause", helper -> unloaded(helper, UnloadedChunkMode.PAUSE));
    }

    private NetworkGameTests() {
    }

    private static void plain(String name, Consumer<GameTestHelper> test) {
        PLAIN.add(name);
        register(name, test);
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("network"),
                StorageTestEnvironment.plain());
        for (String name : PLAIN) {
            test(event, name, plain);
        }
        Holder<TestEnvironmentDefinition<?>> zones = event.registerEnvironment(MaxtechStorage.id("network_zone_cap"),
                new StorageTestEnvironment("network.zoneSize", "8", false));
        test(event, "network_zone_cap", zones);
        test(event, "network_routes_ignore_order", zones);
        test(event, "network_unloaded_truncate", event.registerEnvironment(MaxtechStorage.id("network_truncate"),
                new StorageTestEnvironment(StorageTestEnvironment.UNLOADED_MODE, "truncate", false)));
        test(event, "network_unloaded_pause", event.registerEnvironment(MaxtechStorage.id("network_pause"),
                new StorageTestEnvironment(StorageTestEnvironment.UNLOADED_MODE, "pause", false)));
    }

    private static void test(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(env, STRUCTURE, 400, 0, true)));
    }

    /**
     * A controller with a device beside it, a run of cables and a device at its end form one network whose index sums
     * both providers; breaking a cable splits off the far device with its storage, and replacing it merges them back.
     */
    private static void formSplitMerge(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 8, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice far = device(helper, at(9));
        TestStorage.TestDevice near = device(helper, new BlockPos(1, Y, Z + 1));
        BlockPos cut = at(5);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), 3, "devices");
            helper.assertTrue(network == far.network() && network == near.network(), "devices not in it");
        }).thenExecute(() -> {
            far.provider.setDirect(COBBLE, 10);
            near.provider.setDirect(COBBLE, 5);
            helper.assertValueEqual(contents(helper, controller), 15L, "indexed total");
        }).thenExecute(() -> helper.destroyBlock(cut)).thenWaitUntil(() -> {
            NetworkState main = state(helper, controller);
            NetworkState split = net(helper, far);
            helper.assertTrue(split != main, "not split");
            helper.assertValueEqual(contents(helper, controller), 5L, "main network after the split");
            helper.assertValueEqual(split.contents().amount(COBBLE), 10L, "split-off network");
        }).thenExecute(() -> ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(cut),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null)).thenWaitUntil(() -> {
                    NetworkState network = state(helper, controller);
                    helper.assertTrue(network == far.network(), "not merged");
                    helper.assertValueEqual(network.deviceCount(), 3, "devices after the merge");
                    helper.assertValueEqual(contents(helper, controller), 15L, "total after the merge");
                }).thenSucceed();
    }

    /** With a zone size of 8, a controller and 19 cables in a line make three zones of 8, 8 and 4 nodes. */
    private static void zoneCap(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.ZONE_SIZE.getAsInt(), 8, "zone size from the environment");
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 20, CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = state(helper, controller);
            helper.assertValueEqual(network.nodeCount(), 20, "nodes");
            helper.assertValueEqual(network.zoneCount(), 3, "zones");
            for (int zone = 0; zone < network.zoneCount(); zone++) {
                helper.assertTrue(network.zoneSize(zone) <= 8, "zone " + zone + " has " + network.zoneSize(zone));
            }
            helper.assertValueEqual(network.zone(helper.absolutePos(at(20)).asLong()), 2, "zone of the last cable");
        }).thenSucceed();
    }

    /**
     * Two equal 12 x 8 grids of cables, each with a controller at its corner, laid in opposite orders at different
     * places, get the same routes and zones node for node: ties between equal routes go to the lowest position and
     * zones fill by depth then position, never by link, placement or hash order. With a zone size of 8 the grid has
     * many zones, so a zone filled in another order would show.
     */
    private static void routesIgnoreOrder(GameTestHelper helper) {
        int width = 12;
        int depth = 8;
        int[] origins = {2, 14};
        for (int grid = 0; grid < 2; grid++) {
            int z0 = origins[grid];
            helper.setBlock(new BlockPos(1, Y, z0), ControllerContent.CONTROLLER.get());
            for (int i = 0; i < width * depth; i++) {
                // The first grid row by row from the controller, the second from its far corner back.
                int n = grid == 0 ? i : width * depth - 1 - i;
                ConduitBlock.placeConduit(helper.getLevel(),
                        helper.absolutePos(new BlockPos(2 + n % width, Y, z0 + n / width)),
                        CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
            }
        }
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState first = state(helper, new BlockPos(1, Y, origins[0]));
            NetworkState second = state(helper, new BlockPos(1, Y, origins[1]));
            helper.assertTrue(first != second, "the grids share a network");
            helper.assertValueEqual(first.nodeCount(), width * depth + 1, "nodes of the first grid");
            helper.assertValueEqual(second.nodeCount(), width * depth + 1, "nodes of the second grid");
            helper.assertTrue(first.zoneCount() > 4, "only " + first.zoneCount() + " zones");
            int shift = origins[1] - origins[0];
            for (int x = 1; x < 2 + width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (x == 1 && z > 0) {
                        continue;
                    }
                    long a = helper.absolutePos(new BlockPos(x, Y, origins[0] + z)).asLong();
                    long b = helper.absolutePos(new BlockPos(x, Y, origins[1] + z)).asLong();
                    String at = "at x " + x + ", z " + z;
                    helper.assertValueEqual(second.zone(b), first.zone(a), "zone " + at);
                    long parentA = first.routeParent(a);
                    long parentB = second.routeParent(b);
                    // The second grid's parent moved back onto the first grid, to compare positions.
                    long shifted = parentB == Long.MIN_VALUE
                            ? parentB
                            : BlockPos.of(parentB).offset(0, 0, -shift).asLong();
                    helper.assertValueEqual(shifted, parentA, "route parent " + at);
                }
            }
        }).thenSucceed();
    }

    /**
     * A device two tier-1 cables from the controller inserts one item per operation 200 times in one task run (M7N):
     * nothing on the route makes it wait, since channels replaced bandwidth, and no operation walks the route. Every
     * operation's item arrives, none is lost.
     */
    private static void transfersWaitForNoRoute(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice port = device(helper, at(4));
        int[] moved = {-1};
        long[] steps = new long[2];
        StorageTask task = (transfer, gameTime) -> {
            steps[0] = transfer.network().channelSteps();
            int ops = 0;
            while (ops < 200 && insertOne(transfer)) {
                ops++;
            }
            moved[0] = ops;
            steps[1] = transfer.network().channelSteps();
            return StorageTask.DONE;
        };
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(port.network() != null, "no network"))
                .thenExecute(() -> {
                    power(helper, controller, 10_000);
                    port.schedule(task);
                }).thenWaitUntil(() -> helper.assertTrue(moved[0] >= 0, "task did not run")).thenExecute(() -> {
                    helper.assertValueEqual(moved[0], 200, "operations in one run");
                    helper.assertValueEqual(steps[1], steps[0], "route steps walked by the operations");
                    helper.assertValueEqual(contents(helper, controller), 200L, "items in storage");
                }).thenSucceed();
    }

    private static boolean insertOne(StorageTransfer transfer) {
        if (!transfer.canOperate()) {
            return false;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = transfer.insert(COBBLE, 1, transaction);
            transaction.commit();
            return moved == 1;
        }
    }

    /**
     * Eight devices touching each other run without a controller, powered through any of them; a ninth goes offline
     * (M7N: the ad-hoc allowance of eight channels) and says so, while the network and the other eight keep working.
     * Removing one of the eight (without splitting the network) brings the ninth online.
     */
    private static void adHocChannels(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.AD_HOC_CHANNELS.getAsInt(), 8, "ad-hoc channels");
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (int x = 1; x <= 8; x++) {
            devices.add(device(helper, at(x)));
        }
        TestStorage.TestDevice first = devices.get(0);
        TestStorage.TestDevice[] ninth = new TestStorage.TestDevice[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = net(helper, first);
            helper.assertTrue(network == devices.get(7).network(), "network not formed");
            helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status with 8 devices");
            helper.assertValueEqual(network.channelsHeld(), 8, "channels with 8 devices");
        }).thenExecute(() -> {
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(devices.get(5).energyInput().insert(500, transaction), 500, "FE taken");
                transaction.commit();
            }
            helper.assertValueEqual(insert(net(helper, first), COBBLE, 4), 4L, "insert with 8 devices");
            ninth[0] = device(helper, new BlockPos(1, Y, Z + 1));
        }).thenWaitUntil(() -> helper.assertTrue(ninth[0].deviceLink().offlineBy() == net(helper, first),
                "ninth device not offline")).thenExecute(() -> {
                    NetworkState network = net(helper, first);
                    helper.assertTrue(ninth[0].network() == null, "offline device in the network");
                    helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status with 9 devices");
                    helper.assertValueEqual(network.deviceCount(), 8, "devices with 9 placed");
                    ChannelShortage shortage = ninth[0].deviceLink().channelShortage().orElseThrow();
                    helper.assertValueEqual(shortage.kind(), ChannelShortage.Kind.AD_HOC, "reason");
                    helper.assertValueEqual(shortage.used(), 8, "channels in use");
                    helper.assertValueEqual(shortage.capacity(), 8, "allowance");
                    helper.assertValueEqual(insert(network, COBBLE, 4), 4L, "insert with 9");
                    helper.destroyBlock(at(8));
                })
                .thenWaitUntil(() -> helper.assertTrue(ninth[0].network() != null, "ninth not online after a removal"))
                .thenExecute(() -> {
                    helper.assertTrue(ninth[0].deviceLink().channelShortage().isEmpty(), "reason kept");
                    helper.assertTrue(ninth[0].network() == devices.get(0).network(), "ninth in another network");
                }).thenSucceed();
    }

    /**
     * Two controllers placed in the same tick on one network: the lower position is in charge, the other is on standby
     * with an error look, and the network works on the one in charge (M7N item 3). Removing it hands over.
     */
    private static void twoControllersOneInCharge(GameTestHelper helper) {
        BlockPos first = controller(helper, 1);
        cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos second = controller(helper, 5);
        device(helper, new BlockPos(3, Y, Z + 1));
        BlockPos[] order = new BlockPos[2];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = state(helper, first);
            helper.assertTrue(network == network(helper, second), "network not formed");
            helper.assertValueEqual(network.controllerCount(), 2, "controllers");
            boolean firstLower = helper.absolutePos(first).asLong() < helper.absolutePos(second).asLong();
            order[0] = firstLower ? first : second;
            order[1] = firstLower ? second : first;
            helper.assertTrue(network.controllerDevice() == controllerEntity(helper, order[0]), "lower in charge");
            helper.assertTrue(controllerEntity(helper, order[1]).isStandby(), "other on standby");
            helper.assertValueEqual(status(helper, order[1]), ControllerStatus.ERROR, "standby look");
        }).thenExecute(() -> {
            NetworkState network = state(helper, first);
            helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status");
            power(helper, order[0], 100);
            helper.assertValueEqual(insert(network, COBBLE, 1), 1L, "insert with two controllers");
            helper.destroyBlock(order[0]);
        }).thenWaitUntil(() -> {
            helper.assertFalse(controllerEntity(helper, order[1]).isStandby(), "took over");
            helper.assertValueEqual(state(helper, order[1]).status(), StorageNetwork.Status.ONLINE, "status");
            helper.assertValueEqual(status(helper, order[1]), ControllerStatus.OFF, "unpowered controller");
        }).thenExecute(() -> power(helper, order[1], 100)).thenWaitUntil(
                () -> helper.assertValueEqual(status(helper, order[1]), ControllerStatus.ONLINE, "powered controller"))
                .thenSucceed();
    }

    /**
     * Each operation costs 4 FE whatever it moves (the presets set no cost per item or mB); a rolled-back one costs
     * nothing, and an idle network takes nothing.
     */
    private static void energyPerOperation(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        TestStorage.TestDevice drive = device(helper, at(2));
        StackKey water = FluidKey.of(Fluids.WATER);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(drive.network() != null, "no network"))
                .thenExecute(() -> {
                    power(helper, controller, 1000);
                    NetworkState network = net(helper, drive);
                    helper.assertValueEqual(insert(network, COBBLE, 10), 10L, "inserted");
                    helper.assertValueEqual(network.storedEnergy(), 1000L - 4, "after inserting 10 items");
                    helper.assertValueEqual(insert(network, water, 600), 600L, "inserted water");
                    helper.assertValueEqual(network.storedEnergy(), 1000L - 4 - 4, "after 600 mB");
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(network.extract(COBBLE, 4, transaction), 4L, "simulated extract");
                    }
                    helper.assertValueEqual(network.storedEnergy(), 1000L - 8, "after a rolled-back extract");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "items after the rollback");
                }).thenIdle(40)
                .thenExecute(() -> helper.assertValueEqual(net(helper, drive).storedEnergy(), 1000L - 8, "idle"))
                .thenSucceed();
    }

    /**
     * With FE for two operations and 2 FE over, a device's task makes two 8-item operations and is then refused:
     * nothing stored is lost, the network stays together and the task retries only every 10 ticks. New FE lets it go
     * on.
     */
    private static void brownoutSlows(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        TestStorage.TestDevice port = device(helper, at(2));
        int[] moved = new int[1];
        int[] runs = new int[1];
        StorageTask task = (transfer, gameTime) -> {
            runs[0]++;
            try (Transaction transaction = Transaction.openRoot()) {
                long in = transfer.insert(COBBLE, 8, transaction);
                transaction.commit();
                moved[0] += (int) in;
                return in > 0 ? StorageTask.NEXT_TICK : Math.max(1, transfer.retryTicks());
            }
        };
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(port.network() != null, "no network"))
                .thenExecute(() -> {
                    port.provider.setDirect(ItemKey.of(Items.DIRT), 500);
                    // An operation costs 4 FE whatever it moves.
                    power(helper, controller, 10);
                    port.schedule(task);
                }).thenIdle(60).thenExecute(() -> {
                    NetworkState network = net(helper, port);
                    helper.assertValueEqual(moved[0], 16, "items moved before the power ran out");
                    helper.assertValueEqual(network.storedEnergy(), 2L, "FE left, less than an operation");
                    helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.DIRT)), 500L, "stored dirt");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 16L, "stored cobblestone");
                    // Two operations, then a retry every 10 ticks instead of every tick.
                    helper.assertTrue(runs[0] <= 12, runs[0] + " runs in 60 ticks: the brown-out does not slow it");
                    power(helper, controller, 100);
                }).thenWaitUntil(() -> helper.assertTrue(moved[0] > 16, "no progress after power came back"))
                .thenExecute(() -> port.cancel(task)).thenSucceed();
    }

    /**
     * On a test network type whose far end can be marked unloaded: two devices on a cable run without a controller.
     * When the far device and two cables unload, truncate mode keeps the near part working without the far storage, and
     * pause mode stops the network; loading them again restores it either way.
     */
    private static void unloaded(GameTestHelper helper, UnloadedChunkMode mode) {
        helper.assertValueEqual(ServerConfig.UNLOADED_CHUNK_MODE.get(), mode, "mode set by the environment");
        TestStorage.TestDevice near = simDevice(helper, at(1));
        cables(helper, 2, 6, TestStorage.SIM_CABLE.get());
        TestStorage.TestDevice far = simDevice(helper, at(7));
        List<BlockPos> unloaded = List.of(helper.absolutePos(at(5)), helper.absolutePos(at(6)),
                helper.absolutePos(at(7)));
        NetworkManager manager = NetworkManager.get(helper.getLevel());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(net(helper, near) == far.network(), "network not formed");
        }).thenExecute(() -> {
            far.provider.setDirect(COBBLE, 10);
            try (Transaction transaction = Transaction.openRoot()) {
                near.energyInput().insert(500, transaction);
                transaction.commit();
            }
            for (BlockPos pos : unloaded) {
                TestStorage.UNLOADED.add(pos.asLong());
                manager.removeNode(TestStorage.SIM, pos);
            }
        }).thenIdle(3).thenExecute(() -> {
            NetworkState network = net(helper, near);
            if (mode == UnloadedChunkMode.TRUNCATE) {
                helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status while truncated");
                // The cut may have left the near part in a new network, whose ad-hoc store starts empty.
                try (Transaction transaction = Transaction.openRoot()) {
                    near.energyInput().insert(500, transaction);
                    transaction.commit();
                }
                helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "far storage while unloaded");
                helper.assertValueEqual(insert(network, COBBLE, 3), 3L, "insert while truncated");
            } else {
                helper.assertValueEqual(network.status(), StorageNetwork.Status.PAUSED, "status while paused");
                helper.assertValueEqual(insert(network, COBBLE, 3), 0L, "insert while paused");
            }
        }).thenExecute(() -> {
            for (BlockPos pos : unloaded) {
                TestStorage.UNLOADED.remove(pos.asLong());
                manager.addNode(TestStorage.SIM, pos);
            }
        }).thenWaitUntil(() -> {
            NetworkState network = net(helper, near);
            helper.assertTrue(network == far.network(), "not joined again");
            helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status after loading");
            long expected = mode == UnloadedChunkMode.TRUNCATE ? 13L : 10L;
            helper.assertValueEqual(network.contents().amount(COBBLE), expected, "storage after loading");
        }).thenSucceed();
    }

    /**
     * The player who places the controller owns the network: the owner and trusted players get in, strangers do not. A
     * network without a controller and without owned devices lets everyone in.
     */
    private static void ownerAccess(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(level, "storage_owner");
        ServerPlayer friend = player(level, "storage_friend");
        ServerPlayer stranger = player(level, "storage_stranger");
        BlockPos controller = controller(helper, 1);
        BlockState state = helper.getBlockState(controller);
        state.getBlock().setPlacedBy(level, helper.absolutePos(controller), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        TestStorage.TestDevice drive = device(helper, at(2));
        TestStorage.TestDevice loose = device(helper, at(10));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = net(helper, drive);
            helper.assertTrue(network.owner().isOwner(owner.getUUID()), "owner not set");
            helper.assertTrue(network.canAccess(owner), "owner");
            helper.assertFalse(network.canAccess(stranger), "stranger");
            helper.assertFalse(network.canAccess(friend), "friend before being trusted");
        }).thenExecute(() -> {
            ControllerBlockEntity entity = controllerEntity(helper, controller);
            entity.setOwner(entity.ownerRecord().withTrusted(friend.getUUID()));
        }).thenWaitUntil(() -> helper.assertTrue(net(helper, drive).canAccess(friend), "trusted friend"))
                .thenWaitUntil(() -> {
                    helper.assertFalse(net(helper, loose).owner().isOwned(), "loose network owned");
                    helper.assertTrue(net(helper, loose).canAccess(stranger), "unowned network");
                }).thenSucceed();
    }

    /**
     * Jade, the scanner and the analyzer read the same lines: a cable reports its tier, its zone with the route load
     * and the network's power (through Lib's conduit info), the controller its network's size in full detail, and the
     * analyzer's readout joins a block's brief lines and says nothing for a block outside any storage network.
     */
    private static void infoLines(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_2.type().get());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).nodeCount(), 3, "nodes"))
                .thenExecute(() -> {
                    power(helper, controller, 500);
                    List<String> brief = keys(helper, at(3), BlockInfoRequest.Detail.BRIEF);
                    helper.assertValueEqual(brief, List.of(Glance.ONLINE.key(), NetworkInfo.CHANNELS_KEY),
                            "a cable at a glance: status and channels");
                    List<String> cable = keys(helper, at(3), BlockInfoRequest.Detail.FULL);
                    helper.assertTrue(cable.contains(NetworkInfo.CABLE_KEY), "cable tier line: " + cable);
                    helper.assertTrue(cable.contains(NetworkInfo.ZONE_KEY), "zone line: " + cable);
                    helper.assertTrue(cable.contains(NetworkInfo.POWER_KEY), "power line: " + cable);
                    List<String> root = keys(helper, controller, BlockInfoRequest.Detail.FULL);
                    helper.assertTrue(root.contains(NetworkInfo.ROOT_KEY), "root line: " + root);
                    helper.assertTrue(root.contains(NetworkInfo.NETWORK_KEY), "network line: " + root);
                    helper.assertValueEqual(root.getFirst(), Glance.ONLINE.key(), "status first: " + root);
                    helper.assertTrue(readout(helper, at(2)) != null, "no analyzer readout on a cable");
                    helper.assertTrue(readout(helper, at(6)) == null, "analyzer readout on air");
                }).thenSucceed();
    }

    /**
     * A cable and a device added to a settled network hang off the route tree without rebuilding it, and get a zone;
     * removing the cable again rebuilds.
     */
    private static void attachWithoutRebuild(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 5, CableContent.STORAGE_CABLE_TIER_1.type().get());
        int[] rebuilds = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).nodeCount(), 5, "nodes"))
                .thenIdle(10).thenExecute(() -> {
                    rebuilds[0] = state(helper, controller).rebuilds();
                    cables(helper, 6, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
                    device(helper, at(7));
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).deviceCount(), 2, "devices"))
                .thenIdle(10).thenExecute(() -> {
                    NetworkState network = state(helper, controller);
                    helper.assertValueEqual(network.rebuilds(), rebuilds[0], "rebuilds after adding");
                    helper.assertTrue(network.zone(helper.absolutePos(at(7)).asLong()) >= 0, "device has no zone");
                    helper.destroyBlock(at(6));
                }).thenWaitUntil(() -> helper.assertTrue(state(helper, controller).rebuilds() > rebuilds[0],
                        "no rebuild after a removal"))
                .thenSucceed();
    }

    /** The controller keeps its FE and its owner across a save and load (a chunk unload and reload). */
    private static void controllerSavesState(GameTestHelper helper) {
        BlockPos pos = controller(helper, 1);
        ControllerBlockEntity saved = controllerEntity(helper, pos);
        ServerPlayer owner = player(helper.getLevel(), "storage_saver");
        saved.setOwner(OwnerRecord.of(owner));
        power(helper, pos, 1234);
        CompoundTag tag = saved.saveWithoutMetadata(helper.getLevel().registryAccess());
        ControllerBlockEntity loaded = new ControllerBlockEntity(helper.absolutePos(pos), helper.getBlockState(pos));
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        helper.assertValueEqual(loaded.buffer().stored(), 1234L, "FE after loading");
        helper.assertTrue(loaded.ownerRecord().isOwner(owner.getUUID()), "owner after loading");
        helper.succeed();
    }

    /** Storage's tab lists the cables, the controller and the analyzer. */
    private static void creativeTab(GameTestHelper helper) {
        CreativeModeTab tab = ModCreativeTabs.STORAGE.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(helper.getLevel().enabledFeatures(), false,
                helper.getLevel().registryAccess()));
        List<Item> items = new ArrayList<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            items.add(stack.getItem());
        }
        helper.assertTrue(items.contains(CableContent.STORAGE_CABLE_TIER_1.item().get()), "tier-1 cable");
        helper.assertTrue(items.contains(CableContent.STORAGE_CABLE_TIER_2.item().get()), "tier-2 cable");
        helper.assertTrue(items.contains(ControllerContent.CONTROLLER.item().get()), "controller");
        helper.assertTrue(items.contains(NetworkContent.NETWORK_ANALYZER.get()), "analyzer");
        helper.succeed();
    }

    static List<String> keys(GameTestHelper helper, BlockPos pos, BlockInfoRequest.Detail detail) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockInfoProvider provider = InfoLookups.BLOCK.find(helper.getLevel(), absolute, null);
        if (provider == null) {
            throw helper.assertionException(Component.literal("No block info at " + pos.toShortString()));
        }
        List<String> keys = new ArrayList<>();
        provider.addInfo(new BlockInfoRequest(helper.getLevel(), absolute, null, null, detail),
                line -> keys.add(line.getContents() instanceof TranslatableContents translatable
                        ? translatable.getKey()
                        : line.getString()));
        return keys;
    }

    private static @Nullable Component readout(GameTestHelper helper, BlockPos pos) {
        return AnalyzerItem.readout(new BlockInfoRequest(helper.getLevel(), helper.absolutePos(pos), null, null,
                BlockInfoRequest.Detail.BRIEF));
    }

    static ServerPlayer player(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault());
    }

    static BlockPos at(int x) {
        return new BlockPos(x, Y, Z);
    }

    static BlockPos controller(GameTestHelper helper, int x) {
        BlockPos pos = at(x);
        helper.setBlock(pos, ControllerContent.CONTROLLER.get());
        return pos;
    }

    /**
     * The controller block at {@code pos}, or the primary of its multiblock when that is loaded (M7R item 1): what
     * holds the network's state. {@code helper.getBlockEntity} gives the block itself.
     */
    static ControllerBlockEntity controllerEntity(GameTestHelper helper, BlockPos pos) {
        ControllerBlockEntity block = helper.getBlockEntity(pos, ControllerBlockEntity.class);
        ControllerBlockEntity primary = block.primary();
        return primary != null ? primary : block;
    }

    static void power(GameTestHelper helper, BlockPos controller, long fe) {
        controllerEntity(helper, controller).buffer().receive(fe);
    }

    /**
     * The runs of {@code network}'s scheduler so far without the controller's telemetry samples (M7R item 3: every
     * controller samples its network every {@code sampleTicks}), so tests that expect idle devices to sleep count only
     * the devices' runs.
     */
    static long taskRuns(NetworkState network) {
        long samples = network.history() instanceof TelemetryRecorder recorder ? recorder.samplesTaken() : 0;
        return network.scheduler().runs() - samples;
    }

    static ControllerStatus status(GameTestHelper helper, BlockPos controller) {
        return helper.getBlockState(controller).getValue(ControllerBlock.STATUS);
    }

    static void cables(GameTestHelper helper, int fromX, int toX, ConduitType type) {
        for (int x = fromX; x <= toX; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(at(x)), type, null);
        }
    }

    static TestStorage.TestDevice device(GameTestHelper helper, BlockPos pos) {
        return place(helper, pos, TestStorage.DEVICE.get());
    }

    static TestStorage.TestDevice simDevice(GameTestHelper helper, BlockPos pos) {
        return place(helper, pos, TestStorage.SIM_DEVICE.get());
    }

    private static TestStorage.TestDevice place(GameTestHelper helper, BlockPos pos, Block block) {
        helper.setBlock(pos, block);
        return helper.getBlockEntity(pos, TestStorage.TestDevice.class);
    }

    /** The network of the node at {@code pos}; fails the test (to be retried) while there is none. */
    static NetworkState state(GameTestHelper helper, BlockPos pos) {
        NetworkState network = network(helper, pos);
        if (network == null) {
            throw helper.assertionException(Component.literal("No storage network at " + pos.toShortString()));
        }
        return network;
    }

    /** The network {@code device} is in; fails the test (to be retried) while it is in none. */
    static NetworkState net(GameTestHelper helper, TestStorage.TestDevice device) {
        NetworkState network = device.network();
        if (network == null) {
            throw helper.assertionException(
                    Component.literal("No storage network at device " + device.getBlockPos().toShortString()));
        }
        return network;
    }

    static @Nullable NetworkState network(GameTestHelper helper, BlockPos pos) {
        return StorageNetworks.network(helper.getLevel(), StorageNetworks.ID, helper.absolutePos(pos));
    }

    private static long contents(GameTestHelper helper, BlockPos pos) {
        NetworkState network = network(helper, pos);
        return network == null ? -1 : network.contents().amount(COBBLE);
    }

    /** A player insert of {@code amount}, committed. */
    static long insert(@Nullable NetworkState network, StackKey key, long amount) {
        if (network == null) {
            return -1;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.insert(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }
}
