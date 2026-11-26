package dev.silencebound.maxtech.storage.research;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * A research skip token found in structure chests: using it adds one skip token to the user's team, which can then
 * complete any research node whose requirements are done.
 */
public final class SkipTokenItem extends Item {
    public static final String USED_KEY = "message.maxtech_storage.research.token_used";

    public SkipTokenItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            ResearchTeam team = Research.team(serverPlayer, true);
            Research.addToken(serverPlayer.level().getServer(), team);
            player.getItemInHand(hand).consume(1, player);
            // A player built by a GameTest has no connection to send to.
            if (serverPlayer.connection != null) {
                serverPlayer.sendSystemMessage(Component.translatable(USED_KEY, team.tokens()), true);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
