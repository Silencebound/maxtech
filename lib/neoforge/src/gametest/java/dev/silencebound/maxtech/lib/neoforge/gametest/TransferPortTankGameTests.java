package dev.silencebound.maxtech.lib.neoforge.gametest;

import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.OFF;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PULL;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PUSH;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestTanks.ForeignEntity;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestTanks.PortEntity;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestTanks.TankEntity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import java.util.List;
import java.util.Objects;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;

/**
 * Lib's transfer ports with tanks (ADR-0018) on real blocks: fluids pushed into a NeoForge tank and pulled from a
 * vanilla cauldron, tank roles, filters, the rules between two ports, full and partly full targets, unknown contents, a
 * handler without transactions that executes less than it simulated (the GameTest kind {@code test_glow}), sleeping,
 * and a target in a chunk below full status.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TransferPortTankGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockPos PORT = new BlockPos(1, 0, 1);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);
    private static final FluidKey LAVA = FluidKey.of(Fluids.LAVA);
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<Test> TESTS = List.of(new Test("transfer_port_fluid_push_tank", 100, false),
            new Test("transfer_port_fluid_pull_cauldron", 100, false),
            new Test("transfer_port_fluid_tank_roles", 100, false), new Test("transfer_port_fluid_filter", 150, false),
            new Test("transfer_port_fluid_between_ports", 100, false),
            new Test("transfer_port_fluid_full_target", 100, false),
            new Test("transfer_port_fluid_unknown", 100, false),
            new Test("transfer_port_foreign_push_short", 200, false),
            new Test("transfer_port_foreign_pull_short", 200, false),
            new Test("transfer_port_foreign_push_one_move", 400, false),
            new Test("transfer_port_fluid_pull_unknown", 100, false),
            new Test("transfer_port_fluid_pull_filter", 150, false),
            new Test("transfer_port_fluid_neighbour_filters", 100, false),
            new Test("transfer_port_fluid_sleeps", 1600, false),
            new Test("transfer_port_fluid_target_unload", 400, true),
            new Test("transfer_port_fluid_stale_cache", 800, true));

    static {
        FUNCTIONS.register("transfer_port_fluid_push_tank", () -> TransferPortTankGameTests::pushTank);
        FUNCTIONS.register("transfer_port_fluid_pull_cauldron", () -> TransferPortTankGameTests::pullCauldron);
        FUNCTIONS.register("transfer_port_fluid_tank_roles", () -> TransferPortTankGameTests::tankRoles);
        FUNCTIONS.register("transfer_port_fluid_filter", () -> TransferPortTankGameTests::filter);
        FUNCTIONS.register("transfer_port_fluid_between_ports", () -> TransferPortTankGameTests::betweenPorts);
        FUNCTIONS.register("transfer_port_fluid_full_target", () -> TransferPortTankGameTests::fullTarget);
        FUNCTIONS.register("transfer_port_fluid_unknown", () -> TransferPortTankGameTests::unknown);
        FUNCTIONS.register("transfer_port_foreign_push_short", () -> TransferPortTankGameTests::foreignPush);
        FUNCTIONS.register("transfer_port_foreign_pull_short", () -> TransferPortTankGameTests::foreignPull);
        FUNCTIONS.register("transfer_port_foreign_push_one_move", () -> TransferPortTankGameTests::foreignOneMove);
        FUNCTIONS.register("transfer_port_fluid_pull_unknown", () -> TransferPortTankGameTests::pullUnknown);
        FUNCTIONS.register("transfer_port_fluid_pull_filter", () -> TransferPortTankGameTests::pullFilter);
        FUNCTIONS.register("transfer_port_fluid_neighbour_filters", () -> TransferPortTankGameTests::neighbourFilters);
        FUNCTIONS.register("transfer_port_fluid_sleeps", () -> TransferPortTankGameTests::sleeps);
        FUNCTIONS.register("transfer_port_fluid_target_unload", () -> TransferPortTankGameTests::targetUnload);
        FUNCTIONS.register("transfer_port_fluid_stale_cache", () -> TransferPortTankGameTests::staleCache);
    }

    private record Test(String name, int maxTicks, boolean far) {
    }

    private TransferPortTankGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechLib.id("transfer_port_tank"));
        for (Test test : TESTS) {
            Identifier id = MaxtechLib.id(test.name());
            event.registerTest(id,
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                            new TestData<>(environment, test.far() ? LibGameTests.EMPTY_STRUCTURE : STRUCTURE,
                                    test.maxTicks(), 0, true)));
        }
    }

    private static PortSettings faces(Direction face, PortFaceMode mode) {
        return PortSettings.EMPTY.withMode(ResourceKinds.FLUID, face, mode);
    }

    private static PortEntity port(GameTestHelper helper, BlockPos pos) {
        return TestTanks.place(helper.getLevel(), helper.absolutePos(pos), Block.UPDATE_ALL);
    }

    private static TankEntity tank(GameTestHelper helper, BlockPos pos, int capacity) {
        return TestTanks.tank(helper.getLevel(), helper.absolutePos(pos), capacity, Block.UPDATE_ALL);
    }

    /** The amount of {@code fluid} in a NeoForge fluid handler's first index. */
    private static long amount(FluidStacksResourceHandler handler, Fluid fluid) {
        return handler.getResource(0).equals(FluidResource.of(fluid)) ? handler.getAmountAsLong(0) : 0;
    }

    private static long amount(FluidTank tank, FluidKey key) {
        return tank.resource().filter(key::equals).isPresent() ? tank.amount() : 0;
    }

    /** A pushing face empties the output tank into a NeoForge tank, up to the configured fluid per operation each. */
    private static void pushTank(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        TankEntity sink = tank(helper, PORT.east(), 16000);
        port.output.set(WATER, 3500);
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.assertValueEqual(port.port.kinds(), List.of(ResourceKinds.FLUID, ConduitApiGameTests.TEST_KIND),
                "kinds in the order added");
        helper.succeedWhen(() -> {
            helper.assertValueEqual(amount(sink.handler, Fluids.WATER), 3500L, "water in the tank");
            helper.assertValueEqual(port.output.amount(), 0L, "water left");
            helper.assertValueEqual(port.port.status(ResourceKinds.FLUID), PortStatus.IDLE, "status once empty");
        });
    }

    /** A pulling face empties a full vanilla water cauldron into the first pulling tank. */
    private static void pullCauldron(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        helper.setBlock(PORT.west(), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        port.port.setSettings(faces(Direction.WEST, PULL));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(amount(port.inputA, WATER), 1000L, "water pulled");
            helper.assertBlockPresent(Blocks.CAULDRON, PORT.west());
            helper.assertValueEqual(port.output.amount(), 0L, "into the push-only tank");
        });
    }

    /**
     * Roles of several tanks of one kind: a push takes only from the pushing tank (lava in a pulling tank stays), a
     * pull skips the pulling tank holding another fluid and fills the next one, never the push-only tank, also when the
     * last operation fills it with less than the fluid per operation.
     */
    private static void tankRoles(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        TankEntity sink = tank(helper, PORT.east(), 16000);
        TankEntity source = tank(helper, PORT.west(), 16000);
        port.inputA.set(LAVA, 1000);
        port.output.set(WATER, 2000);
        port.inputB.set(WATER, 500);
        source.handler.set(0, FluidResource.of(Fluids.WATER), 6000);
        port.port.setSettings(faces(Direction.EAST, PUSH).withMode(ResourceKinds.FLUID, Direction.WEST, PULL));
        helper.startSequence().thenExecuteAfter(80, () -> {
            helper.assertValueEqual(amount(sink.handler, Fluids.WATER), 2000L, "water pushed");
            helper.assertValueEqual(amount(sink.handler, Fluids.LAVA), 0L, "lava pushed from a pulling tank");
            helper.assertValueEqual(amount(port.inputA, LAVA), 1000L, "lava kept");
            helper.assertValueEqual(amount(port.inputB, WATER), 4000L, "water pulled into the second tank");
            helper.assertValueEqual(amount(source.handler, Fluids.WATER), 2500L, "water left in the source");
            helper.assertValueEqual(port.output.amount(), 0L, "pulled into the push-only tank");
        }).thenSucceed();
    }

    /** A port's filter: a deny list with the water tag keeps water in; denying lava instead lets it out. */
    private static void filter(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        TankEntity sink = tank(helper, PORT.east(), 16000);
        port.output.set(WATER, 1000);
        PortSettings push = faces(Direction.EAST, PUSH);
        port.port.setSettings(push.withFilter(ResourceKinds.FLUID, Optional.of(PortFilter.of(ResourceKinds.FLUID,
                PortFilter.Mode.DENY, List.of(), List.of(Identifier.withDefaultNamespace("water"))))));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertValueEqual(amount(sink.handler, Fluids.WATER), 0L, "denied water pushed");
            helper.assertValueEqual(port.output.amount(), 1000L, "water left");
            port.port.setSettings(push.withFilter(ResourceKinds.FLUID,
                    Optional.of(PortFilter.of(ResourceKinds.FLUID, PortFilter.Mode.DENY, List.of(LAVA), List.of()))));
        }).thenWaitUntil(() -> helper.assertValueEqual(amount(sink.handler, Fluids.WATER), 1000L, "water pushed"))
                .thenSucceed();
    }

    /**
     * Every pair of modes on two facing port faces (port A's east, port B's west): only a pushing face facing a pulling
     * one moves, into the puller's pulling tanks; an off face is a plain block, reached through its plain capability.
     */
    private static void betweenPorts(GameTestHelper helper) {
        PortFaceMode[] modes = PortFaceMode.values();
        PortEntity[] as = new PortEntity[9];
        PortEntity[] bs = new PortEntity[9];
        for (int k = 0; k < 9; k++) {
            BlockPos a = new BlockPos((k % 2) * 3, (k / 2) % 2, k / 4);
            as[k] = port(helper, a);
            bs[k] = port(helper, a.east());
            as[k].output.set(WATER, 1000);
            bs[k].output.set(LAVA, 1000);
            as[k].port.setSettings(faces(Direction.EAST, modes[k / 3]));
            bs[k].port.setSettings(faces(Direction.WEST, modes[k % 3]));
        }
        helper.startSequence().thenExecuteAfter(40, () -> {
            for (int k = 0; k < 9; k++) {
                PortFaceMode a = modes[k / 3];
                PortFaceMode b = modes[k % 3];
                String pair = a + " facing " + b + ": ";
                long waterToInput = a == PUSH && b == PULL ? 1000 : 0;
                long waterToPlain = a == PUSH && b == OFF ? 1000 : 0;
                long lavaToInput = a == PULL && b == PUSH ? 1000 : 0;
                long lavaToPlain = a == OFF && b == PUSH ? 1000 : 0;
                PortEntity pa = as[k];
                PortEntity pb = bs[k];
                helper.assertValueEqual(amount(pb.inputA, WATER), waterToInput, pair + "water in B");
                helper.assertValueEqual(amount(pb.plain, Fluids.WATER), waterToPlain, pair + "water at B");
                helper.assertValueEqual(amount(pa.output, WATER), 1000 - waterToInput - waterToPlain,
                        pair + "water left in A");
                helper.assertValueEqual(amount(pa.inputA, LAVA), lavaToInput, pair + "lava in A");
                helper.assertValueEqual(amount(pa.plain, Fluids.LAVA), lavaToPlain, pair + "lava at A");
                helper.assertValueEqual(amount(pb.output, LAVA), 1000 - lavaToInput - lavaToPlain,
                        pair + "lava left in B");
            }
        }).thenSucceed();
    }

    /**
     * A full target takes nothing and the port is blocked; a partly full one takes what fits and the rest stays in the
     * port, so the totals never change.
     */
    private static void fullTarget(GameTestHelper helper) {
        PortEntity full = port(helper, PORT);
        TankEntity fullSink = tank(helper, PORT.east(), 1000);
        fullSink.handler.set(0, FluidResource.of(Fluids.WATER), 1000);
        full.output.set(WATER, 2000);
        full.port.setSettings(faces(Direction.EAST, PUSH));
        BlockPos other = new BlockPos(1, 0, 2);
        PortEntity partial = port(helper, other);
        TankEntity partialSink = tank(helper, other.east(), 1500);
        partialSink.handler.set(0, FluidResource.of(Fluids.WATER), 1000);
        partial.output.set(WATER, 2000);
        partial.port.setSettings(faces(Direction.EAST, PUSH));
        helper.startSequence().thenExecuteAfter(30, () -> {
            helper.assertValueEqual(full.output.amount(), 2000L, "water left beside a full tank");
            helper.assertValueEqual(amount(fullSink.handler, Fluids.WATER), 1000L, "full tank");
            helper.assertValueEqual(full.port.status(ResourceKinds.FLUID), PortStatus.BLOCKED, "blocked status");
            helper.assertValueEqual(partial.output.amount(), 1500L, "water left beside a partly full tank");
            helper.assertValueEqual(amount(partialSink.handler, Fluids.WATER), 1500L, "partly full tank filled");
        }).thenSucceed();
    }

    /** A pushing tank whose saved fluid no longer loads keeps it: nothing is pushed, the contents stay unknown. */
    private static void unknown(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        TankEntity sink = tank(helper, PORT.east(), 16000);
        ServerLevel level = helper.getLevel();
        port.output.set(WATER, 1000);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        port.output.save(out);
        CompoundTag saved = out.buildResult();
        CompoundTag missing = saved.copy();
        missing.getCompoundOrEmpty("resource").putString("id", "missing_mod:goo");
        port.output.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), missing));
        helper.assertTrue(port.output.unknown(), "the tank holds unknown contents: " + missing);
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertValueEqual(sink.handler.getAmountAsLong(0), 0L, "pushed from unknown contents");
            helper.assertTrue(port.output.unknown(), "the contents are no longer unknown");
            helper.assertValueEqual(port.output.unknownAmount(), 1000L, "unknown amount");
        }).thenSucceed();
    }

    /**
     * Pushing into a store without transactions that executes half of what it simulated: the port's tank gives exactly
     * what the store took, so the total stays the same on every tick until everything moved.
     */
    private static void foreignPush(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ForeignEntity store = TestTanks.foreign(helper.getLevel(), helper.absolutePos(PORT.east()), 5, 0, 100_000);
        port.glowOut.set(5, 1000);
        port.port.setSettings(PortSettings.EMPTY.withMode(ConduitApiGameTests.TEST_KIND, Direction.EAST, PUSH));
        helper.onEachTick(() -> helper.assertValueEqual(port.glowOut.amount() + store.handler.amount, 1000L,
                "glow in the port and the store"));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(store.handler.amount, 1000L, "glow in the store");
            helper.assertTrue(store.handler.executes > 1, "executes: " + store.handler.executes);
        });
    }

    /** Pulling from a store without transactions that gives half of what it simulated: the totals stay the same. */
    private static void foreignPull(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ForeignEntity store = TestTanks.foreign(helper.getLevel(), helper.absolutePos(PORT.west()), 7, 1000, 100_000);
        port.port.setSettings(PortSettings.EMPTY.withMode(ConduitApiGameTests.TEST_KIND, Direction.WEST, PULL));
        helper.onEachTick(() -> helper.assertValueEqual(port.glowIn.amount() + store.handler.amount, 1000L,
                "glow in the port and the store"));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(port.glowIn.amount(), 1000L, "glow in the port");
            helper.assertValueEqual(port.glowIn.resource(), Optional.of(7), "glow key");
            helper.assertTrue(store.handler.executes > 1, "executes: " + store.handler.executes);
        });
    }

    /**
     * Two pushing glow tanks and a store without transactions: the first tank holds less than one operation moves, so
     * an operation that went on would take the rest from the second tank in the same tick. One operation makes one
     * foreign move, so on the tick the first tank first reaches 0 the second still holds all it had, and the totals
     * stay the same on every tick.
     */
    private static void foreignOneMove(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ForeignEntity store = TestTanks.foreign(helper.getLevel(), helper.absolutePos(PORT.east()), 5, 0, 100_000);
        port.glowOut.set(5, 300);
        port.glowOut2.set(5, 1000);
        port.port.setSettings(PortSettings.EMPTY.withMode(ConduitApiGameTests.TEST_KIND, Direction.EAST, PUSH));
        boolean[] firstEmptied = {false};
        helper.onEachTick(() -> {
            helper.assertValueEqual(port.glowOut.amount() + port.glowOut2.amount() + store.handler.amount, 1300L,
                    "glow in the port and the store");
            if (!firstEmptied[0] && port.glowOut.amount() == 0) {
                firstEmptied[0] = true;
                helper.assertValueEqual(port.glowOut2.amount(), 1000L, "second tank on the tick the first one emptied");
            }
        });
        helper.succeedWhen(() -> helper.assertValueEqual(store.handler.amount, 1300L, "glow in the store"));
    }

    /**
     * A pull into pulling tanks where the one with room holds unknown contents and the other is full moves nothing, and
     * the unknown contents stay as saved.
     */
    private static void pullUnknown(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        TankEntity source = tank(helper, PORT.west(), 16000);
        source.handler.set(0, FluidResource.of(Fluids.WATER), 1000);
        ServerLevel level = helper.getLevel();
        port.inputA.set(WATER, 1000);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        port.inputA.save(out);
        CompoundTag missing = out.buildResult().copy();
        missing.getCompoundOrEmpty("resource").putString("id", "missing_mod:goo");
        port.inputA.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), missing));
        helper.assertTrue(port.inputA.unknown(), "the tank holds unknown contents: " + missing);
        port.inputB.set(LAVA, 4000);
        port.port.setSettings(faces(Direction.WEST, PULL));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertValueEqual(amount(source.handler, Fluids.WATER), 1000L, "water left in the source");
            helper.assertTrue(port.inputA.unknown(), "the contents are no longer unknown");
            helper.assertValueEqual(port.inputA.unknownAmount(), 1000L, "unknown amount");
            helper.assertValueEqual(amount(port.inputB, LAVA), 4000L, "lava in the full tank");
            helper.assertValueEqual(port.output.amount(), 0L, "pulled into the push-only tank");
        }).thenSucceed();
    }

    /** A pulling face's filter: a deny list with the water tag pulls no water; denying lava instead pulls it. */
    private static void pullFilter(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        TankEntity source = tank(helper, PORT.west(), 16000);
        source.handler.set(0, FluidResource.of(Fluids.WATER), 1000);
        PortSettings pull = faces(Direction.WEST, PULL);
        port.port.setSettings(pull.withFilter(ResourceKinds.FLUID, Optional.of(PortFilter.of(ResourceKinds.FLUID,
                PortFilter.Mode.DENY, List.of(), List.of(Identifier.withDefaultNamespace("water"))))));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertValueEqual(amount(source.handler, Fluids.WATER), 1000L, "denied water pulled");
            helper.assertValueEqual(amount(port.inputA, WATER), 0L, "water in the port");
            port.port.setSettings(pull.withFilter(ResourceKinds.FLUID,
                    Optional.of(PortFilter.of(ResourceKinds.FLUID, PortFilter.Mode.DENY, List.of(LAVA), List.of()))));
        }).thenWaitUntil(() -> helper.assertValueEqual(amount(port.inputA, WATER), 1000L, "water pulled"))
                .thenSucceed();
    }

    /**
     * A push into a neighbouring port's pulling face asks both ports' filters: water moves only when neither denies it
     * (pairs: the pusher denies, the puller denies, neither does).
     */
    private static void neighbourFilters(GameTestHelper helper) {
        Optional<PortFilter<FluidKey>> denyWater = Optional
                .of(PortFilter.of(ResourceKinds.FLUID, PortFilter.Mode.DENY, List.of(WATER), List.of()));
        PortEntity[] as = new PortEntity[3];
        PortEntity[] bs = new PortEntity[3];
        for (int k = 0; k < 3; k++) {
            BlockPos a = new BlockPos(0, 0, k);
            as[k] = port(helper, a);
            bs[k] = port(helper, a.east());
            as[k].output.set(WATER, 1000);
            PortSettings push = faces(Direction.EAST, PUSH);
            PortSettings pull = faces(Direction.WEST, PULL);
            as[k].port.setSettings(k == 0 ? push.withFilter(ResourceKinds.FLUID, denyWater) : push);
            bs[k].port.setSettings(k == 1 ? pull.withFilter(ResourceKinds.FLUID, denyWater) : pull);
        }
        helper.startSequence().thenExecuteAfter(40, () -> {
            String[] pairs = {"the pusher denies: ", "the puller denies: ", "neither denies: "};
            for (int k = 0; k < 3; k++) {
                long moved = k == 2 ? 1000 : 0;
                helper.assertValueEqual(amount(bs[k].inputA, WATER), moved, pairs[k] + "water in B");
                helper.assertValueEqual(amount(bs[k].plain, Fluids.WATER), 0L, pairs[k] + "water at B");
                helper.assertValueEqual(amount(as[k].output, WATER), 1000 - moved, pairs[k] + "water left in A");
            }
        }).thenSucceed();
    }

    /**
     * A port blocked by a tank of another fluid runs only at its backoff, a port with nothing to push never runs, and
     * emptying the blocking tank (its block entity reports the change) wakes the blocked port within a tick.
     */
    private static void sleeps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int maxBackoff = ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt();
        int window = 2 * maxBackoff;
        TaskGroup blockedGroup = LevelTasks.newGroup(level);
        TaskGroup emptyGroup = LevelTasks.newGroup(level);
        PortEntity blocked = TestTanks.place(level, helper.absolutePos(PORT), blockedGroup, Block.UPDATE_ALL);
        TankEntity full = tank(helper, PORT.east(), 1000);
        full.handler.set(0, FluidResource.of(Fluids.LAVA), 1000);
        blocked.output.set(WATER, 1000);
        blocked.port.setSettings(faces(Direction.EAST, PUSH));
        PortEntity empty = TestTanks.place(level, helper.absolutePos(new BlockPos(4, 0, 1)), emptyGroup,
                Block.UPDATE_ALL);
        tank(helper, new BlockPos(5, 0, 1), 1000);
        empty.port.setSettings(faces(Direction.EAST, PUSH));
        long[] runs = new long[2];
        long[] freedAt = new long[1];
        helper.startSequence().thenExecuteAfter(2 * maxBackoff + 100, () -> {
            runs[0] = blockedGroup.runs();
            runs[1] = emptyGroup.runs();
            helper.assertValueEqual(blocked.port.status(ResourceKinds.FLUID), PortStatus.BLOCKED, "blocked status");
        }).thenExecuteAfter(window, () -> {
            long blockedRuns = blockedGroup.runs() - runs[0];
            long allowed = window / (maxBackoff - maxBackoff / 8) + 1L;
            helper.assertTrue(blockedRuns <= allowed,
                    blockedRuns + " runs of a blocked port in " + window + " ticks, at most " + allowed);
            helper.assertValueEqual(emptyGroup.runs() - runs[1], 0L, "runs of a port with nothing to push");
            full.handler.set(0, FluidResource.EMPTY, 0);
            full.setChanged();
            freedAt[0] = level.getGameTime();
        }).thenWaitUntil(() -> helper.assertValueEqual(amount(full.handler, Fluids.WATER), 1000L, "water pushed"))
                .thenExecute(() -> {
                    long delay = level.getGameTime() - freedAt[0];
                    helper.assertTrue(delay <= 1, "woke " + delay + " ticks after the tank changed");
                    blockedGroup.close();
                    emptyGroup.close();
                }).thenSucceed();
    }

    /**
     * A tank in a chunk below full status is never loaded by a pushing port and gets nothing; when its chunk is back at
     * full status, the port finds it with a new cache and pushes. Real tickets far from the test grid, as for items.
     */
    private static void targetUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int anchorX = 6700;
        int chunkZ = 6700;
        int portChunkX = anchorX + 2;
        int tankChunkX = anchorX + 3;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos portPos = new BlockPos(portChunkX * 16 + 15, y, chunkZ * 16 + 8);
        BlockPos tankPos = portPos.east();
        PortEntity[] port = new PortEntity[1];
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(anchorX, chunkZ, true);
            level.setChunkForced(tankChunkX, chunkZ, true);
            level.getChunk(portChunkX, chunkZ);
            level.getChunk(tankChunkX, chunkZ);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(portChunkX, chunkZ) != null, "port chunk not full");
            helper.assertTrue(level.getChunkSource().getChunkNow(tankChunkX, chunkZ) != null, "tank chunk not full");
        }).thenExecute(() -> {
            TestTanks.tank(level, tankPos, 16000, QUIET);
            port[0] = TestTanks.place(level, portPos, LevelTasks.newGroup(level), QUIET);
            port[0].output.set(WATER, 1000);
            level.setChunkForced(tankChunkX, chunkZ, false);
        }).thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(tankChunkX, chunkZ) == null,
                "the tank's chunk is still at full status"))
                .thenExecute(() -> port[0].port.setSettings(faces(Direction.EAST, PUSH))).thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(tankChunkX, chunkZ) == null,
                            "the port loaded the tank's chunk");
                    helper.assertValueEqual(port[0].output.amount(), 1000L, "water moved");
                    level.setChunkForced(tankChunkX, chunkZ, true);
                    level.getChunk(tankChunkX, chunkZ);
                }).thenWaitUntil(() -> helper.assertValueEqual(port[0].output.amount(), 0L,
                        "water left after the tank's chunk came back"))
                .thenExecute(() -> {
                    TankEntity tank = (TankEntity) level.getBlockEntity(tankPos);
                    helper.assertTrue(tank != null && amount(tank.handler, Fluids.WATER) == 1000, "water in the tank");
                    level.setBlock(portPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setBlock(tankPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setChunkForced(tankChunkX, chunkZ, false);
                    level.setChunkForced(anchorX, chunkZ, false);
                }).thenSucceed();
    }

    /**
     * A face whose cache already holds the tank's handler (the port is blocked by lava) when the tank's chunk drops
     * below full status and comes back: once the lava is gone the water goes into the live tank, and the totals never
     * change on any tick. The chunk comes back from memory with the same block entity, so this pins only that the
     * totals are preserved; it cannot pin the face-cache rebuild, which a chunk reloaded from disk (a new block entity)
     * needs.
     */
    private static void staleCache(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int anchorX = 6800;
        int chunkZ = 6800;
        int portChunkX = anchorX + 2;
        int tankChunkX = anchorX + 3;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos portPos = new BlockPos(portChunkX * 16 + 15, y, chunkZ * 16 + 8);
        BlockPos tankPos = portPos.east();
        PortEntity[] port = new PortEntity[1];
        boolean[] checking = new boolean[1];
        helper.onEachTick(() -> {
            if (checking[0]) {
                TankEntity live = (TankEntity) level.getBlockEntity(tankPos);
                long inTank = live == null ? 0 : amount(live.handler, Fluids.WATER);
                helper.assertValueEqual(port[0].output.amount() + inTank, 1000L, "water in the port and the live tank");
            }
        });
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(anchorX, chunkZ, true);
            level.setChunkForced(tankChunkX, chunkZ, true);
            level.getChunk(portChunkX, chunkZ);
            level.getChunk(tankChunkX, chunkZ);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(portChunkX, chunkZ) != null, "port chunk not full");
            helper.assertTrue(level.getChunkSource().getChunkNow(tankChunkX, chunkZ) != null, "tank chunk not full");
        }).thenExecute(() -> {
            TankEntity tank = TestTanks.tank(level, tankPos, 1000, QUIET);
            tank.handler.set(0, FluidResource.of(Fluids.LAVA), 1000);
            port[0] = TestTanks.place(level, portPos, LevelTasks.newGroup(level), QUIET);
            port[0].output.set(WATER, 1000);
            port[0].port.setSettings(faces(Direction.EAST, PUSH));
        }).thenWaitUntil(() -> helper.assertValueEqual(port[0].port.status(ResourceKinds.FLUID), PortStatus.BLOCKED,
                "status beside the lava")).thenExecute(() -> level.setChunkForced(tankChunkX, chunkZ, false))
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(tankChunkX, chunkZ) == null,
                        "the tank's chunk is still at full status"))
                .thenIdle(ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt() + 20).thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(tankChunkX, chunkZ) == null,
                            "the port loaded the tank's chunk");
                    level.setChunkForced(tankChunkX, chunkZ, true);
                    level.getChunk(tankChunkX, chunkZ);
                }).thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(tankChunkX, chunkZ) != null,
                        "the tank's chunk is not back at full status"))
                .thenExecute(() -> {
                    TankEntity live = (TankEntity) Objects.requireNonNull(level.getBlockEntity(tankPos),
                            "no tank after its chunk came back");
                    live.handler.set(0, FluidResource.EMPTY, 0);
                    live.setChanged();
                    checking[0] = true;
                }).thenWaitUntil(() -> helper.assertValueEqual(port[0].output.amount(), 0L,
                        "water left after the lava was gone"))
                .thenExecute(() -> {
                    TankEntity live = (TankEntity) level.getBlockEntity(tankPos);
                    helper.assertTrue(live != null && amount(live.handler, Fluids.WATER) == 1000,
                            "water in the live tank");
                    checking[0] = false;
                    level.setBlock(portPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setBlock(tankPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setChunkForced(tankChunkX, chunkZ, false);
                    level.setChunkForced(anchorX, chunkZ, false);
                }).thenSucceed();
    }
}
