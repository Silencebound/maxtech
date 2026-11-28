package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.farms.lab.LabNetwork;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Lab tubes show the hub tank: its fluid and fill step on every tube, flowing from the hub along the network, set again
 * only when the step changes (with a margin at the boundaries) and cleared when the tank empties or the hub goes.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class LabTubeContentsGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final BlockPos HUB = new BlockPos(1, 1, 1);
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("lab_tube_contents_follow_tank", LabTubeContentsGameTests::followTank),
            Map.entry("lab_tube_contents_upstream", LabTubeContentsGameTests::upstreamTowardsHub),
            Map.entry("lab_tube_contents_clear", LabTubeContentsGameTests::clear),
            Map.entry("lab_tube_contents_sync_count", LabTubeContentsGameTests::syncCount),
            Map.entry("lab_tube_contents_split_no_hub", LabTubeContentsGameTests::splitWithoutHub),
            Map.entry("lab_tube_contents_hub_swap", LabTubeContentsGameTests::hubSwap),
            Map.entry("lab_tube_contents_fluid_change", LabTubeContentsGameTests::fluidChange));

    private LabTubeContentsGameTests() {
    }

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        for (Map.Entry<String, Consumer<GameTestHelper>> test : TESTS.entrySet()) {
            event.register(Registries.TEST_FUNCTION, MaxtechFarms.id(test.getKey()), test::getValue);
        }
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechFarms.id("lab_tube_contents"));
        for (String name : TESTS.keySet()) {
            event.registerTest(MaxtechFarms.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechFarms.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /**
     * Every tube shows the tank's fluid (any fluid, lava here) and fill step; within a step nothing is set again, past
     * the next boundary every tube shows the new step.
     */
    private static void followTank(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        tubes(helper, 2, 5);
        int capacity = FarmsServerConfig.hubTankCapacity();
        SyncCounter syncs = new SyncCounter(helper, new BlockPos(2, 1, 1));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(lab(helper, new BlockPos(5, 1, 1)).hub().isPresent(), "hub"))
                .thenExecute(() -> fill(hub, lava(), capacity / 2)).thenWaitUntil(() -> assertAll(helper, lava(), 2))
                .thenExecute(() -> {
                    syncs.count();
                    fill(hub, lava(), capacity / 16);
                    helper.assertValueEqual(syncs.count(), 0, "syncs within a step");
                    assertAll(helper, lava(), 2);
                    fill(hub, lava(), capacity / 8);
                    helper.assertValueEqual(syncs.count(), 1, "syncs past the boundary");
                    assertAll(helper, lava(), 3);
                    fill(hub, lava(), capacity);
                    assertAll(helper, lava(), ConduitContents.FULL);
                }).thenSucceed();
    }

    /**
     * On a branched lab every tube's upstream side is its next step towards the hub: along the run, from the run into
     * the side branches, and up from the branch below.
     */
    private static void upstreamTowardsHub(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        fill(hub, water(), FarmsServerConfig.hubTankCapacity());
        tubes(helper, 2, 5);
        place(helper, new BlockPos(4, 1, 0));
        place(helper, new BlockPos(4, 1, 2));
        place(helper, new BlockPos(4, 0, 1));
        place(helper, new BlockPos(4, 0, 2));
        helper.succeedWhen(() -> {
            assertUpstream(helper, new BlockPos(2, 1, 1), Direction.WEST);
            assertUpstream(helper, new BlockPos(3, 1, 1), Direction.WEST);
            assertUpstream(helper, new BlockPos(4, 1, 1), Direction.WEST);
            assertUpstream(helper, new BlockPos(5, 1, 1), Direction.WEST);
            assertUpstream(helper, new BlockPos(4, 1, 0), Direction.SOUTH);
            assertUpstream(helper, new BlockPos(4, 1, 2), Direction.NORTH);
            assertUpstream(helper, new BlockPos(4, 0, 1), Direction.UP);
            // Five steps either way, through (4,0,1) or (4,1,2); Lib's route tree breaks the tie deterministically.
            assertUpstream(helper, new BlockPos(4, 0, 2), Direction.NORTH);
            helper.assertValueEqual(contents(helper, new BlockPos(4, 0, 2)).fluid(), Optional.of(Fluids.WATER),
                    "fluid in the branch");
        });
    }

    /** Draining the tank clears every tube; filling shows it again; removing the hub clears them again. */
    private static void clear(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        int capacity = FarmsServerConfig.hubTankCapacity();
        fill(hub, water(), capacity / 4);
        tubes(helper, 2, 4);
        helper.startSequence().thenWaitUntil(() -> assertAll(helper, water(), 1)).thenExecute(() -> {
            try (Transaction transaction = Transaction.openRoot()) {
                hub.storage().fluidHandler().extract(water(), capacity, transaction);
                transaction.commit();
            }
            assertEmpty(helper);
            fill(hub, water(), capacity);
            assertAll(helper, water(), ConduitContents.FULL);
            helper.setBlock(HUB, Blocks.AIR);
        }).thenWaitUntil(() -> assertEmpty(helper)).thenSucceed();
    }

    /**
     * Filling the tank 1 mB at a time sets the tubes only when the fluid appears and when the level passes the next
     * boundary by the margin, not on every change; going back and forth around that boundary sets them no more.
     */
    private static void syncCount(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        tubes(helper, 2, 5);
        int capacity = FarmsServerConfig.hubTankCapacity();
        int boundary = capacity * 3 / 8;
        int margin = capacity / 32;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(lab(helper, new BlockPos(5, 1, 1)).hub().isPresent(), "hub"))
                .thenExecute(() -> {
                    SyncCounter syncs = new SyncCounter(helper, new BlockPos(2, 1, 1));
                    int total = 0;
                    for (int i = 0; i < boundary + margin; i++) {
                        fill(hub, water(), 1);
                        total += syncs.count();
                    }
                    helper.assertValueEqual(total, 2, "syncs while filling by 1 mB");
                    assertAll(helper, water(), 2);
                    // Back to the boundary, then just below and on it again.
                    drain(hub, margin);
                    total = syncs.count();
                    for (int i = 0; i < 200; i++) {
                        drain(hub, margin / 2);
                        total += syncs.count();
                        fill(hub, water(), margin / 2);
                        total += syncs.count();
                    }
                    helper.assertValueEqual(total, 0, "syncs while hovering at a boundary");
                }).thenSucceed();
    }

    /**
     * A split that leaves a side without a hub clears that side's tubes; the hub's side keeps showing the tank.
     */
    private static void splitWithoutHub(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        fill(hub, water(), FarmsServerConfig.hubTankCapacity() / 2);
        tubes(helper, 2, 5);
        helper.startSequence().thenWaitUntil(() -> assertAll(helper, water(), 2))
                .thenExecute(() -> helper.setBlock(new BlockPos(3, 1, 1), Blocks.AIR)).thenWaitUntil(() -> {
                    helper.assertTrue(contents(helper, new BlockPos(2, 1, 1)).fluid().isPresent(),
                            "the hub's side still shows the tank");
                    helper.assertTrue(contents(helper, new BlockPos(4, 1, 1)).isEmpty(), "cleared at (4,1,1)");
                    helper.assertTrue(contents(helper, new BlockPos(5, 1, 1)).isEmpty(), "cleared at (5,1,1)");
                }).thenSucceed();
    }

    /**
     * Removing the running hub hands the lab to another hub: the tubes show the new hub's fluid and step, read afresh.
     */
    private static void hubSwap(GameTestHelper helper) {
        BlockPos otherPos = new BlockPos(6, 1, 1);
        int capacity = FarmsServerConfig.hubTankCapacity();
        LabHubBlockEntity first = hub(helper, HUB);
        fill(first, water(), capacity);
        LabHubBlockEntity second = hub(helper, otherPos);
        fill(second, lava(), capacity / 2);
        tubes(helper, 2, 5);
        boolean[] firstRuns = new boolean[1];
        helper.startSequence().thenWaitUntil(() -> {
            LabHubBlockEntity running = lab(helper, new BlockPos(2, 1, 1)).hub()
                    .orElseThrow(() -> helper.assertionException(Component.literal("no hub runs the lab")));
            firstRuns[0] = running == first;
            helper.assertTrue(contents(helper, new BlockPos(5, 1, 1)).fluid().isPresent(), "tubes show a tank");
        }).thenExecute(() -> {
            assertAll(helper, firstRuns[0] ? water() : lava(), firstRuns[0] ? ConduitContents.FULL : 2);
            helper.setBlock(firstRuns[0] ? HUB : otherPos, Blocks.AIR);
        }).thenWaitUntil(
                () -> assertAll(helper, firstRuns[0] ? lava() : water(), firstRuns[0] ? 2 : ConduitContents.FULL))
                .thenSucceed();
    }

    /** After the tank empties, filling it with another fluid shows the new fluid on every tube. */
    private static void fluidChange(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        int capacity = FarmsServerConfig.hubTankCapacity();
        fill(hub, water(), capacity / 2);
        tubes(helper, 2, 5);
        helper.startSequence().thenWaitUntil(() -> assertAll(helper, water(), 2)).thenExecute(() -> {
            drain(hub, capacity);
            assertEmpty(helper);
            fill(hub, lava(), capacity / 2);
            assertAll(helper, lava(), 2);
        }).thenSucceed();
    }

    /** Counts how often a tube's contents were set anew, by what it shows (every set builds different contents). */
    private static final class SyncCounter {
        private final GameTestHelper helper;
        private final BlockPos tube;
        private ConduitContents last;

        SyncCounter(GameTestHelper helper, BlockPos tube) {
            this.helper = helper;
            this.tube = tube;
            this.last = contents(helper, tube);
        }

        /** The syncs since the last call: 1 when the tube shows other contents than then, else 0. */
        int count() {
            ConduitContents now = contents(helper, tube);
            boolean changed = !now.equals(last);
            last = now;
            return changed ? 1 : 0;
        }
    }

    private static FluidResource water() {
        return FluidResource.of(Fluids.WATER);
    }

    private static FluidResource lava() {
        return FluidResource.of(Fluids.LAVA);
    }

    private static void assertAll(GameTestHelper helper, FluidResource fluid, int fill) {
        for (BlockPos tube : tubesIn(helper)) {
            ConduitContents contents = contents(helper, tube);
            helper.assertValueEqual(contents.fluid(), Optional.of(fluid.getFluid()), "fluid at " + tube);
            helper.assertValueEqual(contents.fill(), fill, "fill at " + tube);
        }
    }

    private static void assertEmpty(GameTestHelper helper) {
        for (BlockPos tube : tubesIn(helper)) {
            helper.assertTrue(contents(helper, tube).isEmpty(), "contents cleared at " + tube);
        }
    }

    private static void assertUpstream(GameTestHelper helper, BlockPos tube, Direction side) {
        helper.assertValueEqual(contents(helper, tube).upstream(), Optional.of(side), "upstream of " + tube);
    }

    /** Positions of the lab tubes on the test's row (y 1, z 1). */
    private static java.util.List<BlockPos> tubesIn(GameTestHelper helper) {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        for (int x = 0; x < 7; x++) {
            BlockPos pos = new BlockPos(x, 1, 1);
            if (helper.getBlockState(pos).is(LabTubes.TUBE.block().get())) {
                found.add(pos);
            }
        }
        return found;
    }

    private static ConduitContents contents(GameTestHelper helper, BlockPos tube) {
        return Conduits.host(helper.getLevel(), helper.absolutePos(tube))
                .orElseThrow(() -> helper.assertionException(Component.literal("no tube at " + tube))).contents();
    }

    private static void fill(LabHubBlockEntity hub, FluidResource fluid, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            hub.storage().fluidHandler().insert(fluid, amount, transaction);
            transaction.commit();
        }
    }

    private static void drain(LabHubBlockEntity hub, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            hub.storage().fluidHandler().extract(hub.storage().fluidHandler().getResource(0), amount, transaction);
            transaction.commit();
        }
    }

    private static LabHubBlockEntity hub(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, FarmsContent.LAB_HUB.get());
        return helper.getBlockEntity(pos, LabHubBlockEntity.class);
    }

    private static void tubes(GameTestHelper helper, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            place(helper, new BlockPos(x, 1, 1));
        }
    }

    private static void place(GameTestHelper helper, BlockPos pos) {
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(pos), LabTubes.TUBE.type().get()),
                "placed a tube at " + pos);
    }

    private static LabNetwork lab(GameTestHelper helper, BlockPos tube) {
        return LabNetwork.at(helper.getLevel(), helper.absolutePos(tube))
                .orElseThrow(() -> helper.assertionException(Component.literal("no lab network at " + tube)));
    }
}
