package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity.LabLink;
import dev.silencebound.maxtech.farms.lab.FluidPortBlockEntity;
import dev.silencebound.maxtech.farms.lab.LabNetwork;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import java.util.Map;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Lab tubes, the lab network and the fluid port: tubes beside a hub form one lab that the hub runs; a second hub on the
 * same tubes stays out until the first goes; the hub's MU store is the buffer of the lab's energy network; a port puts
 * what is pushed in into the hub's tank and nothing without a hub or room; output reaches the hub's buffer through the
 * lab. Nothing here ticks.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class LabNetworkGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final BlockPos HUB = new BlockPos(1, 1, 1);
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.of("lab_network_forms",
            LabNetworkGameTests::forms, "lab_network_one_hub", LabNetworkGameTests::oneHub, "lab_network_energy",
            LabNetworkGameTests::energy, "lab_fluid_port_fills_hub", LabNetworkGameTests::portFillsHub,
            "lab_fluid_port_needs_hub", LabNetworkGameTests::portNeedsHub, "lab_fluid_port_full_tank",
            LabNetworkGameTests::portFullTank, "lab_output_reaches_hub", LabNetworkGameTests::outputReachesHub,
            "lab_parts_never_tick", LabNetworkGameTests::neverTick);

    private LabNetworkGameTests() {
    }

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        for (Map.Entry<String, Consumer<GameTestHelper>> test : TESTS.entrySet()) {
            event.register(Registries.TEST_FUNCTION, MaxtechFarms.id(test.getKey()), test::getValue);
        }
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechFarms.id("lab_network"));
        for (String name : TESTS.keySet()) {
            event.registerTest(MaxtechFarms.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechFarms.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /**
     * Hub, two tubes and a port: one lab of two tubes run by the hub, the port in it, the tube's face towards the hub.
     */
    private static void forms(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        tubes(helper, 2, 3);
        FluidPortBlockEntity port = port(helper, new BlockPos(4, 1, 1));
        helper.succeedWhen(() -> {
            LabNetwork lab = lab(helper, new BlockPos(2, 1, 1));
            helper.assertTrue(lab.hub().orElse(null) == hub, "the lab knows its hub");
            helper.assertValueEqual(lab.tubes(), 2, "tubes");
            helper.assertValueEqual(lab.portCount(), 1, "ports");
            helper.assertTrue(hub.labLink() == LabLink.ACTIVE && hub.lab() == lab, "the hub runs the lab");
            helper.assertTrue(port.lab().orElse(null) == lab, "the port feeds the lab");
            ConduitHost tube = Conduits.host(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 1)))
                    .orElseThrow();
            helper.assertTrue(ConduitConnections.isConnected(tube.blockFaces(), Direction.WEST),
                    "the tube faces the hub");
        });
    }

    /**
     * A second hub on the tubes stays out: refused, not in the lab's energy network, the lab still knows the first.
     * Once the first goes, the second runs the lab.
     */
    private static void oneHub(GameTestHelper helper) {
        LabHubBlockEntity first = hub(helper, HUB);
        tubes(helper, 2, 4);
        BlockPos secondPos = new BlockPos(5, 1, 1);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(first.labLink() == LabLink.ACTIVE, "first runs"))
                .thenExecute(() -> hub(helper, secondPos)).thenWaitUntil(() -> {
                    LabHubBlockEntity second = helper.getBlockEntity(secondPos, LabHubBlockEntity.class);
                    LabNetwork lab = lab(helper, new BlockPos(3, 1, 1));
                    helper.assertTrue(second.labLink() == LabLink.REFUSED, "the second hub is refused");
                    helper.assertTrue(lab.hub().orElse(null) == first, "the lab keeps its first hub");
                    helper.assertTrue(second.energy().network().isEmpty(), "the second hub's MU stays out");
                    helper.assertTrue(first.energy().network().orElse(null) == lab.energy(), "first's MU is in");
                }).thenExecute(() -> helper.setBlock(HUB, Blocks.AIR)).thenWaitUntil(() -> {
                    LabHubBlockEntity second = helper.getBlockEntity(secondPos, LabHubBlockEntity.class);
                    LabNetwork lab = lab(helper, new BlockPos(3, 1, 1));
                    helper.assertTrue(second.labLink() == LabLink.ACTIVE, "the second hub took over");
                    helper.assertTrue(lab.hub().orElse(null) == second, "the lab's hub is the second");
                }).thenSucceed();
    }

    /** The hub's store is the lab energy network's buffer: a stand-in MU consumer in the lab draws from it. */
    private static void energy(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        hub.energy().setStored(10_000);
        tubes(helper, 2, 3);
        EnergyEndpoint consumer = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        consumer.setDemand(50);
        helper.startSequence().thenWaitUntil(() -> {
            LabNetwork lab = lab(helper, new BlockPos(2, 1, 1));
            helper.assertTrue(hub.energy().network().orElse(null) == lab.energy(), "the store joined the lab");
        }).thenExecute(() -> lab(helper, new BlockPos(3, 1, 1)).energy().add(consumer)).thenWaitUntil(() -> {
            helper.assertValueEqual(consumer.received(), 50L, "MU per tick the consumer gets");
            helper.assertTrue(hub.energy().stored() < 10_000, "the hub's store paid for it");
        }).thenExecute(() -> consumer.network().ifPresent(network -> network.remove(consumer))).thenSucceed();
    }

    /** What a pipe pushes in lands in the hub's tank, only on commit; nothing comes back out of the port. */
    private static void portFillsHub(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        tubes(helper, 2, 2);
        BlockPos portPos = new BlockPos(3, 1, 1);
        FluidPortBlockEntity port = port(helper, portPos);
        FluidResource water = FluidResource.of(Fluids.WATER);
        int capacity = FarmsServerConfig.hubTankCapacity();
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(port.lab().flatMap(LabNetwork::hub).orElse(null) == hub, "the port feeds the hub"))
                .thenExecute(() -> {
                    ResourceHandler<FluidResource> input = fluid(helper, portPos);
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(input.insert(water, 3000, transaction), 3000, "pushed in");
                    }
                    helper.assertValueEqual(hub.storage().tank().getAmountAsLong(0), 0L, "aborted push");
                    try (Transaction transaction = Transaction.openRoot()) {
                        input.insert(water, 3000, transaction);
                        transaction.commit();
                    }
                    helper.assertValueEqual(hub.storage().tank().getAmountAsLong(0), 3000L, "committed push");
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(input.extract(water, 1000, transaction), 0, "the port never gives");
                        helper.assertValueEqual(input.insert(water, capacity, transaction), capacity - 3000,
                                "only what fits");
                    }
                }).thenSucceed();
    }

    /** A port on tubes without a hub takes nothing. */
    private static void portNeedsHub(GameTestHelper helper) {
        tubes(helper, 2, 2);
        BlockPos portPos = new BlockPos(3, 1, 1);
        FluidPortBlockEntity port = port(helper, portPos);
        helper.succeedWhen(() -> {
            helper.assertTrue(port.lab().isPresent(), "the port is in a lab");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(
                        fluid(helper, portPos).insert(FluidResource.of(Fluids.WATER), 1000, transaction), 0,
                        "taken without a hub");
            }
        });
    }

    /** A port takes nothing while the hub's tank is full. */
    private static void portFullTank(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        FluidResource water = FluidResource.of(Fluids.WATER);
        try (Transaction transaction = Transaction.openRoot()) {
            hub.storage().tank().insert(water, FarmsServerConfig.hubTankCapacity(), transaction);
            transaction.commit();
        }
        tubes(helper, 2, 2);
        BlockPos portPos = new BlockPos(3, 1, 1);
        FluidPortBlockEntity port = port(helper, portPos);
        helper.succeedWhen(() -> {
            helper.assertTrue(port.lab().flatMap(LabNetwork::hub).isPresent(), "the port feeds the hub");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(fluid(helper, portPos).insert(water, 1000, transaction), 0,
                        "taken by a full tank");
            }
        });
    }

    /** Output a specimen hands the lab lands in the hub's buffer on commit; a lab without a hub takes none. */
    private static void outputReachesHub(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        tubes(helper, 2, 2);
        tubes(helper, 5, 5);
        ItemResource wheat = ItemResource.of(Items.WHEAT);
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(lab(helper, new BlockPos(2, 1, 1)).hub().isPresent(), "the lab has its hub"))
                .thenExecute(() -> {
                    LabNetwork lab = lab(helper, new BlockPos(2, 1, 1));
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(lab.insertOutput(wheat, 5, transaction), 5, "output in");
                    }
                    helper.assertValueEqual(hub.storage().output().getAmountAsLong(0), 0L, "aborted output");
                    try (Transaction transaction = Transaction.openRoot()) {
                        lab.insertOutput(wheat, 5, transaction);
                        transaction.commit();
                    }
                    helper.assertValueEqual(hub.storage().output().getAmountAsLong(0), 5L, "committed output");
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(lab(helper, new BlockPos(5, 1, 1)).insertOutput(wheat, 5, transaction),
                                0, "a lab without a hub");
                    }
                }).thenSucceed();
    }

    /** Tubes and ports register no ticker, and an idle lab's energy network sleeps. */
    private static void neverTick(GameTestHelper helper) {
        hub(helper, HUB);
        tubes(helper, 2, 2);
        BlockPos portPos = new BlockPos(3, 1, 1);
        port(helper, portPos);
        assertNoTicker(helper, new BlockPos(2, 1, 1));
        assertNoTicker(helper, portPos);
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            LabNetwork lab = lab(helper, new BlockPos(2, 1, 1));
            helper.assertTrue(lab.hub().isPresent(), "the lab has its hub");
            helper.assertTrue(lab.energy().isAsleep(), "an idle lab's energy network sleeps");
        }).thenSucceed();
    }

    private static void assertNoTicker(GameTestHelper helper, BlockPos pos) {
        BlockEntity entity = helper.getBlockEntity(pos, BlockEntity.class);
        helper.assertTrue(ticker(helper, pos, entity.getType()), "a ticker at " + pos);
    }

    private static <T extends BlockEntity> boolean ticker(GameTestHelper helper, BlockPos pos,
            BlockEntityType<T> type) {
        return helper.getBlockState(pos).getTicker(helper.getLevel(), type) == null;
    }

    private static LabHubBlockEntity hub(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, FarmsContent.LAB_HUB.get());
        return helper.getBlockEntity(pos, LabHubBlockEntity.class);
    }

    private static FluidPortBlockEntity port(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, FarmsContent.FLUID_PORT.get());
        return helper.getBlockEntity(pos, FluidPortBlockEntity.class);
    }

    /** Lab tubes along x from {@code fromX} to {@code toX} at y 1, z 1. */
    private static void tubes(GameTestHelper helper, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 1)),
                    LabTubes.TUBE.type().get()), "placed a tube");
        }
    }

    private static LabNetwork lab(GameTestHelper helper, BlockPos tube) {
        return LabNetwork.at(helper.getLevel(), helper.absolutePos(tube))
                .orElseThrow(() -> helper.assertionException(Component.literal("no lab network at " + tube)));
    }

    private static ResourceHandler<FluidResource> fluid(GameTestHelper helper, BlockPos pos) {
        ResourceHandler<FluidResource> handler = helper.getLevel().getCapability(Capabilities.Fluid.BLOCK,
                helper.absolutePos(pos), Direction.UP);
        if (handler == null) {
            throw helper.assertionException(Component.literal("no fluid capability at " + pos));
        }
        return handler;
    }
}
