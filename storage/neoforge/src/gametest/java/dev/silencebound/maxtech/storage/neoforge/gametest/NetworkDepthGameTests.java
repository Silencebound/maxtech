package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode;
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
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.network.TeamCaps;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: tests move items in NeoForge transactions like the network does.
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Network depth (M6d): a cable removed or cut reroutes only the nodes behind it (no full rebuild), also when part of
 * the network unloads in either unloaded-chunk mode; the server caps per team (devices, zones, bytes); and the cells of
 * a drive whose chunk unloads counted as offline until it loads.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkDepthGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String PATCH = "network_patch_cable_removal";
    private static final String TRUNCATE = "network_patch_unloaded_truncate";
    private static final String PAUSE = "network_patch_unloaded_pause";
    private static final String DEVICES = "network_cap_devices";
    private static final String ZONES = "network_cap_zones";
    private static final String TEAM_ZONES = "network_cap_zones_ftb_team";
    private static final String BYTES = "network_cap_bytes";
    private static final String OFFLINE = "network_offline_cells";

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(PATCH, NetworkDepthGameTests::patchCableRemoval);
        register(TRUNCATE, helper -> patchUnloaded(helper, UnloadedChunkMode.TRUNCATE));
        register(PAUSE, helper -> patchUnloaded(helper, UnloadedChunkMode.PAUSE));
        register(DEVICES, NetworkDepthGameTests::deviceCap);
        register(ZONES, NetworkDepthGameTests::zoneCap);
        register(TEAM_ZONES, NetworkDepthGameTests::teamZoneCap);
        register(BYTES, NetworkDepthGameTests::byteCap);
        register(OFFLINE, NetworkDepthGameTests::offlineCells);
    }

    private NetworkDepthGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("network_depth"),
                StorageTestEnvironment.plain());
        test(event, PATCH, plain);
        test(event, OFFLINE, plain);
        test(event, TRUNCATE, event.registerEnvironment(MaxtechStorage.id("network_patch_truncate"),
                new StorageTestEnvironment(StorageTestEnvironment.UNLOADED_MODE, "truncate", false)));
        test(event, PAUSE, event.registerEnvironment(MaxtechStorage.id("network_patch_pause"),
                new StorageTestEnvironment(StorageTestEnvironment.UNLOADED_MODE, "pause", false)));
        test(event, DEVICES, event.registerEnvironment(MaxtechStorage.id("network_cap_devices"),
                new StorageTestEnvironment("caps.devicesPerTeam", "3", false)));
        Holder<TestEnvironmentDefinition<?>> zones = event.registerEnvironment(MaxtechStorage.id("network_cap_zones"),
                new StorageTestEnvironment("caps.zonesPerTeam", "2", false));
        test(event, ZONES, zones);
        test(event, TEAM_ZONES, zones);
        test(event, BYTES, event.registerEnvironment(MaxtechStorage.id("network_cap_bytes"),
                new StorageTestEnvironment("caps.bytesPerTeam", "10", false)));
    }

    private static void test(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(env, STRUCTURE, 300, 0, true)));
    }

    /** Two rows of cables side by side from x = 2 to {@code toX}, at z = 1 and z = 2, of {@code type}. */
    private static void ribbon(GameTestHelper helper, int toX, ConduitType type) {
        for (int x = 2; x <= toX; x++) {
            for (int z = 1; z <= 2; z++) {
                ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, z)), type, null);
            }
        }
    }

    /** The device's transfer's wait; 0 when it can operate now. */
    private static int retry(TestStorage.TestDevice device) {
        StorageTransfer transfer = device.deviceLink().transfer();
        return transfer == null ? -1 : transfer.retryTicks();
    }

    /**
     * A controller feeds a two-cable-wide ribbon with a device at its far end. Breaking a cable on the device's route
     * patches the routes: no full rebuild, the device keeps a zone and can operate, and a cable placed back attaches
     * without one either.
     */
    private static void patchCableRemoval(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        ribbon(helper, 10, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(11));
        int[] before = new int[2];
        BlockPos[] broken = new BlockPos[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.nodeCount(), 20, "nodes");
            helper.assertTrue(device.network() == network, "device joined");
        }).thenExecute(() -> NetworkGameTests.power(helper, controller, 10_000)).thenIdle(10).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            before[0] = network.rebuilds();
            before[1] = network.patches();
            // Break the cable at x = 5 in whichever row carries the device's route.
            broken[0] = onRoute(helper, network, device);
            helper.destroyBlock(broken[0]);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(network.patches() > before[1], "no patch");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.rebuilds(), before[0], "full rebuilds after a cable removal");
            helper.assertTrue(network.zone(device.getBlockPos().asLong()) >= 0, "device lost its zone");
            helper.assertValueEqual(retry(device), 0, "device waits after the patch");
            // The rerouted device took its channel on its new route without a full count (M7N).
            helper.assertTrue(device.network() == network && device.deviceLink().holdsChannel(), "device's channel");
            helper.assertValueEqual(network.channelsHeld(), 1, "channels after the patch");
            helper.assertValueEqual(network.segmentChannels(device.nodePos()), 1, "the device's own segment");
            helper.assertValueEqual(network.nodeCount(), 19, "nodes after the removal");
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(broken[0]),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }).thenIdle(5).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.nodeCount(), 20, "nodes after placing back");
            helper.assertValueEqual(network.rebuilds(), before[0], "full rebuilds after placing back");
        }).thenSucceed();
    }

    /** The cable at x = 5 on the device's route, in whichever row it runs. */
    private static BlockPos onRoute(GameTestHelper helper, NetworkState network, TestStorage.TestDevice device) {
        BlockPos row1 = new BlockPos(5, 1, 1);
        BlockPos row2 = new BlockPos(5, 1, 2);
        long node = device.getBlockPos().asLong();
        for (int i = 0; i < 64 && !network.isRouteRoot(node); i++) {
            if (node == helper.absolutePos(row1).asLong()) {
                return row1;
            }
            if (node == helper.absolutePos(row2).asLong()) {
                return row2;
            }
            node = network.routeParent(node);
        }
        throw helper.assertionException(Component.literal("x = 5 not on the device's route"));
    }

    /**
     * On the test network type: two devices at the ends of a ribbon of cables. Two cables of one row unload. In
     * truncate mode the network keeps running over the other row, patched without a rebuild; in pause mode it pauses.
     * Loaded again, both devices work again.
     */
    private static void patchUnloaded(GameTestHelper helper, UnloadedChunkMode mode) {
        helper.assertValueEqual(ServerConfig.UNLOADED_CHUNK_MODE.get(), mode, "mode set by the environment");
        TestStorage.TestDevice near = NetworkGameTests.simDevice(helper, NetworkGameTests.at(1));
        ribbon(helper, 10, TestStorage.SIM_CABLE.get());
        TestStorage.TestDevice far = NetworkGameTests.simDevice(helper, NetworkGameTests.at(11));
        List<BlockPos> unloaded = List.of(helper.absolutePos(NetworkGameTests.at(5)),
                helper.absolutePos(NetworkGameTests.at(6)));
        NetworkManager manager = NetworkManager.get(helper.getLevel());
        int[] before = new int[2];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(NetworkGameTests.net(helper, near) == far.network(), "network not formed");
        }).thenIdle(10).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, near);
            before[0] = network.rebuilds();
            before[1] = network.patches();
            for (BlockPos pos : unloaded) {
                TestStorage.UNLOADED.add(pos.asLong());
                manager.removeNode(TestStorage.SIM, pos);
            }
        }).thenIdle(3).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, near);
            helper.assertTrue(far.network() == network, "the other row keeps the network whole");
            // The graph reports the change even to a paused network, so both modes patch.
            helper.assertValueEqual(network.rebuilds(), before[0], "full rebuilds while unloaded");
            helper.assertTrue(network.patches() > before[1], "no patch while unloaded");
            if (mode == UnloadedChunkMode.TRUNCATE) {
                helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status while truncated");
                powerAdHoc(near);
                helper.assertValueEqual(retry(far), 0, "far device waits while truncated");
            } else {
                helper.assertValueEqual(network.status(), StorageNetwork.Status.PAUSED, "status while paused");
            }
        }).thenExecute(() -> {
            for (BlockPos pos : unloaded) {
                TestStorage.UNLOADED.remove(pos.asLong());
                manager.addNode(TestStorage.SIM, pos);
            }
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, near);
            helper.assertTrue(network == far.network(), "not joined again");
            helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status after loading");
            helper.assertValueEqual(network.nodeCount(), 20, "nodes after loading");
        }).thenExecute(() -> {
            powerAdHoc(near);
            helper.assertValueEqual(retry(far), 0, "far device waits after loading");
        }).thenSucceed();
    }

    private static void powerAdHoc(TestStorage.TestDevice device) {
        try (Transaction transaction = Transaction.openRoot()) {
            device.energyInput().insert(500, transaction);
            transaction.commit();
        }
    }

    private static ServerPlayer owned(GameTestHelper helper, String name, BlockPos... positions) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), name);
        for (BlockPos pos : positions) {
            BlockEntity entity = helper.getBlockEntity(pos, BlockEntity.class);
            if (entity instanceof StorageDeviceBlockEntity device) {
                device.setOwner(OwnerRecord.of(owner));
            }
        }
        return owner;
    }

    /**
     * Device cap 3 per team: an owned controller with four owned devices takes three and turns the fourth away with the
     * team line; a second network of the same owner takes none.
     */
    private static void deviceCap(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt(), 3, "cap from the environment");
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        List<BlockPos> positions = new ArrayList<>();
        positions.add(controller);
        for (int x = 2; x <= 5; x++) {
            BlockPos pos = new BlockPos(x, 1, 2);
            devices.add(NetworkGameTests.device(helper, pos));
            positions.add(pos);
        }
        BlockPos second = new BlockPos(1, 1, 10);
        helper.setBlock(second, ControllerContent.CONTROLLER.get());
        BlockPos secondDevice = new BlockPos(2, 1, 10);
        TestStorage.TestDevice lone = NetworkGameTests.device(helper, secondDevice);
        positions.add(second);
        positions.add(secondDevice);
        helper.startSequence().thenIdle(2)
                .thenExecute(() -> owned(helper, "cap_owner", positions.toArray(BlockPos[]::new))).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.limitedDevices(), 3, "devices under the team cap");
                    int refused = 0;
                    for (TestStorage.TestDevice device : devices) {
                        if (device.network() == null && device.deviceLink().refusedBy() == network) {
                            refused++;
                        }
                    }
                    helper.assertValueEqual(refused, 1, "devices turned away");
                    helper.assertTrue(network.teamCapped(), "the team cap is what limits");
                    helper.assertTrue(lone.network() == null, "the second network took a device past the cap");
                }).thenExecute(() -> {
                    for (int i = 0; i < devices.size(); i++) {
                        if (devices.get(i).network() == null) {
                            List<String> keys = keys(helper, positions.get(i + 1));
                            helper.assertTrue(keys.contains(NetworkInfo.TEAM_FULL_KEY), "team line: " + keys);
                        }
                    }
                }).thenSucceed();
    }

    /**
     * Zone cap 2 per team: two owned networks of one zone each route; a third gets no zone and its device, without a
     * route, stays offline (M7N) and says so.
     */
    private static void zoneCap(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt(), 2, "cap from the environment");
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "zone_owner");
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        List<BlockPos> controllers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            controllers.add(new BlockPos(1, 1, 1 + i * 6));
        }
        helper.startSequence().thenExecute(() -> {
            for (int i = 0; i < 2; i++) {
                devices.add(network(helper, controllers.get(i), owner));
            }
        }).thenWaitUntil(() -> {
            for (int i = 0; i < 2; i++) {
                NetworkState network = NetworkGameTests.state(helper, controllers.get(i));
                helper.assertValueEqual(network.zoneCount(), 1, "zones of network " + i);
                helper.assertTrue(network.capTeam() != null, "owned network " + i);
            }
        }).thenExecute(() -> devices.add(network(helper, controllers.get(2), owner))).thenWaitUntil(() -> {
            NetworkState third = NetworkGameTests.state(helper, controllers.get(2));
            helper.assertTrue(devices.get(2).deviceLink().offlineBy() == third, "third device not offline");
            helper.assertValueEqual(devices.get(2).deviceLink().channelShortage().orElseThrow(),
                    ChannelShortage.NO_ROUTE, "third device's reason");
            helper.assertValueEqual(third.zoneCount(), 0, "zones past the cap");
        }).thenExecute(() -> {
            for (int i = 0; i < 3; i++) {
                NetworkGameTests.power(helper, controllers.get(i), 1000);
            }
            helper.assertValueEqual(retry(devices.get(0)), 0, "first network routes");
            helper.assertValueEqual(retry(devices.get(2)), -1, "third network's device has no transfer");
        }).thenSucceed();
    }

    /**
     * Zone cap 2 per team with a team mod (FTB Teams; here the logic tests' fake team source): two players in one team
     * share the cap, so the team's third network gets no zone whichever of them owns it, while a player outside the
     * team keeps a cap of their own.
     */
    private static void teamZoneCap(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt(), 2, "cap from the environment");
        LogicActionGameTests.installFakeTeams();
        ServerPlayer first = NetworkGameTests.player(helper.getLevel(), "team_zone_first");
        ServerPlayer second = NetworkGameTests.player(helper.getLevel(), "team_zone_second");
        ServerPlayer outsider = NetworkGameTests.player(helper.getLevel(), "team_zone_outsider");
        UUID team = UUID.randomUUID();
        LogicActionGameTests.FAKE_TEAMS.put(first.getUUID(), team);
        LogicActionGameTests.FAKE_TEAMS.put(second.getUUID(), team);
        helper.assertTrue(team.equals(TeamCaps.team(helper.getLevel(), OwnerRecord.of(second))), "the shared team");
        helper.assertTrue(outsider.getUUID().equals(TeamCaps.team(helper.getLevel(), OwnerRecord.of(outsider))),
                "the outsider's team");
        ServerPlayer[] owners = {first, second, second, outsider};
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        List<BlockPos> controllers = new ArrayList<>();
        for (int i = 0; i < owners.length; i++) {
            controllers.add(new BlockPos(1, 1, 1 + i * 6));
        }
        helper.startSequence().thenExecute(() -> {
            for (int i = 0; i < 2; i++) {
                devices.add(network(helper, controllers.get(i), owners[i]));
            }
        }).thenWaitUntil(() -> {
            for (int i = 0; i < 2; i++) {
                NetworkState network = NetworkGameTests.state(helper, controllers.get(i));
                helper.assertValueEqual(network.zoneCount(), 1, "zones of network " + i);
                helper.assertTrue(team.equals(network.capTeam()), "team of network " + i);
            }
        }).thenExecute(() -> {
            for (int i = 2; i < owners.length; i++) {
                devices.add(network(helper, controllers.get(i), owners[i]));
            }
        }).thenWaitUntil(() -> {
            NetworkState capped = NetworkGameTests.state(helper, controllers.get(2));
            helper.assertTrue(devices.get(2).deviceLink().offlineBy() == capped, "the team's third device not offline");
            NetworkState outsiders = NetworkGameTests.state(helper, controllers.get(3));
            helper.assertTrue(devices.get(3).network() == outsiders, "the outsider's device joined");
            helper.assertValueEqual(NetworkGameTests.state(helper, controllers.get(2)).zoneCount(), 0,
                    "zones of the team's third network");
            helper.assertValueEqual(NetworkGameTests.state(helper, controllers.get(3)).zoneCount(), 1,
                    "zones of the outsider's network");
        }).thenExecute(() -> {
            LogicActionGameTests.FAKE_TEAMS.remove(first.getUUID());
            LogicActionGameTests.FAKE_TEAMS.remove(second.getUUID());
        }).thenSucceed();
    }

    /** An owned controller, one cable east of it and a device after that, all owned by {@code owner}. */
    private static TestStorage.TestDevice network(GameTestHelper helper, BlockPos controller, ServerPlayer owner) {
        helper.setBlock(controller, ControllerContent.CONTROLLER.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(controller.east()),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, controller.east(2));
        NetworkGameTests.controllerEntity(helper, controller).setOwner(OwnerRecord.of(owner));
        device.setOwner(OwnerRecord.of(owner));
        return device;
    }

    /**
     * Byte cap 10 per team: an owned network stores 10 items (a byte each), then, once the team's bytes are summed
     * again, refuses to store more and says so, while taking out still works; with room again it stores.
     */
    private static void byteCap(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.CAP_BYTES_PER_TEAM.getAsLong(), 10L, "cap from the environment");
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        device.provider.countBytes();
        helper.startSequence().thenExecute(() -> owned(helper, "byte_owner", controller, NetworkGameTests.at(2)))
                .thenWaitUntil(() -> helper.assertTrue(
                        NetworkGameTests.state(helper, controller).capTeam() != null && device.network() != null,
                        "owned network"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, controller, 10_000);
                    helper.assertValueEqual(insert(helper, controller, 10), 10L, "stored under the cap");
                }).thenIdle(25).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertTrue(network.bytesCapped(), "at the cap");
                    helper.assertValueEqual(insert(helper, controller, 1), 0L, "stored past the cap");
                    helper.assertTrue(keys(helper, controller).contains(NetworkInfo.BYTES_CAPPED_KEY),
                            "cap line on the controller");
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(network.extract(COBBLE, 5, transaction), 5L, "taken at the cap");
                        transaction.commit();
                    }
                }).thenIdle(25)
                .thenExecute(() -> helper.assertValueEqual(insert(helper, controller, 1), 1L, "stored with room again"))
                .thenSucceed();
    }

    private static long insert(GameTestHelper helper, BlockPos controller, long amount) {
        return NetworkGameTests.insert(NetworkGameTests.state(helper, controller), COBBLE, amount);
    }

    /**
     * A drive with two cells at the end of a cable line: when its chunk unloads (the hooks NeoForge calls, in order),
     * its cells leave the network and the controller says "2 cells offline"; when it loads again they are back and the
     * line is gone.
     */
    private static void offlineCells(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos drivePos = NetworkGameTests.at(5);
        DriveBlockEntity drive = CellGameTests.drive(helper, drivePos);
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        CompoundTag[] saved = new CompoundTag[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        drive.network() == NetworkGameTests.state(helper, controller) && drive.providers().size() == 2,
                        "drive with cells in the network"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, controller, 10_000);
                    helper.assertValueEqual(insert(helper, controller, 20), 20L, "stored in the cells");
                    saved[0] = drive.saveWithFullMetadata(helper.getLevel().registryAccess());
                    drive.onChunkUnloaded();
                    helper.getLevel().removeBlockEntity(helper.absolutePos(drivePos));
                    // In a real unload nothing is left at the position; here a later block entity lookup would make
                    // a fresh, empty drive, so the block goes too.
                    helper.setBlock(drivePos, Blocks.AIR);
                }).thenIdle(2).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.offlineCells(), 2, "offline cells");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "offline contents");
                    List<String> keys = keys(helper, controller);
                    helper.assertTrue(keys.contains(NetworkInfo.OFFLINE_KEY), "offline line: " + keys);
                    helper.setBlock(drivePos, DriveContent.DRIVE.get());
                    BlockState state = helper.getBlockState(drivePos);
                    BlockEntity loaded = BlockEntity.loadStatic(helper.absolutePos(drivePos), state, saved[0],
                            helper.getLevel().registryAccess());
                    if (loaded == null) {
                        throw helper.assertionException(Component.literal("drive did not load"));
                    }
                    helper.getLevel().setBlockEntity(loaded);
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 20L, "contents after loading");
                    helper.assertValueEqual(network.offlineCells(), 0, "offline cells after loading");
                    helper.assertFalse(keys(helper, controller).contains(NetworkInfo.OFFLINE_KEY),
                            "offline line after loading");
                }).thenSucceed();
    }

    private static List<String> keys(GameTestHelper helper, BlockPos pos) {
        BlockInfoProvider provider = InfoLookups.BLOCK.find(helper.getLevel(), helper.absolutePos(pos), null);
        List<String> keys = new ArrayList<>();
        if (provider == null) {
            return keys;
        }
        provider.addInfo(new BlockInfoRequest(helper.getLevel(), helper.absolutePos(pos), null, null,
                BlockInfoRequest.Detail.FULL), line -> keys.add(key(line)));
        return keys;
    }

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents translatable
                ? translatable.getKey()
                : line.getString();
    }
}
