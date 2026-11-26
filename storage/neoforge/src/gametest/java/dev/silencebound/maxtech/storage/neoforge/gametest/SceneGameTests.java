package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlock;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.dev.ShowcaseScene;
import dev.silencebound.maxtech.storage.dev.ShowcaseScenes;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * The screenshot scenes ({@link ShowcaseScenes}): each code draft builds without warnings and powers up (controller
 * online, network powered, no device short of a channel, stock in); the crafting scene's CPU is busy and the processing
 * scene's machines have energy. Then the scene is saved as a vanilla structure, cleared and placed again from it, as
 * {@code scene save} and {@code scene place} do with the owner's edits, and must power up the same way.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SceneGameTests {
    private static final int TICKS = 800;

    static {
        StorageTestEnvironment.init();
        for (ShowcaseScene scene : ShowcaseScene.values()) {
            StorageTests.function(name(scene), StorageTestEnvironment.clearing(helper -> powersUp(helper, scene)));
        }
    }

    private SceneGameTests() {
    }

    private static String name(ShowcaseScene scene) {
        return "scene_" + scene.id().toLowerCase(Locale.ROOT) + "_powers_up";
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(MaxtechStorage.id("scenes"), StorageTestEnvironment.plain());
        for (ShowcaseScene scene : ShowcaseScene.values()) {
            StorageTests.test(event, environment, name(scene), MaxtechStorage.id("empty_16x12x16"), TICKS);
        }
    }

    private static void powersUp(GameTestHelper helper, ShowcaseScene scene) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "scene_" + scene.id());
        // Two layers of floor under the room, inside the structure.
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1 + ShowcaseScene.BELOW, 1));
        ShowcaseScenes.Placed draft = ShowcaseScenes.build(level, scene, origin, player, null);
        helper.assertTrue(draft.warnings().isEmpty(), scene.id() + " draft warnings: " + draft.warnings());
        helper.assertFalse(draft.fromFile(), "the draft came from a file");
        StructureTemplate[] saved = new StructureTemplate[1];
        helper.startSequence().thenWaitUntil(() -> check(helper, scene, origin)).thenExecute(() -> {
            saved[0] = ShowcaseScenes.capture(level, origin);
            ShowcaseScenes.clear(level, origin);
            helper.assertTrue(controller(helper, origin) == null, scene.id() + ": clearing left a controller");
            ShowcaseScenes.Placed again = ShowcaseScenes.build(level, scene, origin, player, saved[0]);
            helper.assertTrue(again.fromFile(), "the second build did not use the structure");
            helper.assertTrue(again.warnings().isEmpty(), scene.id() + " structure warnings: " + again.warnings());
        }).thenWaitUntil(() -> check(helper, scene, origin)).thenExecute(() -> ShowcaseScenes.clear(level, origin))
                .thenSucceed();
    }

    /** Throws until the scene at {@code origin} has powered up. */
    private static void check(GameTestHelper helper, ShowcaseScene scene, BlockPos origin) {
        BlockPos controller = controller(helper, origin);
        if (controller == null) {
            throw helper.assertionException(Component.literal(scene.id() + ": no controller"));
        }
        helper.assertValueEqual(helper.getLevel().getBlockState(controller).getValue(ControllerBlock.STATUS),
                ControllerStatus.ONLINE, scene.id() + " controller");
        NetworkState network = StorageNetworks.network(helper.getLevel(), StorageNetworks.ID, controller);
        if (network == null) {
            throw helper.assertionException(Component.literal(scene.id() + ": no network"));
        }
        helper.assertTrue(network.isPowered(), scene.id() + ": network not powered");
        helper.assertTrue(network.offlineDevices().isEmpty(),
                scene.id() + ": devices short of a channel: " + network.offlineDevices().size());
        helper.assertTrue(network.contents().size() > 0, scene.id() + ": no stock");
        int drives = 0;
        int cpus = 0;
        int busy = 0;
        String cpuInfo = "";
        BlockPos corner = ShowcaseScenes.corner(origin);
        Vec3i size = ShowcaseScenes.size();
        for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(size).offset(-1, -1, -1))) {
            BlockEntity entity = helper.getLevel().getBlockEntity(pos);
            if (entity instanceof DriveBlockEntity drive) {
                helper.assertTrue(drive.cellCount() > 0, scene.id() + ": empty drive at " + pos.toShortString());
                drives++;
            } else if (entity instanceof CpuCoreBlockEntity cpu) {
                cpus++;
                busy += cpu.busy() ? 1 : 0;
                cpuInfo = cpu.conflict() ? " (in a conflict)" : "";
            } else if (entity instanceof StorageMachineBlockEntity machine) {
                helper.assertTrue(machine.energy().stored() > 0,
                        scene.id() + ": no energy in the machine at " + pos.toShortString());
            }
        }
        helper.assertTrue(drives > 0, scene.id() + ": no drive");
        if (scene == ShowcaseScene.CRAFTING) {
            helper.assertTrue(cpus > 0 && busy == cpus, "crafting: CPUs busy " + busy + " of " + cpus + cpuInfo
                    + ", jobs " + JobQueue.jobsOn(network).size());
        }
    }

    private static @Nullable BlockPos controller(GameTestHelper helper, BlockPos origin) {
        BlockPos corner = ShowcaseScenes.corner(origin);
        Vec3i size = ShowcaseScenes.size();
        for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(size).offset(-1, -1, -1))) {
            if (helper.getLevel().getBlockEntity(pos) instanceof ControllerBlockEntity) {
                return pos.immutable();
            }
        }
        return null;
    }
}
