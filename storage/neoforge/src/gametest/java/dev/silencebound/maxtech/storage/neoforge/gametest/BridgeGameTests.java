package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.bridge.BridgeBlock;
import dev.silencebound.maxtech.storage.bridge.BridgeBlockEntity;
import dev.silencebound.maxtech.storage.bridge.BridgeContent;
import dev.silencebound.maxtech.storage.bridge.BridgeStorage;
import dev.silencebound.maxtech.storage.bridge.ForeignNetworks;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortTier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: tests move items in NeoForge transactions like the network does.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * The network bridge (M7b item 1) against a fake foreign network ({@link FakeForeignNetwork}): its contents in the
 * Storage network's totals, takes and stores that move exactly what really moved and nothing on rollback, a real move
 * falling short of the forecast without dupe or loss, the loop guard with the foreign network mounting the export, two
 * bridges between one pair of networks counting once, the tier's rate and the slow poll, thousands of moves in one
 * transaction (exact and within a perf budget) and the bridge going inside a transaction.
 *
 * <p>
 * Layout on one row: a controller at x = 1, cables 2 and 3, the bridge at x = 4 facing east to the foreign block at x =
 * 5; a second bridge at (3, 1, 2) faces south to (3, 1, 3).
 */
// Networks and bridges are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class BridgeGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final StackKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final StackKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final StackKey WATER = FluidKey.of(Fluids.WATER);
    private static final StackKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final String PERF = "perf_bridge_moves";
    /** Moves of 1 mB in the perf test's transaction, half takes, half stores; within a tier-3 bridge's fluid rate. */
    private static final int PERF_MOVES = 8_000;
    /**
     * 8,000 moves in one transaction and their rollback. Each move is a few map reads and writes plus a call into the
     * fake network: about 7 ms on a desktop. Moves that scanned the transaction's log instead took 40 to 75 ms there.
     */
    private static final long BUDGET_NANOS = 20_000_000;
    private static final BlockPos SECOND = new BlockPos(3, 1, 2);
    /** Fake networks by the absolute position of the block that stands for them. */
    private static final Map<BlockPos, FakeForeignNetwork> FAKES = new HashMap<>();
    private static final String[] NAMES = {"bridge_shows_foreign_contents", "bridge_take_exact", "bridge_store_exact",
            "bridge_short_modulate_no_dupe", "bridge_loop_guard", "bridge_two_bridges_once", "bridge_tier_rate",
            "bridge_polls_without_events", "bridge_drops_held", "bridge_many_moves", "bridge_unmount_in_transaction",
            "bridge_break_in_transaction", "bridge_break_refused_fluid", "bridge_break_drops_copied"};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        ForeignNetworks.register((level, pos, side) -> {
            FakeForeignNetwork fake = FAKES.get(pos);
            return fake == null ? null : fake.handle();
        });
        StorageTests.function(NAMES[0], StorageTestEnvironment.clearing(BridgeGameTests::showsContents));
        StorageTests.function(NAMES[1], StorageTestEnvironment.clearing(BridgeGameTests::takeExact));
        StorageTests.function(NAMES[2], StorageTestEnvironment.clearing(BridgeGameTests::storeExact));
        StorageTests.function(NAMES[3], StorageTestEnvironment.clearing(BridgeGameTests::shortModulate));
        StorageTests.function(NAMES[4], StorageTestEnvironment.clearing(BridgeGameTests::loopGuard));
        StorageTests.function(NAMES[5], StorageTestEnvironment.clearing(BridgeGameTests::twoBridges));
        StorageTests.function(NAMES[6], StorageTestEnvironment.clearing(BridgeGameTests::tierRate));
        StorageTests.function(NAMES[7], StorageTestEnvironment.clearing(BridgeGameTests::polls));
        StorageTests.function(NAMES[8], StorageTestEnvironment.clearing(BridgeGameTests::dropsHeld));
        StorageTests.function(NAMES[9], StorageTestEnvironment.clearing(BridgeGameTests::manyMoves));
        StorageTests.function(NAMES[10], StorageTestEnvironment.clearing(BridgeGameTests::unmountInTransaction));
        StorageTests.function(NAMES[11], StorageTestEnvironment.clearing(BridgeGameTests::breakInTransaction));
        StorageTests.function(NAMES[12], StorageTestEnvironment.clearing(BridgeGameTests::breakRefusedFluid));
        StorageTests.function(NAMES[13], StorageTestEnvironment.clearing(BridgeGameTests::breakDropsCopied));
        StorageTests.function(PERF, StorageTestEnvironment.clearing(BridgeGameTests::perf));
    }

    private BridgeGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("bridge"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
        // Its own environment, so it runs in its own batch.
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(PERF), StorageTestEnvironment.plain()),
                PERF, STRUCTURE, 400);
    }

    // --- Layout --------------------------------------------------------------------------------------------------

    /** A powered network: controller and cables 2 to 3. Returns the controller's position. */
    private static BlockPos network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return controller;
    }

    /** A bridge of {@code tier} at {@code pos} facing {@code facing}, with {@code fake} behind the block it faces. */
    private static BridgeBlockEntity bridge(GameTestHelper helper, BlockPos pos, Direction facing, PortTier tier,
            FakeForeignNetwork fake) {
        BlockPos front = pos.relative(facing);
        helper.setBlock(front, Blocks.IRON_BLOCK);
        FAKES.put(helper.absolutePos(front), fake);
        BridgeBlock block = switch (tier) {
            case TIER_1 -> BridgeContent.BRIDGE_TIER_1.get();
            case TIER_2 -> BridgeContent.BRIDGE_TIER_2.get();
            case TIER_3 -> BridgeContent.BRIDGE_TIER_3.get();
        };
        helper.setBlock(pos, block.defaultBlockState().setValue(BridgeBlock.FACING, facing));
        return helper.getBlockEntity(pos, BridgeBlockEntity.class);
    }

    private static BridgeBlockEntity bridge(GameTestHelper helper, PortTier tier, FakeForeignNetwork fake) {
        return bridge(helper, NetworkGameTests.at(4), Direction.EAST, tier, fake);
    }

    private static NetworkState mounted(GameTestHelper helper, BlockPos controller, BridgeBlockEntity bridge) {
        NetworkState network = NetworkGameTests.state(helper, controller);
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

    // --- Tests ---------------------------------------------------------------------------------------------------

    /**
     * What the foreign network holds shows in the totals once mounted, and a change it signals shows after the next
     * refresh; the network's own (native) storage, which is what it exports, leaves the bridge out.
     */
    private static void showsContents(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.own.put(COBBLE, 50);
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = mounted(helper, controller, bridge);
            helper.assertValueEqual(network.contents().amount(COBBLE), 50L, "foreign cobble in the totals");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.nativeAmount(COBBLE), 0L, "the bridge is not native storage");
            fake.setDirect(IRON, 12);
        }).thenWaitUntil(
                () -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(IRON), 12L,
                        "signalled change shows"))
                .thenExecute(() -> FAKES.values().remove(fake)).thenSucceed();
    }

    /**
     * A take returns exactly what came out of the foreign network, also when that is less than the network asked for; a
     * rolled-back take puts it back, and what the foreign network then refuses stays held and counted.
     */
    private static void takeExact(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.own.put(COBBLE, 10);
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = mounted(helper, controller, bridge);
            helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "cobble shows");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 100_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            fake.modulateExtractCap = 6;
            helper.assertValueEqual(extract(network, COBBLE, 10), 6L, "take returns what really came out");
            helper.assertValueEqual(fake.own.getLong(COBBLE), 4L, "foreign after the take");
            helper.assertValueEqual(network.contents().amount(COBBLE), 4L, "totals after the take");
            fake.modulateExtractCap = -1;
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.extract(COBBLE, 3, transaction), 3L, "take in a rolled-back move");
            }
            helper.assertValueEqual(fake.own.getLong(COBBLE), 4L, "rolled-back take is back");
            helper.assertValueEqual(network.contents().amount(COBBLE), 4L, "totals after the rollback");
            fake.refuseInserts = true;
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.extract(COBBLE, 2, transaction), 2L, "take before a refused return");
            }
            helper.assertValueEqual(fake.own.getLong(COBBLE), 2L, "foreign refused the return");
            helper.assertValueEqual(bridge.storage().heldTotal(), 2L, "the bridge holds what was refused");
            helper.assertValueEqual(network.contents().amount(COBBLE), 4L, "nothing lost from the totals");
            fake.refuseInserts = false;
            helper.assertValueEqual(extract(network, COBBLE, 4), 4L, "held and foreign both taken");
            helper.assertValueEqual(fake.own.getLong(COBBLE), 0L, "foreign empty");
            helper.assertValueEqual(bridge.storage().heldTotal(), 0L, "held empty");
            helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "totals empty");
            FAKES.values().remove(fake);
        }).thenSucceed();
    }

    /** A store goes in once, at commit; a rolled-back store, also a nested one, never reaches the foreign network. */
    private static void storeExact(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 100_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(NetworkGameTests.insert(network, IRON, 10), 10L, "store");
            helper.assertValueEqual(fake.own.getLong(IRON), 10L, "foreign after the store");
            helper.assertValueEqual(network.contents().amount(IRON), 10L, "totals after the store");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.insert(IRON, 5, transaction), 5L, "store in a rolled-back move");
                helper.assertValueEqual(fake.own.getLong(IRON), 10L, "nothing real before the commit");
            }
            helper.assertValueEqual(fake.own.getLong(IRON), 10L, "rolled-back store");
            try (Transaction root = Transaction.openRoot()) {
                try (Transaction kept = Transaction.open(root)) {
                    network.insert(IRON, 3, kept);
                    kept.commit();
                }
                try (Transaction dropped = Transaction.open(root)) {
                    network.insert(IRON, 2, dropped);
                }
                root.commit();
            }
            helper.assertValueEqual(fake.own.getLong(IRON), 13L, "only the kept nested store");
            helper.assertValueEqual(network.contents().amount(IRON), 13L, "totals after the nested stores");
            helper.assertValueEqual(bridge.storage().heldTotal(), 0L, "nothing held");
            FAKES.values().remove(fake);
        }).thenSucceed();
    }

    /**
     * The foreign network takes less at the real insert than it forecast: the rest is held by the bridge, counted and
     * carried by its dropped item, offered again later, and everything stored can be taken back exactly once.
     */
    private static void shortModulate(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.modulateInsertCap = 4;
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 100_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(NetworkGameTests.insert(network, IRON, 10), 10L, "store promised in full");
            helper.assertValueEqual(fake.own.getLong(IRON), 4L, "foreign took less");
            helper.assertValueEqual(bridge.storage().heldTotal(), 6L, "the bridge holds the rest");
            helper.assertValueEqual(network.contents().amount(IRON), 10L, "totals: nothing lost, nothing twice");
            ItemStack drop = new ItemStack(BridgeContent.BRIDGE_TIER_3.get());
            bridge.writeHeld(drop, helper.getLevel().registryAccess());
            helper.assertTrue(drop.has(DataComponents.BLOCK_ENTITY_DATA), "the dropped bridge carries the held");
            CompoundTag tag = bridge.saveWithoutMetadata(helper.getLevel().registryAccess());
            BridgeBlockEntity loaded = new BridgeBlockEntity(bridge.getBlockPos(), bridge.getBlockState());
            loaded.loadCustomOnly(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
            helper.assertValueEqual(loaded.storage().heldTotal(), 6L, "held after loading");
            fake.modulateInsertCap = -1;
            fake.setDirect(COBBLE, 1);
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(bridge.storage().heldTotal(), 0L, "held offered again");
            helper.assertValueEqual(fake.own.getLong(IRON), 10L, "foreign has it all");
        }).thenIdle(20).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.contents().amount(IRON), 10L, "totals after the hand-over");
            helper.assertValueEqual(extract(network, IRON, 64), 10L, "everything back, once");
            helper.assertValueEqual(fake.own.getLong(IRON), 0L, "foreign empty");
            helper.assertValueEqual(network.contents().amount(IRON), 0L, "totals empty");
            FAKES.values().remove(fake);
        }).thenSucceed();
    }

    /**
     * The foreign network mounts the export and lists and fills it like any storage: the Storage network still counts
     * its own diamonds once, a store through the bridge lands in the foreign store instead of coming back, nothing
     * recurses, and the foreign side does see and fill the Storage network through the export.
     */
    private static void loopGuard(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.mountsExport = true;
        fake.own.put(COBBLE, 30);
        BlockPos controller = network(helper);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenExecute(() -> device.provider.setDirect(DIAMOND, 5)).thenWaitUntil(() -> {
            NetworkState network = mounted(helper, controller, bridge);
            helper.assertValueEqual(fake.mounts().size(), 1, "export mounted");
            helper.assertValueEqual(network.contents().amount(COBBLE), 30L, "foreign cobble");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 100_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "own diamonds counted once");
            helper.assertValueEqual(bridge.exported().amount(DIAMOND), 5L, "the foreign side sees the diamonds");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(bridge.storage().insert(IRON, 8, transaction), 8L, "store through the bridge");
                transaction.commit();
            }
            helper.assertValueEqual(fake.own.getLong(IRON), 8L, "the store stayed in the foreign network");
            helper.assertValueEqual(device.provider.amount(IRON), 0L, "nothing came back through the export");
            helper.assertValueEqual(network.contents().amount(IRON), 8L, "iron counted once");
            helper.assertValueEqual(extract(network, COBBLE, 5), 5L, "take through the bridge");
            helper.assertValueEqual(fake.own.getLong(COBBLE), 25L, "taken from the foreign store");
            helper.assertValueEqual(bridge.exported().insert(GOLD, 4, false), 4L, "the foreign side stores");
            helper.assertValueEqual(device.provider.amount(GOLD), 4L, "into the Storage network's own storage");
            helper.assertValueEqual(fake.maxDepth, 1, "no call into the foreign network nested");
            fake.setDirect(COBBLE, 20);
        }).thenWaitUntil(
                () -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 20L,
                        "refreshed"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.contents().amount(GOLD), 4L, "gold counted once after a refresh");
                    helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "diamonds still once");
                    helper.assertValueEqual(fake.maxDepth, 1, "no recursion");
                    FAKES.values().remove(fake);
                }).thenSucceed();
    }

    /**
     * Two bridges of one network to the same foreign network: one mounts and exports, the other stands by; the contents
     * count once, a store lands once, and the standby takes over when the first goes.
     */
    private static void twoBridges(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.mountsExport = true;
        fake.own.put(COBBLE, 30);
        BlockPos controller = network(helper);
        BridgeBlockEntity first = bridge(helper, PortTier.TIER_3, fake);
        BridgeBlockEntity second = bridge(helper, SECOND, Direction.SOUTH, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(first.network() == network && second.network() == network, "both bridges in");
            helper.assertTrue(first.isMounted() != second.isMounted(), "exactly one bridge mounts");
            helper.assertValueEqual(fake.mounts().size(), 1, "one export");
            helper.assertValueEqual(network.contents().amount(COBBLE), 30L, "foreign cobble once");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 100_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 5), 5L, "store");
            helper.assertValueEqual(fake.own.getLong(COBBLE), 35L, "stored once in the foreign network");
            helper.assertValueEqual(network.contents().amount(COBBLE), 35L, "counted once");
            helper.assertValueEqual(fake.maxDepth, 1, "no ping-pong");
            BridgeBlockEntity gone = first.isMounted() ? first : second;
            helper.setBlock(gone == first ? NetworkGameTests.at(4) : SECOND, Blocks.AIR);
        }).thenWaitUntil(() -> {
            BridgeBlockEntity left = first.isRemoved() ? second : first;
            helper.assertTrue(left.isMounted(), "the standby took over");
            helper.assertValueEqual(fake.mounts().size(), 1, "one export after the hand-over");
            helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 35L,
                    "counted once after the hand-over");
        }).thenExecute(() -> FAKES.values().remove(fake)).thenSucceed();
    }

    /** A bridge moves at most its port tier's items per second; a tier-3 bridge moves more than a tier-1 one. */
    private static void tierRate(GameTestHelper helper) {
        FakeForeignNetwork slowFake = new FakeForeignNetwork();
        slowFake.own.put(COBBLE, 10_000);
        FakeForeignNetwork fastFake = new FakeForeignNetwork();
        fastFake.own.put(IRON, 10_000);
        BlockPos controller = network(helper);
        BridgeBlockEntity slow = bridge(helper, PortTier.TIER_1, slowFake);
        BridgeBlockEntity fast = bridge(helper, SECOND, Direction.SOUTH, PortTier.TIER_3, fastFake);
        helper.startSequence().thenWaitUntil(() -> {
            mounted(helper, controller, slow);
            mounted(helper, controller, fast);
        }).thenExecute(() -> {
            long tier1 = StorageServerConfig.PORT_TIER_1_ITEMS.getAsInt();
            long tier3 = StorageServerConfig.PORT_TIER_3_ITEMS.getAsInt();
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(slow.storage().extract(COBBLE, 10_000, transaction), tier1, "tier-1 rate");
                helper.assertValueEqual(slow.storage().extract(COBBLE, 10_000, transaction), 0L,
                        "the rate counts the open transaction");
                helper.assertValueEqual(fast.storage().extract(IRON, 10_000, transaction), tier3, "tier-3 rate");
                transaction.commit();
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(slow.storage().extract(COBBLE, 10_000, transaction), 0L,
                        "spent for this second");
            }
            helper.assertValueEqual(slowFake.own.getLong(COBBLE), 10_000 - tier1, "tier-1 moved its rate");
            FAKES.values().remove(slowFake);
            FAKES.values().remove(fastFake);
        }).thenSucceed();
    }

    /** A foreign network that cannot signal changes is read again on the slow poll, not before. */
    private static void polls(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.events = false;
        fake.own.put(COBBLE, 5);
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        long[] changedAt = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = mounted(helper, controller, bridge);
            helper.assertValueEqual(network.contents().amount(COBBLE), 5L, "first read");
        }).thenExecute(() -> {
            fake.setDirect(COBBLE, 9);
            changedAt[0] = helper.getLevel().getGameTime();
        }).thenIdle(StorageServerConfig.BRIDGE_REFRESH_TICKS.getAsInt() + 1)
                .thenExecute(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 5L,
                        "no signal: not read yet"))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, controller).contents().amount(COBBLE), 9L, "polled"))
                .thenExecute(() -> {
                    long waited = helper.getLevel().getGameTime() - changedAt[0];
                    helper.assertTrue(waited <= StorageServerConfig.BRIDGE_POLL_TICKS.getAsInt() + 2,
                            "read within one poll interval, after " + waited);
                    FAKES.values().remove(fake);
                }).thenSucceed();
    }

    /**
     * A bridge holding a shortfall drops itself with the held store, also when broken in creative mode; placed again,
     * the held store is back in the network's totals.
     */
    private static void dropsHeld(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.modulateInsertCap = 4;
        BlockPos controller = network(helper);
        BlockPos pos = NetworkGameTests.at(4);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 100_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(NetworkGameTests.insert(network, IRON, 10), 10L, "store");
            helper.assertValueEqual(bridge.storage().heldTotal(), 6L, "held after the short insert");
            fake.refuseInserts = true;
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            ItemStack item = drop(helper, pos);
            helper.assertTrue(item.has(DataComponents.BLOCK_ENTITY_DATA), "the dropped bridge carries the held store");
            helper.assertValueEqual(network.contents().amount(IRON), 0L, "nothing left counted after breaking");
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            BlockPos front = helper.absolutePos(pos.east());
            item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(front), Direction.WEST, front, false)));
            helper.assertTrue(player.getMainHandItem().isEmpty(), "the bridge item stayed in the hand");
        }).thenWaitUntil(() -> {
            BridgeBlockEntity placed = helper.getBlockEntity(pos, BridgeBlockEntity.class);
            helper.assertValueEqual(placed.facing(), Direction.EAST, "placed facing the foreign block");
            mounted(helper, controller, placed);
            helper.assertValueEqual(placed.storage().heldTotal(), 6L, "held after placing again");
            helper.assertValueEqual(NetworkGameTests.state(helper, controller).contents().amount(IRON), 10L,
                    "foreign and held counted after placing again");
        }).thenExecute(() -> {
            Player creative = helper.makeMockPlayer(GameType.CREATIVE);
            // The mock player's abilities do not follow its game mode.
            creative.getAbilities().instabuild = true;
            BlockState state = helper.getBlockState(pos);
            BlockPos absolute = helper.absolutePos(pos);
            state.getBlock().playerWillDestroy(helper.getLevel(), absolute, state, creative);
            helper.getLevel().removeBlock(absolute, false);
            ItemStack item = drop(helper, pos);
            helper.assertTrue(item.has(DataComponents.BLOCK_ENTITY_DATA), "held kept when broken in creative mode");
            FAKES.values().remove(fake);
        }).thenSucceed();
    }

    /**
     * Thousands of moves in one transaction, some in nested ones: every move is capped exactly by what the rate and the
     * last read still allow after the earlier ones, a nested rollback frees exactly its part, and the root's rollback
     * or commit leaves the foreign network and the totals exact.
     */
    private static void manyMoves(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.own.put(WATER, 1500);
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = mounted(helper, controller, bridge);
            helper.assertValueEqual(network.contents().amount(WATER), 1500L, "water shows");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            BridgeStorage storage = bridge.storage();
            long rate = StorageServerConfig.PORT_TIER_3_FLUID_MB.getAsInt();
            try (Transaction root = Transaction.openRoot()) {
                moves(helper, storage, 1000, 0, root);
                try (Transaction nested = Transaction.open(root)) {
                    moves(helper, storage, 500, 500, nested);
                }
                helper.assertValueEqual(fake.own.getLong(WATER), 500L, "the nested takes went back");
                helper.assertValueEqual(storage.extract(WATER, 1_000_000, root), 500L,
                        "a take is capped by the last read minus the open takes");
                helper.assertValueEqual(storage.insert(LAVA, 1_000_000, root), rate - 1500,
                        "a store is capped by the rate minus the open moves");
                helper.assertValueEqual(storage.extract(WATER, 1, root), 0L, "nothing left of the rate");
            }
            helper.assertValueEqual(fake.own.getLong(WATER), 1500L, "rolled-back takes are back");
            helper.assertValueEqual(fake.own.getLong(LAVA), 0L, "rolled-back stores never went in");
            helper.assertValueEqual(network.contents().amount(WATER), 1500L, "totals after the rollback");
            try (Transaction root = Transaction.openRoot()) {
                moves(helper, storage, 1000, 1000, root);
                try (Transaction nested = Transaction.open(root)) {
                    moves(helper, storage, 0, 100, nested);
                    nested.commit();
                }
                root.commit();
            }
            helper.assertValueEqual(fake.own.getLong(WATER), 500L, "foreign water after the commit");
            helper.assertValueEqual(fake.own.getLong(LAVA), 1100L, "foreign lava after the commit");
            helper.assertValueEqual(storage.heldTotal(), 0L, "nothing held");
            helper.assertValueEqual(network.contents().amount(WATER), 500L, "water totals after the commit");
            helper.assertValueEqual(network.contents().amount(LAVA), 1100L, "lava totals after the commit");
            helper.assertValueEqual(storage.lastRead(WATER), 500L, "last read follows the committed takes");
            FAKES.values().remove(fake);
        }).thenSucceed();
    }

    /** {@code takes} takes of 1 mB of water and {@code stores} stores of 1 mB of lava, interleaved, each moving 1. */
    private static void moves(GameTestHelper helper, BridgeStorage storage, int takes, int stores,
            Transaction transaction) {
        for (int i = 0; i < Math.max(takes, stores); i++) {
            if (i < takes && storage.extract(WATER, 1, transaction) != 1) {
                throw helper.assertionException(Component.literal("take " + i + " fell short"));
            }
            if (i < stores && storage.insert(LAVA, 1, transaction) != 1) {
                throw helper.assertionException(Component.literal("store " + i + " fell short"));
            }
        }
    }

    /**
     * The bridge goes inside a transaction that has a take through it open: the last read is forgotten only once the
     * take commits, not while the commit still has to count it.
     */
    private static void unmountInTransaction(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.own.put(COBBLE, 10);
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            BridgeStorage storage = bridge.storage();
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(storage.extract(COBBLE, 3, transaction), 3L, "take");
                helper.setBlock(NetworkGameTests.at(4), Blocks.AIR);
                helper.assertTrue(!bridge.isMounted(), "unmounted inside the transaction");
                helper.assertValueEqual(storage.lastRead(COBBLE), 10L, "last read kept while the take is open");
                transaction.commit();
            }
            helper.assertValueEqual(storage.lastRead(COBBLE), 0L, "last read forgotten after the commit");
            helper.assertValueEqual(fake.own.getLong(COBBLE), 7L, "the take came out once");
            FAKES.values().remove(fake);
        }).thenSucceed();
    }

    /**
     * The bridge is broken while a store and a take through it are open. On commit the store still reaches the foreign
     * network and what it refuses goes into the dropped bridge item; on rollback the take goes back and what the
     * foreign network refuses to take back goes into the item. Nothing is lost or counted twice either way.
     */
    private static void breakInTransaction(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.own.put(COBBLE, 10);
        BlockPos controller = network(helper);
        BlockPos pos = NetworkGameTests.at(4);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            BridgeStorage storage = bridge.storage();
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(storage.insert(IRON, 5, transaction), 5L, "store");
                helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
                fake.modulateInsertCap = 2;
                transaction.commit();
            }
            fake.modulateInsertCap = -1;
            helper.assertValueEqual(fake.own.getLong(IRON), 2L, "the committed store reached the foreign network");
            helper.assertValueEqual(dropped(helper, pos, Items.IRON_INGOT), 0L, "no loose iron");
            helper.assertValueEqual(storage.heldTotal(), 0L, "nothing left in the removed bridge");
            helper.assertValueEqual(heldIn(helper, drop(helper, pos), IRON), 3L, "the bridge item holds the refusal");
        }).thenExecute(() -> bridge(helper, PortTier.TIER_3, fake))
                .thenWaitUntil(() -> mounted(helper, controller, helper.getBlockEntity(pos, BridgeBlockEntity.class)))
                .thenExecute(() -> {
                    BridgeStorage storage = helper.getBlockEntity(pos, BridgeBlockEntity.class).storage();
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(storage.insert(IRON, 4, transaction), 4L, "store to roll back");
                        helper.assertValueEqual(storage.extract(COBBLE, 3, transaction), 3L, "take to roll back");
                        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
                        fake.refuseInserts = true;
                    }
                    fake.refuseInserts = false;
                    helper.assertValueEqual(fake.own.getLong(IRON), 2L, "the rolled-back store never went in");
                    helper.assertValueEqual(fake.own.getLong(COBBLE), 7L, "the refused take-back left the network");
                    helper.assertValueEqual(dropped(helper, pos, Items.COBBLESTONE), 0L, "no loose cobble");
                    ItemStack item = drop(helper, pos);
                    helper.assertValueEqual(heldIn(helper, item, COBBLE), 3L, "the bridge item holds the take");
                    helper.assertValueEqual(heldIn(helper, item, IRON), 0L, "and no rolled-back store");
                    FAKES.values().remove(fake);
                }).thenSucceed();
    }

    /** How many of {@code item} lie as item entities near {@code pos}. */
    private static long dropped(GameTestHelper helper, BlockPos pos, Item item) {
        long count = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2))) {
            if (entity.getItem().is(item)) {
                count += entity.getItem().getCount();
            }
        }
        return count;
    }

    /**
     * The bridge's drops are taken and thrown away (as a quarry copying them would) or it is broken in creative mode,
     * each with a store open that the foreign network then partly refuses: exactly one bridge item appears once the
     * transaction closes, holding exactly the refused part, and nothing drops loose.
     */
    private static void breakDropsCopied(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        BlockPos controller = network(helper);
        BlockPos pos = NetworkGameTests.at(4);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            BlockPos absolute = helper.absolutePos(pos);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(bridge.storage().insert(IRON, 5, transaction), 5L, "store");
                List<ItemStack> copied = Block.getDrops(helper.getBlockState(pos), helper.getLevel(), absolute, bridge);
                for (ItemStack stack : copied) {
                    helper.assertTrue(!stack.is(BridgeContent.BRIDGE_TIER_3.item().get()),
                            "no bridge item in the drops while the store is open");
                }
                helper.getLevel().removeBlock(absolute, false);
                fake.modulateInsertCap = 2;
                transaction.commit();
            }
            fake.modulateInsertCap = -1;
            helper.assertValueEqual(fake.own.getLong(IRON), 2L, "the store went in as far as taken");
            helper.assertValueEqual(dropped(helper, pos, Items.IRON_INGOT), 0L, "no loose iron");
            helper.assertValueEqual(heldIn(helper, drop(helper, pos), IRON), 3L,
                    "one fresh bridge item holds the rest");
        }).thenExecute(() -> bridge(helper, PortTier.TIER_3, fake))
                .thenWaitUntil(() -> mounted(helper, controller, helper.getBlockEntity(pos, BridgeBlockEntity.class)))
                .thenExecute(() -> {
                    BridgeBlockEntity second = helper.getBlockEntity(pos, BridgeBlockEntity.class);
                    Player creative = helper.makeMockPlayer(GameType.CREATIVE);
                    creative.getAbilities().instabuild = true;
                    BlockPos absolute = helper.absolutePos(pos);
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(second.storage().insert(IRON, 4, transaction), 4L, "store");
                        BlockState state = helper.getBlockState(pos);
                        state.getBlock().playerWillDestroy(helper.getLevel(), absolute, state, creative);
                        helper.getLevel().removeBlock(absolute, false);
                        helper.assertValueEqual(dropped(helper, pos, BridgeContent.BRIDGE_TIER_3.item().get()), 0L,
                                "no bridge item before the commit");
                        fake.refuseInserts = true;
                        transaction.commit();
                    }
                    fake.refuseInserts = false;
                    helper.assertValueEqual(fake.own.getLong(IRON), 2L, "the refused store did not go in");
                    helper.assertValueEqual(heldIn(helper, drop(helper, pos), IRON), 4L,
                            "the creative break dropped one bridge item holding it");
                    FAKES.values().remove(fake);
                }).thenSucceed();
    }

    /** How much of {@code key} the bridge item {@code stack} carries in its held store, read as placing would. */
    private static long heldIn(GameTestHelper helper, ItemStack stack, StackKey key) {
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return 0;
        }
        BridgeBlockEntity loaded = new BridgeBlockEntity(BlockPos.ZERO,
                BridgeContent.BRIDGE_TIER_3.get().defaultBlockState());
        data.loadInto(loaded, helper.getLevel().registryAccess());
        return loaded.storage().amount(key);
    }

    /**
     * The bridge is broken while a fluid store through it is open and the foreign network refuses the real insert at
     * commit: the fluid cannot drop, so the dropped bridge item holds exactly that amount, and placed again it offers
     * the fluid to the foreign network.
     */
    private static void breakRefusedFluid(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        BlockPos controller = network(helper);
        BlockPos pos = NetworkGameTests.at(4);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(bridge.storage().insert(LAVA, 1234, transaction), 1234L, "fluid store");
                helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
                fake.refuseInserts = true;
                transaction.commit();
            }
            fake.refuseInserts = false;
            helper.assertValueEqual(fake.own.getLong(LAVA), 0L, "the foreign network refused it");
            ItemStack item = drop(helper, pos);
            helper.assertValueEqual(heldIn(helper, item, LAVA), 1234L, "the bridge item holds the refused fluid");
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            BlockPos front = helper.absolutePos(pos.east());
            item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(front), Direction.WEST, front, false)));
        }).thenWaitUntil(() -> {
            BridgeBlockEntity placed = helper.getBlockEntity(pos, BridgeBlockEntity.class);
            mounted(helper, controller, placed);
            helper.assertValueEqual(fake.own.getLong(LAVA), 1234L, "offered again once placed");
            helper.assertValueEqual(placed.storage().heldTotal(), 0L, "held handed over");
        }).thenExecute(() -> FAKES.values().remove(fake)).thenSucceed();
    }

    /** {@link #PERF_MOVES} moves through one bridge in one transaction, rolled back, within {@link #BUDGET_NANOS}. */
    private static void perf(GameTestHelper helper) {
        FakeForeignNetwork fake = new FakeForeignNetwork();
        fake.own.put(WATER, PERF_MOVES);
        BlockPos controller = network(helper);
        BridgeBlockEntity bridge = bridge(helper, PortTier.TIER_3, fake);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, controller, bridge)).thenExecute(() -> {
            BridgeStorage storage = bridge.storage();
            PerfBudget.measureUnder(helper, PERF, BUDGET_NANOS, () -> {
                long start = System.nanoTime();
                try (Transaction root = Transaction.openRoot()) {
                    moves(helper, storage, PERF_MOVES / 2, PERF_MOVES / 2, root);
                }
                long took = System.nanoTime() - start;
                helper.assertValueEqual(fake.own.getLong(WATER), (long) PERF_MOVES, "takes back after the rollback");
                helper.assertValueEqual(fake.own.getLong(LAVA), 0L, "no store went in");
                LOGGER.info("{}: {} moves and their rollback in {} ms (budget {} ms).", PERF, PERF_MOVES, took / 1e6,
                        BUDGET_NANOS / 1e6);
                return took;
            }, took -> PERF_MOVES + " moves took " + took / 1e6 + " ms");
            FAKES.values().remove(fake);
        }).thenSucceed();
    }

    /** The one dropped bridge near {@code pos}; fails on none or more than one. Discards the entities. */
    private static ItemStack drop(GameTestHelper helper, BlockPos pos) {
        List<ItemEntity> entities = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2));
        List<ItemStack> bridges = new ArrayList<>();
        for (ItemEntity entity : entities) {
            if (entity.getItem().is(BridgeContent.BRIDGE_TIER_3.item().get())) {
                bridges.add(entity.getItem().copy());
            }
            entity.discard();
        }
        helper.assertValueEqual(bridges.size(), 1, "dropped bridges");
        return bridges.get(0);
    }
}
