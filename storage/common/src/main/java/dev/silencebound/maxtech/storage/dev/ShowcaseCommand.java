package dev.silencebound.maxtech.storage.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * {@code /maxtech_storage showcase [<station> [here]|list|clear|reset-steps]}: builds the dev showcase next to the
 * caller, or one station around the caller's feet, lists the stations, or removes every build in the dimension (game
 * masters only), and {@code /maxtech_storage steps tick <wall> <step>}, which the chat's tick links of a step wall run
 * ({@link ShowcaseBoards}), plus {@code /maxtech_storage scene ...} for the screenshot scenes ({@link ShowcaseScenes}).
 * The NeoForge glue registers them in dev runs only.
 */
public final class ShowcaseCommand {
    private ShowcaseCommand() {
    }

    /**
     * The {@code maxtech_storage} root with the {@code showcase} and {@code logic} ({@link LogicGraphCommand})
     * literals.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> showcase = Commands.literal("showcase")
                .executes(context -> run(context.getSource(), List.of(ShowcaseStation.values()), false));
        for (ShowcaseStation station : ShowcaseStation.values()) {
            showcase.then(Commands.literal(station.id())
                    .executes(context -> run(context.getSource(), List.of(station), false)).then(Commands
                            .literal("here").executes(context -> run(context.getSource(), List.of(station), true))));
        }
        showcase.then(Commands.literal("list").executes(context -> list(context.getSource())));
        showcase.then(Commands.literal("clear").executes(context -> clear(context.getSource())));
        showcase.then(Commands.literal("reset-steps").executes(context -> resetSteps(context.getSource())));
        // The steps literal is open to everyone: the chat's tick links run it, and a link to a command that needs
        // permissions asks for a confirmation on every click. It only ticks checklist lines on showcase walls.
        LiteralArgumentBuilder<CommandSourceStack> steps = Commands.literal("steps")
                .then(Commands.literal("tick")
                        .then(Commands.argument("wall", BlockPosArgument.blockPos())
                                .then(Commands.argument("step", IntegerArgumentType.integer(1))
                                        .executes(context -> tick(context.getSource(),
                                                BlockPosArgument.getBlockPos(context, "wall"),
                                                IntegerArgumentType.getInteger(context, "step"))))));
        return Commands.literal("maxtech_storage")
                .then(showcase.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)))
                .then(scene().requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)))
                .then(LogicGraphCommand.build().requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)))
                .then(steps);
    }

    /**
     * {@code scene place <name> [<start>]} builds a scene ({@link ShowcaseScenes}) at its site for the start spot
     * (default: the caller's feet), {@code scene save <name> [<corner>]} saves it to its file in the repository
     * (default corner: where it was placed last), and {@code scene view <name>} puts the caller at the scene's camera
     * pose.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> scene() {
        LiteralArgumentBuilder<CommandSourceStack> place = Commands.literal("place");
        LiteralArgumentBuilder<CommandSourceStack> save = Commands.literal("save");
        LiteralArgumentBuilder<CommandSourceStack> view = Commands.literal("view");
        for (ShowcaseScene scene : ShowcaseScene.values()) {
            place.then(Commands.literal(scene.id())
                    .executes(context -> place(context.getSource(), scene,
                            BlockPos.containing(context.getSource().getPosition())))
                    .then(Commands.argument("start", BlockPosArgument.blockPos())
                            .executes(context -> place(context.getSource(), scene,
                                    BlockPosArgument.getBlockPos(context, "start")))));
            save.then(Commands.literal(scene.id())
                    .executes(context -> save(context.getSource(), scene,
                            ShowcaseScenes.placed(context.getSource().getLevel(), scene).orElse(null)))
                    .then(Commands.argument("corner", BlockPosArgument.blockPos())
                            .executes(context -> save(context.getSource(), scene,
                                    BlockPosArgument.getBlockPos(context, "corner")))));
            view.then(Commands.literal(scene.id()).executes(context -> view(context.getSource(), scene)));
        }
        return Commands.literal("scene").then(place).then(save).then(view);
    }

    private static int place(CommandSourceStack source, ShowcaseScene scene, BlockPos start) {
        Optional<Path> root = SceneSites.repoRoot(source.getServer().getServerDirectory());
        ShowcaseScenes.Placed placed = ShowcaseScenes.place(source.getLevel(), scene, start, root.orElse(null),
                source.getPlayer());
        Component from = Component.translatable(
                placed.fromFile() ? Showcase.Messages.SCENE_FROM_FILE : Showcase.Messages.SCENE_FROM_DRAFT);
        source.sendSuccess(() -> Component.translatable(Showcase.Messages.SCENE_PLACED, scene.id(),
                placed.origin().toShortString(), from), true);
        for (String warning : placed.warnings()) {
            source.sendFailure(Component.translatable(Showcase.Messages.WARNING, warning));
        }
        return 1;
    }

    private static int save(CommandSourceStack source, ShowcaseScene scene, @Nullable BlockPos corner) {
        if (corner == null) {
            source.sendFailure(Component.translatable(Showcase.Messages.SCENE_NOT_PLACED, scene.id()));
            return 0;
        }
        Path directory = source.getServer().getServerDirectory();
        Optional<Path> root = SceneSites.repoRoot(directory);
        if (root.isEmpty()) {
            source.sendFailure(
                    Component.translatable(Showcase.Messages.SCENE_NO_REPO, directory.toAbsolutePath().toString()));
            return 0;
        }
        try {
            Path file = ShowcaseScenes.save(source.getLevel(), scene, corner, root.get());
            source.sendSuccess(() -> Component.translatable(Showcase.Messages.SCENE_SAVED, scene.id(),
                    root.get().relativize(file).toString()), true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.translatable(Showcase.Messages.SCENE_SAVE_FAILED, scene.id(),
                    String.valueOf(e.getMessage())));
            return 0;
        }
    }

    private static int view(CommandSourceStack source, ShowcaseScene scene) {
        ServerPlayer player = source.getPlayer();
        Optional<BlockPos> origin = ShowcaseScenes.placed(source.getLevel(), scene);
        if (player == null || origin.isEmpty()) {
            source.sendFailure(Component.translatable(Showcase.Messages.SCENE_NOT_PLACED, scene.id()));
            return 0;
        }
        ShowcaseShots.Pose pose = SceneSites.pose(scene, origin.get().getX(), origin.get().getY(), origin.get().getZ());
        player.teleportTo(source.getLevel(), pose.x(), pose.y() - player.getEyeHeight(), pose.z(), Set.of(), pose.yaw(),
                pose.pitch(), true);
        return 1;
    }

    private static int resetSteps(CommandSourceStack source) {
        int boards = ShowcaseBoards.reset(source.getLevel());
        if (boards == 0) {
            source.sendFailure(Component.translatable(Showcase.Messages.NO_STEPS));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(Showcase.Messages.STEPS_RESET, boards), true);
        return boards;
    }

    private static int tick(CommandSourceStack source, BlockPos wall, int step) {
        Optional<Component> line = ShowcaseBoards.toggle(source.getLevel(), wall, step);
        if (line.isEmpty()) {
            source.sendFailure(Component.translatable(Showcase.Messages.NO_STEP, step));
            return 0;
        }
        source.sendSuccess(() -> line.get(), false);
        return 1;
    }

    /** Each station with its summary; clicking a line builds that station at the clicker's feet. */
    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable(Showcase.Messages.LIST), false);
        for (ShowcaseStation station : ShowcaseStation.values()) {
            String command = "/maxtech_storage showcase " + station.id() + " here";
            Component line = Component
                    .translatable(Showcase.Messages.STATION,
                            Component.literal(station.id()).withStyle(ChatFormatting.AQUA),
                            Component.translatable(station.summaryKey()))
                    .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
            source.sendSuccess(() -> line, false);
        }
        return ShowcaseStation.values().length;
    }

    private static int run(CommandSourceStack source, List<ShowcaseStation> stations, boolean here) {
        ServerPlayer player = source.getPlayer();
        BlockPos origin = origin(BlockPos.containing(source.getPosition()), here);
        Showcase.Build build = Showcase.build(source.getLevel(), origin, stations, player);
        source.sendSuccess(
                () -> Component.translatable(Showcase.Messages.BUILT, stations.size(), origin.toShortString()), true);
        for (ShowcaseStation station : stations) {
            source.sendSuccess(() -> Component.translatable(Showcase.Messages.STATION,
                    Component.translatable(station.nameKey()), Component.translatable(station.summaryKey())), false);
        }
        for (String warning : build.warnings()) {
            source.sendFailure(Component.translatable(Showcase.Messages.WARNING, warning));
        }
        return stations.size();
    }

    /**
     * The first pad's north-west corner for a caller at {@code feet}. Here: the caller stands in the gap of the pad's
     * north fence, looking in. Otherwise the row starts two blocks south-east of the caller, in view, and nobody stands
     * inside it.
     */
    public static BlockPos origin(BlockPos feet, boolean here) {
        return here ? feet.offset(-Showcase.PAD / 2, 0, 0) : feet.offset(2, 0, 2);
    }

    private static int clear(CommandSourceStack source) {
        int builds = Showcase.clear(source.getLevel());
        if (builds == 0) {
            source.sendFailure(Component.translatable(Showcase.Messages.NOTHING));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(Showcase.Messages.CLEARED, builds), true);
        return builds;
    }
}
