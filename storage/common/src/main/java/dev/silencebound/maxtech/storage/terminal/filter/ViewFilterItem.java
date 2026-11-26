package dev.silencebound.maxtech.storage.terminal.filter;

import dev.silencebound.maxtech.storage.cell.CellFilter;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * The view filter (B09 item 24): use it in the main hand to set up its list ({@link ViewFilterMenu}), then put it in a
 * terminal's filter slots. Its list is a {@link CellFilter}, so it takes the same entries as a cell's partition (items,
 * fluids, tags, fuzzy matching) and shows or hides what they match. Stacks to one, like a cell.
 */
public final class ViewFilterItem extends Item {
    public ViewFilterItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** {@code stack}'s filter; blank for a new one or for anything that is no view filter. */
    public static CellFilter filter(ItemStack stack) {
        return stack.getOrDefault(ViewFilterContent.FILTER.get(), CellFilter.EMPTY);
    }

    /** Whether {@code stack} is a view filter. */
    public static boolean is(ItemStack stack) {
        return stack.getItem() instanceof ViewFilterItem;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            // The menu reads the filter off the main hand, which its slot sync covers; the offhand it would not.
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            ViewFilterMenu.open(serverPlayer, player.getItemInHand(hand).getHoverName());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        CellFilter filter = filter(stack);
        if (filter.isBlank()) {
            lines.accept(Component.translatable(ViewFilterContent.BLANK_KEY).withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.accept(Component.translatable(filter.deny() ? ViewFilterContent.HIDES_KEY : ViewFilterContent.SHOWS_KEY)
                .withStyle(ChatFormatting.GRAY));
        lines.accept(Component.translatable(ViewFilterContent.ENTRIES_KEY,
                filter.entries().size() + filter.kept().size(), CellFilter.SIZE).withStyle(ChatFormatting.GRAY));
    }
}
