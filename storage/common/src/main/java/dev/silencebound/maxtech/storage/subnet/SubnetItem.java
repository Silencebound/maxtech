package dev.silencebound.maxtech.storage.subnet;

import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The subnet controller's item. It keeps the block's settings and FE in vanilla's block entity data, and the tooltip
 * names the mode, priority and stored FE (CLAUDE.md: stored state shows on the item). Placing it needs the Subnets
 * research for the placing player's team; without a player it is not checked, as for controller expansions.
 */
public final class SubnetItem extends BlockItem {
    public SubnetItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (player != null && !ResearchUnlocks.isUnlocked(player, ResearchGates.SUBNET_CONTROLLER)) {
            // A GameTest player has no connection to send the message to.
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
                serverPlayer.sendOverlayMessage(Component.translatable(ExpansionContent.NEEDS_RESEARCH_KEY,
                        Component.translatable(ResearchNode.unlockKey(ResearchGates.SUBNET_CONTROLLER))));
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
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTagWithoutId();
        SubnetMode mode = SubnetMode.byId(tag.getIntOr("mode", SubnetMode.MERGED.id()));
        lines.accept(Component.translatable(SubnetTexts.TOOLTIP_MODE, Component.translatable(mode.translationKey()),
                tag.getIntOr("priority", 0), StorageSaveFormats.savedMu(tag)));
    }
}
