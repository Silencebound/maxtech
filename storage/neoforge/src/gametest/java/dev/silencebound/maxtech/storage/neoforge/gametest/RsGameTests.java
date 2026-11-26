package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.composite.PriorityStorage;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.bridge.BridgeBlock;
import dev.silencebound.maxtech.storage.bridge.BridgeBlockEntity;
import dev.silencebound.maxtech.storage.bridge.BridgeContent;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage.RefinedStorageCompat;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.dev.ShowcaseBridges;
import dev.silencebound.maxtech.storage.dev.ShowcaseStation;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: tests move items in NeoForge transactions like the network does.
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The network bridge against a real Refined Storage network (M7b item 3); registered only with Refined Storage loaded,
 * so these run only with {@code -PwithRs=true -Pgametest='maxtech_storage:rs_*'}. CI runs them in a step of their own,
 * nightly and on PRs that touch the bridge; the plain suite tests the bridge through a fake ({@link BridgeGameTests}).
 * An RS cable stands in front of the bridge; its network gets an in-memory RS storage as its disk where a test needs
 * one.
 *
 * <p>
 * Layout on one row: a controller at x = 1, cables 2 and 3, the bridge at x = 4 facing east to the RS cable at x = 5,
 * and a test storage device at (2, 1, 2) as the Storage network's own storage.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class RsGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();

    static {
        if (ModList.get().isLoaded(RefinedStorageCompat.MOD_ID)) {
            TestStorage.init();
            StorageTestEnvironment.init();
            register("rs_take_store", helper -> WithRs.takeStore(helper));
            register("rs_export_loop_guard", helper -> WithRs.exportLoopGuard(helper));
            register("rs_export_follows_network", helper -> WithRs.exportFollowsNetwork(helper));
            register("rs_cable_placed_later", helper -> WithRs.cablePlacedLater(helper));
            register("rs_unpowered_offline", helper -> WithRs.unpoweredOffline(helper));
            register("rs_export_resources_shrink", helper -> WithRs.exportResourcesShrink(helper));
            register("rs_two_bridges_once", helper -> WithRs.twoBridges(helper));
            register("rs_unmount_on_break", helper -> WithRs.unmountOnBreak(helper));
            register("rs_merge_split", helper -> WithRs.mergeSplit(helper));
            register("rs_showcase_bridges", helper -> WithRs.showcase(helper));
        }
    }

    private RsGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        if (NAMES.isEmpty()) {
            return;
        }
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("rs"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    /** The RS calls, in a class of their own so nothing loads RS's classes without RS. */
    private static final class WithRs {
        private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
        private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
        private static final StackKey DIAMOND = ItemKey.of(Items.DIAMOND);
        private static final StackKey GOLD = ItemKey.of(Items.GOLD_INGOT);
        private static final Actor ACTOR = () -> "test";
        private static final BlockPos CABLE = NetworkGameTests.at(5);
        /** Where a cable links the second device (at (3, 1, 3)) to the network. */
        private static final BlockPos LINK = new BlockPos(3, 1, 2);

        private WithRs() {
        }

        /** Controller, cables, the bridge facing east and an RS cable in front of it; returns the controller. */
        private static BlockPos layout(GameTestHelper helper) {
            return layout(helper, "creative_controller");
        }

        /** As {@link #layout(GameTestHelper)}, with the RS controller block named (behind the RS cable) as RS power. */
        private static BlockPos layout(GameTestHelper helper, String rsController) {
            BlockPos controller = NetworkGameTests.controller(helper, 1);
            NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
            Block cable = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("refinedstorage", "cable"));
            helper.setBlock(CABLE, cable);
            helper.setBlock(NetworkGameTests.at(6),
                    BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("refinedstorage", rsController)));
            helper.setBlock(NetworkGameTests.at(4),
                    BridgeContent.BRIDGE_TIER_3.get().defaultBlockState().setValue(BridgeBlock.FACING, Direction.EAST));
            return controller;
        }

        private static BridgeBlockEntity bridge(GameTestHelper helper) {
            return helper.getBlockEntity(NetworkGameTests.at(4), BridgeBlockEntity.class);
        }

        /** The RS cable's network's root storage; fails (to be retried) while RS has not given the cable one yet. */
        private static StorageNetworkComponent root(GameTestHelper helper) {
            return network(helper, CABLE).getComponent(StorageNetworkComponent.class);
        }

        /** The network of the RS block at {@code pos}; fails (to be retried) while RS has not given it one yet. */
        private static Network network(GameTestHelper helper, BlockPos pos) {
            NetworkNodeContainerProvider provider = helper.getLevel().getCapability(
                    RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
                    helper.absolutePos(pos), Direction.WEST);
            if (provider != null) {
                for (InWorldNetworkNodeContainer container : provider.getContainers()) {
                    Network network = container.getNode().getNetwork();
                    if (network != null) {
                        return network;
                    }
                }
            }
            throw helper.assertionException(Component.literal("no RS network at " + pos + " yet"));
        }

        private static Block rsBlock(String name) {
            return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("refinedstorage", name));
        }

        private static ItemResource rs(StackKey key) {
            ItemKey item = (ItemKey) key;
            return new ItemResource(item.item(), item.components());
        }

        private static NetworkState mounted(GameTestHelper helper, BlockPos controller) {
            NetworkState network = NetworkGameTests.state(helper, controller);
            BridgeBlockEntity bridge = bridge(helper);
            helper.assertTrue(bridge.network() == network, "bridge in the network");
            helper.assertTrue(bridge.isMounted(), "bridge mounted");
            return network;
        }

        private static long extract(NetworkState network, StackKey key, long amount) {
            try (Transaction transaction = Transaction.openRoot()) {
                long moved = network.extract(key, amount, transaction);
                transaction.commit();
                return moved;
            }
        }

        /**
         * The export keeps RS resources only for the kinds RS lists from it now: kinds that leave the Storage network
         * are dropped, and a kind that comes back is listed by RS again as before.
         */
        static void exportResourcesShrink(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            helper.startSequence().thenExecute(() -> {
                device.provider.setDirect(DIAMOND, 5);
                device.provider.setDirect(GOLD, 7);
                device.provider.setDirect(COBBLE, 9);
            }).thenWaitUntil(() -> {
                mounted(helper, controller);
                helper.assertValueEqual(root(helper).get(rs(COBBLE)), 9L, "RS lists the cobble");
                helper.assertValueEqual(RefinedStorageCompat.exportResources(root(helper)), 3, "three kinds kept");
            }).thenExecute(() -> {
                device.provider.setDirect(GOLD, 0);
                device.provider.setDirect(COBBLE, 0);
            }).thenWaitUntil(() -> {
                helper.assertValueEqual(root(helper).get(rs(GOLD)), 0L, "RS lost the gold");
                helper.assertValueEqual(root(helper).get(rs(COBBLE)), 0L, "RS lost the cobble");
                helper.assertValueEqual(RefinedStorageCompat.exportResources(root(helper)), 1,
                        "only the diamonds kept");
            }).thenExecute(() -> device.provider.setDirect(GOLD, 3)).thenWaitUntil(() -> {
                helper.assertValueEqual(root(helper).get(rs(GOLD)), 3L, "RS lists the gold again");
                helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "diamonds unchanged");
                helper.assertValueEqual(RefinedStorageCompat.exportResources(root(helper)), 2, "two kinds kept");
            }).thenSucceed();
        }

        /**
         * Two bridges from one Storage network to the same RS network: one mounts, both sides count everything once,
         * and the other takes over when the mounted one is broken.
         */
        static void twoBridges(GameTestHelper helper) {
            StorageImpl disk = new StorageImpl();
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            // The second bridge behind cable 3, facing south to an RS cable that runs round to the first one's.
            BlockPos second = new BlockPos(3, 1, 2);
            Block cable = rsBlock("cable");
            for (BlockPos pos : List.of(new BlockPos(3, 1, 3), new BlockPos(4, 1, 3), new BlockPos(5, 1, 3),
                    new BlockPos(5, 1, 2))) {
                helper.setBlock(pos, cable);
            }
            helper.setBlock(second, BridgeContent.BRIDGE_TIER_3.get().defaultBlockState().setValue(BridgeBlock.FACING,
                    Direction.SOUTH));
            BridgeBlockEntity first = bridge(helper);
            BridgeBlockEntity other = helper.getBlockEntity(second, BridgeBlockEntity.class);
            boolean[] added = new boolean[1];
            helper.startSequence().thenExecute(() -> device.provider.setDirect(DIAMOND, 5)).thenWaitUntil(() -> {
                StorageNetworkComponent root = root(helper);
                if (!added[0]) {
                    added[0] = true;
                    root.addSource(disk);
                    root.insert(rs(COBBLE), 20, Action.EXECUTE, ACTOR);
                }
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertTrue(first.network() == network && other.network() == network, "both bridges in");
                helper.assertTrue(first.found() != null && other.found() != null, "both bridges see RS");
                helper.assertTrue(first.isMounted() != other.isMounted(), "exactly one bridge mounts");
                helper.assertValueEqual(RefinedStorageCompat.mountedExports(root), 1, "one export");
                helper.assertValueEqual(root.get(rs(DIAMOND)), 5L, "RS lists the diamonds once");
                helper.assertValueEqual(network.contents().amount(COBBLE), 20L, "RS cobble once");
                helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds once");
            }).thenExecute(() -> helper.setBlock(first.isMounted() ? NetworkGameTests.at(4) : second, Blocks.AIR))
                    .thenWaitUntil(() -> {
                        BridgeBlockEntity left = first.isRemoved() ? other : first;
                        NetworkState network = NetworkGameTests.state(helper, controller);
                        helper.assertTrue(left.isMounted(), "the standby took over");
                        helper.assertValueEqual(RefinedStorageCompat.mountedExports(root(helper)), 1,
                                "one export after");
                        helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "diamonds once after");
                        helper.assertValueEqual(network.contents().amount(COBBLE), 20L, "RS cobble once after");
                        helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds once after");
                    }).thenSucceed();
        }

        /** Breaking the bridge takes the export out of RS: RS no longer lists the Storage network's things. */
        static void unmountOnBreak(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            helper.startSequence().thenExecute(() -> device.provider.setDirect(DIAMOND, 5)).thenWaitUntil(() -> {
                mounted(helper, controller);
                helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "RS lists the diamonds");
            }).thenExecute(() -> {
                helper.setBlock(NetworkGameTests.at(4), Blocks.AIR);
                helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 0L, "RS lost the diamonds at once");
                helper.assertValueEqual(RefinedStorageCompat.mountedExports(root(helper)), 0, "nothing mounted");
                helper.assertValueEqual(device.provider.amount(DIAMOND), 5L, "the device keeps them");
            }).thenSucceed();
        }

        /**
         * The RS network in front merges with another and splits again while the bridge is mounted. RS hands the cable
         * a different network object each time; the bridge finds it, moves its export over, and both sides count
         * exactly: the other network's disk shows only while merged, and RS lists the diamonds once in whichever
         * network the bridge is in and not in the one it left.
         */
        @SuppressWarnings("ReferenceEquality") // RS's network objects; identity is what merges and splits change
        static void mergeSplit(GameTestHelper helper) {
            StorageImpl disk = new StorageImpl();
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            // A second RS network, earlier in RS's position order (z, then x) than the bridge's, so RS keeps its
            // network object on the merge and on the split, and the bridge's cable is the one that changes network.
            BlockPos other = new BlockPos(7, 1, 0);
            BlockPos join = new BlockPos(6, 1, 0);
            helper.setBlock(other, rsBlock("creative_controller"));
            Network[] before = new Network[1];
            helper.startSequence().thenExecute(() -> device.provider.setDirect(DIAMOND, 5)).thenWaitUntil(() -> {
                Network theirs = network(helper, other);
                if (before[0] == null) {
                    StorageNetworkComponent root = theirs.getComponent(StorageNetworkComponent.class);
                    root.addSource(disk);
                    root.insert(rs(COBBLE), 20, Action.EXECUTE, ACTOR);
                    before[0] = network(helper, CABLE);
                }
                mounted(helper, controller);
                helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "RS lists the diamonds");
            }).thenExecute(() -> helper.setBlock(join, rsBlock("cable"))).thenWaitUntil(() -> {
                Network merged = network(helper, other);
                helper.assertTrue(network(helper, CABLE) == merged, "merged");
                Network old = before[0];
                helper.assertTrue(old != null && merged != old, "the cable changed network");
                NetworkState network = mounted(helper, controller);
                StorageNetworkComponent root = merged.getComponent(StorageNetworkComponent.class);
                helper.assertValueEqual(RefinedStorageCompat.mountedExports(root), 1, "one export");
                helper.assertValueEqual(
                        RefinedStorageCompat.mountedExports(old.getComponent(StorageNetworkComponent.class)), 0,
                        "none left in the old network");
                helper.assertValueEqual(root.get(rs(DIAMOND)), 5L, "the merged network lists the diamonds once");
                helper.assertValueEqual(root.get(rs(COBBLE)), 20L, "and the disk");
                helper.assertValueEqual(network.contents().amount(COBBLE), 20L, "disk cobble once");
                helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds once");
            }).thenExecute(() -> helper.setBlock(join, Blocks.AIR)).thenWaitUntil(() -> {
                Network theirs = network(helper, other);
                helper.assertTrue(network(helper, CABLE) != theirs, "split");
                NetworkState network = mounted(helper, controller);
                StorageNetworkComponent left = theirs.getComponent(StorageNetworkComponent.class);
                helper.assertValueEqual(RefinedStorageCompat.mountedExports(root(helper)), 1, "one export");
                helper.assertValueEqual(RefinedStorageCompat.mountedExports(left), 0, "none left in the other");
                helper.assertValueEqual(left.get(rs(DIAMOND)), 0L, "the other network lost the diamonds");
                helper.assertValueEqual(left.get(rs(COBBLE)), 20L, "and kept its disk");
                helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "the bridge's network lists them once");
                helper.assertValueEqual(root(helper).get(rs(COBBLE)), 0L, "not the other disk");
                helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "the disk left the totals");
                helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds once");
            }).thenSucceed();
        }

        /**
         * An RS network with no energy (a controller that was never charged) is offline for the bridge: nothing is
         * listed, a take moves nothing and a store is refused, while its disk keeps what it holds.
         */
        static void unpoweredOffline(GameTestHelper helper) {
            StorageImpl disk = new StorageImpl();
            BlockPos controller = layout(helper, "controller");
            helper.startSequence().thenWaitUntil(() -> {
                StorageNetworkComponent root = root(helper);
                root.addSource(disk);
                root.insert(rs(COBBLE), 20, Action.EXECUTE, ACTOR);
                helper.assertTrue(bridge(helper).network() == NetworkGameTests.state(helper, controller),
                        "bridge in the network");
            }).thenIdle(30).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, 100_000);
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "nothing listed");
                helper.assertValueEqual(extract(network, COBBLE, 5), 0L, "a take moves nothing");
                helper.assertValueEqual(NetworkGameTests.insert(network, IRON, 7), 0L, "a store is refused");
                helper.assertValueEqual(root(helper).get(rs(COBBLE)), 20L, "RS keeps its items");
                helper.assertValueEqual(root(helper).get(rs(IRON)), 0L, "nothing reached RS");
            }).thenSucceed();
        }

        /**
         * An RS cable placed in front of a bridge already in its network: RS gives the cable its network only on the
         * next tick, after the bridge looked, and the bridge still mounts it (the finder's retry).
         */
        static void cablePlacedLater(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            Block cable = helper.getBlockState(CABLE).getBlock();
            helper.setBlock(CABLE, Blocks.AIR);
            helper.startSequence().thenWaitUntil(() -> {
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertTrue(bridge(helper).network() == network, "bridge in the network");
                helper.assertFalse(bridge(helper).isMounted(), "nothing to mount yet");
            }).thenIdle(5).thenExecute(() -> helper.setBlock(CABLE, cable))
                    .thenWaitUntil(() -> helper.assertTrue(bridge(helper).isMounted(), "mounted once RS has a network"))
                    .thenSucceed();
        }

        /**
         * An RS disk's contents show in the Storage network; takes and stores move exactly what moved in RS, a
         * rolled-back take is back in RS, and a rolled-back store never reaches it.
         */
        static void takeStore(GameTestHelper helper) {
            StorageImpl disk = new StorageImpl();
            BlockPos controller = layout(helper);
            helper.startSequence().thenWaitUntil(() -> {
                StorageNetworkComponent root = root(helper);
                root.addSource(disk);
                root.insert(rs(COBBLE), 20, Action.EXECUTE, ACTOR);
            }).thenWaitUntil(() -> {
                NetworkState network = mounted(helper, controller);
                helper.assertValueEqual(network.contents().amount(COBBLE), 20L, "RS cobble in the totals");
            }).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, 100_000);
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertValueEqual(extract(network, COBBLE, 5), 5L, "take");
                helper.assertValueEqual(disk.getAll().stream().mapToLong(a -> a.amount()).sum(), 15L, "RS after take");
                try (Transaction transaction = Transaction.openRoot()) {
                    helper.assertValueEqual(network.extract(COBBLE, 3, transaction), 3L, "rolled-back take");
                }
                helper.assertValueEqual(root(helper).get(rs(COBBLE)), 15L, "rolled-back take is back in RS");
                helper.assertValueEqual(NetworkGameTests.insert(network, IRON, 7), 7L, "store");
                helper.assertValueEqual(root(helper).get(rs(IRON)), 7L, "RS after store");
                try (Transaction transaction = Transaction.openRoot()) {
                    network.insert(IRON, 4, transaction);
                }
                helper.assertValueEqual(root(helper).get(rs(IRON)), 7L, "rolled-back store never reached RS");
                helper.assertValueEqual(network.contents().amount(IRON), 7L, "iron counted once");
                helper.assertValueEqual(bridge(helper).storage().heldTotal(), 0L, "nothing held");
            }).thenSucceed();
        }

        /**
         * With no RS disk, RS sees the Storage network through the export: RS lists the device's diamonds, takes and
         * stores land in the device, the Storage network counts its own things once (also after a refresh RS
         * signalled), and a store through the bridge does not come back through the export.
         */
        static void exportLoopGuard(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            helper.startSequence().thenExecute(() -> device.provider.setDirect(DIAMOND, 5)).thenWaitUntil(() -> {
                mounted(helper, controller);
                helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "RS lists the diamonds");
            }).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, 100_000);
                NetworkState network = NetworkGameTests.state(helper, controller);
                StorageNetworkComponent root = root(helper);
                helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds counted once");
                helper.assertValueEqual(root.extract(rs(DIAMOND), 2, Action.EXECUTE, ACTOR), 2L, "RS takes");
                helper.assertValueEqual(device.provider.amount(DIAMOND), 3L, "from the device");
                helper.assertValueEqual(root.get(rs(DIAMOND)), 3L, "RS's list follows");
                helper.assertValueEqual(root.insert(rs(GOLD), 4, Action.SIMULATE, ACTOR), 4L, "RS forecast");
                helper.assertValueEqual(device.provider.amount(GOLD), 0L, "a simulated store moves nothing");
                helper.assertValueEqual(root.insert(rs(GOLD), 4, Action.EXECUTE, ACTOR), 4L, "RS stores");
                helper.assertValueEqual(device.provider.amount(GOLD), 4L, "into the device");
                helper.assertValueEqual(root.get(rs(GOLD)), 4L, "RS lists the gold");
                try (Transaction transaction = Transaction.openRoot()) {
                    helper.assertValueEqual(bridge(helper).storage().insert(IRON, 8, transaction), 0L,
                            "the bridge does not store into its own export");
                    transaction.commit();
                }
                helper.assertValueEqual(device.provider.amount(IRON), 0L, "nothing came back through the export");
                device.provider.setDirect(COBBLE, 9);
            }).thenWaitUntil(() -> helper.assertValueEqual(root(helper).get(rs(COBBLE)), 9L, "RS sees a new key"))
                    .thenExecute(() -> {
                        // A change of RS's own storage (a disk ahead of the export), so the bridge reads RS again.
                        StorageNetworkComponent root = root(helper);
                        root.addSource(PriorityStorage.of(new StorageImpl(), 10, 10));
                        root.insert(rs(IRON), 1, Action.EXECUTE, ACTOR);
                    })
                    .thenWaitUntil(() -> helper.assertValueEqual(
                            NetworkGameTests.state(helper, controller).contents().amount(IRON), 1L, "refreshed"))
                    .thenExecute(() -> {
                        NetworkState network = NetworkGameTests.state(helper, controller);
                        helper.assertValueEqual(network.contents().amount(GOLD), 4L, "gold once after a refresh");
                        helper.assertValueEqual(network.contents().amount(DIAMOND), 3L, "diamonds once");
                        helper.assertValueEqual(network.contents().amount(COBBLE), 9L, "cobble once");
                    }).thenSucceed();
        }

        /**
         * The showcase's bridges station with Refined Storage: the RS network gets its disk, is stocked and gets its
         * bridge; the Storage network lists RS's prismarine shards and RS the station's cobblestone.
         */
        static void showcase(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            Showcase.Build build = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1)),
                    List.of(ShowcaseStation.BRIDGES), null);
            helper.assertTrue(build.warnings().isEmpty(), "builder warnings: " + build.warnings());
            BlockPos pad = Objects.requireNonNull(build.pads().get(ShowcaseStation.BRIDGES));
            BlockPos hub = Objects.requireNonNull(build.hubs().get(ShowcaseStation.BRIDGES));
            helper.startSequence().thenWaitUntil(() -> {
                helper.assertFalse(Showcase.stocking(), "the showcase is still stocking");
                NetworkState network = StorageNetworks.network(level, StorageNetworks.ID, hub);
                if (network == null) {
                    throw helper.assertionException(Component.literal("no Storage network"));
                }
                helper.assertTrue(level.getBlockEntity(pad.offset(6, 0, 5)) instanceof BridgeBlockEntity bridge
                        && bridge.isMounted(), "the RS bridge is not mounted");
                helper.assertValueEqual(network.contents().amount(ShowcaseBridges.Foreign.REFINED_STORAGE.marker()),
                        256L, "RS's shards in the Storage network");
                NetworkNodeContainerProvider provider = level.getCapability(
                        RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
                        pad.offset(6, 0, 6), Direction.NORTH);
                Network rs = provider == null
                        ? null
                        : provider.getContainers().iterator().next().getNode().getNetwork();
                if (rs == null) {
                    throw helper.assertionException(Component.literal("no RS network at the cable"));
                }
                helper.assertTrue(rs.getComponent(StorageNetworkComponent.class).get(rs(COBBLE)) >= 500,
                        "the station's cobblestone in RS");
            }).thenExecute(() -> Showcase.clear(level, build.bounds())).thenSucceed();
        }

        /**
         * The bridge's Storage network grows by a device that held things before it joined: RS lists them, and loses
         * them again when the device leaves. RS only learns what the export tells it, so this covers the deltas of
         * devices joining and leaving reaching RS's cache.
         */
        static void exportFollowsNetwork(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            TestStorage.TestDevice later = NetworkGameTests.device(helper, new BlockPos(3, 1, 3));
            helper.startSequence().thenExecute(() -> {
                device.provider.setDirect(DIAMOND, 5);
                later.provider.setDirect(GOLD, 7);
            }).thenWaitUntil(() -> {
                mounted(helper, controller);
                helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "RS lists the diamonds");
                helper.assertValueEqual(root(helper).get(rs(GOLD)), 0L, "not the gold of the device outside");
            }).thenExecute(() -> ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(LINK),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null)).thenWaitUntil(() -> {
                        helper.assertTrue(later.network() == NetworkGameTests.state(helper, controller),
                                "the device joined");
                        helper.assertValueEqual(root(helper).get(rs(GOLD)), 7L, "RS lists the joined gold");
                        helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "and the diamonds once");
                    }).thenExecute(() -> helper.setBlock(LINK, Blocks.AIR))
                    .thenWaitUntil(() -> helper.assertValueEqual(root(helper).get(rs(GOLD)), 0L,
                            "RS loses the gold of the device that left"))
                    .thenExecute(() -> helper.assertValueEqual(root(helper).get(rs(DIAMOND)), 5L, "diamonds stay"))
                    .thenSucceed();
        }
    }
}
