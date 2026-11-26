package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentDemoScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Registers the client command {@code /maxtech_lib accent_demo}, which opens {@link AccentDemoScreen}, in dev runs
 * only: FML reports production for every game started from a built jar, so players never see it. NeoForge's chat screen
 * stays closed when a command opens another screen, so the command can open it directly.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
public final class AccentDemoCommand {
    private AccentDemoCommand() {
    }

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.isProduction()) {
            return;
        }
        event.getDispatcher()
                .register(Commands.literal(MaxtechLib.MOD_ID).then(Commands.literal("accent_demo").executes(context -> {
                    Minecraft.getInstance().setScreen(new AccentDemoScreen());
                    return 1;
                })));
    }
}
