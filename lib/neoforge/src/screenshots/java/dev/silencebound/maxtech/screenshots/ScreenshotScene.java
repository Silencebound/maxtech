package dev.silencebound.maxtech.screenshots;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * A scene for {@link ScreenshotHarness}: what to build in the fresh flat world and the shots to take of it. The world
 * is the default flat preset without structures, so the floor (the first air block) is at {@link #FLOOR} everywhere and
 * the player starts near 0, 0; build there. Positions are absolute: each shot is the camera's eye position, yaw (0
 * looks south, 90 west) and pitch (90 looks straight down). A {@linkplain #menu menu shot} stands the player there in
 * creative mode, uses the block like a right click, waits for its screen and then runs the scene's screen action.
 */
public final class ScreenshotScene {
    /** The y of the first air block above the flat world's grass. */
    public static final int FLOOR = -60;

    private final String name;
    private final Consumer<ServerLevel> build;
    private final List<Shot> shots = new ArrayList<>();

    private ScreenshotScene(String name, Consumer<ServerLevel> build) {
        this.name = name;
        this.build = build;
    }

    /** A scene called {@code name} (the {@code -Pscene=} value), built by {@code build} on the server thread. */
    public static ScreenshotScene of(String name, Consumer<ServerLevel> build) {
        return new ScreenshotScene(name, build);
    }

    /** Adds a shot of the world from eye position {@code x, y, z}. */
    public ScreenshotScene shot(String shot, double x, double y, double z, float yaw, float pitch) {
        shots.add(new Shot(shot, x, y, z, yaw, pitch, null, screen -> {
        }));
        return this;
    }

    /**
     * Adds a shot of the screen {@code block} opens when used from eye position {@code x, y, z} (within reach), taken
     * after {@code action} ran on the open screen.
     */
    public ScreenshotScene menu(String shot, double x, double y, double z, float yaw, float pitch, BlockPos block,
            Consumer<Screen> action) {
        shots.add(new Shot(shot, x, y, z, yaw, pitch, block, action));
        return this;
    }

    /** A screen action that clicks, at its centre, the first widget matching {@code widget}; logs when none does. */
    public static Consumer<Screen> click(Predicate<AbstractWidget> widget) {
        return screen -> {
            for (GuiEventListener child : screen.children()) {
                if (child instanceof AbstractWidget each && widget.test(each)) {
                    double x = each.getX() + each.getWidth() / 2.0;
                    double y = each.getY() + each.getHeight() / 2.0;
                    screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
                    return;
                }
            }
            ScreenshotHarness.LOGGER.warn("[screenshots] no widget to click on {}", screen.getClass().getName());
        };
    }

    /** A screen action that clicks the first widget of the class with the simple name {@code simpleName}. */
    public static Consumer<Screen> clickWidgetOfClass(String simpleName) {
        return click(widget -> widget.getClass().getSimpleName().equals(simpleName));
    }

    String name() {
        return name;
    }

    void build(ServerLevel level) {
        build.accept(level);
    }

    List<Shot> shots() {
        return shots;
    }

    /** One shot: the eye pose, and for a menu shot the block to use and the action on its screen. */
    record Shot(String name, double x, double y, double z, float yaw, float pitch, @Nullable BlockPos open,
            Consumer<Screen> action) {
    }
}
