package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** PROTOTYPE (proto/tube-fx only): the showcase's system carries the render values the client model reads. */
@EventBusSubscriber(modid = TubeFx.MOD_ID)
public final class TubeFxGameTests {
    private static final String NAME = "proto_tube_fx_system";
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(TubeFx.MOD_ID,
            Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> TubeFxGameTests::system);
    }

    private TubeFxGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(Identifier.fromNamespaceAndPath(TubeFx.MOD_ID, "proto_tube_fx"));
        Identifier id = Identifier.fromNamespaceAndPath(TubeFx.MOD_ID, NAME);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id), new TestData<>(
                        environment, Identifier.fromNamespaceAndPath(TubeFx.MOD_ID, "empty_42x4x27"), 100, 0, true)));
    }

    private static void system(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(1, 0, 0));
        TubeFx.Value sample = new TubeFx.Value(7, 3, Direction.WEST, true, 8, 3);
        helper.assertValueEqual(sample, TubeFx.Value.decode(sample.encode()), "render value round trip");
        TubeFxShowcase.buildSystem(helper.getLevel(), base, TubeFx.TUBE.type().get(),
                net.minecraft.world.level.material.Fluids.LAVA, 2, true, new int[]{1, 3}, true);
        ConduitHost first = Conduits.host(helper.getLevel(), base.south()).orElseThrow();
        TubeFx.Value value = TubeFx.Value.decode(first.renderValue());
        helper.assertValueEqual(BuiltInRegistries.FLUID.getId(net.minecraft.world.level.material.Fluids.LAVA),
                value.fluidId(), "fluid");
        helper.assertTrue(value.upstream() == Direction.NORTH, "upstream of the first piece");
        helper.assertValueEqual(ConduitConnections.bit(Direction.NORTH), first.blockFaces(), "machine face");
        ConduitHost branch = Conduits.host(helper.getLevel(), base.offset(0, 1, 4)).orElseThrow();
        helper.assertTrue(TubeFx.Value.decode(branch.renderValue()).upstream() == Direction.DOWN,
                "upstream of the branch");
        helper.succeed();
    }
}
