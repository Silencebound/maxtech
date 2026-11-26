package dev.silencebound.maxtech.storage.neoforge.dev;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.dev.ShowcaseBoards;
import dev.silencebound.maxtech.storage.dev.ShowcaseCommand;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Registers {@code /maxtech_storage showcase} ({@link ShowcaseCommand}) in dev runs only: FML reports production for
 * every game started from a built jar, so players never see it. Also runs the showcase's queued work and step walls
 * each tick, and turns a right-click on a step wall into the wall's step list in chat (instead of the display's
 * settings screen); without a showcase build both cost one empty check.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ShowcaseCommands {
    private ShowcaseCommands() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        if (!FMLEnvironment.isProduction()) {
            event.getDispatcher().register(ShowcaseCommand.build());
        }
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        Showcase.tick();
    }

    @SubscribeEvent
    static void stopped(ServerStoppedEvent event) {
        Showcase.forget();
    }

    /** A right-click on a step wall lists its steps, with tick links for those ticked by hand. */
    @SubscribeEvent
    static void useWall(PlayerInteractEvent.RightClickBlock event) {
        if (FMLEnvironment.isProduction() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Optional<List<Component>> lines = ShowcaseBoards.describe(level, event.getPos());
        if (lines.isEmpty()) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            for (Component line : lines.get()) {
                player.sendSystemMessage(line);
            }
        }
    }
}
