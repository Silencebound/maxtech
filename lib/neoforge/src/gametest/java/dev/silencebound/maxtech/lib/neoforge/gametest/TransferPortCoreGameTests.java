package dev.silencebound.maxtech.lib.neoforge.gametest;

import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.OFF;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PULL;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PUSH;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import dev.silencebound.maxtech.lib.api.transfer.TransferPorts;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestPorts.PortEntity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.transfer.PortRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Lib's transfer port core with items (ADR-0018) on real blocks: pushing and pulling, round-robin, filters, buffer
 * roles, the rules between two ports, exact counts against full, partly full and stingy targets, sleeping and waking,
 * and chunk unloads of a target and of the port itself.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TransferPortCoreGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockPos PORT = new BlockPos(1, 0, 1);
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<Test> TESTS = List.of(new Test("transfer_port_push_chest", 100, false),
            new Test("transfer_port_pull_chest", 100, false), new Test("transfer_port_round_robin", 100, false),
            new Test("transfer_port_filter", 100, false), new Test("transfer_port_buffer_roles", 150, false),
            new Test("transfer_port_between_ports", 100, false), new Test("transfer_port_full_target", 100, false),
            new Test("transfer_port_partial_room", 100, false), new Test("transfer_port_stingy_target", 100, false),
            new Test("transfer_port_settings", 20, false), new Test("transfer_port_sleeps", 1600, false),
            new Test("transfer_port_target_unload", 400, true), new Test("transfer_port_unload_reload", 600, true));

    static {
        FUNCTIONS.register("transfer_port_push_chest", () -> TransferPortCoreGameTests::pushChest);
        FUNCTIONS.register("transfer_port_pull_chest", () -> TransferPortCoreGameTests::pullChest);
        FUNCTIONS.register("transfer_port_round_robin", () -> TransferPortCoreGameTests::roundRobin);
        FUNCTIONS.register("transfer_port_filter", () -> TransferPortCoreGameTests::filter);
        FUNCTIONS.register("transfer_port_buffer_roles", () -> TransferPortCoreGameTests::bufferRoles);
        FUNCTIONS.register("transfer_port_between_ports", () -> TransferPortCoreGameTests::betweenPorts);
        FUNCTIONS.register("transfer_port_full_target", () -> TransferPortCoreGameTests::fullTarget);
        FUNCTIONS.register("transfer_port_partial_room", () -> TransferPortCoreGameTests::partialRoom);
        FUNCTIONS.register("transfer_port_stingy_target", () -> TransferPortCoreGameTests::stingyTarget);
        FUNCTIONS.register("transfer_port_settings", () -> TransferPortCoreGameTests::settings);
        FUNCTIONS.register("transfer_port_sleeps", () -> TransferPortCoreGameTests::sleeps);
        FUNCTIONS.register("transfer_port_target_unload", () -> TransferPortCoreGameTests::targetUnload);
        FUNCTIONS.register("transfer_port_unload_reload", () -> TransferPortCoreGameTests::unloadReload);
    }

    private record Test(String name, int maxTicks, boolean far) {
    }

    private TransferPortCoreGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("transfer_port"));
        for (Test test : TESTS) {
            Identifier id = MaxtechLib.id(test.name());
            event.registerTest(id,
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                            new TestData<>(environment, test.far() ? LibGameTests.EMPTY_STRUCTURE : STRUCTURE,
                                    test.maxTicks(), 0, true)));
        }
    }

    private static PortSettings faces(Direction face, PortFaceMode mode) {
        return PortSettings.EMPTY.withMode(ResourceKinds.ITEM, face, mode);
    }

    private static PortEntity port(GameTestHelper helper, BlockPos pos, TaskGroup group) {
        return TestPorts.place(helper.getLevel(), helper.absolutePos(pos), group, Block.UPDATE_ALL);
    }

    private static PortEntity port(GameTestHelper helper, BlockPos pos) {
        return port(helper, pos, LevelTasks.newGroup(helper.getLevel()));
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.CHEST);
        return helper.getBlockEntity(pos, ChestBlockEntity.class);
    }

    private static int count(ChestBlockEntity chest, Item item) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(item)) {
                total += chest.getItem(i).getCount();
            }
        }
        return total;
    }

    private static void fill(ChestBlockEntity chest, Item item, int slots) {
        for (int i = 0; i < slots; i++) {
            chest.setItem(i, new ItemStack(item, 64));
        }
    }

    /** A pushing face empties the output slots into a chest, one stack per operation. */
    private static void pushChest(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ChestBlockEntity chest = chest(helper, PORT.east());
        port.output.set(0, new ItemStack(Items.STONE, 64));
        port.output.set(1, new ItemStack(Items.DIRT, 10));
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(count(chest, Items.STONE), 64, "stone in the chest");
            helper.assertValueEqual(count(chest, Items.DIRT), 10, "dirt in the chest");
            helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 0, "stone left");
        });
    }

    /** A pulling face fills the input slots from a chest, up to the configured items per operation each. */
    private static void pullChest(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ChestBlockEntity chest = chest(helper, PORT.west());
        chest.setItem(3, new ItemStack(Items.COAL, 20));
        chest.setItem(7, new ItemStack(Items.COAL, 50));
        port.port.setSettings(faces(Direction.WEST, PULL));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(TestPorts.count(port.input, Items.COAL), 70, "coal pulled");
            helper.assertValueEqual(count(chest, Items.COAL), 0, "coal left in the chest");
            helper.assertValueEqual(port.port.status(ResourceKinds.ITEM), PortStatus.IDLE, "status once empty");
        });
    }

    /** Two pushing faces take turns: each chest gets one of the two stacks. */
    private static void roundRobin(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ChestBlockEntity east = chest(helper, PORT.east());
        ChestBlockEntity west = chest(helper, PORT.west());
        port.output.set(0, new ItemStack(Items.STONE, 64));
        port.output.set(1, new ItemStack(Items.STONE, 64));
        port.port.setSettings(faces(Direction.EAST, PUSH).withMode(ResourceKinds.ITEM, Direction.WEST, PUSH));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 0, "stone left");
            helper.assertValueEqual(count(east, Items.STONE), 64, "stone east");
            helper.assertValueEqual(count(west, Items.STONE), 64, "stone west");
        });
    }

    /** A deny filter keeps its items in the port; the rest moves. */
    private static void filter(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ChestBlockEntity chest = chest(helper, PORT.east());
        port.output.set(0, new ItemStack(Items.STONE, 32));
        port.output.set(1, new ItemStack(Items.DIRT, 32));
        port.port.setSettings(faces(Direction.EAST, PUSH).withFilter(ResourceKinds.ITEM, Optional.of(
                PortFilter.of(ResourceKinds.ITEM, PortFilter.Mode.DENY, List.of(ItemKey.of(Items.STONE)), List.of()))));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertValueEqual(count(chest, Items.DIRT), 32, "dirt moved");
            helper.assertValueEqual(count(chest, Items.STONE), 0, "denied stone moved");
            helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 32, "stone kept");
        }).thenSucceed();
    }

    /**
     * The pulling buffer is never pushed from and the pushing buffer never pulled into, though each has a slot whose
     * role would allow it: the pulling buffer's output slot keeps its gold, and with the pulling buffer's inputs full
     * the coal stays at the source instead of going into the pushing buffer's input slot. Then, with room, coal is
     * pulled and not pushed on.
     */
    private static void bufferRoles(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ChestBlockEntity source = chest(helper, PORT.west());
        ChestBlockEntity target = chest(helper, PORT.east());
        source.setItem(0, new ItemStack(Items.COAL, 16));
        port.input.set(0, new ItemStack(Items.DIRT, 64));
        port.input.set(1, new ItemStack(Items.DIRT, 64));
        port.input.set(2, new ItemStack(Items.GOLD_INGOT, 8));
        port.output.set(0, new ItemStack(Items.IRON_INGOT, 8));
        port.port.setSettings(faces(Direction.WEST, PULL).withMode(ResourceKinds.ITEM, Direction.EAST, PUSH));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertValueEqual(count(target, Items.IRON_INGOT), 8, "iron pushed");
            helper.assertValueEqual(count(target, Items.GOLD_INGOT), 0, "gold pushed from the pulling buffer");
            helper.assertValueEqual(count(source, Items.COAL), 16, "coal pulled into the pushing buffer");
            helper.assertValueEqual(TestPorts.count(port.output, Items.COAL), 0, "coal in the pushing buffer");
            port.input.set(1, ItemStack.EMPTY);
        }).thenExecuteAfter(40, () -> {
            helper.assertValueEqual(TestPorts.count(port.input, Items.COAL), 16, "coal in the pulling buffer");
            helper.assertValueEqual(count(source, Items.COAL), 0, "coal left at the source");
            helper.assertValueEqual(count(target, Items.COAL), 0, "pulled coal pushed on");
        }).thenSucceed();
    }

    /**
     * Every pair of modes on two facing port faces (port A's east, port B's west). Only a pushing face facing a pulling
     * one moves, into the puller's input buffer; two pushing or two pulling faces move nothing; an off face is a plain
     * block, reached through its plain capability (three slots the port does not use).
     */
    private static void betweenPorts(GameTestHelper helper) {
        PortFaceMode[] modes = PortFaceMode.values();
        PortEntity[] as = new PortEntity[9];
        PortEntity[] bs = new PortEntity[9];
        for (int k = 0; k < 9; k++) {
            BlockPos a = new BlockPos((k % 2) * 3, (k / 2) % 2, k / 4);
            as[k] = port(helper, a);
            bs[k] = port(helper, a.east());
            as[k].output.set(0, new ItemStack(Items.STONE, 10));
            bs[k].output.set(0, new ItemStack(Items.DIRT, 10));
            as[k].plain.set(0, ItemResource.of(Items.SAND), 5);
            bs[k].plain.set(0, ItemResource.of(Items.SAND), 5);
            as[k].port.setSettings(faces(Direction.EAST, modes[k / 3]));
            bs[k].port.setSettings(faces(Direction.WEST, modes[k % 3]));
        }
        helper.startSequence().thenExecuteAfter(40, () -> {
            for (int k = 0; k < 9; k++) {
                PortFaceMode a = modes[k / 3];
                PortFaceMode b = modes[k % 3];
                String pair = a + " facing " + b + ": ";
                int stoneToInput = a == PUSH && b == PULL ? 10 : 0;
                int stoneToPlain = a == PUSH && b == OFF ? 10 : 0;
                int dirtToInput = a == PULL && b == PUSH ? 10 : 0;
                int dirtToPlain = a == OFF && b == PUSH ? 10 : 0;
                int sandToA = a == PULL && b == OFF ? 5 : 0;
                int sandToB = a == OFF && b == PULL ? 5 : 0;
                PortEntity pa = as[k];
                PortEntity pb = bs[k];
                helper.assertValueEqual(TestPorts.count(pb.input, Items.STONE), stoneToInput, pair + "stone in B");
                helper.assertValueEqual(TestPorts.count(pb.plain, Items.STONE), stoneToPlain, pair + "stone at B");
                helper.assertValueEqual(TestPorts.count(pa.output, Items.STONE), 10 - stoneToInput - stoneToPlain,
                        pair + "stone left in A");
                helper.assertValueEqual(TestPorts.count(pa.input, Items.DIRT), dirtToInput, pair + "dirt in A");
                helper.assertValueEqual(TestPorts.count(pa.plain, Items.DIRT), dirtToPlain, pair + "dirt at A");
                helper.assertValueEqual(TestPorts.count(pb.output, Items.DIRT), 10 - dirtToInput - dirtToPlain,
                        pair + "dirt left in B");
                helper.assertValueEqual(TestPorts.count(pa.input, Items.SAND), sandToA, pair + "sand in A");
                helper.assertValueEqual(TestPorts.count(pb.plain, Items.SAND), 5 - sandToA, pair + "sand at B");
                helper.assertValueEqual(TestPorts.count(pb.input, Items.SAND), sandToB, pair + "sand in B");
                helper.assertValueEqual(TestPorts.count(pa.plain, Items.SAND), 5 - sandToB, pair + "sand at A");
                helper.assertValueEqual(
                        TestPorts.count(pb.output, Items.STONE) + TestPorts.count(pa.output, Items.DIRT), 0,
                        pair + "into an output buffer");
            }
        }).thenSucceed();
    }

    /** A full chest takes nothing: both sides keep their counts and the port reports blocked. */
    private static void fullTarget(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ChestBlockEntity chest = chest(helper, PORT.east());
        fill(chest, Items.COBBLESTONE, 27);
        port.output.set(0, new ItemStack(Items.STONE, 64));
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.startSequence().thenExecuteAfter(30, () -> {
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 27 * 64, "cobblestone in the chest");
            helper.assertValueEqual(count(chest, Items.STONE), 0, "stone in the chest");
            helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 64, "stone in the port");
            helper.assertValueEqual(port.port.status(ResourceKinds.ITEM), PortStatus.BLOCKED, "status");
        }).thenSucceed();
    }

    /** A chest with room for 10 takes exactly 10. */
    private static void partialRoom(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        ChestBlockEntity chest = chest(helper, PORT.east());
        fill(chest, Items.COBBLESTONE, 26);
        chest.setItem(26, new ItemStack(Items.STONE, 54));
        port.output.set(0, new ItemStack(Items.STONE, 64));
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.startSequence().thenExecuteAfter(30, () -> {
            helper.assertValueEqual(count(chest, Items.STONE), 64, "stone in the chest");
            helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 54, "stone in the port");
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 26 * 64, "cobblestone in the chest");
        }).thenSucceed();
    }

    /** A target that takes 7 of the 64 offered: exactly 7 leave the port. */
    private static void stingyTarget(GameTestHelper helper) {
        PortEntity port = port(helper, PORT);
        helper.setBlock(PORT.east(), TestPorts.SINK.get());
        TestPorts.SinkEntity sink = helper.getBlockEntity(PORT.east(), TestPorts.SinkEntity.class);
        port.output.set(0, new ItemStack(Items.STONE, 64));
        port.output.set(1, new ItemStack(Items.DIRT, 64));
        port.port.setSettings(faces(Direction.EAST, PUSH));
        helper.startSequence().thenExecuteAfter(30, () -> {
            helper.assertValueEqual(TestPorts.count(sink.handler, Items.STONE), 7, "stone in the sink");
            helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 57, "stone in the port");
            helper.assertValueEqual(TestPorts.count(port.output, Items.DIRT), 64, "dirt in the port");
        }).thenSucceed();
    }

    /**
     * Settings outside the buffers' modes are put back, the defaults are not saved and load back for data without
     * settings, changed settings save and load, and a wrench cycles through the allowed modes.
     */
    private static void settings(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        ItemSlots slots = ItemSlots.builder(1).input(0, 1).build(slot -> {
        });
        PortSettings defaults = faces(Direction.WEST, PULL).withMode(ResourceKinds.ITEM, Direction.UP, PUSH);
        PortSettings pullOnly = faces(Direction.WEST, PULL);
        int[] changes = new int[1];
        TransferPort port = TransferPorts.builder(LevelTasks.newGroup(helper.getLevel())).items(slots, Set.of(PULL))
                .defaults(defaults).onSettingsChanged(() -> changes[0]++).build();
        helper.assertValueEqual(port.settings(), pullOnly, "defaults within the allowed modes");
        helper.assertValueEqual(port.allowed(ResourceKinds.ITEM), Set.of(OFF, PULL), "allowed modes");
        helper.assertValueEqual(port.kinds(), List.of(ResourceKinds.ITEM), "kinds");
        helper.assertValueEqual(port.status(ResourceKinds.ITEM), PortStatus.OFF, "status while stopped");

        TagValueOutput unchanged = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        port.save(unchanged);
        CompoundTag unchangedTag = unchanged.buildResult();
        helper.assertTrue(unchangedTag.isEmpty(), "defaults saved: " + unchangedTag);

        helper.assertValueEqual(port.cycleFace(ResourceKinds.ITEM, Direction.WEST), OFF, "pull to off");
        helper.assertValueEqual(port.cycleFace(ResourceKinds.ITEM, Direction.EAST), PULL, "off to pull");
        helper.assertValueEqual(port.cycleFace(ResourceKinds.FLUID, Direction.EAST), OFF, "a kind without buffer");
        helper.assertValueEqual(changes[0], 2, "settings listener runs");
        PortSettings changed = faces(Direction.EAST, PULL);
        helper.assertValueEqual(port.settings(), changed, "after two clicks");

        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        port.save(out);
        CompoundTag saved = out.buildResult();
        TransferPort loaded = TransferPorts.builder(LevelTasks.newGroup(helper.getLevel())).items(slots, Set.of(PULL))
                .defaults(defaults).onSettingsChanged(() -> changes[0]++).build();
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, saved));
        helper.assertValueEqual(loaded.settings(), changed, "loaded settings");
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, new CompoundTag()));
        helper.assertValueEqual(loaded.settings(), pullOnly, "data without settings loads the defaults");
        helper.assertValueEqual(changes[0], 2, "load ran the settings listener");
        helper.succeed();
    }

    /**
     * A port blocked by a full chest backs off to the scheduler's maximum (it runs at most about once per maximum
     * backoff), a port with nothing to push waits without running at all, and freeing room in the chest beside the
     * blocked port wakes it within a tick.
     */
    private static void sleeps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int maxBackoff = ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt();
        int window = 2 * maxBackoff;
        TaskGroup blockedGroup = LevelTasks.newGroup(level);
        TaskGroup emptyGroup = LevelTasks.newGroup(level);
        PortEntity blocked = port(helper, PORT, blockedGroup);
        ChestBlockEntity full = chest(helper, PORT.east());
        fill(full, Items.COBBLESTONE, 27);
        blocked.output.set(0, new ItemStack(Items.STONE, 64));
        blocked.port.setSettings(faces(Direction.EAST, PUSH));
        PortEntity empty = port(helper, new BlockPos(4, 0, 1), emptyGroup);
        chest(helper, new BlockPos(5, 0, 1));
        empty.port.setSettings(faces(Direction.EAST, PUSH));
        long[] runs = new long[2];
        long[] freedAt = new long[1];
        helper.startSequence().thenExecuteAfter(2 * maxBackoff + 100, () -> {
            runs[0] = blockedGroup.runs();
            runs[1] = emptyGroup.runs();
            helper.assertValueEqual(blocked.port.status(ResourceKinds.ITEM), PortStatus.BLOCKED, "blocked status");
        }).thenExecuteAfter(window, () -> {
            long blockedRuns = blockedGroup.runs() - runs[0];
            long allowed = window / (maxBackoff - maxBackoff / 8) + 1L;
            helper.assertTrue(blockedRuns <= allowed,
                    blockedRuns + " runs of a blocked port in " + window + " ticks, at most " + allowed);
            helper.assertValueEqual(emptyGroup.runs() - runs[1], 0L, "runs of a port with nothing to push");
            full.removeItem(5, 64);
            freedAt[0] = level.getGameTime();
        }).thenWaitUntil(() -> helper.assertValueEqual(count(full, Items.STONE), 64, "stone pushed after room freed"))
                .thenExecute(() -> {
                    long delay = level.getGameTime() - freedAt[0];
                    helper.assertTrue(delay <= 1, "woke " + delay + " ticks after the chest changed");
                }).thenSucceed();
    }

    /**
     * A chest in a chunk below full status is never loaded by a pushing port and gets nothing; the chunk stays in
     * memory, so its return to full status invalidates no capability cache, and the polling port finds the chest again
     * with a new cache and pushes. Real tickets far from the test grid: the forced anchor keeps the port's chunk, two
     * chunks away, at full status, while the chest's chunk next to it is not.
     */
    private static void targetUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int anchorX = 6300;
        int chunkZ = 6300;
        int portChunkX = anchorX + 2;
        int chestChunkX = anchorX + 3;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos portPos = new BlockPos(portChunkX * 16 + 15, y, chunkZ * 16 + 8);
        BlockPos chestPos = portPos.east();
        PortEntity[] port = new PortEntity[1];
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(anchorX, chunkZ, true);
            level.setChunkForced(chestChunkX, chunkZ, true);
            level.getChunk(portChunkX, chunkZ);
            level.getChunk(chestChunkX, chunkZ);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(portChunkX, chunkZ) != null, "port chunk not full");
            helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) != null, "chest chunk not full");
        }).thenExecute(() -> {
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), QUIET);
            port[0] = TestPorts.place(level, portPos, LevelTasks.newGroup(level), QUIET);
            port[0].output.set(0, new ItemStack(Items.STONE, 64));
            level.setChunkForced(chestChunkX, chunkZ, false);
        }).thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) == null,
                "the chest's chunk is still at full status"))
                .thenExecute(() -> port[0].port.setSettings(faces(Direction.EAST, PUSH))).thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) == null,
                            "the port loaded the chest's chunk");
                    helper.assertValueEqual(TestPorts.count(port[0].output, Items.STONE), 64, "stone moved");
                    helper.assertValueEqual(port[0].port.status(ResourceKinds.ITEM), PortStatus.IDLE, "status");
                    level.setChunkForced(chestChunkX, chunkZ, true);
                    level.getChunk(chestChunkX, chunkZ);
                }).thenWaitUntil(() -> helper.assertValueEqual(TestPorts.count(port[0].output, Items.STONE), 0,
                        "stone left after the chest's chunk came back"))
                .thenExecute(() -> {
                    ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
                    helper.assertTrue(chest != null && count(chest, Items.STONE) == 64, "stone in the chest");
                    level.setBlock(portPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setBlock(chestPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setChunkForced(chestChunkX, chunkZ, false);
                    level.setChunkForced(anchorX, chunkZ, false);
                }).thenSucceed();
    }

    /**
     * A port whose own chunk unloads leaves its group and the port registry; when the chunk loads again the new block
     * entity starts with the saved settings and goes on where it stopped, without duping or losing an item.
     */
    private static void unloadReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int chunkX = 6400;
        int chunkZ = 6400;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos portPos = new BlockPos(chunkX * 16 + 5, y, chunkZ * 16 + 8);
        BlockPos chestPos = portPos.east();
        TaskGroup group = LevelTasks.newGroup(level);
        PortSettings slow = faces(Direction.EAST, PUSH).withRate(ResourceKinds.ITEM, 1);
        PortEntity[] port = new PortEntity[1];
        int[] before = new int[1];
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(chunkX, chunkZ, true);
            level.getChunk(chunkX, chunkZ);
        }).thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) != null,
                "the port's chunk is not at full status")).thenExecute(() -> {
                    level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), QUIET);
                    port[0] = TestPorts.place(level, portPos, group, QUIET);
                    port[0].output.set(0, new ItemStack(Items.STONE, 64));
                    port[0].port.setSettings(slow);
                }).thenWaitUntil(() -> helper.assertTrue(chestStone(level, chestPos) >= 2, "no stone pushed yet"))
                .thenExecute(() -> {
                    helper.assertValueEqual(group.size(), 1, "tasks in the port's group");
                    before[0] = chestStone(level, chestPos);
                    level.setChunkForced(chunkX, chunkZ, false);
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(group.size(), 0, "tasks after the chunk unloaded");
                    helper.assertFalse(PortRegistry.has(level, portPos.asLong()), "the unloaded port is registered");
                }).thenExecute(() -> {
                    level.setChunkForced(chunkX, chunkZ, true);
                    level.getChunk(chunkX, chunkZ);
                }).thenWaitUntil(() -> {
                    helper.assertTrue(level.getBlockEntity(portPos) instanceof PortEntity reloaded
                            && reloaded != port[0] && PortRegistry.has(level, portPos.asLong()), "not reloaded");
                    helper.assertTrue(chestStone(level, chestPos) >= before[0] + 3, "no stone pushed after reload");
                }).thenExecute(() -> {
                    PortEntity reloaded = (PortEntity) Objects.requireNonNull(level.getBlockEntity(portPos), "no port");
                    helper.assertValueEqual(reloaded.port.settings(), slow, "settings after reload");
                    helper.assertValueEqual(TestPorts.count(reloaded.output, Items.STONE) + chestStone(level, chestPos),
                            64, "stone in all");
                    level.setBlock(portPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setBlock(chestPos, Blocks.AIR.defaultBlockState(), QUIET);
                    level.setChunkForced(chunkX, chunkZ, false);
                }).thenSucceed();
    }

    private static int chestStone(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChestBlockEntity chest ? count(chest, Items.STONE) : -1;
    }
}
