package dev.silencebound.maxtech.storage.neoforge.gametest;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.me.storage.NetworkStorage;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.bridge.BridgeBlock;
import dev.silencebound.maxtech.storage.bridge.BridgeBlockEntity;
import dev.silencebound.maxtech.storage.bridge.BridgeContent;
import dev.silencebound.maxtech.storage.bridge.ForeignNetwork;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.dev.ShowcaseBridges;
import dev.silencebound.maxtech.storage.dev.ShowcaseStation;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import dev.silencebound.maxtech.storage.neoforge.compat.ae2.Ae2Lang;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: tests move items in NeoForge transactions like the network does.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The network bridge against a real AE2 grid (M7b item 2); registered only with AE2 loaded, so these run only with
 * {@code -PwithAe2=true -Pgametest='maxtech_storage:ae2_*'}. CI runs them in a step of their own, nightly and on PRs
 * that touch the bridge; the plain suite tests the bridge against a fake ({@link BridgeGameTests}). The grid is a
 * creative energy cell at x = 5 in front of the bridge and an ME drive at x = 6 with a 1k item and a 1k fluid cell; the
 * Storage network is a controller at x = 1, cables 2 and 3, the bridge at x = 4 and a test device at (2, 1, 2) as its
 * own storage.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class Ae2BridgeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final List<Integer> TICKS = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final StackKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final StackKey WATER = FluidKey.of(Fluids.WATER);

    static {
        if (ModList.get().isLoaded(Ae2Lang.MOD_ID)) {
            TestStorage.init();
            StorageTestEnvironment.init();
            register("ae2_bridge_take_store", 400, helper -> WithAe2.takeStore(helper));
            register("ae2_bridge_export_loop", 400, helper -> WithAe2.exportLoop(helper));
            register("ae2_bridge_break_unmounts", 400, helper -> WithAe2.breakUnmounts(helper));
            register("ae2_bridge_two_bridges", 400, helper -> WithAe2.twoBridges(helper));
            // Two polls (bridges.pollIntervalTicks) and AE2's boot after each grid change.
            register("ae2_bridge_grid_merge_split", 1200, helper -> WithAe2.mergeSplit(helper));
            register("ae2_showcase_bridges", 400, helper -> WithAe2.showcase(helper));
        }
    }

    private Ae2BridgeGameTests() {
    }

    private static void register(String name, int maxTicks, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        TICKS.add(maxTicks);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        if (NAMES.isEmpty()) {
            return;
        }
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("ae2"),
                StorageTestEnvironment.plain());
        for (int i = 0; i < NAMES.size(); i++) {
            StorageTests.test(event, environment, NAMES.get(i), STRUCTURE, TICKS.get(i));
        }
    }

    /** The AE2 calls, in a class of their own so nothing loads AE2's classes without AE2. */
    private static final class WithAe2 {
        private static final BlockPos CELL = NetworkGameTests.at(5);
        private static final BlockPos DRIVE = NetworkGameTests.at(6);

        private WithAe2() {
        }

        /** The Storage network, the AE2 grid (powered, with an item and a fluid cell); returns the controller. */
        private static BlockPos layout(GameTestHelper helper) {
            BlockPos controller = NetworkGameTests.controller(helper, 1);
            NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
            helper.setBlock(CELL, AEBlocks.CREATIVE_ENERGY_CELL.block());
            helper.setBlock(DRIVE, AEBlocks.DRIVE.block());
            DriveBlockEntity drive = helper.getBlockEntity(DRIVE, DriveBlockEntity.class);
            drive.getInternalInventory().insertItem(0, AEItems.ITEM_CELL_1K.stack(), false);
            drive.getInternalInventory().insertItem(1, AEItems.FLUID_CELL_1K.stack(), false);
            return controller;
        }

        private static BridgeBlockEntity bridge(GameTestHelper helper) {
            return bridge(helper, NetworkGameTests.at(4));
        }

        private static BridgeBlockEntity bridge(GameTestHelper helper, BlockPos pos) {
            helper.setBlock(pos,
                    BridgeContent.BRIDGE_TIER_3.get().defaultBlockState().setValue(BridgeBlock.FACING, Direction.EAST));
            return helper.getBlockEntity(pos, BridgeBlockEntity.class);
        }

        // AE2 declares the capability's context as plain Void; NeoForge's no-context overload wants @Nullable Void.
        @SuppressWarnings("NullAway")
        private static @Nullable IGridNode node(GameTestHelper helper, BlockPos pos) {
            IInWorldGridNodeHost host = helper.getLevel().getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                    helper.absolutePos(pos));
            return host == null ? null : host.getGridNode(Direction.WEST);
        }

        /** The grid of the energy cell at {@code pos} once it is up; fails the wait step before. */
        private static IGrid gridAt(GameTestHelper helper, BlockPos pos) {
            IGridNode node = node(helper, pos);
            if (node == null || !node.isActive()) {
                throw helper.assertionException(Component.literal("AE2 grid powered and booted at " + pos));
            }
            return node.getGrid();
        }

        /** The storage of the grid in front of the bridge once the grid is up; fails the wait step before. */
        private static MEStorage grid(GameTestHelper helper) {
            return gridAt(helper, CELL).getStorageService().getInventory();
        }

        private static MEStorage inventory(GameTestHelper helper, BlockPos pos) {
            return gridAt(helper, pos).getStorageService().getInventory();
        }

        /**
         * How many Storage exports {@code inventory} (a grid's storage) has mounted. An export left behind after its
         * bridge went reads empty, so only AE2's mount table shows it; AE2 has no api to list the mounts, so this reads
         * its private table (AE2 26.1.13-beta's {@code NetworkStorage.priorityInventory}).
         */
        private static int exports(MEStorage inventory) {
            Map<?, ?> mounts;
            try {
                Field field = NetworkStorage.class.getDeclaredField("priorityInventory");
                field.setAccessible(true);
                mounts = (Map<?, ?>) field.get(inventory);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("AE2's mount table moved", e);
            }
            int count = 0;
            for (Object list : mounts.values()) {
                for (Object mounted : (List<?>) list) {
                    if (mounted instanceof MEStorage storage
                            && storage.getDescription().getContents() instanceof TranslatableContents text
                            && text.getKey().equals(Ae2Lang.EXPORT)) {
                        count++;
                    }
                }
            }
            return count;
        }

        /** The grid the bridge reads and exports into now, or null without one. */
        private static @Nullable Object foreignGrid(BridgeBlockEntity bridge) {
            ForeignNetwork found = bridge.found();
            return found == null ? null : found.identity();
        }

        /** AE2's key for the test's plain keys (no components), built by hand rather than with the code under test. */
        private static AEKey ae(StackKey key) {
            return switch (key) {
                case ItemKey item -> AEItemKey.of(item.item());
                case FluidKey fluid -> AEFluidKey.of(fluid.fluid());
            };
        }

        private static long amount(MEStorage grid, StackKey key) {
            return grid.getAvailableStacks().get(ae(key));
        }

        private static long take(NetworkState network, StackKey key, long amount) {
            try (Transaction transaction = Transaction.openRoot()) {
                long moved = network.extract(key, amount, transaction);
                transaction.commit();
                return moved;
            }
        }

        /**
         * AE2's items and fluids show in the Storage network's totals; a take moves exactly what came out of AE2, a
         * rolled-back take puts it back, and a store lands in AE2's cells at commit.
         */
        static void takeStore(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            List<BridgeBlockEntity> bridge = new ArrayList<>();
            IActionSource source = IActionSource.empty();
            helper.startSequence().thenWaitUntil(() -> grid(helper)).thenExecute(() -> {
                MEStorage grid = grid(helper);
                helper.assertValueEqual(grid.insert(ae(COBBLE), 10, Actionable.MODULATE, source), 10L, "cobble in AE2");
                helper.assertValueEqual(grid.insert(ae(WATER), 4000, Actionable.MODULATE, source), 4000L,
                        "water in AE2");
                bridge.add(bridge(helper));
            }).thenWaitUntil(() -> {
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertTrue(bridge.getFirst().isMounted(), "bridge mounted");
                helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "AE2 cobble in the totals");
                helper.assertValueEqual(network.contents().amount(WATER), 4000L, "AE2 water in the totals");
            }).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, 100_000);
                NetworkState network = NetworkGameTests.state(helper, controller);
                MEStorage grid = grid(helper);
                helper.assertValueEqual(take(network, COBBLE, 4), 4L, "take through the bridge");
                helper.assertValueEqual(amount(grid, COBBLE), 6L, "taken out of AE2");
                helper.assertValueEqual(take(network, WATER, 1000), 1000L, "fluid take through the bridge");
                helper.assertValueEqual(amount(grid, WATER), 3000L, "fluid taken out of AE2");
                try (Transaction transaction = Transaction.openRoot()) {
                    helper.assertValueEqual(network.extract(COBBLE, 3, transaction), 3L, "take, then roll back");
                }
                helper.assertValueEqual(amount(grid, COBBLE), 6L, "rolled-back take is back in AE2");
                try (Transaction transaction = Transaction.openRoot()) {
                    helper.assertValueEqual(network.insert(IRON, 5, transaction), 5L, "store through the bridge");
                    helper.assertValueEqual(amount(grid, IRON), 0L, "nothing stored before the commit");
                    transaction.commit();
                }
                helper.assertValueEqual(amount(grid, IRON), 5L, "stored in AE2 at the commit");
                helper.assertValueEqual(network.contents().amount(IRON), 5L, "iron in the totals once");
                helper.assertValueEqual(bridge.getFirst().storage().heldTotal(), 0L, "nothing held");
            }).thenSucceed();
        }

        /**
         * The showcase's bridges station with AE2: the AE2 grid boots, is stocked, and gets its bridge; the Storage
         * network lists AE2's amethyst shards and the AE2 grid the station's cobblestone.
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
                helper.assertTrue(level.getBlockEntity(pad.offset(3, 0, 5)) instanceof BridgeBlockEntity bridge
                        && bridge.isMounted(), "the AE2 bridge is not mounted");
                helper.assertValueEqual(network.contents().amount(ShowcaseBridges.Foreign.AE2.marker()), 256L,
                        "AE2's shards in the Storage network");
                IGrid grid = gridAt(helper, pad.offset(3, 0, 6).subtract(helper.absolutePos(BlockPos.ZERO)));
                helper.assertTrue(amount(grid.getStorageService().getInventory(), COBBLE) >= 500,
                        "the station's cobblestone in AE2");
            }).thenExecute(() -> Showcase.clear(level, build.bounds())).thenSucceed();
        }

        /**
         * The Storage network shows in AE2 and AE2 takes from it; the bridge never counts its own network through AE2,
         * also after a refresh.
         */
        static void exportLoop(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            List<BridgeBlockEntity> bridge = new ArrayList<>();
            IActionSource source = IActionSource.empty();
            helper.startSequence().thenWaitUntil(() -> grid(helper)).thenExecute(() -> {
                device.provider.setDirect(DIAMOND, 5);
                helper.assertValueEqual(grid(helper).insert(ae(COBBLE), 7, Actionable.MODULATE, source), 7L,
                        "cobble in AE2");
                bridge.add(bridge(helper));
            }).thenWaitUntil(() -> {
                helper.assertTrue(bridge.getFirst().isMounted(), "bridge mounted");
                helper.assertValueEqual(amount(grid(helper), DIAMOND), 5L, "AE2 sees the Storage network");
            }).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, 100_000);
                NetworkState network = NetworkGameTests.state(helper, controller);
                MEStorage grid = grid(helper);
                helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds counted once");
                helper.assertValueEqual(network.contents().amount(COBBLE), 7L, "AE2 cobble counted once");
                helper.assertValueEqual(grid.extract(ae(DIAMOND), 2, Actionable.SIMULATE, source), 2L,
                        "AE2 simulates a take from Storage");
                helper.assertValueEqual(device.provider.amount(DIAMOND), 5L, "a simulation moves nothing");
                helper.assertValueEqual(grid.extract(ae(DIAMOND), 2, Actionable.MODULATE, source), 2L,
                        "AE2 takes from Storage");
                helper.assertValueEqual(device.provider.amount(DIAMOND), 3L, "taken out of Storage's own storage");
                helper.assertValueEqual(amount(grid, COBBLE), 7L, "AE2 cobble once on AE2's side too");
                helper.assertValueEqual(grid.insert(ae(COBBLE), 3, Actionable.MODULATE, source), 3L, "more cobble");
            }).thenWaitUntil(() -> {
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "polled change shows");
            }).thenExecute(() -> {
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertValueEqual(network.contents().amount(DIAMOND), 3L, "diamonds still once after a refresh");
                helper.assertValueEqual(take(network, DIAMOND, 3), 3L, "own diamonds taken natively");
                helper.assertValueEqual(network.contents().amount(DIAMOND), 0L, "none counted through AE2");
            }).thenSucceed();
        }

        /**
         * Breaking the bridge takes the Storage network out of the AE2 grid's inventory at once and AE2's contents out
         * of the Storage network's totals; both sides keep their own contents.
         */
        static void breakUnmounts(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            List<BridgeBlockEntity> bridge = new ArrayList<>();
            IActionSource source = IActionSource.empty();
            helper.startSequence().thenWaitUntil(() -> grid(helper)).thenExecute(() -> {
                device.provider.setDirect(DIAMOND, 5);
                helper.assertValueEqual(grid(helper).insert(ae(COBBLE), 7, Actionable.MODULATE, source), 7L,
                        "cobble in AE2");
                bridge.add(bridge(helper));
            }).thenWaitUntil(() -> {
                helper.assertTrue(bridge.getFirst().isMounted(), "bridge mounted");
                helper.assertValueEqual(amount(grid(helper), DIAMOND), 5L, "AE2 sees the Storage network");
                helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 7L,
                        "AE2 cobble in the totals");
                helper.assertValueEqual(exports(grid(helper)), 1, "one export mounted");
            }).thenExecute(() -> {
                helper.setBlock(NetworkGameTests.at(4), Blocks.AIR);
                MEStorage grid = grid(helper);
                helper.assertValueEqual(exports(grid), 0, "export unmounted with the bridge");
                helper.assertValueEqual(amount(grid, DIAMOND), 0L, "Storage network gone from AE2 with the bridge");
                helper.assertValueEqual(grid.extract(ae(DIAMOND), 1, Actionable.SIMULATE, source), 0L,
                        "AE2 cannot take from Storage any more");
                helper.assertValueEqual(amount(grid, COBBLE), 7L, "AE2's own contents stay");
                helper.assertValueEqual(device.provider.amount(DIAMOND), 5L, "Storage's own contents stay");
            }).thenWaitUntil(
                    () -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE),
                            0L, "AE2 cobble gone from the totals"))
                    .thenSucceed();
        }

        /**
         * Two bridges of one Storage network facing one AE2 grid (a second energy cell at (5, 1, 0) next to the first,
         * a second bridge at (4, 1, 0) on a cable at (3, 1, 0)): only one mounts, so each side counts the other once;
         * when it is broken the other takes over with the same totals.
         */
        // The bridges' foreign networks are compared by identity: one AE2 grid is one object.
        @SuppressWarnings("ReferenceEquality")
        static void twoBridges(GameTestHelper helper) {
            BlockPos controller = layout(helper);
            helper.setBlock(new BlockPos(5, 1, 0), AEBlocks.CREATIVE_ENERGY_CELL.block());
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 0)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            List<BridgeBlockEntity> bridges = new ArrayList<>();
            IActionSource source = IActionSource.empty();
            helper.startSequence().thenWaitUntil(() -> grid(helper)).thenExecute(() -> {
                device.provider.setDirect(DIAMOND, 5);
                helper.assertValueEqual(grid(helper).insert(ae(COBBLE), 7, Actionable.MODULATE, source), 7L,
                        "cobble in AE2");
                bridges.add(bridge(helper));
                bridges.add(bridge(helper, new BlockPos(4, 1, 0)));
            }).thenWaitUntil(() -> {
                BridgeBlockEntity first = bridges.get(0);
                BridgeBlockEntity second = bridges.get(1);
                Object shared = foreignGrid(first);
                helper.assertTrue(shared != null && shared == foreignGrid(second), "both bridges face one grid");
                helper.assertTrue(first.isMounted() != second.isMounted(), "exactly one bridge mounted");
                helper.assertValueEqual(amount(grid(helper), DIAMOND), 5L, "AE2 counts the Storage network once");
                helper.assertValueEqual(exports(grid(helper)), 1, "one export mounted");
                helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 7L,
                        "Storage counts the AE2 grid once");
            }).thenExecute(() -> {
                BridgeBlockEntity lead = bridges.get(0).isMounted() ? bridges.get(0) : bridges.get(1);
                helper.setBlock(lead == bridges.get(0) ? NetworkGameTests.at(4) : new BlockPos(4, 1, 0), Blocks.AIR);
                bridges.remove(lead);
            }).thenWaitUntil(() -> {
                helper.assertTrue(bridges.getFirst().isMounted(), "the standby bridge took over");
                helper.assertValueEqual(amount(grid(helper), DIAMOND), 5L, "AE2 still counts the Storage network once");
                helper.assertValueEqual(exports(grid(helper)), 1, "one export mounted after the hand-over");
                helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 7L,
                        "Storage still counts the AE2 grid once");
            }).thenSucceed();
        }

        /**
         * The bridge's grid merges with a bigger one and splits off again while mounted. The bridge-side grid is a lone
         * energy cell at x = 5; the bigger grid is a drive with an item cell at x = 7, an energy cell at 8 and an empty
         * drive at 9; an energy cell placed at 6 joins them. AE2 keeps the bigger grid's object on a merge and the
         * pivot's on a split, so both times the bridge's node moves to another grid: the bridge notices on its poll
         * ({@code connected()} false), finds the new grid, mounts it again, and both sides count each other exactly
         * once. After the split the bigger grid no longer holds the Storage network.
         */
        // AE2 grids are compared by identity: a merge or split makes the node's grid another object.
        @SuppressWarnings("ReferenceEquality")
        static void mergeSplit(GameTestHelper helper) {
            BlockPos controller = NetworkGameTests.controller(helper, 1);
            NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
            BlockPos joint = NetworkGameTests.at(6);
            BlockPos farCell = NetworkGameTests.at(8);
            helper.setBlock(CELL, AEBlocks.CREATIVE_ENERGY_CELL.block());
            helper.setBlock(NetworkGameTests.at(7), AEBlocks.DRIVE.block());
            helper.getBlockEntity(NetworkGameTests.at(7), DriveBlockEntity.class).getInternalInventory().insertItem(0,
                    AEItems.ITEM_CELL_1K.stack(), false);
            helper.setBlock(farCell, AEBlocks.CREATIVE_ENERGY_CELL.block());
            helper.setBlock(NetworkGameTests.at(9), AEBlocks.DRIVE.block());
            TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
            List<BridgeBlockEntity> bridge = new ArrayList<>();
            List<IGrid> before = new ArrayList<>();
            IActionSource source = IActionSource.empty();
            helper.startSequence().thenWaitUntil(() -> {
                helper.assertTrue(gridAt(helper, CELL) != gridAt(helper, farCell), "two grids");
            }).thenExecute(() -> {
                device.provider.setDirect(DIAMOND, 5);
                helper.assertValueEqual(inventory(helper, farCell).insert(ae(COBBLE), 7, Actionable.MODULATE, source),
                        7L, "cobble in the far grid");
                bridge.add(bridge(helper));
            }).thenWaitUntil(() -> {
                helper.assertTrue(bridge.getFirst().isMounted(), "bridge mounted");
                helper.assertTrue(foreignGrid(bridge.getFirst()) == gridAt(helper, CELL), "bridge on its grid");
                helper.assertValueEqual(amount(grid(helper), DIAMOND), 5L, "AE2 sees the Storage network");
            }).thenExecute(() -> {
                before.add(gridAt(helper, CELL));
                helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 0L,
                        "far cobble not reachable yet");
                helper.setBlock(joint, AEBlocks.CREATIVE_ENERGY_CELL.block());
            }).thenWaitUntil(() -> {
                IGrid merged = gridAt(helper, CELL);
                helper.assertTrue(merged == gridAt(helper, farCell), "grids merged");
                helper.assertTrue(foreignGrid(bridge.getFirst()) == merged, "bridge found the merged grid");
                helper.assertTrue(bridge.getFirst().isMounted(), "bridge mounted on the merged grid");
                helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 7L,
                        "far cobble through the merged grid");
                helper.assertValueEqual(amount(grid(helper), DIAMOND), 5L, "merged grid sees the Storage network");
            }).thenExecute(() -> {
                helper.assertTrue(gridAt(helper, CELL) != before.getFirst(), "the merge moved the bridge's node");
                NetworkState network = NetworkGameTests.state(helper, controller);
                helper.assertValueEqual(network.contents().amount(COBBLE), 7L, "AE2 cobble counted once");
                helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds counted once");
                helper.assertValueEqual(amount(grid(helper), COBBLE), 7L, "AE2 cobble once on AE2's side");
                helper.assertValueEqual(grid(helper).extract(ae(DIAMOND), 6, Actionable.SIMULATE, source), 5L,
                        "AE2 can take only the five diamonds there are");
                helper.assertValueEqual(exports(grid(helper)), 1, "one export on the merged grid");
                before.set(0, gridAt(helper, CELL));
                helper.setBlock(joint, Blocks.AIR);
            }).thenWaitUntil(() -> {
                IGrid near = gridAt(helper, CELL);
                helper.assertTrue(near != gridAt(helper, farCell), "grids split");
                helper.assertTrue(foreignGrid(bridge.getFirst()) == near, "bridge found its split-off grid");
                helper.assertTrue(bridge.getFirst().isMounted(), "bridge mounted on the split-off grid");
                helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 0L,
                        "far cobble gone from the totals");
                helper.assertValueEqual(amount(grid(helper), DIAMOND), 5L, "split-off grid sees the Storage network");
                helper.assertValueEqual(amount(inventory(helper, farCell), DIAMOND), 0L,
                        "far grid no longer holds the Storage network");
            }).thenExecute(() -> {
                helper.assertValueEqual(exports(grid(helper)), 1, "one export on the split-off grid");
                helper.assertValueEqual(exports(inventory(helper, farCell)), 0, "no export left on the far grid");
                helper.assertTrue(gridAt(helper, CELL) != before.getFirst(), "the split moved the bridge's node");
                helper.assertValueEqual(amount(inventory(helper, farCell), COBBLE), 7L, "far cobble stays in AE2");
                helper.assertValueEqual(device.provider.amount(DIAMOND), 5L, "own diamonds stay");
                helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(DIAMOND), 5L,
                        "own diamonds counted once");
            }).thenSucceed();
        }
    }
}
