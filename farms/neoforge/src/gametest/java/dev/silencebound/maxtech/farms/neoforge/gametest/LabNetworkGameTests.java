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
 * Lab tubes, the lab network and the fluid port: tubes beside a hub form one lab that the hub runs; a hub placed later
 * on the same tubes stays out until the first goes, the lowest hub wins only among hubs alike; the hub's MU store is
 * the buffer of the lab's energy network; a port puts what is pushed in into the hub's tank and nothing without a hub
 * or room; output reaches the hub's buffer through the lab. Nothing here ticks.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class LabNetworkGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final BlockPos HUB = new BlockPos(1, 1, 1);
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("lab_network_forms", LabNetworkGameTests::forms),
            Map.entry("lab_network_one_hub", LabNetworkGameTests::oneHub),
            Map.entry("lab_network_energy", LabNetworkGameTests::energy),
            Map.entry("lab_fluid_port_fills_hub", LabNetworkGameTests::portFillsHub),
            Map.entry("lab_fluid_port_needs_hub", LabNetworkGameTests::portNeedsHub),
            Map.entry("lab_fluid_port_full_tank", LabNetworkGameTests::portFullTank),
            Map.entry("lab_output_reaches_hub", LabNetworkGameTests::outputReachesHub),
            Map.entry("lab_parts_never_tick", LabNetworkGameTests::neverTick),
            Map.entry("lab_new_hub_refused", LabNetworkGameTests::newHubRefused),
            Map.entry("lab_lowest_hub_wins", LabNetworkGameTests::lowestHubWins),
            Map.entry("lab_split_keeps_running_hub", LabNetworkGameTests::splitKeepsRunningHub),
            Map.entry("lab_merge_keeps_lowest_hub", LabNetworkGameTests::mergeKeepsLowestHub),
            Map.entry("lab_split_hub_one_side", LabNetworkGameTests::splitHubOneSide),
            Map.entry("lab_tube_removed_port_hub", LabNetworkGameTests::tubeRemovedBetweenPortAndHub),
            Map.entry("lab_hub_two_networks", LabNetworkGameTests::hubBesideTwoNetworks));

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

    /**
     * A hub placed beside a running lab is refused even at a lower position (lower y): the running hub stays in charge,
     * one store in the energy network, neither store gains or loses MU.
     */
    private static void newHubRefused(GameTestHelper helper) {
        LabHubBlockEntity first = hub(helper, HUB);
        first.energy().setStored(3000);
        tubes(helper, 2, 4);
        BlockPos lowerPos = new BlockPos(4, 0, 1);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(first.labLink() == LabLink.ACTIVE, "first runs"))
                .thenExecute(() -> hub(helper, lowerPos).energy().setStored(5000)).thenWaitUntil(() -> {
                    LabHubBlockEntity lower = helper.getBlockEntity(lowerPos, LabHubBlockEntity.class);
                    helper.assertTrue(lower.labLink() == LabLink.REFUSED, "the placed hub is refused");
                }).thenIdle(3).thenExecute(() -> {
                    LabHubBlockEntity lower = helper.getBlockEntity(lowerPos, LabHubBlockEntity.class);
                    LabNetwork lab = lab(helper, new BlockPos(3, 1, 1));
                    helper.assertTrue(lab.hub().orElse(null) == first, "the running hub keeps the lab");
                    helper.assertTrue(lower.labLink() == LabLink.REFUSED, "the placed hub stays refused");
                    assertOnlyStore(helper, lab, first, lower);
                    helper.assertValueEqual(first.energy().stored(), 3000L, "the running hub's MU");
                    helper.assertValueEqual(lower.energy().stored(), 5000L, "the placed hub's MU");
                }).thenSucceed();
    }

    /** With no hub running yet (both there before the tubes), the lowest one runs the lab; lowest y beats lowest x. */
    private static void lowestHubWins(GameTestHelper helper) {
        LabHubBlockEntity west = hub(helper, HUB);
        LabHubBlockEntity lower = hub(helper, new BlockPos(4, 0, 1));
        tubes(helper, 2, 4);
        helper.succeedWhen(() -> {
            LabNetwork lab = lab(helper, new BlockPos(3, 1, 1));
            helper.assertTrue(lab.hub().orElse(null) == lower, "the lowest hub runs the lab");
            helper.assertTrue(west.labLink() == LabLink.REFUSED, "the other hub is refused");
            assertOnlyStore(helper, lab, lower, west);
        });
    }

    /**
     * A split keeps the running hub on its side although a lower hub placed later is on the same side; the other side
     * has no hub.
     */
    private static void splitKeepsRunningHub(GameTestHelper helper) {
        BlockPos runningPos = new BlockPos(5, 1, 0);
        BlockPos lowerPos = new BlockPos(4, 1, 2);
        LabHubBlockEntity running = hub(helper, runningPos);
        tubes(helper, 2, 5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(running.labLink() == LabLink.ACTIVE, "the first hub runs"))
                .thenExecute(() -> hub(helper, lowerPos))
                .thenWaitUntil(() -> helper.assertTrue(
                        helper.getBlockEntity(lowerPos, LabHubBlockEntity.class).labLink() == LabLink.REFUSED,
                        "the placed hub is refused"))
                .thenExecute(() -> helper.setBlock(new BlockPos(3, 1, 1), Blocks.AIR)).thenWaitUntil(() -> {
                    helper.assertTrue(lab(helper, new BlockPos(2, 1, 1)).hub().isEmpty(),
                            "the cut-off side has no hub");
                    helper.assertValueEqual(lab(helper, new BlockPos(4, 1, 1)).tubes(), 2, "tubes on the hubs' side");
                }).thenIdle(3).thenExecute(() -> {
                    LabNetwork lab = lab(helper, new BlockPos(4, 1, 1));
                    helper.assertTrue(lab.hub().orElse(null) == running, "the running hub keeps its side");
                    helper.assertTrue(
                            helper.getBlockEntity(lowerPos, LabHubBlockEntity.class).labLink() == LabLink.REFUSED,
                            "the placed hub stays refused");
                    assertOnlyStore(helper, lab, running, helper.getBlockEntity(lowerPos, LabHubBlockEntity.class));
                }).thenSucceed();
    }

    /**
     * Two labs with a hub each merge: the lowest hub runs the merged lab although the other lab (the larger one, which
     * survives the merge) had the other hub; one store in the energy network, MU unchanged.
     */
    private static void mergeKeepsLowestHub(GameTestHelper helper) {
        BlockPos lowPos = new BlockPos(0, 1, 1);
        BlockPos highPos = new BlockPos(6, 1, 1);
        LabHubBlockEntity low = hub(helper, lowPos);
        LabHubBlockEntity high = hub(helper, highPos);
        low.energy().setStored(1000);
        high.energy().setStored(2000);
        tubes(helper, 1, 1);
        tubes(helper, 3, 5);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        low.labLink() == LabLink.ACTIVE && high.labLink() == LabLink.ACTIVE, "each lab runs a hub"))
                .thenExecute(() -> tubes(helper, 2, 2)).thenWaitUntil(() -> {
                    LabNetwork lab = lab(helper, new BlockPos(2, 1, 1));
                    helper.assertValueEqual(lab.tubes(), 5, "merged tubes");
                    helper.assertTrue(lab.hub().orElse(null) == low, "the lowest hub runs the merged lab");
                    helper.assertTrue(high.labLink() == LabLink.REFUSED, "the other hub is refused");
                    assertOnlyStore(helper, lab, low, high);
                    helper.assertValueEqual(low.energy().stored() + high.energy().stored(), 3000L, "MU of both");
                }).thenSucceed();
    }

    /**
     * Breaking a tube splits the lab: the port on the hub's side still fills the tank, the port on the other side takes
     * nothing.
     */
    private static void splitHubOneSide(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        tubes(helper, 2, 5);
        BlockPos nearPos = new BlockPos(2, 1, 0);
        BlockPos farPos = new BlockPos(6, 1, 1);
        port(helper, nearPos);
        FluidPortBlockEntity far = port(helper, farPos);
        FluidResource water = FluidResource.of(Fluids.WATER);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(far.lab().flatMap(LabNetwork::hub).orElse(null) == hub,
                        "the far port feeds the hub"))
                .thenExecute(() -> helper.setBlock(new BlockPos(4, 1, 1), Blocks.AIR)).thenWaitUntil(() -> {
                    helper.assertTrue(lab(helper, new BlockPos(5, 1, 1)).hub().isEmpty(), "the far side has no hub");
                    helper.assertTrue(lab(helper, new BlockPos(2, 1, 1)).hub().orElse(null) == hub,
                            "the near side keeps the hub");
                }).thenExecute(() -> {
                    helper.assertValueEqual(push(helper, farPos, water, 1000), 0, "taken by the far port");
                    helper.assertValueEqual(push(helper, nearPos, water, 1000), 1000, "taken by the near port");
                    helper.assertValueEqual(hub.storage().tank().getAmountAsLong(0), 1000L, "in the tank");
                }).thenSucceed();
    }

    /** With the only tube between them gone, the port takes nothing and the hub runs no lab. */
    private static void tubeRemovedBetweenPortAndHub(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        tubes(helper, 2, 2);
        BlockPos portPos = new BlockPos(3, 1, 1);
        FluidPortBlockEntity port = port(helper, portPos);
        FluidResource water = FluidResource.of(Fluids.WATER);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(port.lab().flatMap(LabNetwork::hub).orElse(null) == hub,
                        "the port feeds the hub"))
                .thenExecute(() -> helper.setBlock(new BlockPos(2, 1, 1), Blocks.AIR)).thenWaitUntil(() -> {
                    helper.assertTrue(port.lab().isEmpty(), "the port is in no lab");
                    helper.assertTrue(hub.labLink() == LabLink.NONE, "the hub runs no lab");
                    helper.assertTrue(hub.energy().network().isEmpty(), "the hub's MU left the lab");
                }).thenExecute(() -> {
                    helper.assertValueEqual(push(helper, portPos, water, 1000), 0, "taken by the port");
                    helper.assertValueEqual(hub.storage().tank().getAmountAsLong(0), 0L, "in the tank");
                }).thenSucceed();
    }

    /**
     * A hub between two lab networks runs the one that took it first; the other's port takes nothing. When the first
     * network goes, the other takes the hub.
     */
    private static void hubBesideTwoNetworks(GameTestHelper helper) {
        BlockPos hubPos = new BlockPos(3, 1, 1);
        LabHubBlockEntity hub = hub(helper, hubPos);
        tubes(helper, 2, 2);
        BlockPos westPort = new BlockPos(1, 1, 1);
        BlockPos eastPort = new BlockPos(5, 1, 1);
        port(helper, westPort);
        FluidPortBlockEntity east = port(helper, eastPort);
        FluidResource water = FluidResource.of(Fluids.WATER);
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(hub.lab() == lab(helper, new BlockPos(2, 1, 1)), "the west lab took the hub"))
                .thenExecute(() -> tubes(helper, 4, 4)).thenWaitUntil(() -> {
                    helper.assertTrue(east.lab().isPresent(), "the east port is in its lab");
                    helper.assertTrue(lab(helper, new BlockPos(4, 1, 1)).hub().isEmpty(), "the east lab has no hub");
                    helper.assertTrue(hub.lab() == lab(helper, new BlockPos(2, 1, 1)), "the west lab keeps it");
                }).thenExecute(() -> {
                    helper.assertValueEqual(push(helper, eastPort, water, 1000), 0, "taken by the east port");
                    helper.assertValueEqual(push(helper, westPort, water, 1000), 1000, "taken by the west port");
                    helper.setBlock(new BlockPos(2, 1, 1), Blocks.AIR);
                }).thenWaitUntil(() -> {
                    LabNetwork lab = lab(helper, new BlockPos(4, 1, 1));
                    helper.assertTrue(lab.hub().orElse(null) == hub, "the east lab took the hub");
                    helper.assertTrue(hub.energy().network().orElse(null) == lab.energy(), "the store moved over");
                }).thenExecute(() -> {
                    helper.assertValueEqual(push(helper, eastPort, water, 1000), 1000, "taken by the east port");
                    helper.assertValueEqual(hub.storage().tank().getAmountAsLong(0), 2000L, "in the tank");
                }).thenSucceed();
    }

    /** {@code active}'s store is the only one in the lab's energy network; {@code other}'s is in none. */
    private static void assertOnlyStore(GameTestHelper helper, LabNetwork lab, LabHubBlockEntity active,
            LabHubBlockEntity other) {
        helper.assertTrue(active.energy().network().orElse(null) == lab.energy(), "the active store is in the lab");
        helper.assertTrue(other.energy().network().isEmpty(), "the inactive store is in no energy network");
        helper.assertValueEqual(lab.energy().size(), 1, "endpoints in the lab's energy network");
    }

    /** Pushes {@code amount} of {@code fluid} into the port at {@code pos}, committed; returns what it took. */
    private static int push(GameTestHelper helper, BlockPos pos, FluidResource fluid, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            int taken = fluid(helper, pos).insert(fluid, amount, transaction);
            transaction.commit();
            return taken;
        }
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
