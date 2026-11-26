package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.energy.EnergyReadout;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.info.EnergyInfo;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import dev.silencebound.maxtech.lib.info.ScannerItem;
import dev.silencebound.maxtech.lib.neoforge.compat.jade.BlockInfoLines;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Energy diagnostics (MLE item 3) on a real level: a test block shows a consumer endpoint through the
 * {@link EnergyNetworks#ENDPOINT_CAPABILITY} capability, as an addon would register it. The readout and the Jade and
 * scanner lines match a known setup, and asking for them, however often, never wakes a sleeping network.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class EnergyDiagnosticsGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final String KNOWN = "energy_readout_known_setup";
    private static final String NO_WAKE = "energy_readout_never_wakes";
    private static final BlockPos MACHINE = new BlockPos(1, 1, 1);

    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry().registrar(MaxtechLib.MOD_ID,
            Registries.BLOCK);
    private static final RegistryEntry<Block, EndpointBlock> ENDPOINT_BLOCK = BLOCKS.register("test_energy_endpoint",
            id -> new EndpointBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<EndpointEntity>> ENDPOINT_ENTITY = BLOCK_ENTITIES
            .register("test_energy_endpoint",
                    () -> new BlockEntityType<>(EndpointEntity::new, Set.of(ENDPOINT_BLOCK.get())));
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        EnergyConfigEnvironment.init();
        FUNCTIONS.register(KNOWN, () -> EnergyDiagnosticsGameTests::knownSetup);
        FUNCTIONS.register(NO_WAKE, () -> EnergyDiagnosticsGameTests::neverWakes);
    }

    private EnergyDiagnosticsGameTests() {
    }

    /** The api's capability, made from its id as an addon with only the api jar would. */
    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        BlockCapability<EnergyEndpoint, @Nullable Direction> capability = BlockCapability
                .createSided(EnergyNetworks.ENDPOINT_CAPABILITY, EnergyEndpoint.class);
        event.registerBlockEntity(capability, ENDPOINT_ENTITY.get(), (entity, side) -> entity.endpoint);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechLib.id("energy_diagnostics"), new EnergyConfigEnvironment("", ""));
        for (String name : new String[]{KNOWN, NO_WAKE}) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    private static EnergyNetwork network(GameTestHelper helper) {
        EnergyNetwork network = EnergyNetworks.of(helper.getLevel()).createNetwork();
        EnergyConfigEnvironment.onTeardown(network::dispose);
        return network;
    }

    /** The test machine at {@link #MACHINE}, its consumer demanding {@code demand} MU/t, joined to {@code network}. */
    private static EnergyEndpoint machine(GameTestHelper helper, EnergyNetwork network, long demand) {
        helper.setBlock(MACHINE, ENDPOINT_BLOCK.get());
        EnergyEndpoint endpoint = helper.getBlockEntity(MACHINE, EndpointEntity.class).endpoint;
        endpoint.setDemand(demand);
        network.add(endpoint);
        return endpoint;
    }

    private static List<Component> jade(GameTestHelper helper, boolean details) {
        List<Component> texts = new ArrayList<>();
        for (InfoEntry entry : BlockInfoLines.collect(helper.getLevel(), helper.absolutePos(MACHINE), Direction.UP,
                null, details)) {
            texts.add(entry.text());
        }
        return texts;
    }

    private static List<Component> scan(GameTestHelper helper) {
        return ScannerItem.scan(new BlockInfoRequest(helper.getLevel(), helper.absolutePos(MACHINE), Direction.UP, null,
                BlockInfoRequest.Detail.FULL));
    }

    private static List<String> keys(List<Component> lines) {
        List<String> keys = new ArrayList<>();
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents translatable) {
                keys.add(translatable.getKey());
            }
        }
        return keys;
    }

    /**
     * 100 MU/t of supply, a machine asking 160 and a leftovers-only battery giving up to 40 of its 5,000 MU: the
     * machine gets 140 (87.5 %), the battery empties in 125 ticks, and Jade and the scanner say so.
     */
    private static void knownSetup(GameTestHelper helper) {
        EnergyNetwork network = network(helper);
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(100);
        network.add(generator);
        EnergyEndpoint battery = EnergyEndpoint.buffer(10_000, 40, 40, EnergyPriority.LEFTOVERS);
        battery.setStored(5_000);
        network.add(battery);
        EnergyEndpoint machine = machine(helper, network, 160);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertTrue(EnergyLookups.BLOCK.find(helper.getLevel(), helper.absolutePos(MACHINE), null) == machine,
                    "the capability shows the machine's endpoint");
            EnergyReadout readout = network.readout();
            helper.assertValueEqual(100L, readout.supply(), "supply");
            helper.assertValueEqual(160L, readout.demand(), "demand");
            helper.assertValueEqual(140L, readout.received(), "received");
            helper.assertValueEqual(40L, readout.bufferDischarge(), "battery output");
            helper.assertTrue(readout.brownout(), "a brownout");
            helper.assertValueEqual(0.875, readout.suppliedFraction(), "supplied fraction");
            helper.assertValueEqual(10_000L, readout.capacity(), "capacity");
            helper.assertValueEqual(battery.stored(), readout.stored(), "stored");
            helper.assertValueEqual(OptionalLong.of(battery.stored() / 40), readout.ticksToEmpty(), "time to empty");
            helper.assertValueEqual(OptionalLong.empty(), readout.ticksToFull(), "time to full");
            helper.assertValueEqual(0.875, machine.suppliedFraction(), "the machine's own fraction");

            List<String> brief = keys(jade(helper, false));
            // Compact: one entry, the machine's own use; the network's lines wait for the details.
            helper.assertValueEqual(List.of(EnergyInfo.USING_KEY), brief, "Jade's brief lines");
            List<String> full = keys(jade(helper, true));
            helper.assertTrue(full.contains(EnergyInfo.COST_KEY) && full.contains(EnergyInfo.NETWORK_STORED_KEY),
                    "Jade's details show the pass cost and stored energy: " + full);
            List<String> scanned = keys(scan(helper));
            helper.assertTrue(scanned.contains(EnergyInfo.USING_KEY) && scanned.contains(EnergyInfo.COST_KEY),
                    "the scanner shows the energy lines: " + scanned);
        }).thenSucceed();
    }

    /**
     * A generator feeding the machine with no battery: after the first pass the network sleeps with no predicted wake.
     * Reading it every tick for 40 ticks through the readout, Jade (brief and details) and the scanner runs no pass.
     */
    private static void neverWakes(GameTestHelper helper) {
        EnergyNetwork network = network(helper);
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(100);
        network.add(generator);
        EnergyEndpoint machine = machine(helper, network, 60);
        long[] passes = new long[1];
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertValueEqual(60L, machine.received(), "the machine is served");
            helper.assertTrue(network.isAsleep(), "the network sleeps");
            passes[0] = network.passCount();
        }).thenExecuteFor(40, () -> {
            for (int i = 0; i < 10; i++) {
                network.readout();
                jade(helper, false);
                jade(helper, true);
                scan(helper);
            }
        }).thenExecute(() -> {
            helper.assertValueEqual(passes[0], network.passCount(), "passes while only read");
            helper.assertTrue(network.isAsleep(), "the network still sleeps");
            helper.assertValueEqual(60L, network.readout().received(), "the readout");
        }).thenSucceed();
    }

    static final class EndpointBlock extends Block implements EntityBlock {
        static final MapCodec<EndpointBlock> CODEC = simpleCodec(EndpointBlock::new);

        EndpointBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new EndpointEntity(pos, state);
        }
    }

    /** A machine with one consumer endpoint; not saved. */
    static final class EndpointEntity extends BlockEntity {
        final EnergyEndpoint endpoint = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);

        EndpointEntity(BlockPos pos, BlockState state) {
            super(ENDPOINT_ENTITY.get(), pos, state);
        }
    }
}
