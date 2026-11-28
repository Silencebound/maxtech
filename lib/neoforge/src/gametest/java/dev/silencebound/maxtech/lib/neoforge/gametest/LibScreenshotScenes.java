package dev.silencebound.maxtech.lib.neoforge.gametest;

import static dev.silencebound.maxtech.screenshots.ScreenshotScene.FLOOR;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.screenshots.ScreenshotHarness;
import dev.silencebound.maxtech.screenshots.ScreenshotScene;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Lib's scenes for {@code ./gradlew :lib:neoforge:runScreenshots -Pscene=<name>} (the client-check skill). */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
public final class LibScreenshotScenes {
    /** The toolkit demo machine. */
    private static final BlockPos MACHINE = new BlockPos(0, FLOOR, 3);

    /**
     * The pipe showcase from 0, FLOOR, 0 facing south: rows run south (+z), shapes west (-x). The first row (picked
     * look, lab stripe) has its straight at x -2..-4, the L at -6..-7, the T's bar at x -10..-12 with its stem at x
     * -11, and the cross centred on x -15; all bars at z 3.
     */
    static final ScreenshotScene PIPES = ScreenshotScene
            .of("pipes", level -> PipeShowcase.build(level, new BlockPos(0, FLOOR, 0), Direction.SOUTH))
            .shot("overview", -21, FLOOR + 16, -8, 0, 45).shot("first_rows", -9.5, FLOOR + 4, -3, 0, 35)
            .shot("t_closeup", -10.5, FLOOR + 0.7, 1.6, 0, 12).shot("cross_from_above", -14.5, FLOOR + 1.8, 3.5, 0, 90);

    /** The toolkit demo machine, its screen, and the screen with the side config tab open. */
    static final ScreenshotScene TOOLKIT = ScreenshotScene
            .of("toolkit", level -> level.setBlockAndUpdate(MACHINE, ToolkitDemo.BLOCK.get().defaultBlockState()))
            .shot("machine", 1.8, FLOOR + 1.6, 1.2, 35, 25)
            .menu("screen", 0.5, FLOOR + 1.6, 1.0, 0, 30, MACHINE, screen -> {
            }).menu("side_tab", 0.5, FLOOR + 1.6, 1.0, 0, 30, MACHINE, ScreenshotScene.clickWidgetOfClass("TabButton"));

    private LibScreenshotScenes() {
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        ScreenshotHarness.install(List.of(PIPES, TOOLKIT));
    }
}
