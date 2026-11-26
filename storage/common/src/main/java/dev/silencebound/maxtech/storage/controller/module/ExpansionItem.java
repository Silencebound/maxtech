package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/**
 * The item of a controller expansion or of a network device that was one (M7R item 4): refuses to place a block whose
 * {@link ModuleKind#unlock()} the placing player's team has not researched, with an action-bar message. Placed without
 * a player (a dispenser or another mod's placer) it is not checked, as for energy cells.
 */
public final class ExpansionItem extends BlockItem {
    private final ModuleKind kind;

    public ExpansionItem(Block block, ModuleKind kind, Properties properties) {
        super(block, properties);
        this.kind = kind;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Identifier unlock = kind.unlock();
        Player player = context.getPlayer();
        if (unlock != null && player != null && !ResearchUnlocks.isUnlocked(player, unlock)) {
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
