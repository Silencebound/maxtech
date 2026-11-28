package dev.silencebound.maxtech.lib.neoforge;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.profile.ProfileCommand;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Registers {@code /maxtech_lib}, for game masters: {@code profile} ({@link ProfileCommand}). */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class LibCommands {
    private LibCommands() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(MaxtechLib.MOD_ID)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(ProfileCommand.build()));
    }
}
