package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** The pipe showcase ({@link PipeShowcase}) builds every row and clears again, as its command does. */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class PipeShowcaseGameTests {
    private static final String NAME = "pipe_showcase_builds_and_clears";
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    // Far above the test grid, so the showcase's area meets no other test.
    private static final int LIFT = 150;
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> PipeShowcaseGameTests::buildsAndClears);
    }

    private PipeShowcaseGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("pipe_showcase"));
        event.registerTest(MaxtechLib.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 400, 0, true)));
    }

    /**
     * Every row's pipes stand, of the row's own types, each filled; the labels are there; clearing removes both, and a
     * second build replaces the first rather than adding to it.
     */
    private static void buildsAndClears(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        BoundingBox area = PipeShowcase.area(origin, Direction.SOUTH);
        // Labels are entities; one added before its chunk's entities have loaded was not always listed (a player's own
        // view has them loaded), so the test holds its area's chunks and waits for their entities first.
        forceChunks(helper, area, true);
        helper.startSequence().thenWaitUntil(() -> {
            for (ChunkPos chunk : chunks(area)) {
                helper.assertTrue(helper.getLevel().areEntitiesLoaded(chunk.pack()), "chunk entities not loaded yet");
            }
        }).thenExecute(() -> {
            PipeShowcase.build(helper.getLevel(), origin, Direction.SOUTH);
            helper.assertValueEqual(area, PipeShowcase.build(helper.getLevel(), origin, Direction.SOUTH), "area");
            check(helper, area);
            refusesOverOtherBlocks(helper, origin, area);
            forceChunks(helper, area, false);
        }).thenSucceed();
    }

    /** A block that is not the showcase's stays untouched, and nothing is built over it. */
    private static void refusesOverOtherBlocks(GameTestHelper helper, BlockPos origin, BoundingBox area) {
        BlockPos stone = new BlockPos(area.minX() + 1, area.minY(), area.minZ() + 1);
        helper.getLevel().setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        boolean refused = false;
        try {
            PipeShowcase.build(helper.getLevel(), origin, Direction.SOUTH);
        } catch (IllegalStateException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "build refuses over a foreign block");
        helper.assertTrue(helper.getLevel().getBlockState(stone).is(Blocks.STONE), "foreign block kept");
        helper.getLevel().setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void check(GameTestHelper helper, BoundingBox area) {
        Set<Block> expected = new HashSet<>();
        int pipes = 0;
        // the title, one per section and row, and T1 to T4 over each tier row
        int labels = 1 + PipeShowcase.SECTIONS.size();
        for (PipeShowcase.Section section : PipeShowcase.SECTIONS) {
            for (PipeShowcase.Row row : section.rows()) {
                for (ConduitEntry<ConduitType> type : row.types()) {
                    expected.add(type.block().get());
                }
                // straight 3, L 3, T 4, crossing 5; a tier row is four Ts
                pipes += row.tiers() ? 16 : 15;
                labels += row.tiers() ? 5 : 1;
            }
        }
        Set<Block> found = new HashSet<>();
        int counted = 0;
        for (BlockPos pos : BlockPos.betweenClosed(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(),
                area.maxZ())) {
            Optional<ConduitHost> host = Conduits.host(helper.getLevel(), pos);
            if (host.isPresent()) {
                counted++;
                found.add(helper.getLevel().getBlockState(pos).getBlock());
                helper.assertTrue(host.get().contents().resource().isPresent(), "empty pipe at " + pos);
            }
        }
        helper.assertValueEqual(pipes, counted, "pipes built");
        helper.assertValueEqual(expected, found, "pipe types built");
        helper.assertValueEqual(labels, labels(helper), "labels");
        helper.assertValueEqual(1, PipeShowcase.clear(helper.getLevel()), "showcases cleared");
        for (BlockPos pos : BlockPos.betweenClosed(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(),
                area.maxZ())) {
            helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "left at " + pos);
        }
        helper.assertValueEqual(0, labels(helper), "labels after clearing");
    }

    private static void forceChunks(GameTestHelper helper, BoundingBox area, boolean force) {
        for (ChunkPos chunk : chunks(area)) {
            helper.getLevel().setChunkForced(chunk.x(), chunk.z(), force);
        }
    }

    private static List<ChunkPos> chunks(BoundingBox area) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = SectionPos.blockToSectionCoord(area.minX()); x <= SectionPos
                .blockToSectionCoord(area.maxX()); x++) {
            for (int z = SectionPos.blockToSectionCoord(area.minZ()); z <= SectionPos
                    .blockToSectionCoord(area.maxZ()); z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    private static int labels(GameTestHelper helper) {
        return helper.getLevel()
                .getEntities(EntityType.TEXT_DISPLAY, display -> display.entityTags().contains(PipeShowcase.TAG))
                .size();
    }
}
