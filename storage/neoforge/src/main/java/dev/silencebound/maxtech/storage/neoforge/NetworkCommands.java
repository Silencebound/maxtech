package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.admin.LogicAdminCommand;
import dev.silencebound.maxtech.storage.network.NetworkCommand;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers {@code /maxtech network} ({@link NetworkCommand}) and {@code /maxtech logic} ({@link LogicAdminCommand}).
 * Brigadier merges literals of the same name, so another mod of the suite may add its own {@code /maxtech} subcommands
 * beside it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkCommands {
    private NetworkCommands() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("maxtech").then(NetworkCommand.build()).then(LogicAdminCommand.build()));
    }
}
