package dev.silencebound.maxtech.storage.crafting.provider;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/** The tooltip summary of a provider's item, the same for the block and the part (see {@link ProviderLogic#write}). */
public final class ProviderItems {
    private ProviderItems() {
    }

    /** How many patterns the item holds, whether blocking is off and its lock mode. */
    public static void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        long held = contents == null ? 0 : contents.nonEmptyItemCopyStream().count();
        if (held > 0) {
            lines.accept(Component.translatable(ProviderContent.TOOLTIP_PATTERNS_KEY, held));
        }
        if (Boolean.TRUE.equals(stack.get(ProviderContent.nonBlockingType()))) {
            lines.accept(Component.translatable(ProviderContent.TOOLTIP_NON_BLOCKING_KEY));
        }
        LockMode lock = stack.get(ProviderContent.lockType());
        if (lock != null && lock != LockMode.NONE) {
            lines.accept(Component.translatable(ProviderContent.LOCK_MODE_KEY,
                    Component.translatable(lock.translationKey())));
        }
    }

    /** The provider block's item, with the summary in its tooltip. */
    public static final class ProviderBlockItem extends BlockItem {
        public ProviderBlockItem(Block block, Properties properties) {
            super(block, properties);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            appendTooltip(stack, lines);
        }
    }
}
