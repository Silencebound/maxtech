package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.hub.HubBlockEntity;
import dev.silencebound.maxtech.storage.hub.HubContent;
import dev.silencebound.maxtech.storage.hub.HubTier;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Hubs (M7N item 2, PRD 7.2 Channels with hubs): the devices behind a hub count against the hub and use one channel
 * upstream; each tier holds its channels; over a full hub the newest devices go offline and name the hub, and a better
 * hub brings them back; a hub never splits a network, and hubs nest.
 *
 * <p>
 * Most layouts are a controller at x 1, a tier-1 cable at x 2 (8 channels, so anything past 8 devices proves the hub
 * took them), the hub at x 3 and a tier-4 trunk from x 4 with up to four devices beside each trunk cable.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class HubGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String BRANCH = "hubs_branch_one_upstream_channel";
    private static final String CAPACITY = "hubs_capacity_per_tier";
    private static final String FULL = "hubs_full_newest_offline_then_upgrade";
    private static final String NO_SPLIT = "hubs_never_split_and_nest";
    private static final String RELOAD = "hubs_state_after_reload";
    private static final String REMOVED = "hubs_removed_full_falls_back";
    private static final List<String> PLAIN = List.of(BRANCH, FULL, NO_SPLIT, RELOAD, REMOVED);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final int[] ROW_Z = {1, 7, 13};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(BRANCH, StorageTestEnvironment.clearing(HubGameTests::branch));
        StorageTests.function(CAPACITY, StorageTestEnvironment.clearing(HubGameTests::capacityPerTier));
        StorageTests.function(FULL, StorageTestEnvironment.clearing(HubGameTests::fullThenUpgrade));
        StorageTests.function(NO_SPLIT, StorageTestEnvironment.clearing(HubGameTests::neverSplit));
        StorageTests.function(RELOAD, StorageTestEnvironment.clearing(HubGameTests::stateAfterReload));
        StorageTests.function(REMOVED, StorageTestEnvironment.clearing(HubGameTests::removedFallsBack));
    }

    private HubGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("hubs"),
                StorageTestEnvironment.plain());
        for (String name : PLAIN) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(CAPACITY), new StorageTestEnvironment("", "", false)),
                CAPACITY, STRUCTURE, 400);
    }

    /**
     * Twelve devices behind a tier-1 hub, which sits behind a tier-1 cable of 8 channels: all twelve are online, the
     * hub counts twelve, and the cable towards the controller carries one channel, the hub's.
     */
    private static void branch(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, HubTier.TIER_1, 4);
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (BlockPos pos : positions(1, 4, 6)) {
            devices.add(NetworkGameTests.device(helper, pos));
        }
        long hub = helper.absolutePos(new BlockPos(3, 1, 1)).asLong();
        long bottleneck = helper.absolutePos(new BlockPos(2, 1, 1)).asLong();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            for (TestStorage.TestDevice device : devices) {
                helper.assertTrue(device.network() == network, "a device behind the hub is offline");
            }
            helper.assertValueEqual(network.channelsHeld(), 12, "devices holding channels");
            helper.assertTrue(network.isHub(hub), "the hub has no budget");
            helper.assertValueEqual(network.hubChannels(hub), 12, "devices counting against the hub");
            helper.assertValueEqual(network.hubCapacity(hub), 16, "a tier-1 hub's budget");
            helper.assertValueEqual(network.segmentChannels(bottleneck), 1, "channels on the cable before the hub");
            helper.assertValueEqual(network.segmentChannels(hub), 1, "channels on the hub's own link");
            helper.assertValueEqual(network.limitedDevices(), 12, "the hub is no device for the limit");
        }).thenSucceed();
    }

    /**
     * One row per hub tier, each with two devices more than the hub holds (the tier-3 hub feeds two branches, since a
     * tier-4 cable carries 64): 16, 32 and 64 devices are online, and the two newest of each row are offline and name
     * their hub, full at its budget.
     */
    private static void capacityPerTier(GameTestHelper helper) {
        int[] capacity = {16, 32, 64};
        List<List<TestStorage.TestDevice>> rows = new ArrayList<>();
        for (HubTier tier : HubTier.values()) {
            int z = ROW_Z[tier.level() - 1];
            int count = capacity[tier.level() - 1] + 2;
            layout(helper, z, tier, 20);
            List<BlockPos> spots = new ArrayList<>();
            if (tier == HubTier.TIER_3) {
                // A second branch from the hub's south face, along z.
                for (int bz = z + 1; bz <= z + 10; bz++) {
                    ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, bz)),
                            CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
                }
                List<BlockPos> east = positions(z, 4, 12);
                List<BlockPos> south = new ArrayList<>();
                for (int bz = z + 2; bz <= z + 10; bz++) {
                    south.add(new BlockPos(2, 1, bz));
                    south.add(new BlockPos(4, 1, bz));
                    south.add(new BlockPos(3, 2, bz));
                    south.add(new BlockPos(3, 0, bz));
                }
                spots.addAll(east.subList(0, count / 2));
                spots.addAll(south.subList(0, count - count / 2));
            } else {
                spots.addAll(positions(z, 4, 4 + count / 4));
            }
            List<TestStorage.TestDevice> devices = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                devices.add(NetworkGameTests.device(helper, spots.get(i)));
            }
            rows.add(devices);
        }
        helper.startSequence().thenWaitUntil(() -> {
            for (HubTier tier : HubTier.values()) {
                int z = ROW_Z[tier.level() - 1];
                int cap = capacity[tier.level() - 1];
                NetworkState network = NetworkGameTests.state(helper, new BlockPos(1, 1, z));
                long hub = helper.absolutePos(new BlockPos(3, 1, z)).asLong();
                int online = 0;
                int offline = 0;
                for (TestStorage.TestDevice device : rows.get(tier.level() - 1)) {
                    if (device.network() == network) {
                        online++;
                    } else if (device.deviceLink().offlineBy() == network) {
                        offline++;
                        ChannelShortage shortage = device.deviceLink().channelShortage().orElseThrow();
                        helper.assertValueEqual(shortage.kind(), ChannelShortage.Kind.HUB, "what is full, " + tier);
                        helper.assertValueEqual(shortage.pos(), hub, "which hub, " + tier);
                        helper.assertValueEqual(shortage.used(), cap, "used, " + tier);
                        helper.assertValueEqual(shortage.capacity(), cap, "capacity, " + tier);
                    }
                }
                helper.assertValueEqual(online, cap, "devices online behind " + tier);
                helper.assertValueEqual(offline, 2, "devices offline behind " + tier);
                helper.assertValueEqual(network.hubChannels(hub), cap, "the hub's count, " + tier);
                helper.assertValueEqual(network.segmentChannels(helper.absolutePos(new BlockPos(2, 1, z)).asLong()), 1,
                        "channels before the hub, " + tier);
            }
        }).thenSucceed();
    }

    /**
     * Sixteen devices fill a tier-1 hub; two placed later go offline (the newest, though they sit nearer the hub) and
     * their info lines name the hub with 16 of 16. Replacing the hub with a tier-2 one brings both back, and the
     * Network tab lists the hub with its use.
     */
    private static void fullThenUpgrade(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, HubTier.TIER_1, 12);
        List<BlockPos> spots = positions(1, 4, 9);
        List<BlockPos> old = spots.subList(4, 20);
        List<BlockPos> late = spots.subList(0, 2);
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (BlockPos pos : old) {
            devices.add(NetworkGameTests.device(helper, pos));
        }
        BlockPos hubPos = new BlockPos(3, 1, 1);
        long hub = helper.absolutePos(hubPos).asLong();
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).hubChannels(hub), 16,
                                "sixteen devices count against the hub"))
                .thenExecute(() -> {
                    for (BlockPos pos : late) {
                        devices.add(NetworkGameTests.device(helper, pos));
                    }
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    for (BlockPos pos : late) {
                        TestStorage.TestDevice device = helper.getBlockEntity(pos, TestStorage.TestDevice.class);
                        helper.assertTrue(device.deviceLink().offlineBy() == network, "a late device is online");
                    }
                    for (BlockPos pos : old) {
                        helper.assertTrue(helper.getBlockEntity(pos, TestStorage.TestDevice.class).network() == network,
                                "an old device lost its channel");
                    }
                }).thenExecute(() -> {
                    TranslatableContents line = offlineLine(helper, late.get(0));
                    BlockPos abs = helper.absolutePos(hubPos);
                    Object[] args = line.getArgs();
                    helper.assertValueEqual(String.valueOf(args[0]), String.valueOf(abs.getX()), "x");
                    helper.assertValueEqual(String.valueOf(args[1]), String.valueOf(abs.getY()), "y");
                    helper.assertValueEqual(String.valueOf(args[2]), String.valueOf(abs.getZ()), "z");
                    helper.assertValueEqual(String.valueOf(args[3]), "16", "used");
                    helper.assertValueEqual(String.valueOf(args[4]), "16", "capacity");
                    NetworkOverview overview = NetworkOverview.of(NetworkGameTests.state(helper, controller),
                            NetworkOverview.Cluster.NO_CLUSTER);
                    helper.assertValueEqual(overview.hubs(), List.of(new NetworkOverview.Hub(hub, 16, 16)),
                            "hubs on the Network tab");
                    helper.destroyBlock(hubPos);
                    helper.setBlock(hubPos, HubContent.TIER_2.get());
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    for (TestStorage.TestDevice device : devices) {
                        helper.assertTrue(device.network() == network, "a device still offline after the upgrade");
                        helper.assertTrue(device.deviceLink().channelShortage().isEmpty(), "a reason kept");
                    }
                    helper.assertValueEqual(network.hubChannels(hub), 18, "the new hub's count");
                    helper.assertValueEqual(network.hubCapacity(hub), 32, "the new hub's budget");
                }).thenSucceed();
    }

    /**
     * A device touching the controller, a hub, a device behind it, a second hub behind that and three devices behind
     * the second: everything is one network with one storage list (cobblestone in the first and last device both
     * count), the outer hub counts its device and the inner hub as two, the inner hub counts its three, and the hub
     * reports its use in its info line.
     */
    private static void neverSplit(GameTestHelper helper) {
        BlockPos controller = new BlockPos(1, 1, 1);
        helper.setBlock(controller, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice front = NetworkGameTests.device(helper, controller.above());
        cable(helper, new BlockPos(2, 1, 1), CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.setBlock(new BlockPos(3, 1, 1), HubContent.TIER_1.get());
        for (int x = 4; x <= 8; x++) {
            cable(helper, new BlockPos(x, 1, 1), CableContent.STORAGE_CABLE_TIER_4.type().get());
        }
        TestStorage.TestDevice middle = NetworkGameTests.device(helper, new BlockPos(5, 1, 0));
        helper.setBlock(new BlockPos(9, 1, 1), HubContent.TIER_1.get());
        for (int x = 10; x <= 14; x++) {
            cable(helper, new BlockPos(x, 1, 1), CableContent.STORAGE_CABLE_TIER_4.type().get());
        }
        List<TestStorage.TestDevice> inner = new ArrayList<>();
        for (BlockPos pos : List.of(new BlockPos(11, 1, 0), new BlockPos(12, 1, 2), new BlockPos(13, 2, 1))) {
            inner.add(NetworkGameTests.device(helper, pos));
        }
        long outerHub = helper.absolutePos(new BlockPos(3, 1, 1)).asLong();
        long innerHub = helper.absolutePos(new BlockPos(9, 1, 1)).asLong();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(front.network() == network, "the front device is elsewhere");
            helper.assertTrue(middle.network() == network, "the middle device is elsewhere");
            for (TestStorage.TestDevice device : inner) {
                helper.assertTrue(device.network() == network, "a device behind both hubs is elsewhere");
            }
            helper.assertTrue(helper.getBlockEntity(new BlockPos(9, 1, 1), HubBlockEntity.class).network() == network,
                    "the inner hub is elsewhere");
            helper.assertValueEqual(network.hubChannels(outerHub), 2, "the outer hub counts a device and a hub");
            helper.assertValueEqual(network.hubChannels(innerHub), 3, "the inner hub counts its devices");
            helper.assertValueEqual(network.segmentChannels(helper.absolutePos(new BlockPos(2, 1, 1)).asLong()), 1,
                    "channels before the outer hub");
            helper.assertValueEqual(network.channelsHeld(), 5, "devices holding channels");
        }).thenExecute(() -> {
            front.provider.setDirect(COBBLE, 3);
            inner.get(2).provider.setDirect(COBBLE, 4);
        }).thenWaitUntil(
                () -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 7L,
                        "cobblestone in the front device and behind both hubs"))
                .thenExecute(() -> {
                    List<String> keys = NetworkGameTests.keys(helper, new BlockPos(9, 1, 1),
                            BlockInfoRequest.Detail.FULL);
                    helper.assertTrue(keys.contains(NetworkInfo.HUB_KEY), "no hub line: " + keys);
                }).thenSucceed();
    }

    /**
     * Sixteen old devices fill a tier-1 hub and two later ones wait offline. The hub unloads and loads again from its
     * saved data (its branch drops out of the network and comes back): the same sixteen are online and the same two
     * offline. Then the hub and every device unload and load again, in reverse order: still the same.
     */
    private static void stateAfterReload(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, HubTier.TIER_1, 12);
        List<BlockPos> spots = positions(1, 4, 9);
        List<BlockPos> old = spots.subList(4, 20);
        List<BlockPos> late = spots.subList(0, 2);
        for (BlockPos pos : old) {
            NetworkGameTests.device(helper, pos);
        }
        BlockPos hubPos = new BlockPos(3, 1, 1);
        long hub = helper.absolutePos(hubPos).asLong();
        List<BlockPos> all = new ArrayList<>(old);
        all.addAll(late);
        all.add(hubPos);
        CompoundTag[] saved = new CompoundTag[all.size()];
        int hubIndex = all.size() - 1;
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).hubChannels(hub), 16,
                                "sixteen devices count against the hub"))
                .thenExecute(() -> {
                    for (BlockPos pos : late) {
                        NetworkGameTests.device(helper, pos);
                    }
                }).thenWaitUntil(() -> assertFullHub(helper, controller, hub, old, late))
                .thenExecute(() -> saved[hubIndex] = unload(helper, hubPos))
                .thenWaitUntil(() -> helper.assertFalse(NetworkGameTests.state(helper, controller).isHub(hub),
                        "the unloaded hub still has a budget"))
                .thenExecute(() -> load(helper, hubPos, saved[hubIndex]))
                .thenWaitUntil(() -> assertFullHub(helper, controller, hub, old, late)).thenExecute(() -> {
                    for (int i = 0; i < all.size(); i++) {
                        saved[i] = unload(helper, all.get(i));
                    }
                }).thenIdle(5).thenExecute(() -> {
                    for (int i = all.size() - 1; i >= 0; i--) {
                        load(helper, all.get(i), saved[i]);
                    }
                }).thenWaitUntil(() -> assertFullHub(helper, controller, hub, old, late)).thenSucceed();
    }

    /** The hub counts sixteen, the {@code old} devices are online and the {@code late} ones offline at the hub. */
    private static void assertFullHub(GameTestHelper helper, BlockPos controller, long hub, List<BlockPos> old,
            List<BlockPos> late) {
        NetworkState network = NetworkGameTests.state(helper, controller);
        helper.assertTrue(network.isHub(hub), "the hub has no budget");
        helper.assertValueEqual(network.hubChannels(hub), 16, "devices counting against the hub");
        for (BlockPos pos : old) {
            helper.assertTrue(helper.getBlockEntity(pos, TestStorage.TestDevice.class).network() == network,
                    "an old device is offline");
        }
        for (BlockPos pos : late) {
            TestStorage.TestDevice device = helper.getBlockEntity(pos, TestStorage.TestDevice.class);
            helper.assertTrue(device.deviceLink().offlineBy() == network, "a late device is not offline");
            helper.assertValueEqual(device.deviceLink().channelShortage().orElseThrow().kind(),
                    ChannelShortage.Kind.HUB, "what a late device waits for");
        }
    }

    /**
     * Sixteen devices fill a tier-1 hub and two later ones wait. The hub is broken and a tier-4 cable put in its place:
     * the devices fall back to the tier-1 cable before it (8 channels), so the eight oldest by join order are online
     * and the other ten offline, naming that cable. A tier-1 hub put back brings the sixteen oldest online and leaves
     * the two newest offline at the hub.
     */
    private static void removedFallsBack(GameTestHelper helper) {
        BlockPos controller = layout(helper, 1, HubTier.TIER_1, 12);
        List<BlockPos> spots = positions(1, 4, 9);
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (BlockPos pos : spots.subList(4, 20)) {
            devices.add(NetworkGameTests.device(helper, pos));
        }
        BlockPos hubPos = new BlockPos(3, 1, 1);
        long hub = helper.absolutePos(hubPos).asLong();
        long bottleneck = helper.absolutePos(new BlockPos(2, 1, 1)).asLong();
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).hubChannels(hub), 16,
                                "sixteen devices count against the hub"))
                .thenExecute(() -> {
                    for (BlockPos pos : spots.subList(0, 2)) {
                        devices.add(NetworkGameTests.device(helper, pos));
                    }
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, controller).offlineDevices().size(), 2, "devices offline"))
                .thenExecute(() -> {
                    helper.destroyBlock(hubPos);
                    cable(helper, hubPos, CableContent.STORAGE_CABLE_TIER_4.type().get());
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertFalse(network.isHub(hub), "the removed hub kept its budget");
                    helper.assertValueEqual(network.segmentChannels(bottleneck), 8, "channels on the tier-1 cable");
                    List<TestStorage.TestDevice> byJoin = byJoinOrder(devices);
                    for (int i = 0; i < byJoin.size(); i++) {
                        TestStorage.TestDevice device = byJoin.get(i);
                        if (i < 8) {
                            helper.assertTrue(device.network() == network, "device " + i + " in join order offline");
                        } else {
                            helper.assertTrue(device.deviceLink().offlineBy() == network,
                                    "device " + i + " in join order online");
                            ChannelShortage shortage = device.deviceLink().channelShortage().orElseThrow();
                            helper.assertValueEqual(shortage.kind(), ChannelShortage.Kind.CABLE, "what is full");
                            helper.assertValueEqual(shortage.pos(), bottleneck, "which cable");
                        }
                    }
                }).thenExecute(() -> {
                    helper.destroyBlock(hubPos);
                    helper.setBlock(hubPos, HubContent.TIER_1.get());
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.hubChannels(hub), 16, "devices counting against the new hub");
                    List<TestStorage.TestDevice> byJoin = byJoinOrder(devices);
                    for (int i = 0; i < byJoin.size(); i++) {
                        TestStorage.TestDevice device = byJoin.get(i);
                        if (i < 16) {
                            helper.assertTrue(device.network() == network, "device " + i + " in join order offline");
                        } else {
                            helper.assertTrue(device.deviceLink().offlineBy() == network,
                                    "device " + i + " in join order online");
                            helper.assertValueEqual(device.deviceLink().channelShortage().orElseThrow().kind(),
                                    ChannelShortage.Kind.HUB, "what a newest device waits for");
                        }
                    }
                }).thenSucceed();
    }

    private static List<TestStorage.TestDevice> byJoinOrder(List<TestStorage.TestDevice> devices) {
        List<TestStorage.TestDevice> sorted = new ArrayList<>(devices);
        sorted.sort(Comparator.comparingLong(device -> device.deviceLink().joinSeq()));
        return sorted;
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

    /**
     * A controller at x 1 on row {@code z}, a tier-1 cable at x 2, a hub of {@code tier} at x 3 and a tier-4 trunk from
     * x 4 to {@code trunkEnd}. Returns the controller.
     */
    private static BlockPos layout(GameTestHelper helper, int z, HubTier tier, int trunkEnd) {
        BlockPos controller = new BlockPos(1, 1, z);
        helper.setBlock(controller, ControllerContent.CONTROLLER.get());
        cable(helper, new BlockPos(2, 1, z), CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.setBlock(new BlockPos(3, 1, z), HubContent.hub(tier).get());
        for (int x = 4; x <= trunkEnd; x++) {
            cable(helper, new BlockPos(x, 1, z), CableContent.STORAGE_CABLE_TIER_4.type().get());
        }
        return controller;
    }

    private static void cable(GameTestHelper helper, BlockPos pos, ConduitType type) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos), type, null);
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

    /** The hub-full line of the device at {@code pos}. */
    private static TranslatableContents offlineLine(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockInfoProvider provider = InfoLookups.BLOCK.find(helper.getLevel(), absolute, null);
        if (provider == null) {
            throw helper.assertionException(Component.literal("No block info at " + pos.toShortString()));
        }
        List<Component> lines = new ArrayList<>();
        provider.addInfo(new BlockInfoRequest(helper.getLevel(), absolute, null, null, BlockInfoRequest.Detail.FULL),
                lines::add);
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents translatable
                    && translatable.getKey().equals(NetworkInfo.offlineKey(ChannelShortage.Kind.HUB))) {
                return translatable;
            }
        }
        throw helper.assertionException(Component.literal("No hub-full line at " + pos.toShortString()));
    }
}
