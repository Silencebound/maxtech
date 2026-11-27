package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Runs {@link LayoutCheck} on every MaxTech screen each time it lays itself out (opening, resizing, rebuilding its
 * widgets), in dev runs only, and logs each problem once: a container screen's panel or a widget that leaves the
 * window, and widgets that overlap. It only reads the screen, so it cannot change what players see; FML reports
 * production for every game started from a built jar, so players never run it.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
public final class LayoutCheckClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PACKAGE = "dev.silencebound.maxtech.";
    /** Problems already logged, so a screen that rebuilds every frame does not flood the log. */
    private static final Set<String> REPORTED = new HashSet<>();

    private LayoutCheckClient() {
    }

    @SubscribeEvent
    static void laidOut(ScreenEvent.Init.Post event) {
        if (FMLEnvironment.isProduction()) {
            return;
        }
        Screen screen = event.getScreen();
        if (!screen.getClass().getName().startsWith(PACKAGE)) {
            return;
        }
        List<LayoutCheck.Box> panels = screen instanceof AbstractContainerScreen<?> container
                ? List.of(new LayoutCheck.Box("panel", container.getLeftPos(), container.getTopPos(),
                        container.getImageWidth(), container.getImageHeight()))
                : List.of();
        for (String problem : LayoutCheck.problems(screen.width, screen.height, panels,
                LayoutCheck.widgets(screen.children()))) {
            String line = screen.getClass().getSimpleName() + " at " + screen.width + "x" + screen.height + ": "
                    + problem;
            if (REPORTED.add(line)) {
                LOGGER.warn("Layout check: {}", line);
            }
        }
    }
}
