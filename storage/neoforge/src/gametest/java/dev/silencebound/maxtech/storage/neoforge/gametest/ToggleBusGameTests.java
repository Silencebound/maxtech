package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusContent;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusPart;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusWaits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The toggle bus (B09 item 19): on a cable linked west to a controller, a bus on the cable's east face links the far
 * side (a cable and a test drive, or the drive straight behind the bus) only while the cable is powered (the inverted
 * one only while it is not). The far drive goes offline and its storage leaves the controller's view while the bus is
 * open, and comes back with the same amount when it links. Under a redstone clock that changes every tick the bus
 * switches at most once per {@code network.toggleBus.minIntervalTicks}, the networks rebuild only that often, and the
 * bus ends in the clock's last state. Layouts run along x at y = 1, z = 1: controller, then cables.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ToggleBusGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String SPLIT = "toggle_bus_splits_and_merges";
    private static final String INVERTED = "toggle_bus_inverted";
    private static final String CLOCK = "toggle_bus_clock_debounced";
    private static final String SHARED_TICK = "toggle_bus_shared_tick_on_time";
    private static final String UNLOADED = "toggle_bus_unloaded_chunk_waits";
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final int CLOCK_TICKS = 60;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(SPLIT, StorageTestEnvironment.clearing(ToggleBusGameTests::splitsAndMerges));
        StorageTests.function(INVERTED, StorageTestEnvironment.clearing(ToggleBusGameTests::inverted));
        StorageTests.function(CLOCK, StorageTestEnvironment.clearing(ToggleBusGameTests::clockDebounced));
        StorageTests.function(SHARED_TICK, StorageTestEnvironment.clearing(ToggleBusGameTests::sharedTickOnTime));
        StorageTests.function(UNLOADED, StorageTestEnvironment.clearing(ToggleBusGameTests::unloadedChunkWaits));
    }

    private ToggleBusGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(MaxtechStorage.id("toggle_bus"), StorageTestEnvironment.plain());
        StorageTests.test(event, environment, SPLIT, STRUCTURE, 400);
        StorageTests.test(event, environment, INVERTED, STRUCTURE, 400);
        StorageTests.test(event, environment, CLOCK, STRUCTURE, 400);
        StorageTests.test(event, environment, SHARED_TICK, STRUCTURE, 400);
        // Its own batch: it makes every bus see every chunk as unloaded for a while.
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(UNLOADED), StorageTestEnvironment.plain()),
                UNLOADED, STRUCTURE, 400);
    }

    /** Puts a bus of {@code type} on the east face of the cable at {@code x}; the network places parts the same way. */
    static ToggleBusPart place(GameTestHelper helper, int x, PartType type) {
        helper.assertTrue(PartGameTests.parts(helper, x).place(Direction.EAST, type, null, new ItemStack(type.item())),
                "the bus was not placed");
        CablePart part = PartGameTests.part(helper, PartGameTests.parts(helper, x), Direction.EAST);
        if (part instanceof ToggleBusPart bus) {
            return bus;
        }
        throw helper.assertionException(Component.literal("Not a toggle bus: " + part));
    }

    /** Asserts the far drive is in the controller's network, online, and its 100 cobblestone counted once. */
    private static void assertLinked(GameTestHelper helper, BlockPos controller, TestStorage.TestDevice far) {
        NetworkState main = NetworkGameTests.state(helper, controller);
        helper.assertTrue(far.network() == main, "the far drive is not in the controller's network");
        helper.assertValueEqual(far.deviceLink().linkLook(), LinkLook.ONLINE, "the far drive's line");
        helper.assertValueEqual(main.contents().amount(COBBLE), 100L, "cobblestone in the controller's view");
    }

    /** Asserts the far drive is cut off: in a network of its own, offline, its storage out of the controller's view. */
    private static void assertOpen(GameTestHelper helper, BlockPos controller, TestStorage.TestDevice far) {
        NetworkState main = NetworkGameTests.state(helper, controller);
        NetworkState split = NetworkGameTests.net(helper, far);
        helper.assertTrue(split != main, "the far drive still links through the open bus");
        helper.assertTrue(far.deviceLink().linkLook() != LinkLook.ONLINE, "the cut-off drive is still online");
        helper.assertValueEqual(main.contents().amount(COBBLE), 0L, "cobblestone beyond the open bus in the view");
        helper.assertValueEqual(split.contents().amount(COBBLE), 100L, "cobblestone in the cut-off network");
    }

    /**
     * Controller, cables at 2 to 4 and a drive at 5; a bus on cable 3's east face. Unpowered it is open; a redstone
     * block on the cable links it; taking the block away opens it again.
     */
    private static void splitsAndMerges(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice far = NetworkGameTests.device(helper, NetworkGameTests.at(5));
        BlockPos signal = NetworkGameTests.at(3).above();
        ToggleBusPart[] bus = new ToggleBusPart[1];
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(far.network() == NetworkGameTests.state(helper, controller), "not formed"))
                .thenExecute(() -> {
                    far.provider.setDirect(COBBLE, 100);
                    bus[0] = place(helper, 3, ToggleBusContent.TOGGLE_BUS);
                    helper.assertFalse(bus[0].linked(), "linked without a signal");
                }).thenWaitUntil(() -> assertOpen(helper, controller, far))
                .thenExecute(() -> helper.setBlock(signal, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> assertLinked(helper, controller, far))
                .thenExecute(() -> helper.setBlock(signal, Blocks.AIR))
                .thenWaitUntil(() -> assertOpen(helper, controller, far)).thenExecute(() -> {
                    helper.assertFalse(bus[0].linked(), "the bus says linked");
                    helper.assertTrue(bus[0].deviceLink().network() == NetworkGameTests.state(helper, controller),
                            "the bus left its own cable's network");
                }).thenSucceed();
    }

    /**
     * An inverted bus on cable 4's east face, the drive straight behind it at 5: linked without a signal, open with a
     * redstone block on the cable, linked again once the block is gone.
     */
    private static void inverted(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice far = NetworkGameTests.device(helper, NetworkGameTests.at(5));
        BlockPos signal = NetworkGameTests.at(4).above();
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(far.network() == NetworkGameTests.state(helper, controller), "not formed"))
                .thenExecute(() -> {
                    far.provider.setDirect(COBBLE, 100);
                    helper.assertTrue(place(helper, 4, ToggleBusContent.INVERTED).linked(), "open without a signal");
                }).thenWaitUntil(() -> assertLinked(helper, controller, far))
                .thenExecute(() -> helper.setBlock(signal, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> assertOpen(helper, controller, far))
                .thenExecute(() -> helper.setBlock(signal, Blocks.AIR))
                .thenWaitUntil(() -> assertLinked(helper, controller, far)).thenSucceed();
    }

    /**
     * The layout of {@link #splitsAndMerges} under a redstone clock that changes every tick for {@value #CLOCK_TICKS}
     * ticks and stops powered: the bus switches at most once per interval (plus the first), the controller's network
     * rebuilds or patches at most twice per switch, and the bus ends linked.
     */
    private static void clockDebounced(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice far = NetworkGameTests.device(helper, NetworkGameTests.at(5));
        BlockPos signal = NetworkGameTests.at(3).above();
        int interval = StorageServerConfig.TOGGLE_BUS_MIN_INTERVAL.getAsInt();
        int allowed = CLOCK_TICKS / interval + 2;
        ToggleBusPart[] bus = new ToggleBusPart[1];
        int[] before = new int[2];
        int[] tick = new int[1];
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(far.network() == NetworkGameTests.state(helper, controller), "not formed"))
                .thenExecute(() -> {
                    far.provider.setDirect(COBBLE, 100);
                    bus[0] = place(helper, 3, ToggleBusContent.TOGGLE_BUS);
                }).thenWaitUntil(() -> assertOpen(helper, controller, far)).thenExecute(() -> {
                    NetworkState main = NetworkGameTests.state(helper, controller);
                    before[0] = bus[0].switches();
                    before[1] = main.rebuilds() + main.patches();
                }).thenExecuteFor(CLOCK_TICKS, () -> {
                    helper.setBlock(signal, tick[0]++ % 2 == 0 ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
                }).thenExecute(() -> helper.setBlock(signal, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> assertLinked(helper, controller, far)).thenExecute(() -> {
                    NetworkState main = NetworkGameTests.state(helper, controller);
                    int switches = bus[0].switches() - before[0];
                    int changes = main.rebuilds() + main.patches() - before[1];
                    helper.assertTrue(switches >= 2, "the bus did not follow the clock: " + switches + " switches");
                    helper.assertTrue(switches <= allowed, switches + " switches in " + CLOCK_TICKS
                            + " ticks with an interval of " + interval + "; at most " + allowed);
                    helper.assertTrue(changes <= 2 * allowed,
                            changes + " rebuilds and patches for " + switches + " switches");
                }).thenSucceed();
    }

    /**
     * Two pending requests for one cable's shared scheduled tick: another part has asked for a tick 40 ticks away, then
     * the bus holds back a switch due after the interval. Vanilla would drop the bus's earlier request; the cable moves
     * its tick forward, so the bus switches on time, not 40 ticks later.
     */
    private static void sharedTickOnTime(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice far = NetworkGameTests.device(helper, NetworkGameTests.at(5));
        BlockPos signal = NetworkGameTests.at(3).above();
        int interval = StorageServerConfig.TOGGLE_BUS_MIN_INTERVAL.getAsInt();
        ToggleBusPart[] bus = new ToggleBusPart[1];
        long[] linkedAt = new long[1];
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(far.network() == NetworkGameTests.state(helper, controller), "not formed"))
                .thenExecute(() -> {
                    far.provider.setDirect(COBBLE, 100);
                    bus[0] = place(helper, 3, ToggleBusContent.TOGGLE_BUS);
                }).thenWaitUntil(() -> assertOpen(helper, controller, far)).thenExecute(() -> {
                    helper.setBlock(signal, Blocks.REDSTONE_BLOCK);
                    helper.assertTrue(bus[0].linked(), "the first switch was held back");
                    linkedAt[0] = helper.getLevel().getGameTime();
                    // Another part on the cable asks for the shared tick much later.
                    PartGameTests.parts(helper, 3).scheduleTick(4 * interval);
                }).thenIdle(1).thenExecute(() -> {
                    helper.setBlock(signal, Blocks.AIR);
                    helper.assertTrue(bus[0].linked(), "switched inside the interval");
                }).thenWaitUntil(() -> helper.assertFalse(bus[0].linked(), "not open yet")).thenExecute(() -> {
                    long late = helper.getLevel().getGameTime() - linkedAt[0];
                    helper.assertTrue(late <= interval + 2,
                            "the held-back switch came " + late + " ticks after the last, interval " + interval);
                }).thenSucceed();
    }

    /**
     * While a chunk the signal read would touch is not loaded, a bus does not read and schedules nothing (no retry);
     * when that chunk loads it reads and follows the signal.
     */
    private static void unloadedChunkWaits(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice far = NetworkGameTests.device(helper, NetworkGameTests.at(5));
        BlockPos cable = helper.absolutePos(NetworkGameTests.at(3));
        BlockPos signal = NetworkGameTests.at(3).above();
        ToggleBusPart[] bus = new ToggleBusPart[1];
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(far.network() == NetworkGameTests.state(helper, controller), "not formed"))
                .thenExecute(() -> {
                    far.provider.setDirect(COBBLE, 100);
                    bus[0] = place(helper, 3, ToggleBusContent.TOGGLE_BUS);
                }).thenWaitUntil(() -> assertOpen(helper, controller, far)).thenExecute(() -> {
                    ToggleBusPart.setChunkCheck((level, x, z) -> false);
                    helper.setBlock(signal, Blocks.REDSTONE_BLOCK);
                }).thenIdle(40).thenExecute(() -> {
                    ToggleBusPart.setChunkCheck(null);
                    helper.assertFalse(bus[0].linked(), "read the signal with a chunk unloaded");
                    helper.assertFalse(
                            helper.getLevel().getBlockTicks().hasScheduledTick(cable,
                                    helper.getBlockState(NetworkGameTests.at(3)).getBlock()),
                            "the bus retries on a timer");
                    ToggleBusWaits.loaded(helper.getLevel(), ChunkPos.pack(cable));
                }).thenWaitUntil(() -> assertLinked(helper, controller, far)).thenSucceed();
    }
}
