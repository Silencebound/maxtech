package dev.silencebound.maxtech.storage.bulk;

import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.save.KeptValue;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/**
 * The bulk drive's item. It carries the drive's contents and priority as components, and the tooltip names both
 * (CLAUDE.md: stored state shows on the item). Placing it needs {@link BulkContent#UNLOCK} for the placing player's
 * team; without a player it is not checked, as for the subnet controller.
 */
public final class BulkDriveItem extends BlockItem {
    public BulkDriveItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (player != null && !ResearchUnlocks.isUnlocked(player, BulkContent.UNLOCK)) {
            // A GameTest player has no connection to send the message to.
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
                serverPlayer.sendOverlayMessage(Component.translatable(BulkTexts.NEEDS_RESEARCH,
                        Component.translatable(ResearchNode.unlockKey(BulkContent.UNLOCK))));
            }
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        KeptValue<BulkContents> contents = stack.get(BulkContent.CONTENTS.get());
        BulkContents readable = contents == null ? null : contents.value();
        if (readable != null) {
            lines.accept(BulkTexts.contents(readable));
        } else if (contents != null || BulkDriveBlockEntity.keptOnItem(stack)) {
            lines.accept(Component.translatable(StorageCellItem.KEPT_KEY, 1).withStyle(ChatFormatting.GOLD));
        }
        int priority = stack.getOrDefault(BulkContent.PRIORITY.get(), 0);
        if (priority != 0) {
            lines.accept(BulkTexts.priority(priority));
        }
    }
}
