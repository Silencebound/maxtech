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
            Map.entry("lab_tube_contents_sync_count", LabTubeContentsGameTests::syncCount));

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
        int[] syncs = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(lab(helper, new BlockPos(5, 1, 1)).hub().isPresent(), "hub"))
                .thenExecute(() -> fill(hub, lava(), capacity / 2)).thenWaitUntil(() -> assertAll(helper, lava(), 2))
                .thenExecute(() -> {
                    syncs[0] = lab(helper, new BlockPos(2, 1, 1)).contentsSyncs();
                    fill(hub, lava(), capacity / 16);
                    helper.assertValueEqual(lab(helper, new BlockPos(2, 1, 1)).contentsSyncs(), syncs[0],
                            "syncs within a step");
                    assertAll(helper, lava(), 2);
                    fill(hub, lava(), capacity / 8);
                    helper.assertValueEqual(lab(helper, new BlockPos(2, 1, 1)).contentsSyncs(), syncs[0] + 1,
                            "syncs past the boundary");
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
            // Five steps either way, through (4,0,1) or (4,1,2); Lib's route tree breaks the tie by position.
            Direction tie = contents(helper, new BlockPos(4, 0, 2)).upstream().orElse(null);
            helper.assertTrue(tie == Direction.NORTH || tie == Direction.UP, "upstream of the corner: " + tie);
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
                hub.storage().tank().extract(water(), capacity, transaction);
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
                    LabNetwork lab = lab(helper, new BlockPos(2, 1, 1));
                    int before = lab.contentsSyncs();
                    for (int i = 0; i < boundary + margin; i++) {
                        fill(hub, water(), 1);
                    }
                    helper.assertValueEqual(lab.contentsSyncs() - before, 2, "syncs while filling by 1 mB");
                    assertAll(helper, water(), 2);
                    // Back to the boundary, then just below and on it again.
                    drain(hub, margin);
                    for (int i = 0; i < 200; i++) {
                        drain(hub, margin / 2);
                        fill(hub, water(), margin / 2);
                    }
                    helper.assertValueEqual(lab.contentsSyncs() - before, 2, "syncs while hovering at a boundary");
                }).thenSucceed();
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
            hub.storage().tank().insert(fluid, amount, transaction);
            transaction.commit();
        }
    }

    private static void drain(LabHubBlockEntity hub, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            hub.storage().tank().extract(hub.storage().tank().getResource(0), amount, transaction);
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
