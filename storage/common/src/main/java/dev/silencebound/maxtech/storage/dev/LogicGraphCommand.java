package dev.silencebound.maxtech.storage.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.logic.CompileResult;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;

/**
 * {@code /maxtech_storage logic <pos> <graph> <json>}: saves a graph written as JSON (the saved graph format) into the
 * Logic Controller at {@code pos} as graph {@code graph}, for trying graphs before the node editor exists (M7L area F).
 * {@code /maxtech_storage logic <pos> <graph> remove} takes one out. Dev runs only, like the showcase; the messages are
 * plain English on purpose, since players never see them.
 */
public final class LogicGraphCommand {
    private LogicGraphCommand() {
    }

    /** The {@code logic} literal, for the {@code maxtech_storage} root. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("logic").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("graph", IntegerArgumentType.integer(1, 64))
                        .then(Commands.literal("remove")
                                .executes(context -> remove(context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        IntegerArgumentType.getInteger(context, "graph") - 1)))
                        .then(Commands.argument("json", StringArgumentType.greedyString())
                                .executes(context -> load(context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        IntegerArgumentType.getInteger(context, "graph") - 1,
                                        StringArgumentType.getString(context, "json"))))));
    }

    private static int load(CommandSourceStack source, BlockPos pos, int index, String json) {
        if (!(source.getLevel().getBlockEntity(pos) instanceof LogicControllerBlockEntity controller)) {
            source.sendFailure(Component.literal("No Logic Controller at " + pos.toShortString()));
            return 0;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            source.sendFailure(Component.literal("Not JSON: " + e.getMessage()));
            return 0;
        }
        DataResult<LogicGraph<LogicKey>> graph = LogicKey.GRAPH_CODEC
                .parse(RegistryOps.create(JsonOps.INSTANCE, source.registryAccess()), parsed);
        if (graph.error().isPresent()) {
            source.sendFailure(Component.literal("Not a graph: " + graph.error().get().message()));
            return 0;
        }
        String name = "Graph " + (index + 1);
        Optional<CompileResult<LogicKey>> result = controller.worker().setGraph(index, name, graph.getOrThrow());
        if (result.isEmpty()) {
            source.sendFailure(Component.literal("Graph number out of range"));
            return 0;
        }
        if (result.get().paused()) {
            for (GraphError error : result.get().errors()) {
                source.sendFailure(Component.literal("Node " + error.node() + " " + error.port() + ": "
                        + Component.translatable(error.key(), error.arg()).getString()));
            }
            source.sendSuccess(() -> Component.literal("Saved, but paused until the errors are fixed"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Saved and running: " + name), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, BlockPos pos, int index) {
        if (!(source.getLevel().getBlockEntity(pos) instanceof LogicControllerBlockEntity controller)
                || index >= controller.worker().size()) {
            source.sendFailure(Component.literal("No such graph"));
            return 0;
        }
        controller.worker().removeGraph(index);
        source.sendSuccess(() -> Component.literal("Removed"), true);
        return 1;
    }
}
