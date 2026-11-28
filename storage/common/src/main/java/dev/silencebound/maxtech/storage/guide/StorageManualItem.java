package dev.silencebound.maxtech.storage.guide;

import dev.silencebound.maxtech.lib.api.guide.GuideBook;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * The Storage manual, the book for Storage's GuideME guide. As a {@link GuideBook}, Lib opens {@link #guide()} on the
 * using client; the server only sees the use succeed.
 */
public final class StorageManualItem extends Item implements GuideBook {
    StorageManualItem(Properties properties) {
        super(properties);
    }

    /** The guide this book opens: Storage's guide. */
    @Override
    public Identifier guide() {
        return GuideContent.GUIDE;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(GuideContent.DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
