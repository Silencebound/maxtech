package dev.silencebound.maxtech.storage.neoforge.dev;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.dev.SceneSites;
import dev.silencebound.maxtech.storage.dev.ShowcaseCommand;
import dev.silencebound.maxtech.storage.dev.ShowcaseScene;
import dev.silencebound.maxtech.storage.dev.ShowcaseShots;
import dev.silencebound.maxtech.storage.dev.ShowcaseStation;
import dev.silencebound.maxtech.storage.neoforge.compat.ae2.Ae2Lang;
import dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage.RefinedStorageCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The client command {@code /maxtech_storage shoot <station|all|scenes>} and {@code shoot scene <name>}, dev runs only
 * (FML reports production for every game started from a built jar): takes the scene shots of
 * {@code docs/release/screenshots.md} into {@code screenshots/maxtech_<station>.png} or
 * {@code screenshots/maxtech_scene_<name>.png} of the game directory. A scene ({@link ShowcaseScene}) is placed by the
 * server's {@code scene place} at its site for the player's starting spot, and {@code scene view} puts the camera at
 * its pose, as the server alone knows the floor's height. For each station it teleports the player in spectator mode to
 * the station's camera pose ({@link ShowcaseShots}), builds the station through the server's {@code showcase} command,
 * waits until the visible sections are compiled and faded in, and saves the frame through vanilla's screenshot code.
 * Stations go one after another, a step per client tick, so the render thread never waits. Each station gets its own
 * spot {@link #SPACING} blocks east of the last, so no shot shows a neighbour. HUD, camera, game mode and the player's
 * place come back at the end. Client thread only.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
public final class ShootClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Blocks between the spots of two stations; far more than a shot's view reaches sideways. */
    private static final int SPACING = 64;
    /** Ticks after a teleport before the build, so the spot's chunks load first. */
    private static final int TELEPORT_TICKS = 20;
    /** Ticks after the build: networks form, stock goes in, the bridges' foreign networks boot. */
    private static final int BUILD_TICKS = 60;
    /** Ticks a station may take to render before the run gives up. */
    private static final int RENDER_PATIENCE = 600;
    /** Ticks the run waits for the last files to be written. */
    private static final int SAVE_PATIENCE = 200;
    private static final int MIN_WIDTH = 1920;
    private static final int MIN_HEIGHT = 1080;
    /** Ticks after {@code scene view} before the player's position is the scene's camera pose. */
    private static final int VIEW_TICKS = 10;

    private static @Nullable Run run;

    private ShootClient() {
    }

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.isProduction()) {
            return;
        }
        LiteralArgumentBuilder<CommandSourceStack> shoot = Commands.literal("shoot");
        shoot.then(
                Commands.literal("all").executes(context -> start(context.getSource(), stations(ShowcaseShots.SHOTS))));
        for (ShowcaseShots.Shot shot : ShowcaseShots.SHOTS) {
            shoot.then(Commands.literal(shot.station().id())
                    .executes(context -> start(context.getSource(), stations(List.of(shot)))));
        }
        // Scenes under their own literal: two of them share a name with a station.
        List<Target> scenes = new ArrayList<>();
        LiteralArgumentBuilder<CommandSourceStack> scene = Commands.literal("scene");
        for (ShowcaseScene each : ShowcaseScene.values()) {
            Target target = Target.of(each);
            scenes.add(target);
            scene.then(Commands.literal(each.id()).executes(context -> start(context.getSource(), List.of(target))));
        }
        shoot.then(Commands.literal("scenes").executes(context -> start(context.getSource(), scenes)));
        shoot.then(scene);
        event.getDispatcher().register(Commands.literal(MaxtechStorage.MOD_ID).then(shoot));
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Run current = run;
        if (current == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            stop(minecraft);
            return;
        }
        // A screen in front would end up in the shot, and a paused game builds nothing: wait.
        if (minecraft.screen != null || minecraft.isPaused()) {
            return;
        }
        current.tick(minecraft, player);
    }

    @SubscribeEvent
    static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stop(Minecraft.getInstance());
    }

    private static List<Target> stations(List<ShowcaseShots.Shot> shots) {
        List<Target> targets = new ArrayList<>();
        for (ShowcaseShots.Shot shot : shots) {
            targets.add(Target.of(shot));
        }
        return targets;
    }

    /**
     * One shot of a run: a showcase station ({@code shot}) or a scene ({@code scene}), exactly one of them set. The
     * file is {@code maxtech_<name>.png}.
     */
    private record Target(String name, ShowcaseShots.@Nullable Shot shot, @Nullable ShowcaseScene scene) {
        static Target of(ShowcaseShots.Shot shot) {
            return new Target(shot.station().id(), shot, null);
        }

        static Target of(ShowcaseScene scene) {
            return new Target("scene_" + scene.id(), null, scene);
        }
    }

    private static int start(CommandSourceStack source, List<Target> requested) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (run != null || player == null || gameMode == null) {
            source.sendFailure(Component.translatable(Showcase.Messages.SHOOT_BUSY));
            return 0;
        }
        boolean bridges = ModList.get().isLoaded(Ae2Lang.MOD_ID) && ModList.get().isLoaded(RefinedStorageCompat.MOD_ID);
        List<Target> shots = new ArrayList<>();
        for (Target target : requested) {
            ShowcaseShots.Shot shot = target.shot();
            if (shot != null && shot.station() == ShowcaseStation.BRIDGES && !bridges) {
                source.sendFailure(Component.translatable(Showcase.Messages.SHOOT_SKIPPED, shot.station().id()));
            } else {
                shots.add(target);
            }
        }
        if (shots.isEmpty()) {
            return 0;
        }
        RenderTarget target = minecraft.getMainRenderTarget();
        if (target.width < MIN_WIDTH || target.height < MIN_HEIGHT) {
            LOGGER.warn("Shoot: the window is {}x{}, smaller than {}x{}; the shots will be too", target.width,
                    target.height, MIN_WIDTH, MIN_HEIGHT);
        }
        run = new Run(minecraft, player, gameMode.getPlayerMode(), shots);
        String started = shots.get(0).scene() != null ? Showcase.Messages.SHOOT_SCENES : Showcase.Messages.SHOOT_START;
        source.sendSuccess(() -> Component.translatable(started, shots.size()), false);
        return shots.size();
    }

    /** Ends a run early (log-out, lost player) and gives the HUD and camera back. */
    private static void stop(Minecraft minecraft) {
        Run current = run;
        run = null;
        if (current != null) {
            current.restoreView(minecraft);
        }
    }

    private enum Phase {
        TELEPORT, BUILD, RENDER, SETTLE, SAVE
    }

    /** One {@code shoot} run: the shots left, where the player was and what the HUD looked like before. */
    private static final class Run {
        private final List<Target> shots;
        private final BlockPos start;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final GameType mode;
        private final boolean hideGui;
        private final CameraType camera;
        private final List<Component> results = new ArrayList<>();
        private int index;
        private Phase phase = Phase.TELEPORT;
        private int ticks;
        private int taken;
        private ShowcaseShots.Pose pose;
        private BlockPos origin;

        Run(Minecraft minecraft, LocalPlayer player, GameType mode, List<Target> shots) {
            this.shots = shots;
            this.start = player.blockPosition();
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.mode = mode;
            this.hideGui = minecraft.options.hideGui;
            this.camera = minecraft.options.getCameraType();
            this.origin = start;
            this.pose = pose(shots.get(0), start);
            minecraft.options.hideGui = true;
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            command(player, "gamemode spectator");
            command(player, "time set 6000");
            command(player, "weather clear");
        }

        void tick(Minecraft minecraft, LocalPlayer player) {
            if (phase != Phase.SAVE) {
                hold(player);
            }
            ticks++;
            switch (phase) {
                case TELEPORT -> {
                    if (ticks == 1) {
                        enter(player);
                    } else if (ticks >= TELEPORT_TICKS) {
                        next(Phase.BUILD);
                    }
                }
                case BUILD -> {
                    ShowcaseScene scene = shots.get(index).scene();
                    if (scene == null) {
                        if (ticks == 1) {
                            command(player,
                                    String.format(Locale.ROOT, "execute positioned %d %d %d run %s showcase %s",
                                            start.getX() + index * SPACING, start.getY(), start.getZ(),
                                            MaxtechStorage.MOD_ID, station(index).id()));
                        } else if (ticks >= BUILD_TICKS) {
                            next(Phase.RENDER);
                        }
                    } else if (ticks == 1) {
                        // The server finds the floor's height, so it places the scene and puts the camera there.
                        command(player, String.format(Locale.ROOT, "%s scene place %s %d %d %d", MaxtechStorage.MOD_ID,
                                scene.id(), start.getX(), start.getY(), start.getZ()));
                        command(player,
                                String.format(Locale.ROOT, "%s scene view %s", MaxtechStorage.MOD_ID, scene.id()));
                    } else if (ticks == VIEW_TICKS) {
                        origin = BlockPos.containing(player.getX() - scene.eyeX(), player.getEyeY() - scene.eyeY(),
                                player.getZ() - scene.eyeZ());
                    } else if (ticks >= Math.max(BUILD_TICKS, scene.warmupTicks())) {
                        next(Phase.RENDER);
                    }
                }
                case RENDER -> {
                    if (rendered(minecraft.levelRenderer)) {
                        next(Phase.SETTLE);
                    } else if (ticks > RENDER_PATIENCE) {
                        LOGGER.warn("Shoot: {} did not finish rendering; taking it as it is", shots.get(index).name());
                        next(Phase.SETTLE);
                    }
                }
                case SETTLE -> {
                    // Freshly compiled sections fade in over the option's time; a few frames more for the animations.
                    int fade = (int) Math.ceil(minecraft.options.chunkSectionFadeInTime().get() * 20);
                    if (ticks >= 10 + fade && minecraft.levelRenderer.hasRenderedAllSections()) {
                        capture(minecraft);
                        index++;
                        if (index < shots.size()) {
                            next(Phase.TELEPORT);
                        } else {
                            next(Phase.SAVE);
                        }
                    }
                }
                case SAVE -> {
                    if (results.size() >= taken || ticks > SAVE_PATIENCE) {
                        finish(minecraft, player);
                    }
                }
            }
        }

        private void next(Phase phase) {
            this.phase = phase;
            this.ticks = 0;
        }

        private ShowcaseStation station(int at) {
            ShowcaseShots.Shot shot = shots.get(at).shot();
            if (shot == null) {
                throw new IllegalStateException("not a station shot: " + shots.get(at).name());
            }
            return shot.station();
        }

        /**
         * The camera pose of {@code target} for the spot {@code spot}. A scene's floor height is the server's to find,
         * so its pose takes the spot's height until {@code scene view} puts the camera in place.
         */
        private static ShowcaseShots.Pose pose(Target target, BlockPos spot) {
            ShowcaseScene scene = target.scene();
            ShowcaseShots.Shot shot = target.shot();
            if (scene != null) {
                return SceneSites.pose(scene, SceneSites.originX(scene, spot.getX()), spot.getY(),
                        SceneSites.originZ(spot.getZ()));
            }
            if (shot == null) {
                throw new IllegalStateException("a target without a shot or a scene: " + target.name());
            }
            BlockPos corner = ShowcaseCommand.origin(spot, false);
            return ShowcaseShots.pose(shot, corner.getX(), corner.getY(), corner.getZ(), Showcase.PAD);
        }

        /**
         * Works out the current target's spot and pose and teleports the player's eye there; for a scene, near enough
         * that its chunks load before the server places it.
         */
        private void enter(LocalPlayer player) {
            Target target = shots.get(index);
            ShowcaseScene scene = target.scene();
            if (scene != null) {
                origin = new BlockPos(SceneSites.originX(scene, start.getX()), start.getY(),
                        SceneSites.originZ(start.getZ()));
                pose = pose(target, start);
            } else {
                origin = ShowcaseCommand.origin(start.offset(index * SPACING, 0, 0), false);
                pose = pose(target, start.offset(index * SPACING, 0, 0));
            }
            command(player, String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", pose.x(),
                    pose.y() - player.getEyeHeight(), pose.z(), pose.yaw(), pose.pitch()));
        }

        /** Keeps the view on the pose, should the mouse have moved. */
        private void hold(LocalPlayer player) {
            player.setYRot(pose.yaw());
            player.setXRot(pose.pitch());
            player.yRotO = pose.yaw();
            player.xRotO = pose.pitch();
            player.yHeadRot = pose.yaw();
            player.yHeadRotO = pose.yaw();
        }

        /**
         * Whether nothing waits to compile and the pad's or scene's corners and centre, floor and top, are compiled and
         * visible. Vanilla's world icon also asks for more than ten rendered sections, so a frame right after loading
         * counts as not ready.
         */
        private boolean rendered(LevelRenderer renderer) {
            if (!renderer.hasRenderedAllSections() || renderer.countRenderedSections() <= 10) {
                return false;
            }
            boolean scene = shots.get(index).scene() != null;
            int width = scene ? ShowcaseScene.WIDTH : Showcase.PAD;
            int depth = scene ? ShowcaseScene.DEPTH : Showcase.PAD;
            for (int dy = 0; dy <= 4; dy += 4) {
                for (int dx = 0; dx < width; dx += width / 2) {
                    for (int dz = 0; dz < depth; dz += depth / 2) {
                        if (!renderer.isSectionCompiledAndVisible(origin.offset(dx, dy, dz))) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Saves the last frame, as F2 does, under a fixed name; the file is overwritten on the next run. */
        private void capture(Minecraft minecraft) {
            String name = "maxtech_" + shots.get(index).name() + ".png";
            taken++;
            Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), 1,
                    message -> minecraft.execute(() -> results.add(message)));
        }

        private void finish(Minecraft minecraft, LocalPlayer player) {
            run = null;
            restoreView(minecraft);
            command(player, "gamemode " + mode.getName());
            command(player, String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", x, y, z, yaw, pitch));
            say(minecraft, Component.translatable(Showcase.Messages.SHOOT_DONE, results.size()));
            for (Component result : results) {
                say(minecraft, result);
            }
            if (results.size() < taken) {
                say(minecraft, Component.translatable(Showcase.Messages.SHOOT_MISSING, taken - results.size())
                        .withStyle(ChatFormatting.RED));
            }
        }

        void restoreView(Minecraft minecraft) {
            minecraft.options.hideGui = hideGui;
            minecraft.options.setCameraType(camera);
        }

        private static void command(LocalPlayer player, String command) {
            player.connection.sendCommand(command);
        }

        private static void say(Minecraft minecraft, Component line) {
            minecraft.gui.getChat().addClientSystemMessage(line);
        }
    }
}
