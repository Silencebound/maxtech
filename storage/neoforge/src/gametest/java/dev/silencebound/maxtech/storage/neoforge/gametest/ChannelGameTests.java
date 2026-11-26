package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Channels (M7N, PRD 7.2 Channels with hubs): each cable tier carries its channels and a root link 64; over a limit the
 * devices that joined last go offline and name the full cable or link; the join order survives unloading and loading
 * the devices; a better cable brings offline devices back; and an offline device says why in its info lines.
 *
 * <p>
 * Every layout is a controller, one "bottleneck" cable of the tier under test, then a tier-4 trunk along x with up to
 * four devices beside each trunk cable, so the bottleneck (or the root link behind it) is the only limit.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ChannelGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String CAPACITY = "channels_capacity_per_tier";
    private static final String RELOAD = "channels_newest_offline_after_reload";
    private static final String UPGRADE = "channels_cable_upgrade";
    private static final String REASON = "channels_offline_reason_info";
    private static final String UPLINK = "channels_subnet_uplink_one_channel";
    private static final String PORT_OFFLINE = "channels_port_offline_mid_run";
    private static final String ROOT_LINK = "channels_root_link_cap";
    private static final List<String> NAMES = List.of(CAPACITY, RELOAD, UPGRADE, REASON, UPLINK, PORT_OFFLINE);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** The rows of the capacity test, one per tier, by z. */
    private static final int[] ROW_Z = {1, 7, 13, 19};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(CAPACITY, StorageTestEnvironment.clearing(ChannelGameTests::capacityPerTier));
        StorageTests.function(RELOAD, StorageTestEnvironment.clearing(ChannelGameTests::newestOfflineAfterReload));
        StorageTests.function(UPGRADE, StorageTestEnvironment.clearing(ChannelGameTests::cableUpgrade));
        StorageTests.function(REASON, StorageTestEnvironment.clearing(ChannelGameTests::offlineReason));
        StorageTests.function(UPLINK, StorageTestEnvironment.clearing(ChannelGameTests::subnetUplink));
        StorageTests.function(PORT_OFFLINE, StorageTestEnvironment.clearing(ChannelGameTests::portOfflineMidRun));
        StorageTests.function(ROOT_LINK, StorageTestEnvironment.clearing(ChannelGameTests::rootLinkCap));
    }

    private ChannelGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("channels"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
        // A root link narrower than the cable on it, so the link and not the cable is what fills.
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(ROOT_LINK),
                        new StorageTestEnvironment("network.channels.rootLink", "4", false)),
                ROOT_LINK, STRUCTURE, 400);
    }

    /**
     * With root links of 4 channels, six devices behind a tier-2 cable (16) at the controller: four hold channels, and
     * the two newest are offline because the controller's root link is full.
     */
    private static void rootLinkCap(GameTestHelper helper) {
        helper.assertValueEqual(
                dev.silencebound.maxtech.storage.config.StorageServerConfig.ROOT_LINK_CHANNELS.getAsInt(), 4,
                "root link channels from the environment");
        BlockPos controller = layout(helper, 1, CableContent.STORAGE_CABLE_TIER_2.type().get());
        for (BlockPos pos : positions(1, 3, 4).subList(0, 6)) {
            NetworkGameTests.device(helper, pos);
        }
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.channelsHeld(), 4, "channels held");
            helper.assertValueEqual(network.offlineDevices().size(), 2, "devices offline");
            for (NetworkDevice device : network.offlineDevices()) {
                ChannelShortage shortage = device.deviceLink().channelShortage().orElseThrow();
                helper.assertValueEqual(shortage.kind(), ChannelShortage.Kind.ROOT_LINK, "what is full");
                helper.assertValueEqual(shortage.pos(), helper.absolutePos(new BlockPos(2, 1, 1)).asLong(),
                        "the root link's outside end");
                helper.assertValueEqual(shortage.used(), 4, "used");
                helper.assertValueEqual(shortage.capacity(), 4, "capacity");
            }
            helper.assertValueEqual(network.segmentCapacity(helper.absolutePos(new BlockPos(2, 1, 1)).asLong()), 4,
                    "the root link carries its 4, not the cable's 16");
        }).thenSucceed();
    }

    /**
     * A subnet with three devices hangs off a parent network that has one device of its own: the subnet controller's
     * uplink uses exactly one channel in the parent, the subnet's devices count only in the subnet.
     */
    private static void subnetUplink(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        NetworkGameTests.device(helper, new BlockPos(3, 1, 0));
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<TestStorage.TestDevice> inner = new ArrayList<>();
        for (BlockPos pos : List.of(NetworkGameTests.at(8), new BlockPos(7, 1, 0), new BlockPos(7, 1, 2))) {
            inner.add(NetworkGameTests.device(helper, pos));
        }
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            helper.assertTrue(subnet.parent() == parent, "uplink not in the parent");
            NetworkState child = NetworkGameTests.net(helper, inner.get(0));
            helper.assertTrue(child != parent, "the subnet is its own network");
            helper.assertValueEqual(child.channelsHeld(), 3, "channels in the subnet");
            helper.assertValueEqual(parent.channelsHeld(), 2, "channels in the parent: its device and the uplink");
            helper.assertValueEqual(parent.segmentChannels(helper.absolutePos(NetworkGameTests.at(4)).asLong()), 1,
                    "channels on the parent cable next to the subnet");
            helper.assertValueEqual(parent.segmentChannels(helper.absolutePos(NetworkGameTests.at(2)).asLong()), 2,
                    "channels on the parent's root link");
        }).thenSucceed();
    }

    /**
     * An import port moving a chest of cobblestone into the network goes offline mid-run when an older device comes
     * back from an unload and takes its channel: while offline it moves nothing, and nothing is lost or made at any
     * point; once room frees it comes back and moves the rest.
     */
    private static void portOfflineMidRun(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<BlockPos> spots = positions(1, 3, 4);
        List<BlockPos> old = spots.subList(0, 8);
        for (BlockPos pos : old) {
            NetworkGameTests.device(helper, pos);
        }
        BlockPos portPos = new BlockPos(10, 2, 1);
        BlockPos chestPos = portPos.above();
        int total = 27 * 64;
        CompoundTag[] saved = new CompoundTag[1];
        int[] chestWhenOffline = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelsHeld(),
                        8, "eight devices hold channels"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, controller, 1_000_000);
                    saved[0] = unload(helper, old.get(0));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelsHeld(),
                        7, "seven after the unload"))
                .thenExecute(() -> {
                    helper.setBlock(chestPos, Blocks.CHEST);
                    ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        chest.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                    }
                    helper.setBlock(portPos, PortContent.PORT.get().defaultBlockState()
                            .setValue(PortBlock.FACING, Direction.UP).setValue(PortBlock.MODE, PortMode.IMPORT));
                }).thenWaitUntil(() -> {
                    helper.assertTrue(chestCount(helper, chestPos) < total, "the port moved nothing yet");
                    assertTotal(helper, controller, chestPos, total);
                }).thenExecute(() -> load(helper, old.get(0), saved[0])).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    PortBlockEntity port = helper.getBlockEntity(portPos, PortBlockEntity.class);
                    helper.assertTrue(port.deviceLink().offlineBy() == network, "the port is not offline");
                    helper.assertTrue(device(helper, old.get(0)).network() == network, "the old device is offline");
                }).thenExecute(() -> {
                    assertTotal(helper, controller, chestPos, total);
                    chestWhenOffline[0] = chestCount(helper, chestPos);
                    helper.assertTrue(chestWhenOffline[0] > 0, "the chest emptied before the port went offline");
                }).thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(chestCount(helper, chestPos), chestWhenOffline[0],
                            "items moved while the port was offline");
                    assertTotal(helper, controller, chestPos, total);
                    helper.destroyBlock(old.get(1));
                    // The broken device's contents leave with it: count the rest from here on.
                }).thenWaitUntil(() -> {
                    PortBlockEntity port = helper.getBlockEntity(portPos, PortBlockEntity.class);
                    helper.assertTrue(port.network() != null, "the port is not back online");
                    helper.assertTrue(chestCount(helper, chestPos) < chestWhenOffline[0],
                            "the port moved nothing more");
                }).thenSucceed();
    }

    private static int chestCount(GameTestHelper helper, BlockPos pos) {
        ChestBlockEntity chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            count += chest.getItem(i).getCount();
        }
        return count;
    }

    /** What the chest and the network hold together: never more or less than {@code total}. */
    private static void assertTotal(GameTestHelper helper, BlockPos controller, BlockPos chest, int total) {
        long stored = NetworkGameTests.state(helper, controller).contents().amount(COBBLE);
        helper.assertValueEqual(chestCount(helper, chest) + stored, (long) total, "cobblestone in chest and network");
    }

    /**
     * Four rows, one per tier of bottleneck cable, each with two devices more than the tier carries: tiers 1 to 3 let
     * 8, 16 and 32 devices online and name the bottleneck cable as full; tier 4 lets 64 online, and its root link,
     * which carries 64 too, is what is full. No device limit gets in the way (M7R item 1).
     */
    private static void capacityPerTier(GameTestHelper helper) {
        int[] capacity = {8, 16, 32, 64};
        List<List<TestStorage.TestDevice>> rows = new ArrayList<>();
        for (int tier = 1; tier <= 4; tier++) {
            int z = ROW_Z[tier - 1];
            rows.add(row(helper, z,
                    CableContent.cable(dev.silencebound.maxtech.storage.cable.CableLook.PLAIN, tier).type().get(),
                    capacity[tier - 1] + 2));
        }
        helper.startSequence().thenWaitUntil(() -> {
            for (int tier = 1; tier <= 4; tier++) {
                int z = ROW_Z[tier - 1];
                NetworkState network = NetworkGameTests.state(helper, new BlockPos(1, 1, z));
                List<TestStorage.TestDevice> devices = rows.get(tier - 1);
                int online = 0;
                int offline = 0;
                for (TestStorage.TestDevice device : devices) {
                    if (device.network() == network) {
                        online++;
                    } else if (device.deviceLink().offlineBy() == network) {
                        offline++;
                        ChannelShortage shortage = device.deviceLink().channelShortage().orElseThrow();
                        helper.assertValueEqual(shortage.kind(),
                                tier == 4 ? ChannelShortage.Kind.ROOT_LINK : ChannelShortage.Kind.CABLE,
                                "what is full at tier " + tier);
                        helper.assertValueEqual(shortage.pos(), helper.absolutePos(new BlockPos(2, 1, z)).asLong(),
                                "where at tier " + tier);
                        helper.assertValueEqual(shortage.used(), capacity[tier - 1], "used at tier " + tier);
                        helper.assertValueEqual(shortage.capacity(), capacity[tier - 1], "capacity at tier " + tier);
                    }
                }
                helper.assertValueEqual(online, capacity[tier - 1], "devices online at tier " + tier);
                helper.assertValueEqual(offline, 2, "devices offline at tier " + tier);
                helper.assertValueEqual(network.channelsHeld(), capacity[tier - 1], "channels held at tier " + tier);
                helper.assertValueEqual(network.segmentChannels(helper.absolutePos(new BlockPos(2, 1, z)).asLong()),
                        capacity[tier - 1], "channels on the bottleneck at tier " + tier);
            }
        }).thenSucceed();
    }

    /**
     * Eight devices fill a tier-1 bottleneck; two placed later, nearer the controller (lower positions), go offline:
     * the newest go, whatever their position. One old device unloads and its channel goes to the oldest waiting device;
     * once it loads again, older than that one, a recount gives it its channel back. Then every device unloads and
     * loads again from its saved data: the same two stay offline.
     */
    private static void newestOfflineAfterReload(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<BlockPos> old = new ArrayList<>(positions(1, 5, 6));
        List<BlockPos> late = List.of(new BlockPos(3, 1, 0), new BlockPos(3, 1, 2));
        List<BlockPos> all = new ArrayList<>(old);
        all.addAll(late);
        for (BlockPos pos : old) {
            NetworkGameTests.device(helper, pos);
        }
        CompoundTag[] saved = new CompoundTag[all.size()];
        long[] seq = new long[all.size()];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelsHeld(),
                        8, "eight devices hold channels"))
                .thenExecute(() -> {
                    for (BlockPos pos : late) {
                        NetworkGameTests.device(helper, pos);
                    }
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    for (BlockPos pos : late) {
                        helper.assertTrue(device(helper, pos).deviceLink().offlineBy() == network,
                                "a late device is not offline");
                    }
                    for (BlockPos pos : old) {
                        helper.assertTrue(device(helper, pos).network() == network, "an old device lost its channel");
                    }
                }).thenExecute(() -> {
                    for (int i = 0; i < all.size(); i++) {
                        seq[i] = device(helper, all.get(i)).deviceLink().joinSeq();
                    }
                    saved[0] = unload(helper, old.get(0));
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    int online = 0;
                    for (BlockPos pos : late) {
                        online += device(helper, pos).network() == network ? 1 : 0;
                    }
                    helper.assertValueEqual(online, 1, "late devices online while an old one is unloaded");
                }).thenExecute(() -> load(helper, old.get(0), saved[0])).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertTrue(device(helper, old.get(0)).network() == network,
                            "the reloaded device is offline");
                    for (BlockPos pos : late) {
                        helper.assertTrue(device(helper, pos).deviceLink().offlineBy() == network,
                                "a late device kept the reloaded one's channel");
                    }
                }).thenExecute(() -> {
                    for (int i = 0; i < all.size(); i++) {
                        saved[i] = unload(helper, all.get(i));
                    }
                }).thenIdle(5).thenExecute(() -> {
                    for (int i = all.size() - 1; i >= 0; i--) {
                        load(helper, all.get(i), saved[i]);
                    }
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    for (int i = 0; i < all.size(); i++) {
                        TestStorage.TestDevice device = device(helper, all.get(i));
                        helper.assertValueEqual(device.deviceLink().joinSeq(), seq[i], "saved join order");
                        if (late.contains(all.get(i))) {
                            helper.assertTrue(device.deviceLink().offlineBy() == network,
                                    "a late device online after loading");
                        } else {
                            helper.assertTrue(device.network() == network, "an old device offline after loading");
                        }
                    }
                }).thenSucceed();
    }

    /**
     * Ten devices behind a tier-1 bottleneck: two are offline. Replacing the bottleneck with a tier-2 cable (16
     * channels) brings both back online.
     */
    private static void cableUpgrade(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (BlockPos pos : positions(1, 3, 5).subList(0, 10)) {
            devices.add(NetworkGameTests.device(helper, pos));
        }
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.channelsHeld(), 8, "channels behind tier 1");
            helper.assertValueEqual(network.offlineDevices().size(), 2, "offline behind tier 1");
        }).thenExecute(() -> {
            helper.destroyBlock(new BlockPos(2, 1, 1));
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 1)),
                    CableContent.STORAGE_CABLE_TIER_2.type().get(), null);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.channelsHeld(), 10, "channels behind tier 2");
            for (TestStorage.TestDevice device : devices) {
                helper.assertTrue(device.network() == network, "a device still offline");
                helper.assertTrue(device.deviceLink().channelShortage().isEmpty(), "a reason kept");
            }
        }).thenSucceed();
    }

    /**
     * A ninth device behind a tier-1 bottleneck says in its info lines (Jade, WTHIT, the analyzer) that the cable at
     * the bottleneck is full, with its position and 8 of 8 channels, and that it is in no network.
     */
    private static void offlineReason(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<BlockPos> positions = positions(1, 3, 5);
        for (int i = 0; i < 8; i++) {
            NetworkGameTests.device(helper, positions.get(i));
        }
        BlockPos ninth = positions.get(8);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelsHeld(),
                        8, "eight devices hold channels"))
                .thenExecute(() -> NetworkGameTests.device(helper, ninth))
                .thenWaitUntil(() -> helper.assertTrue(device(helper, ninth).deviceLink().offlineBy() != null,
                        "the ninth device is not offline"))
                .thenExecute(() -> {
                    List<Component> lines = lines(helper, ninth);
                    TranslatableContents offline = null;
                    List<String> keys = new ArrayList<>();
                    for (Component line : lines) {
                        if (line.getContents() instanceof TranslatableContents translatable) {
                            keys.add(translatable.getKey());
                            if (translatable.getKey().equals(NetworkInfo.offlineKey(ChannelShortage.Kind.CABLE))) {
                                offline = translatable;
                            }
                        }
                    }
                    if (offline == null) {
                        throw helper.assertionException(Component.literal("no offline line: " + keys));
                    }
                    helper.assertTrue(keys.contains(NetworkInfo.NO_NETWORK_KEY), "no-network line: " + keys);
                    BlockPos full = helper.absolutePos(new BlockPos(2, 1, 1));
                    Object[] args = offline.getArgs();
                    helper.assertValueEqual(String.valueOf(args[0]), String.valueOf(full.getX()), "x");
                    helper.assertValueEqual(String.valueOf(args[1]), String.valueOf(full.getY()), "y");
                    helper.assertValueEqual(String.valueOf(args[2]), String.valueOf(full.getZ()), "z");
                    helper.assertValueEqual(String.valueOf(args[3]), "8", "used");
                    helper.assertValueEqual(String.valueOf(args[4]), "8", "capacity");
                }).thenSucceed();
    }

    /**
     * A controller at x 1 on row {@code z}, a bottleneck cable of {@code bottleneck} at x 2 and a tier-4 trunk at x 3
     * to 20. Returns the controller.
     */
    private static BlockPos layout(GameTestHelper helper, int z, ConduitType bottleneck) {
        BlockPos controller = new BlockPos(1, 1, z);
        helper.setBlock(controller, dev.silencebound.maxtech.storage.controller.ControllerContent.CONTROLLER.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, z)), bottleneck, null);
        for (int x = 3; x <= 20; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, z)),
                    CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
        }
        return controller;
    }

    /** The layout on row {@code z} with {@code count} devices beside the trunk, placed at once. */
    private static List<TestStorage.TestDevice> row(GameTestHelper helper, int z, ConduitType bottleneck, int count) {
        layout(helper, z, bottleneck);
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        List<BlockPos> positions = positions(z, 3, 3 + (count + 3) / 4);
        for (int i = 0; i < count; i++) {
            devices.add(NetworkGameTests.device(helper, positions.get(i)));
        }
        return devices;
    }

    /** Four spots beside each trunk cable of row {@code z} from x {@code from} to {@code to}, nearest first. */
    private static List<BlockPos> positions(int z, int from, int to) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = from; x <= to; x++) {
            out.add(new BlockPos(x, 1, z - 1));
            out.add(new BlockPos(x, 1, z + 1));
            out.add(new BlockPos(x, 2, z));
            out.add(new BlockPos(x, 0, z));
        }
        return out;
    }

    private static TestStorage.TestDevice device(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, TestStorage.TestDevice.class);
    }

    /** Saves the device at {@code pos} and takes it out of its chunk, as an unload does. */
    private static CompoundTag unload(GameTestHelper helper, BlockPos pos) {
        TestStorage.TestDevice device = device(helper, pos);
        CompoundTag tag = device.saveWithFullMetadata(helper.getLevel().registryAccess());
        device.onChunkUnloaded();
        BlockPos abs = helper.absolutePos(pos);
        helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
        return tag;
    }

    /** Loads the device at {@code pos} from {@code tag} into its chunk, as a chunk load does. */
    private static void load(GameTestHelper helper, BlockPos pos, CompoundTag tag) {
        BlockPos abs = helper.absolutePos(pos);
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), tag,
                helper.getLevel().registryAccess());
        if (loaded == null) {
            throw helper.assertionException(Component.literal("the device did not load"));
        }
        helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(loaded);
    }

    private static List<Component> lines(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockInfoProvider provider = InfoLookups.BLOCK.find(helper.getLevel(), absolute, null);
        if (provider == null) {
            throw helper.assertionException(Component.literal("No block info at " + pos.toShortString()));
        }
        List<Component> lines = new ArrayList<>();
        provider.addInfo(new BlockInfoRequest(helper.getLevel(), absolute, null, null, BlockInfoRequest.Detail.FULL),
                lines::add);
        return lines;
    }
}
