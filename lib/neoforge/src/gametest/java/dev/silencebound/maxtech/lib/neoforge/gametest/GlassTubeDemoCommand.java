package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import java.util.HexFormat;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /maxtech_lib_demo tube <pos> <rrggbb>} sets the render value of the conduit at {@code pos} (0 to empty a
 * {@link GlassTubeDemoClient} tube). Part of the GameTest source set, so it exists in dev runs only.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID)
public final class GlassTubeDemoCommand {
    private GlassTubeDemoCommand() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("maxtech_lib_demo")
                        .then(Commands.literal("tube").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("rgb", StringArgumentType.word()).executes(context -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                    int rgb = HexFormat.fromHexDigits(StringArgumentType.getString(context, "rgb"));
                                    boolean set = Conduits.host(context.getSource().getLevel(), pos).map(host -> {
                                        host.setRenderValue(rgb);
                                        return true;
                                    }).orElse(false);
                                    context.getSource().sendSystemMessage(
                                            Component.literal(set ? "Render value set" : "No conduit there"));
                                    return set ? 1 : 0;
                                })))));
    }
}
