package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * An antenna's item: refuses to place an antenna until the placing player's team has the Antennas research, with an
 * action-bar message, as controller expansions do. Placed without a player it is not checked.
 */
public final class AntennaItem extends BlockItem {
    public AntennaItem(AntennaBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (player != null && !ResearchUnlocks.isUnlocked(player, ResearchGates.ANTENNA)) {
            // A GameTest player has no connection to send the message to.
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
                serverPlayer.sendOverlayMessage(Component.translatable(SignalContent.NEEDS_RESEARCH_KEY,
                        Component.translatable(ResearchNode.unlockKey(ResearchGates.ANTENNA))));
            }
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }
}
