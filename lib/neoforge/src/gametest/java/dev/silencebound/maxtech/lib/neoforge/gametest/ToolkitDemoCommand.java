package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.silencebound.maxtech.lib.MaxtechLib;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /maxtech_lib_demo toolkit} (gamemaster): puts the {@linkplain ToolkitDemo toolkit demo machine} two blocks in
 * front of the player, in the first spot of the line ahead that is free, and tells them where. Dev runs only.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ToolkitDemoCommand {
    private ToolkitDemoCommand() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("maxtech_lib_demo")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("toolkit").executes(context -> place(context.getSource()))));
    }

    private static int place(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);
        for (int tries = 0; tries < 4 && !level.getBlockState(pos).canBeReplaced(); tries++) {
            pos = pos.above();
        }
        BlockState state = ToolkitDemo.BLOCK.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        BlockPos placed = pos;
        source.sendSuccess(
                () -> Component.literal("Toolkit demo machine at " + placed.toShortString()
                        + ": right click it (input takes raw iron, copper or gold, fuel takes coal or charcoal)"),
                false);
        return 1;
    }
}
