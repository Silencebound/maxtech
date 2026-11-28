package dev.silencebound.maxtech.storage.neoforge.gametest;

import static dev.silencebound.maxtech.screenshots.ScreenshotScene.FLOOR;

import dev.silencebound.maxtech.screenshots.ScreenshotHarness;
import dev.silencebound.maxtech.screenshots.ScreenshotScene;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.dev.ShowcaseCommand;
import dev.silencebound.maxtech.storage.dev.ShowcaseShots;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Storage's scenes for {@code ./gradlew :storage:neoforge:runScreenshots -Pscene=<station>} (the client-check skill):
 * one per showcase station with a scene shot, built by the {@code showcase} command and framed as {@link ShowcaseShots}
 * frames it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
public final class StorageScreenshotScenes {
    private static final BlockPos SPOT = new BlockPos(0, FLOOR, 0);

    private StorageScreenshotScenes() {
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        List<ScreenshotScene> scenes = new ArrayList<>();
        BlockPos corner = ShowcaseCommand.origin(SPOT, false);
        for (ShowcaseShots.Shot shot : ShowcaseShots.SHOTS) {
            String station = shot.station().id();
            ShowcaseShots.Pose pose = ShowcaseShots.pose(shot, corner.getX(), corner.getY(), corner.getZ(),
                    Showcase.PAD);
            scenes.add(ScreenshotScene.of(station, level -> {
                MinecraftServer server = level.getServer();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                        String.format(Locale.ROOT, "execute positioned %d %d %d run %s showcase %s", SPOT.getX(),
                                SPOT.getY(), SPOT.getZ(), MaxtechStorage.MOD_ID, station));
            }).shot("front", pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch()));
        }
        ScreenshotHarness.install(scenes);
    }
}
