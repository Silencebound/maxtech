package dev.silencebound.maxtech.storage.crafting.cpu;

import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * A crafting CPU core's item. Placing a tier-2 or tier-3 core needs its research unlock for the placing player's team
 * ({@link CpuTier#unlock()}); without a player it is not checked, as for the subnet controller.
 */
public final class CpuCoreItem extends BlockItem {
    public CpuCoreItem(CpuCoreBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Player player = context.getPlayer();
        Identifier unlock = ((CpuCoreBlock) getBlock()).tier().unlock();
        if (player != null && unlock != null && !ResearchUnlocks.isUnlocked(player, unlock)) {
            // A GameTest player has no connection to send the message to.
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
                serverPlayer.sendOverlayMessage(Component.translatable(ExpansionContent.NEEDS_RESEARCH_KEY,
                        Component.translatable(ResearchNode.unlockKey(unlock))));
            }
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }
}
