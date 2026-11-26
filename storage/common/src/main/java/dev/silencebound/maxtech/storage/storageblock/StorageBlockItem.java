package dev.silencebound.maxtech.storage.storageblock;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.recovery.CellRecovery;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * A storage block's item. It carries the block's cell id, priority and stats ({@link StorageBlockData}); the tooltip
 * shows the bytes used and the kinds held as of when the block was broken. Burnt, blown up or pricked to death, its
 * contents go to the recovery bin of the block's last network, as a cell item's do (M7C item 20).
 */
public final class StorageBlockItem extends BlockItem {
    public StorageBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void onDestroyed(ItemEntity entity, DamageSource source) {
        super.onDestroyed(entity, source);
        StorageBlockData data = entity.getItem().get(StorageBlockContent.DATA.get());
        if (data != null && entity.level() instanceof ServerLevel level) {
            CellRecovery.lost(level, data.id());
        }
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        StorageBlockData data = stack.get(StorageBlockContent.DATA.get());
        long total = data != null && data.totalBytes() > 0
                ? data.totalBytes()
                : getBlock() instanceof StorageBlock block ? StorageServerConfig.loadedOr(block.tier()::bytes, 0) : 0;
        lines.accept(
                StorageBlockTexts.bytes(data == null ? 0 : data.usedBytes(), total).withStyle(ChatFormatting.GRAY));
        lines.accept(StorageBlockTexts.kinds(data == null ? 0 : data.kinds()).withStyle(ChatFormatting.GRAY));
        if (data != null && data.kept() > 0) {
            lines.accept(Component.translatable(StorageBlockTexts.KEPT, data.kept()).withStyle(ChatFormatting.GOLD));
        }
        if (data != null && data.priority() != 0) {
            lines.accept(StorageBlockTexts.priority(data.priority()).withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.accept(Component.translatable(StorageBlockTexts.HOLDS).withStyle(ChatFormatting.DARK_GRAY));
    }
}
