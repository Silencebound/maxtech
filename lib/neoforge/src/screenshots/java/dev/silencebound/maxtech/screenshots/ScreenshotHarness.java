package dev.silencebound.maxtech.screenshots;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Takes a scene's screenshots without a person: the {@code runScreenshots} Gradle run (dev only; this source set is
 * part of each mod's GameTest source set, which no jar contains) sets {@value #SCENE_PROPERTY}. On the title screen (or
 * the first-start accessibility screen) it creates a fresh flat world, builds the {@link ScreenshotScene} there, and
 * for each shot puts the camera at the shot's pose, waits until every visible section is compiled, saves the frame as
 * {@code
 *
<dir>
 * /<scene>/<shot>.png} ({@value #DIR_PROPERTY}) and at the end quits the game. Lines of its log start with
 * {@code [screenshots]}; the last one says how many shots were saved. Client thread only, a step per tick.
 */
public final class ScreenshotHarness {
    static final Logger LOGGER = LogUtils.getLogger();
    /** The scene to shoot; nothing happens without it. */
    public static final String SCENE_PROPERTY = "maxtech.screenshots.scene";
    /** The folder the scene's folder of shots goes in; the game directory's {@code auto} when unset. */
    public static final String DIR_PROPERTY = "maxtech.screenshots.dir";
    private static final String WORLD = "maxtech_screenshots";
    /** Ticks the whole run may take before it gives up and quits. */
    private static final int TIMEOUT = 20 * 180;
    /** Ticks after joining before the build, so the spawn chunks are there. */
    private static final int JOIN_TICKS = 20;
    /** Ticks after the build before the first shot: networks form, contents sync. */
    private static final int BUILD_TICKS = 40;
    /** Ticks after a teleport before the frame may count as rendered. */
    private static final int ENTER_TICKS = 20;
    /** Ticks a shot may take to render, or a screen to open, before it is taken as it is. */
    private static final int PATIENCE = 400;
    /** Ticks between rendered (or a screen action) and the capture, for animations and the screen's first frames. */
    private static final int SETTLE_TICKS = 10;

    private ScreenshotHarness() {
    }

    /**
     * Starts the run when {@value #SCENE_PROPERTY} names one of {@code scenes}; call it once from the mod's client
     * setup. Any thread.
     */
    public static void install(List<ScreenshotScene> scenes) {
        String wanted = System.getProperty(SCENE_PROPERTY, "").trim();
        if (wanted.isEmpty()) {
            return;
        }
        ScreenshotScene scene = null;
        List<String> names = new ArrayList<>();
        for (ScreenshotScene each : scenes) {
            names.add(each.name());
            if (each.name().equals(wanted)) {
                scene = each;
            }
        }
        Run run = new Run(wanted, scene, names);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> run.tick(Minecraft.getInstance()));
    }

    private enum Phase {
        TITLE, JOIN, BUILD, ENTER, OPEN, SETTLE, WRITE, DONE
    }

    private static final class Run {
        private final String wanted;
        private final @Nullable ScreenshotScene scene;
        private final List<String> names;
        private final AtomicInteger pending = new AtomicInteger();
        private final List<String> saved = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();
        private Phase phase = Phase.TITLE;
        private int ticks;
        private int total;
        private int index;
        private @Nullable Path dir;

        Run(String wanted, @Nullable ScreenshotScene scene, List<String> names) {
            this.wanted = wanted;
            this.scene = scene;
            this.names = names;
        }

        void tick(Minecraft minecraft) {
            if (phase == Phase.DONE) {
                return;
            }
            ScreenshotScene current = scene;
            if (current == null) {
                LOGGER.error("[screenshots] no scene '{}' in this mod; scenes: {}", wanted, String.join(", ", names));
                quit(minecraft);
                return;
            }
            ticks++;
            if (++total > TIMEOUT) {
                LOGGER.error("[screenshots] timed out in phase {} at shot {}", phase, index);
                quit(minecraft);
                return;
            }
            switch (phase) {
                case TITLE -> title(minecraft, current);
                case JOIN -> {
                    if (minecraft.player == null || minecraft.level == null || minecraft.screen != null
                            || minecraft.getSingleplayerServer() == null) {
                        ticks = 0;
                    } else if (ticks >= JOIN_TICKS) {
                        next(Phase.BUILD);
                    }
                }
                case BUILD -> {
                    if (ticks == 1) {
                        MinecraftServer server = server(minecraft);
                        server.execute(() -> {
                            command(server, "time set 6000");
                            command(server, "weather clear");
                            command(server, "gamemode spectator @a");
                            current.build(server.overworld());
                        });
                    } else if (ticks >= BUILD_TICKS) {
                        next(Phase.ENTER);
                    }
                }
                case ENTER -> enter(minecraft, current.shots().get(index));
                case OPEN -> open(minecraft, current.shots().get(index));
                case SETTLE -> {
                    hold(minecraft, current.shots().get(index));
                    if (ticks >= SETTLE_TICKS) {
                        capture(minecraft, current, current.shots().get(index));
                        next(Phase.WRITE);
                    }
                }
                case WRITE -> {
                    if (pending.get() == 0 || ticks > PATIENCE) {
                        index++;
                        if (index < current.shots().size()) {
                            next(Phase.ENTER);
                        } else {
                            LOGGER.info("[screenshots] saved {} of {} shots of {} in {}", saved.size(),
                                    current.shots().size(), current.name(), dir);
                            for (String each : saved) {
                                LOGGER.info("[screenshots] saved {}", each);
                            }
                            for (String each : failed) {
                                LOGGER.error("[screenshots] {}", each);
                            }
                            quit(minecraft);
                        }
                    }
                }
                case DONE -> {
                }
            }
        }

        private void next(Phase phase) {
            this.phase = phase;
            this.ticks = 0;
        }

        /** On the title or first-start screen: settles the options, then creates a fresh flat world. */
        private void title(Minecraft minecraft, ScreenshotScene current) {
            Screen screen = minecraft.screen;
            if (minecraft.level != null || minecraft.getOverlay() != null
                    || !(screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen)) {
                return;
            }
            minecraft.options.onboardAccessibility = false;
            minecraft.options.pauseOnLostFocus = false;
            minecraft.options.hideGui = true;
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            minecraft.options.chunkSectionFadeInTime().set(0.0);
            String base = System.getProperty(DIR_PROPERTY, "");
            Path root = base.isBlank() ? minecraft.gameDirectory.toPath().resolve("auto") : Path.of(base);
            Path sceneDir = root.resolve(current.name());
            dir = sceneDir;
            try {
                delete(minecraft.gameDirectory.toPath().resolve("saves").resolve(WORLD));
                delete(sceneDir);
                Files.createDirectories(sceneDir);
            } catch (IOException e) {
                LOGGER.error("[screenshots] could not clear the last run", e);
                quit(minecraft);
                return;
            }
            LOGGER.info("[screenshots] creating a flat world for scene {}", current.name());
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                    WorldDataConfiguration.DEFAULT);
            next(Phase.JOIN);
            minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
                    WorldPresets::createFlatWorldDimensions, screen);
        }

        private void enter(Minecraft minecraft, ScreenshotScene.Shot shot) {
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            if (ticks == 1) {
                if (minecraft.screen != null) {
                    player.closeContainer();
                }
                MinecraftServer server = server(minecraft);
                String mode = shot.open() != null ? "creative" : "spectator";
                double feet = shot.y() - player.getEyeHeight();
                server.execute(() -> {
                    command(server, "gamemode " + mode + " @a");
                    command(server, String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", shot.x(), feet,
                            shot.z(), shot.yaw(), shot.pitch()));
                    // Animals of the flat world would walk into the shots; labels (text displays) stay.
                    command(server, "kill @e[type=!player,type=!text_display,type=!block_display,type=!item_display]");
                });
                return;
            }
            hold(minecraft, shot);
            if (ticks < ENTER_TICKS) {
                return;
            }
            boolean rendered = rendered(minecraft.levelRenderer);
            if (!rendered && ticks <= PATIENCE) {
                return;
            }
            if (!rendered) {
                LOGGER.warn("[screenshots] {} did not finish rendering; taking it as it is", shot.name());
            }
            next(shot.open() != null ? Phase.OPEN : Phase.SETTLE);
        }

        /** Uses the shot's block as a right click would, waits for its screen and runs the shot's action on it. */
        private void open(Minecraft minecraft, ScreenshotScene.Shot shot) {
            hold(minecraft, shot);
            BlockPos block = shot.open();
            LocalPlayer player = minecraft.player;
            MultiPlayerGameMode gameMode = minecraft.gameMode;
            if (block == null || player == null || gameMode == null) {
                next(Phase.SETTLE);
                return;
            }
            if (ticks == 1) {
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(block), Direction.UP, block, false));
                return;
            }
            Screen screen = minecraft.screen;
            if (screen != null && ticks >= 5) {
                shot.action().accept(screen);
                next(Phase.SETTLE);
            } else if (ticks > PATIENCE) {
                failed.add("no screen opened for " + shot.name() + " at " + block.toShortString());
                next(Phase.SETTLE);
            }
        }

        private void capture(Minecraft minecraft, ScreenshotScene current, ScreenshotScene.Shot shot) {
            Path sceneDir = dir;
            if (sceneDir == null) {
                return;
            }
            Path file = sceneDir.resolve(shot.name() + ".png");
            pending.incrementAndGet();
            Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> Util.ioPool().execute(() -> {
                try (NativeImage written = image) {
                    written.writeToFile(file);
                    minecraft.execute(() -> saved.add(file.toAbsolutePath().normalize().toString()));
                } catch (IOException e) {
                    LOGGER.error("[screenshots] could not write {}", file, e);
                    minecraft.execute(() -> failed.add("could not write " + file));
                } finally {
                    pending.decrementAndGet();
                }
            }));
            LOGGER.info("[screenshots] {}/{}: {}", current.name(), shot.name(), file);
        }

        /** Keeps the view on the shot's pose, should the mouse have moved it. */
        private static void hold(Minecraft minecraft, ScreenshotScene.Shot shot) {
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            player.setYRot(shot.yaw());
            player.setXRot(shot.pitch());
            player.yRotO = shot.yaw();
            player.xRotO = shot.pitch();
            player.yHeadRot = shot.yaw();
            player.yHeadRotO = shot.yaw();
        }

        /**
         * Nothing waits to compile. No minimum count of drawn sections (vanilla's world icon wants more than ten): a
         * view straight down shows fewer. {@link #ENTER_TICKS} after the teleport gives the new sections time to be
         * queued.
         */
        private static boolean rendered(LevelRenderer renderer) {
            return renderer.hasRenderedAllSections();
        }

        private void quit(Minecraft minecraft) {
            phase = Phase.DONE;
            minecraft.stop();
        }

        private static MinecraftServer server(Minecraft minecraft) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server == null) {
                throw new IllegalStateException("no integrated server");
            }
            return server;
        }

        private static void command(MinecraftServer server, String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        private static void delete(Path path) throws IOException {
            if (!Files.exists(path)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(each);
                }
            }
        }
    }
}
