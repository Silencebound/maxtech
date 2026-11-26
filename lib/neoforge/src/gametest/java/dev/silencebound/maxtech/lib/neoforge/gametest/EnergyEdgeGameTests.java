package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.energy.FeEdge;
import dev.silencebound.maxtech.lib.energy.FeEndpointHandler;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.neoforge.gametest.RuntimeGameTests.FeEntity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * FE edges (MLE item 2) on a real level, with the test FE block of {@link RuntimeGameTests} as another mod's block
 * (10,000 FE, 1,000 per operation; its top takes FE only, its bottom gives only): round trips at an uneven rate never
 * gain, a rolled-back transaction moves nothing, export off and export limits, foreign blocks in the pass by priority,
 * and the capability cache waking an edge when the neighbour is placed or removed. Each config is its own batch.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class EnergyEdgeGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final String ROUND_TRIP = "energy_edge_round_trip_uneven";
    private static final String ABORTED = "energy_edge_aborted_moves_nothing";
    private static final String EXPORT_OFF = "energy_edge_export_off";
    private static final String EXPORT_LIMIT = "energy_edge_export_limit";
    private static final String IN_PASS = "energy_edge_endpoints_in_pass";
    private static final String NEIGHBOUR = "energy_edge_neighbour_change";
    private static final String UNLOAD = "energy_edge_unload_keeps_held";

    private static final BlockPos SOURCE = new BlockPos(1, 1, 1);
    private static final BlockPos SINK = new BlockPos(3, 1, 1);
    private static final BlockPos SINK_2 = new BlockPos(5, 1, 1);
    private static final long FULL = 10_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        EnergyConfigEnvironment.init();
        FUNCTIONS.register(ROUND_TRIP, () -> EnergyEdgeGameTests::roundTripUneven);
        FUNCTIONS.register(ABORTED, () -> EnergyEdgeGameTests::abortedMovesNothing);
        FUNCTIONS.register(EXPORT_OFF, () -> EnergyEdgeGameTests::exportOff);
        FUNCTIONS.register(EXPORT_LIMIT, () -> EnergyEdgeGameTests::exportLimit);
        FUNCTIONS.register(IN_PASS, () -> EnergyEdgeGameTests::endpointsInPass);
        FUNCTIONS.register(NEIGHBOUR, () -> EnergyEdgeGameTests::neighbourChange);
        FUNCTIONS.register(UNLOAD, () -> EnergyEdgeGameTests::unloadKeepsHeld);
    }

    private EnergyEdgeGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> uneven = event.registerEnvironment(MaxtechLib.id("energy_rate_3_7"),
                new EnergyConfigEnvironment("energy.feRateFe;energy.feRateMu", "3;7"));
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechLib.id("energy_edges"),
                new EnergyConfigEnvironment("", ""));
        register(event, ROUND_TRIP, uneven);
        register(event, ABORTED, uneven);
        register(event, IN_PASS, plain);
        register(event, UNLOAD, plain);
        register(event, EXPORT_OFF, event.registerEnvironment(MaxtechLib.id("energy_export_off"),
                new EnergyConfigEnvironment("energy.exportEnabled", "false")));
        register(event, EXPORT_LIMIT, event.registerEnvironment(MaxtechLib.id("energy_export_limit"),
                new EnergyConfigEnvironment("energy.exportPerEdge;energy.exportPerNetwork", "100;150")));
        // A poll interval far beyond the test, so only the capability cache's invalidation can wake the edge.
        register(event, NEIGHBOUR, event.registerEnvironment(MaxtechLib.id("energy_slow_poll"),
                new EnergyConfigEnvironment("energy.edgePollTicks", "1200")));
    }

    private static void register(RegisterGameTestsEvent event, String name,
            Holder<TestEnvironmentDefinition<?>> environment) {
        Identifier id = MaxtechLib.id(name);
        event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(environment, STRUCTURE, 200, 0, true)));
    }

    private static EnergyNetwork network(GameTestHelper helper) {
        EnergyNetwork network = EnergyNetworks.of(helper.getLevel()).createNetwork();
        EnergyConfigEnvironment.onTeardown(network::dispose);
        return network;
    }

    /** An FE block at {@code pos} holding {@code stored} FE. */
    private static FeEntity block(GameTestHelper helper, BlockPos pos, long stored) {
        helper.setBlock(pos, RuntimeGameTests.FE_BLOCK.get());
        FeEntity entity = helper.getBlockEntity(pos, FeEntity.class);
        entity.buffer.load(stored);
        return entity;
    }

    /** An edge to the face {@code side} of the block at {@code pos}, joined to {@code network}. */
    private static FeEdge edge(GameTestHelper helper, EnergyNetwork network, BlockPos pos, Direction side) {
        FeEdge edge = FeEdge.create(helper.getLevel(), helper.absolutePos(pos), side);
        EnergyConfigEnvironment.onTeardown(edge::close);
        edge.join(network);
        return edge;
    }

    /**
     * 3 FE are 7 MU. A full block gives through its bottom face, the network passes it through a leftovers-only MaxTech
     * buffer to another block's top face. Counted in exact units, what arrived never exceeds what left, and at most a
     * fraction of one FE per edge is lost.
     */
    private static void roundTripUneven(GameTestHelper helper) {
        FeRate rate = ServerConfig.feRate();
        helper.assertValueEqual(rate, new FeRate(3, 7), "the batch's rate");
        FeEntity source = block(helper, SOURCE, FULL);
        FeEntity sink = block(helper, SINK, 0);
        EnergyNetwork network = network(helper);
        FeEdge in = edge(helper, network, SOURCE, Direction.DOWN);
        FeEdge out = edge(helper, network, SINK, Direction.UP);
        EnergyEndpoint buffer = EnergyEndpoint.buffer(5_000, 333, 333, EnergyPriority.LEFTOVERS);
        network.add(buffer);
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.assertTrue(in.importing() > 0 && out.exporting() > 0, "the edges do not flow");
        }).thenIdle(30).thenExecute(() -> {
            helper.assertTrue(source.buffer.stored() < FULL, "nothing left the source");
            helper.assertTrue(sink.buffer.stored() > 0, "nothing reached the sink");
            long held = in.close() + out.close();
            long arrived = rate.unitsOfFe(source.buffer.stored() + sink.buffer.stored() + held)
                    + rate.unitsOfMu(buffer.stored());
            long left = rate.unitsOfFe(FULL);
            helper.assertTrue(arrived <= left, "the round trip gained " + (arrived - left) + " units");
            helper.assertTrue(left - arrived < 2L * rate.mu(), "lost " + (left - arrived) + " units");
        }).thenSucceed();
    }

    /**
     * FE moved from a block into a MaxTech buffer's FE handler inside a transaction that is rolled back, nested and at
     * the root, leaves both where they were; committed, it arrives converted at 3:7.
     */
    private static void abortedMovesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FeEntity source = block(helper, SOURCE, 5_000);
        EnergyNetwork network = network(helper);
        EnergyEndpoint battery = EnergyEndpoint.buffer(100_000, 10_000, 10_000, EnergyPriority.DEFAULT);
        network.add(battery);
        FeEndpointHandler handler = new FeEndpointHandler(battery, level::getGameTime, () -> {
        });
        EnergyHandler from = FeLookups.BLOCK.find(level, helper.absolutePos(SOURCE), Direction.NORTH);
        if (from == null) {
            throw helper.assertionException("the test block has no FE handler");
        }
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(EnergyHandlerUtil.move(from, handler, 600, tx), 600, "moved in the transaction");
            try (Transaction inner = Transaction.open(tx)) {
                EnergyHandlerUtil.move(handler, from, 100, inner);
                inner.commit();
            }
        }
        helper.assertValueEqual(source.buffer.stored(), 5_000L, "source FE after a rollback");
        helper.assertValueEqual(battery.stored(), 0L, "MaxTech buffer after a rollback");
        helper.assertValueEqual(EnergyHandlerUtil.move(from, handler, 600, null), 600, "committed move");
        helper.assertValueEqual(battery.stored(), 1_400L, "600 FE are 1,400 MU");
        helper.assertValueEqual(source.buffer.stored(), 4_400L, "source FE");
        helper.succeed();
    }

    /**
     * Export off: a MaxTech generator charges a MaxTech battery and a block's FE still comes in, but a block taking FE
     * gets nothing, neither through its edge nor by pulling from the battery's FE handler.
     */
    private static void exportOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FeEntity source = block(helper, SOURCE, FULL);
        FeEntity sink = block(helper, SINK, 0);
        EnergyNetwork network = network(helper);
        edge(helper, network, SOURCE, Direction.DOWN);
        FeEdge out = edge(helper, network, SINK, Direction.UP);
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(500);
        EnergyEndpoint battery = EnergyEndpoint.buffer(1_000_000, 5_000, 5_000, EnergyPriority.DEFAULT);
        network.add(generator);
        network.add(battery);
        FeEndpointHandler handler = new FeEndpointHandler(battery, level::getGameTime, () -> {
        });
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(sink.buffer.stored(), 0L, "FE that reached another mod's block");
            helper.assertValueEqual(out.exporting(), 0L, "export flow");
            helper.assertTrue(source.buffer.stored() < FULL, "FE from another mod still comes in");
            helper.assertTrue(battery.stored() > 0, "the battery charges");
            helper.assertValueEqual(EnergyHandlerUtil.move(handler, sink.buffer, 500, null), 0,
                    "FE pulled out of a MaxTech block");
        }).thenSucceed();
    }

    /**
     * 100 MU per edge, 150 per network: two blocks next to a network with plenty of supply get 150 per tick together,
     * neither more than 100.
     */
    private static void exportLimit(GameTestHelper helper) {
        FeEntity first = block(helper, SINK, 0);
        FeEntity second = block(helper, SINK_2, 0);
        EnergyNetwork network = network(helper);
        FeEdge a = edge(helper, network, SINK, Direction.UP);
        FeEdge b = edge(helper, network, SINK_2, Direction.UP);
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(5_000);
        network.add(generator);
        long[] before = new long[2];
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            helper.assertTrue(a.exporting() <= 100 && b.exporting() <= 100,
                    "per-edge limit: " + a.exporting() + ", " + b.exporting());
            helper.assertValueEqual(a.exporting() + b.exporting(), 150L, "per-network limit");
            before[0] = first.buffer.stored();
            before[1] = second.buffer.stored();
        }).thenIdle(20).thenExecute(() -> {
            long gotFirst = first.buffer.stored() - before[0];
            long gotSecond = second.buffer.stored() - before[1];
            helper.assertValueEqual(gotFirst + gotSecond, 150L * 20, "delivered over 20 ticks");
            helper.assertTrue(gotFirst <= 100L * 20 && gotSecond <= 100L * 20, "per-edge limit over 20 ticks");
        }).thenSucceed();
    }

    /**
     * A block giving 1,000 FE per tick feeds a priority-1 machine wanting 600 and a priority-0 machine wanting 800
     * (brownout: half), and a block taking FE at leftovers-only priority gets nothing until the machines are served.
     */
    private static void endpointsInPass(GameTestHelper helper) {
        block(helper, SOURCE, 10 * FULL); // above the capacity, which the block keeps; it gives 1,000 FE per tick
        FeEntity sink = block(helper, SINK, 0);
        EnergyNetwork network = network(helper);
        edge(helper, network, SOURCE, Direction.DOWN);
        FeEdge leftovers = edge(helper, network, SINK, Direction.UP);
        leftovers.setExportPriority(EnergyPriority.LEFTOVERS);
        EnergyEndpoint important = EnergyEndpoint.consumer(1);
        important.setDemand(600);
        EnergyEndpoint normal = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        normal.setDemand(800);
        network.add(important);
        network.add(normal);
        long[] before = new long[1];
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.assertValueEqual(important.received(), 600L, "priority 1");
            helper.assertValueEqual(normal.received(), 400L, "priority 0 in brownout");
            helper.assertValueEqual(leftovers.exporting(), 0L, "leftovers while machines are short");
            helper.assertValueEqual(sink.buffer.stored(), 0L, "FE that reached the leftovers block");
            normal.setDemand(100);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertValueEqual(leftovers.exporting(), 300L, "leftovers once the machines are served");
            before[0] = sink.buffer.stored();
        }).thenIdle(3).thenExecute(() -> helper.assertValueEqual(sink.buffer.stored() - before[0], 900L,
                "FE that reached the leftovers block in 3 ticks")).thenSucceed();
    }

    /**
     * With idle edges polling only every 1,200 ticks, an edge to an empty spot starts sending energy within a few ticks
     * of an FE block being placed there, and stops when it is removed: the capability cache's invalidation wakes it.
     */
    private static void neighbourChange(GameTestHelper helper) {
        EnergyNetwork network = network(helper);
        FeEdge out = edge(helper, network, SINK, Direction.UP);
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(500);
        network.add(generator);
        // Takes back what the edge held for the block once it is gone; gives nothing, so the export is the generator.
        network.add(EnergyEndpoint.buffer(1_000_000, 10_000, 0, EnergyPriority.LEFTOVERS));
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(out.exporting(), 0L, "export to air");
            block(helper, SINK, 0);
        }).thenIdle(4).thenExecute(() -> {
            helper.assertValueEqual(out.exporting(), 500L, "export after the block was placed");
            helper.assertTrue(helper.getBlockEntity(SINK, FeEntity.class).buffer.stored() > 0, "FE delivered");
            helper.setBlock(SINK, Blocks.AIR);
        }).thenIdle(4).thenExecute(() -> {
            helper.assertValueEqual(out.exporting(), 0L, "export after the block was removed");
            helper.assertValueEqual(out.heldFe(), 0L, "FE held for a block that is gone");
        }).thenSucceed();
    }

    /**
     * A battery of another mod with no transfer limit, 1,000,000 FE, next to a network whose only taker is a nearly
     * full MaxTech buffer (1,000 MU of room): the edge holds no more than that room, not the battery's contents. Then
     * the host unloads with the battery (which takes nothing back then), saves what its edge held and restores it on
     * reload: in the end every FE is in the battery or the buffer.
     */
    private static void unloadKeepsHeld(GameTestHelper helper) {
        FeEntity battery = block(helper, SOURCE, 0);
        battery.buffer.setCapacity(10_000_000);
        battery.buffer.setLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);
        battery.buffer.load(1_000_000);
        EnergyNetwork network = network(helper);
        EnergyEndpoint store = EnergyEndpoint.buffer(10_000, 5_000, 0, EnergyPriority.DEFAULT);
        store.setStored(9_000);
        network.add(store);
        long total = 1_000_000 + 9_000;
        FeEdge first = edge(helper, network, SOURCE, Direction.NORTH);
        first.setExportPriority(EnergyPriority.LEFTOVERS);
        long[] saved = new long[1];
        FeEdge[] second = new FeEdge[1];
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(store.stored(), 10_000L, "the buffer filled");
            helper.assertTrue(first.heldFe() <= 1_000,
                    "the edge holds " + first.heldFe() + " FE, more than the network could place");
            // The battery's chunk unloads with the host: it takes nothing back, so the host saves what is held.
            battery.buffer.setLimits(0, Integer.MAX_VALUE);
            long held = first.heldFe();
            saved[0] = first.close();
            helper.assertValueEqual(saved[0], held, "returned by close");
            helper.assertValueEqual(battery.buffer.stored() + store.stored() + saved[0], total, "FE before reload");
            battery.buffer.setLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);
            second[0] = edge(helper, network, SOURCE, Direction.NORTH);
            second[0].setExportPriority(EnergyPriority.LEFTOVERS);
            second[0].restoreHeldFe(saved[0]);
        }).thenIdle(5).thenExecute(() -> {
            long left = second[0].close();
            helper.assertValueEqual(battery.buffer.stored() + store.stored() + left, total, "FE after reload");
        }).thenSucceed();
    }
}
