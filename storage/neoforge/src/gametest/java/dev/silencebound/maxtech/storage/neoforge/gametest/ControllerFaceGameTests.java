package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlock;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerFace;
import java.util.ArrayList;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The controller face's server side (M7R item 7): every block of a multiblock, whatever its shape, learns where the
 * multiblock's centre lies (the glow phase the client model reads), it follows the shape when blocks are broken, and
 * the connected-texture mask read from the level frames only the multiblock's outside. The model itself bakes on the
 * client only; its pure rules are in {@code ControllerFaceTest}.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ControllerFaceGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("controller_face_centre_follows_shape", ControllerFaceGameTests::centreFollowsShape);
        register("controller_face_frames_outside_only", ControllerFaceGameTests::framesOutsideOnly);
    }

    private ControllerFaceGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("controller_face"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(plain, STRUCTURE, 200, 0, true)));
        }
    }

    /** The blocks of a 2 x 2 x 3 box from {@code origin}. */
    private static List<BlockPos> box(BlockPos origin) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 3; z++) {
                    out.add(origin.offset(x, y, z));
                }
            }
        }
        return out;
    }

    /**
     * A 2 x 2 x 3 box: every block's offset leads to the box's centre. Breaking the far layer (four blocks) moves the
     * centre half a block, and every remaining block learns it; a block left alone points at itself.
     */
    private static void centreFollowsShape(GameTestHelper helper) {
        BlockPos origin = new BlockPos(5, 1, 5);
        List<BlockPos> blocks = box(origin);
        BlockPos stray = origin.east(6);
        helper.startSequence().thenExecute(() -> {
            for (BlockPos pos : blocks) {
                helper.setBlock(pos, ControllerContent.CONTROLLER.get());
            }
            helper.setBlock(stray, ControllerContent.CONTROLLER.get());
        }).thenWaitUntil(() -> {
            // The centre lies (0.5, 0.5, 1) blocks from the origin block's centre: (1, 1, 2) in half blocks.
            assertCentre(helper, blocks, helper.absolutePos(origin).multiply(2).offset(1, 1, 2));
        }).thenExecute(() -> {
            for (BlockPos pos : blocks) {
                if (pos.getZ() == origin.getZ() + 2) {
                    helper.getLevel().destroyBlock(helper.absolutePos(pos), false);
                }
            }
        }).thenWaitUntil(() -> {
            List<BlockPos> left = new ArrayList<>();
            for (BlockPos pos : blocks) {
                if (pos.getZ() != origin.getZ() + 2) {
                    left.add(pos);
                }
            }
            assertCentre(helper, left, helper.absolutePos(origin).multiply(2).offset(1, 1, 1));
            helper.assertValueEqual(entity(helper, stray).glowCentre(), 0, "a lone block's centre is its own");
        }).thenSucceed();
    }

    /**
     * A wall three wide and two high with one block on top of its middle: the wall's south faces join (frame only on
     * the wall's rim), and the top block makes an inner corner, so the faces beside it keep their frame on that side.
     */
    private static void framesOutsideOnly(GameTestHelper helper) {
        BlockPos west = new BlockPos(5, 1, 5);
        List<BlockPos> wall = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                wall.add(west.offset(x, y, 0));
            }
        }
        BlockPos top = west.offset(1, 2, 0);
        BlockPos lowerMiddle = west.east();
        BlockPos upperWest = west.above();
        helper.startSequence().thenExecute(() -> {
            for (BlockPos pos : wall) {
                helper.setBlock(pos, ControllerContent.CONTROLLER.get());
            }
            helper.setBlock(top, ControllerContent.CONTROLLER.get());
        }).thenWaitUntil(() -> {
            // South faces: top is up, right is east (vanilla's south UVs).
            helper.assertValueEqual(edges(helper, lowerMiddle, Direction.SOUTH), ControllerFace.BOTTOM,
                    "lower middle south: only the bottom rim");
            helper.assertValueEqual(edges(helper, upperWest, Direction.SOUTH), ControllerFace.LEFT | ControllerFace.TOP,
                    "upper west south: left and top rim");
            helper.assertValueEqual(edges(helper, top, Direction.SOUTH),
                    ControllerFace.LEFT | ControllerFace.TOP | ControllerFace.RIGHT, "top block south");
            // The upper west block's up face: its east neighbour's up face is covered by the top block, an inner
            // corner, so the frame stays on that side.
            helper.assertValueEqual(edges(helper, upperWest, Direction.UP) & ControllerFace.RIGHT, ControllerFace.RIGHT,
                    "inner corner beside the top block");
            helper.assertValueEqual(entity(helper, top).primaryPos(), entity(helper, west).primaryPos(),
                    "one multiblock");
        }).thenSucceed();
    }

    private static void assertCentre(GameTestHelper helper, List<BlockPos> blocks, BlockPos doubledCentre) {
        for (BlockPos pos : blocks) {
            int packed = entity(helper, pos).glowCentre();
            BlockPos at = helper.absolutePos(pos).multiply(2).offset(ControllerFace.centreAxis(packed, 0),
                    ControllerFace.centreAxis(packed, 1), ControllerFace.centreAxis(packed, 2));
            helper.assertValueEqual(at, doubledCentre, "centre seen from " + pos.toShortString());
        }
    }

    /** {@code face}'s edges on the block at {@code pos}, from the level as the client model reads them. */
    private static int edges(GameTestHelper helper, BlockPos pos, Direction face) {
        int occupancy = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx != 0 || dy != 0 || dz != 0)
                            && helper.getBlockState(pos.offset(dx, dy, dz)).getBlock() instanceof ControllerBlock) {
                        occupancy |= ControllerFace.bit(dx, dy, dz);
                    }
                }
            }
        }
        return ControllerFace.edges(face, occupancy);
    }

    private static ControllerBlockEntity entity(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ControllerBlockEntity.class);
    }
}
