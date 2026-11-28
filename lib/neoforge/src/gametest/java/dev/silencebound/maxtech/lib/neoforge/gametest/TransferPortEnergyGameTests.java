package dev.silencebound.maxtech.lib.neoforge.gametest;

import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.OFF;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PULL;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PUSH;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestEnergy.PortEntity;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestEnergy.StoreEntity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import java.util.List;
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
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Lib's transfer ports with standalone energy buffers (ADR-0018) on real blocks: FE pushed into and pulled from a
 * NeoForge FE store at 1:1 and at an uneven FE rate (3 FE = 7 MU), where the totals in exact units must hold on every
 * tick, the remainder carried and saved; a full target, sleeping, the rules between two ports, an endpoint that joined
 * a network, and a target in a chunk below full status.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TransferPortEnergyGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockPos PORT = new BlockPos(1, 0, 1);
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<Test> PLAIN = List.of(new Test("transfer_port_energy_push", 100, false),
            new Test("transfer_port_energy_pull", 100, false),
            new Test("transfer_port_energy_full_target_sleeps", 1600, false),
            new Test("transfer_port_energy_between_ports", 100, false),
            new Test("transfer_port_energy_networked", 100, false),
            new Test("transfer_port_energy_target_unload", 400, true));
    private static final List<Test> UNEVEN = List.of(new Test("transfer_port_energy_uneven_push", 400, false),
            new Test("transfer_port_energy_uneven_pull", 400, false));

    static {
        EnergyConfigEnvironment.init();
        FUNCTIONS.register("transfer_port_energy_push", () -> TransferPortEnergyGameTests::push);
        FUNCTIONS.register("transfer_port_energy_pull", () -> TransferPortEnergyGameTests::pull);
        FUNCTIONS.register("transfer_port_energy_full_target_sleeps", () -> TransferPortEnergyGameTests::fullTarget);
        FUNCTIONS.register("transfer_port_energy_between_ports", () -> TransferPortEnergyGameTests::betweenPorts);
        FUNCTIONS.register("transfer_port_energy_networked", () -> TransferPortEnergyGameTests::networked);
        FUNCTIONS.register("transfer_port_energy_target_unload", () -> TransferPortEnergyGameTests::targetUnload);
        FUNCTIONS.register("transfer_port_energy_uneven_push", () -> TransferPortEnergyGameTests::unevenPush);
        FUNCTIONS.register("transfer_port_energy_uneven_pull", () -> TransferPortEnergyGameTests::unevenPull);
    }

    private record Test(String name, int maxTicks, boolean far) {
    }

    private TransferPortEnergyGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechLib.id("transfer_port_energy"),
                new EnergyConfigEnvironment("", ""));
        Holder<TestEnvironmentDefinition<?>> uneven = event.registerEnvironment(
                MaxtechLib.id("transfer_port_energy_rate_3_7"),
                new EnergyConfigEnvironment("energy.feRateFe;energy.feRateMu", "3;7"));
        register(event, PLAIN, plain);
        register(event, UNEVEN, uneven);
    }

    private static void register(RegisterGameTestsEvent event, List<Test> tests,
            Holder<TestEnvironmentDefinition<?>> environment) {
        for (Test test : tests) {
            Identifier id = MaxtechLib.id(test.name());
            event.registerTest(id,
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                            new TestData<>(environment, test.far() ? LibGameTests.EMPTY_STRUCTURE : STRUCTURE,
                                    test.maxTicks(), 0, true)));
        }
    }

    private static PortSettings faces(Direction face, PortFaceMode mode) {
        return PortSettings.EMPTY.withMode(ResourceKinds.ENERGY, face, mode);
    }

    private static PortEntity port(GameTestHelper helper, BlockPos pos) {
        return TestEnergy.place(helper.getLevel(), helper.absolutePos(pos), Block.UPDATE_ALL);
    }

    private static StoreEntity store(GameTestHelper helper, BlockPos pos, int capacity, int amount) {
        return TestEnergy.store(helper.getLevel(), helper.absolutePos(pos), capacity, amount, Block.UPDATE_ALL);
    }

    /** A pushing face empties the output buffer into an FE store, up to the configured MU per operation each. */
    private static void push(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        StoreEntity store = store(helper, PORT.east(), 100_000, 0);
        port.output.setStored(3500);
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.onEachTick(() -> helper.assertValueEqual(port.stored() + store.handler.getAmountAsLong(), 3500L,
                "energy in the port and the store"));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(store.handler.getAmountAsLong(), 3500L, "FE in the store");
            helper.assertValueEqual(port.output.stored(), 0L, "MU left");
            helper.assertTrue(port.moved > 0, "the owner never heard of a move");
            helper.assertValueEqual(port.port.status(ResourceKinds.ENERGY), PortStatus.IDLE, "status once empty");
        });
    }

    /** A pulling face empties an FE store into the pulling buffer, never into the push-only one. */
    private static void pull(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        StoreEntity store = store(helper, PORT.west(), 100_000, 5000);
        port.port.setSettings(faces(Direction.WEST, PULL));
        helper.onEachTick(() -> helper.assertValueEqual(port.stored() + store.handler.getAmountAsLong(), 5000L,
                "energy in the port and the store"));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(port.input.stored(), 5000L, "MU pulled");
            helper.assertValueEqual(port.output.stored(), 0L, "into the push-only buffer");
            helper.assertValueEqual(store.handler.getAmountAsLong(), 0L, "FE left in the store");
        });
    }

    /**
     * A full store takes nothing: the port is blocked and backs off to the scheduler's maximum but keeps polling, a
     * port with nothing to push never runs, and once the store has room (without any event) the energy moves.
     */
    private static void fullTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int maxBackoff = ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt();
        int window = 2 * maxBackoff;
        TaskGroup blockedGroup = LevelTasks.newGroup(level);
        TaskGroup emptyGroup = LevelTasks.newGroup(level);
        PortEntity blocked = TestEnergy.place(level, helper.absolutePos(PORT), blockedGroup, Block.UPDATE_ALL);
        StoreEntity full = store(helper, PORT.east(), 1000, 1000);
        blocked.output.setStored(1000);
        blocked.port.setSettings(faces(Direction.EAST, PUSH));
        PortEntity empty = TestEnergy.place(level, helper.absolutePos(new BlockPos(4, 0, 1)), emptyGroup,
                Block.UPDATE_ALL);
        store(helper, new BlockPos(5, 0, 1), 1000, 0);
        empty.port.setSettings(faces(Direction.EAST, PUSH));
        long[] runs = new long[2];
        helper.startSequence().thenExecuteAfter(2 * maxBackoff + 100, () -> {
            runs[0] = blockedGroup.runs();
            runs[1] = emptyGroup.runs();
            helper.assertValueEqual(blocked.port.status(ResourceKinds.ENERGY), PortStatus.BLOCKED, "blocked status");
            helper.assertValueEqual(blocked.output.stored(), 1000L, "MU left beside a full store");
            helper.assertValueEqual(full.handler.getAmountAsLong(), 1000L, "full store");
        }).thenExecuteAfter(window, () -> {
            long blockedRuns = blockedGroup.runs() - runs[0];
            long allowed = window / (maxBackoff - maxBackoff / 8) + 1L;
            helper.assertTrue(blockedRuns <= allowed,
                    blockedRuns + " runs of a blocked port in " + window + " ticks, at most " + allowed);
            helper.assertTrue(blockedRuns >= 1, "a blocked port stopped polling");
            helper.assertValueEqual(emptyGroup.runs() - runs[1], 0L, "runs of a port with nothing to push");
            // No event: a plain FE store tells nobody it emptied, so the port must find out by polling.
            full.handler.set(0);
        }).thenWaitUntil(() -> helper.assertValueEqual(full.handler.getAmountAsLong(), 1000L, "FE pushed"))
                .thenExecute(() -> {
                    helper.assertValueEqual(blocked.output.stored(), 0L, "MU left");
                    blockedGroup.close();
                    emptyGroup.close();
                }).thenSucceed();
    }

    /**
     * Every pair of modes on two facing port faces (port A's east, port B's west): only a pushing face facing a pulling
     * one moves, into the puller's pulling buffer; an off face is a plain block, reached through its plain capability.
     */
    private static void betweenPorts(GameTestHelper helper) {
        PortFaceMode[] modes = PortFaceMode.values();
        PortEntity[] as = new PortEntity[9];
        PortEntity[] bs = new PortEntity[9];
        for (int k = 0; k < 9; k++) {
            BlockPos a = new BlockPos((k % 2) * 3, (k / 2) % 2, k / 4);
            as[k] = port(helper, a);
            bs[k] = port(helper, a.east());
            as[k].output.setStored(1000);
            bs[k].output.setStored(2000);
            as[k].port.setSettings(faces(Direction.EAST, modes[k / 3]));
            bs[k].port.setSettings(faces(Direction.WEST, modes[k % 3]));
        }
        helper.startSequence().thenExecuteAfter(40, () -> {
            for (int k = 0; k < 9; k++) {
                PortFaceMode a = modes[k / 3];
                PortFaceMode b = modes[k % 3];
                String pair = a + " facing " + b + ": ";
                long aToInput = a == PUSH && b == PULL ? 1000 : 0;
                long aToPlain = a == PUSH && b == OFF ? 1000 : 0;
                long bToInput = a == PULL && b == PUSH ? 2000 : 0;
                long bToPlain = a == OFF && b == PUSH ? 2000 : 0;
                PortEntity pa = as[k];
                PortEntity pb = bs[k];
                helper.assertValueEqual(pb.input.stored(), aToInput, pair + "MU in B's input");
                helper.assertValueEqual(pb.plain.getAmountAsLong(), aToPlain, pair + "FE at B's plain handler");
                helper.assertValueEqual(pa.output.stored(), 1000 - aToInput - aToPlain, pair + "MU left in A");
                helper.assertValueEqual(pa.input.stored(), bToInput, pair + "MU in A's input");
                helper.assertValueEqual(pa.plain.getAmountAsLong(), bToPlain, pair + "FE at A's plain handler");
                helper.assertValueEqual(pb.output.stored(), 2000 - bToInput - bToPlain, pair + "MU left in B");
            }
        }).thenSucceed();
    }

    /**
     * An endpoint that joined an energy network after the port was built is left alone: networked energy moves through
     * FE edges, never through a port (ADR-0018). The builder refuses such an endpoint outright
     * ({@code transfer_energy_buffer}).
     */
    private static void networked(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        StoreEntity store = store(helper, PORT.east(), 100_000, 0);
        port.output.setStored(1000);
        EnergyNetwork network = EnergyNetworks.of(helper.getLevel()).createNetwork();
        network.add(port.output);
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertValueEqual(store.handler.getAmountAsLong(), 0L, "FE pushed from a networked endpoint");
            helper.assertValueEqual(port.output.stored(), 1000L, "MU in the networked endpoint");
            network.remove(port.output);
            port.port.bufferChanged();
        }).thenWaitUntil(() -> helper.assertValueEqual(store.handler.getAmountAsLong(), 1000L,
                "FE pushed once the endpoint left the network")).thenSucceed();
    }

    /**
     * At 3 FE = 7 MU, pushing 10,000 MU one operation at a time: on every tick the energy in exact units (MU x 3 plus
     * FE x 7) is the starting amount less at most the remainder the port holds (below one MU), so no fraction is lost
     * or made; at the end 4,285 FE went out and 1 MU with 2 units of remainder stay, which the port saves.
     */
    private static void unevenPush(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        StoreEntity store = store(helper, PORT.east(), 100_000, 0);
        port.output.setStored(10_000);
        port.port.setSettings(faces(Direction.EAST, PUSH));
        long total = 10_000L * 3;
        helper.onEachTick(() -> {
            long units = port.stored() * 3 + store.handler.getAmountAsLong() * 7;
            helper.assertTrue(units <= total && units > total - 3,
                    units + " units in the port and the store, started with " + total);
        });
        helper.succeedWhen(() -> {
            helper.assertValueEqual(store.handler.getAmountAsLong(), 4285L, "FE in the store");
            helper.assertValueEqual(port.output.stored(), 1L, "MU left");
            helper.assertValueEqual(remainderAfterLoad(helper, port), 2L, "remainder saved and loaded");
        });
    }

    /**
     * At 3 FE = 7 MU, pulling 4,000 FE (28,000 units): the totals hold on every tick as for pushing, and 9,333 MU
     * arrive with 1 unit of remainder.
     */
    private static void unevenPull(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        StoreEntity store = store(helper, PORT.west(), 100_000, 4000);
        port.port.setSettings(faces(Direction.WEST, PULL));
        long total = 4000L * 7;
        helper.onEachTick(() -> {
            long units = port.stored() * 3 + store.handler.getAmountAsLong() * 7;
            helper.assertTrue(units <= total && units > total - 3,
                    units + " units in the port and the store, started with " + total);
        });
        helper.succeedWhen(() -> {
            helper.assertValueEqual(store.handler.getAmountAsLong(), 0L, "FE left in the store");
            helper.assertValueEqual(port.input.stored(), 9333L, "MU pulled");
            helper.assertValueEqual(remainderAfterLoad(helper, port), 1L, "remainder saved and loaded");
        });
    }

    /** Saves {@code port}'s port, loads it into a new port, saves that and reads its remainder. */
    private static long remainderAfterLoad(GameTestHelper helper, PortEntity port) {
        ServerLevel level = helper.getLevel();
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        port.port.save(out);
        CompoundTag saved = out.buildResult();
        PortEntity copy = port(helper, new BlockPos(5, 1, 2));
        copy.port.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        TagValueOutput again = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        copy.port.save(again);
        return again.buildResult().getLongOr("energy_remainder", 0L);
    }

    /**
     * An FE store in a chunk below full status is never loaded by a pushing port and gets nothing; when its chunk is
     * back at full status, the port finds it with a new cache and pushes. Real tickets far from the test grid, as for
     * items and fluids.
     */
    private static void targetUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int anchorX = 6900;
        int chunkZ = 6900;
        int portChunkX = anchorX + 2;
        int storeChunkX = anchorX + 3;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos portPos = new BlockPos(portChunkX * 16 + 15, y, chunkZ * 16 + 8);
        BlockPos storePos = portPos.east();
        PortEntity[] port = new PortEntity[1];
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(anchorX, chunkZ, true);
            level.setChunkForced(storeChunkX, chunkZ, true);
            level.getChunk(portChunkX, chunkZ);
            level.getChunk(storeChunkX, chunkZ);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(portChunkX, chunkZ) != null, "port chunk not full");
            helper.assertTrue(level.getChunkSource().getChunkNow(storeChunkX, chunkZ) != null, "store chunk not full");
        }).thenExecute(() -> {
            TestEnergy.store(level, storePos, 100_000, 0, QUIET);
            port[0] = TestEnergy.place(level, portPos, LevelTasks.newGroup(level), QUIET);
            port[0].output.setStored(1000);
            level.setChunkForced(storeChunkX, chunkZ, false);
        }).thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(storeChunkX, chunkZ) == null,
                "the store's chunk is still at full status"))
                .thenExecute(() -> port[0].port.setSettings(faces(Direction.EAST, PUSH))).thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(storeChunkX, chunkZ) == null,
                            "the port loaded the store's chunk");
                    helper.assertValueEqual(port[0].output.stored(), 1000L, "MU moved");
                    level.setChunkForced(storeChunkX, chunkZ, true);
                    level.getChunk(storeChunkX, chunkZ);
                }).thenWaitUntil(() -> helper.assertValueEqual(port[0].output.stored(), 0L,
                        "MU left after the store's chunk came back"))
                .thenExecute(() -> {
                    StoreEntity store = (StoreEntity) level.getBlockEntity(storePos);
                    helper.assertTrue(store != null && store.handler.getAmountAsLong() == 1000, "FE in the store");
                    level.setBlock(portPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setBlock(storePos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setChunkForced(storeChunkX, chunkZ, false);
                    level.setChunkForced(anchorX, chunkZ, false);
                }).thenSucceed();
    }
}
