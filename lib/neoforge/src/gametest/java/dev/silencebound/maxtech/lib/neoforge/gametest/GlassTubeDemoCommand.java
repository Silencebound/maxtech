package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import java.util.HexFormat;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Dev-only commands for the test tubes (the GameTest source set, so dev runs only):
 * {@code /maxtech_lib_demo tube <pos> <rrggbb>} sets the render value of the conduit at {@code pos}, for looks and
 * model hooks that draw it; {@code /maxtech_lib_demo tube <pos> fluid <fluid> <fill> [<upstream>|none [lane]]} sets its
 * contents (fill 0 to 4, the upstream side by name), which the shell look draws; {@code ... tube <pos> empty} clears
 * them.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID)
public final class GlassTubeDemoCommand {
    private static final DynamicCommandExceptionType BAD_COLOUR = new DynamicCommandExceptionType(
            text -> Component.literal("Not a hex colour (rrggbb): " + text));
    private static final DynamicCommandExceptionType BAD_FLUID = new DynamicCommandExceptionType(
            text -> Component.literal("No such fluid: " + text));
    private static final DynamicCommandExceptionType BAD_SIDE = new DynamicCommandExceptionType(
            text -> Component.literal("Not a side (down, up, north, south, west, east or none): " + text));

    private GlassTubeDemoCommand() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        ArgumentBuilder<CommandSourceStack, ?> upstream = Commands.argument("upstream", StringArgumentType.word())
                .executes(context -> contents(context, false))
                .then(Commands.literal("lane").executes(context -> contents(context, true)));
        ArgumentBuilder<CommandSourceStack, ?> fluid = Commands.literal("fluid")
                .then(Commands.argument("fluid", IdentifierArgument.id())
                        .then(Commands.argument("fill", IntegerArgumentType.integer(0, ConduitContents.FULL))
                                .executes(context -> contents(context, false)).then(upstream)));
        event.getDispatcher()
                .register(Commands.literal("maxtech_lib_demo")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("tube")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).then(fluid)
                                        .then(Commands.literal("empty")
                                                .executes(context -> setContents(context, ConduitContents.EMPTY)))
                                        .then(Commands.argument("rgb", StringArgumentType.word()).executes(context -> {
                                            String text = StringArgumentType.getString(context, "rgb");
                                            int rgb;
                                            try {
                                                rgb = HexFormat.fromHexDigits(text);
                                            } catch (IllegalArgumentException e) {
                                                throw BAD_COLOUR.create(text);
                                            }
                                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                            boolean set = Conduits.host(context.getSource().getLevel(), pos)
                                                    .map(host -> {
                                                        host.setRenderValue(rgb);
                                                        return true;
                                                    }).orElse(false);
                                            return report(context, set, "Render value set");
                                        })))));
    }

    private static int contents(CommandContext<CommandSourceStack> context, boolean lane)
            throws CommandSyntaxException {
        Identifier id = IdentifierArgument.getId(context, "fluid");
        Optional<Fluid> fluid = BuiltInRegistries.FLUID.getOptional(id)
                .filter(found -> !found.defaultFluidState().isEmpty());
        if (fluid.isEmpty()) {
            throw BAD_FLUID.create(id);
        }
        int fill = IntegerArgumentType.getInteger(context, "fill");
        Optional<Direction> upstream = Optional.empty();
        if (context.getNodes().stream().anyMatch(node -> "upstream".equals(node.getNode().getName()))) {
            String side = StringArgumentType.getString(context, "upstream");
            if (!"none".equals(side)) {
                Direction direction = Direction.byName(side);
                if (direction == null) {
                    throw BAD_SIDE.create(side);
                }
                upstream = Optional.of(direction);
            }
        }
        return setContents(context, new ConduitContents(fluid, fill, upstream, lane));
    }

    private static int setContents(CommandContext<CommandSourceStack> context, ConduitContents contents)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        boolean set = Conduits.host(context.getSource().getLevel(), pos).map(host -> {
            host.setContents(contents);
            return true;
        }).orElse(false);
        return report(context, set, "Contents set");
    }

    private static int report(CommandContext<CommandSourceStack> context, boolean set, String done) {
        context.getSource().sendSystemMessage(Component.literal(set ? done : "No conduit there"));
        return set ? 1 : 0;
    }
}
