package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusContent;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusPart;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * A toggle bus under a redstone clock in a 10,000-node network (B09 item 19, PRD 8.1): two 100 x 50 planes of tier-3
 * cables joined only through one cable with a bus in it, the controller in the near plane and 20 test drives in the far
 * one. The clock changes every tick for {@value #CLOCK_TICKS} ticks; the bus switches at most once per
 * {@code network.toggleBus.minIntervalTicks} (each a split or a merge of about 5,000 nodes). The whole switch path is
 * timed as full server ticks, from the tick the bus switched in to a few after (the part, the node invalidation, the
 * graph's split or merge and the network rebuild), and the dearest such tick of the splits and of the merges must each
 * stay in the budget of a 10,000-node rebuild ({@code perf_hubs_place_remove_10k}). Runs in its own batch with timing
 * on; run it alone.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ToggleBusPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_100x2x101");
    private static final String NAME = "perf_toggle_bus_clock_10k";
    private static final long BUDGET_NANOS = 25_000_000;
    private static final int SIDE = 100;
    private static final int HALF = 50;
    private static final int CLOCK_TICKS = 100;
    private static final int DEVICES = 20;
    /** Server ticks after the one a switch happened in that may carry its split or merge. */
    private static final int WINDOW = 4;
    /** The one cable between the planes, carrying the bus on its south face. */
    private static final BlockPos BUS = new BlockPos(SIDE / 2, 1, HALF + 1);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAME, StorageTestEnvironment.clearing(ToggleBusPerfGameTests::clock));
    }

    private ToggleBusPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(NAME), new StorageTestEnvironment("", "", true)), NAME,
                STRUCTURE, 600 + 2 * (CLOCK_TICKS + 1) + 100);
    }

    private static void clock(GameTestHelper helper) {
        BlockPos core = new BlockPos(0, 1, 0);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (int x = 0; x < SIDE; x++) {
            for (int z = 1; z <= HALF; z++) {
                cable(helper, new BlockPos(x, 1, z));
                // The far plane, z 52 to 100: the structure ends at 100.
                if (z < HALF) {
                    cable(helper, new BlockPos(x, 1, HALF + 1 + z));
                }
            }
        }
        cable(helper, BUS);
        List<TestStorage.TestDevice> far = new ArrayList<>();
        for (int i = 0; i < DEVICES; i++) {
            far.add(NetworkGameTests.device(helper, new BlockPos(5 + 4 * i, 0, SIDE - 2)));
        }
        if (!(helper.getBlockEntity(BUS, ConduitBlockEntity.class).conduit().state() instanceof CableParts parts)) {
            throw helper.assertionException(Component.literal("A storage cable without parts"));
        }
        helper.assertTrue(parts.place(Direction.SOUTH, ToggleBusContent.TOGGLE_BUS, null,
                new ItemStack(ToggleBusContent.TOGGLE_BUS_ITEM.get())), "the bus was not placed");
        CablePart placed = parts.part(Direction.SOUTH);
        if (!(placed instanceof ToggleBusPart bus)) {
            throw helper.assertionException(Component.literal("No toggle bus"));
        }
        BlockPos signal = BUS.below();
        MinecraftServer server = helper.getLevel().getServer();
        int interval = StorageServerConfig.TOGGLE_BUS_MIN_INTERVAL.getAsInt();
        int allowed = CLOCK_TICKS / interval + 2;
        // The dearest whole server tick from the one a switch happened in to WINDOW ticks after, kept for splits and
        // merges apart: [split max, merge max].
        long[] most = new long[2];
        // round, switches when the attempt started, switches seen so far, callbacks left in the window, window kind
        int[] state = new int[5];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState near = NetworkGameTests.state(helper, core);
            helper.assertTrue(far.get(0).network() != null && far.get(0).network() != near, "not formed apart");
        }).thenIdle(20).thenWaitUntil(PerfBudget.measureTicks(helper, NAME, CLOCK_TICKS + 20, BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    Arrays.fill(most, 0);
                    state[0] = 0;
                    state[1] = bus.switches();
                    state[2] = state[1];
                    state[3] = 0;
                }, () -> {
                    // A clock on the bus's cable for the first CLOCK_TICKS ticks, then quiet ticks for the last
                    // held-back switch. Each callback sees the previous server tick's full time: the switch itself
                    // (the part, the node invalidation, the sync), the graph's split or merge and the rebuild after.
                    int tick = state[0]++;
                    if (state[3] > 0) {
                        long previous = server.getTickTimesNanos()[Math.floorMod(server.getTickCount() - 1, 100)];
                        most[state[4]] = Math.max(most[state[4]], previous);
                        state[3]--;
                    }
                    if (tick < CLOCK_TICKS) {
                        helper.setBlock(signal, tick % 2 == 0 ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
                    }
                    if (bus.switches() != state[2]) {
                        state[2] = bus.switches();
                        state[3] = WINDOW + 1;
                        state[4] = bus.linked() ? 1 : 0;
                    }
                }, () -> {
                    int switches = bus.switches() - state[1];
                    helper.assertTrue(switches >= 2, "the bus did not follow the clock: " + switches);
                    helper.assertTrue(switches <= allowed,
                            switches + " switches in " + CLOCK_TICKS + " ticks; at most " + allowed);
                    helper.assertTrue(most[0] > 0 && most[1] > 0, "no split or no merge measured");
                    LOGGER.info(
                            "{}: {} switches, dearest server tick around a split {} ms, a merge {} ms (budget {} ms"
                                    + " each).",
                            NAME, switches, millis(most[0]), millis(most[1]), millis(BUDGET_NANOS));
                    return Math.max(most[0], most[1]);
                }),
                nanos -> "a split or merge by the bus takes " + millis(nanos) + " ms of a server tick (split "
                        + millis(most[0]) + " ms, merge " + millis(most[1]) + " ms), over the " + millis(BUDGET_NANOS)
                        + " ms budget"))
                .thenSucceed();
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_3.type().get(), null);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
