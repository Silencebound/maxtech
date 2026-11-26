package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /maxtech_storage_puzzle <puzzle> <difficulty>}: starts a puzzle session with a random seed for a test node, so
 * the screens can be tried before research opens them. Operators only, and only in dev runs: the gametest source set
 * joins every dev run but never the jar.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PuzzleDebugCommand {
    private PuzzleDebugCommand() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("maxtech_storage_puzzle")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (PuzzleType<?> type : List.of(PuzzleContent.SIGNAL_ROUTING, PuzzleContent.MASK_ALIGNMENT,
                PuzzleContent.BYTE_PACKING, PuzzleContent.WAVEFORM_TUNING)) {
            root.then(Commands.literal(type.id().getPath())
                    .then(Commands.argument("difficulty", IntegerArgumentType.integer(PuzzleType.EASY, PuzzleType.HARD))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                PuzzleSessions.start(player, MaxtechStorage.id("debug"), type.id(),
                                        player.getRandom().nextLong(),
                                        IntegerArgumentType.getInteger(context, "difficulty"));
                                return 1;
                            })));
        }
        event.getDispatcher().register(root);
    }
}
