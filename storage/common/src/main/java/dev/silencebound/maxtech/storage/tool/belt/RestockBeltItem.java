package dev.silencebound.maxtech.storage.tool.belt;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.tool.uplink.PocketUplinkItem;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The restock belt (M6e): carried anywhere in the inventory, it keeps the slots it marks topped up from its bound
 * network ({@link BeltRestock}). Sneak-use on a storage controller binds it; use in the main hand opens
 * {@link BeltMenu} to mark slots and set their counts.
 */
public final class RestockBeltItem extends Item {
    public RestockBeltItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** {@code stack}'s marks; none for a new belt. */
    public static BeltMarks marks(ItemStack stack) {
        return stack.getOrDefault(BeltContent.MARKS.get(), BeltMarks.EMPTY);
    }

    /** Most marks per belt, from config; any thread. */
    public static int maxMarks() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.BELT_MAX_MARKS.getAsInt(), 0);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            // The menu reads the marks off the main hand, which its slot sync covers; the offhand it would not.
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            BeltMenu.open(serverPlayer, player.getItemInHand(hand).getHoverName());
        }
        return InteractionResult.SUCCESS;
    }

    /** Sneak-use on a storage controller core binds the belt to it. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()
                || !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ControllerBlockEntity)) {
            return InteractionResult.PASS;
        }
        PocketUplinkItem.bind(context);
        return InteractionResult.SUCCESS;
    }

    static void tell(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            PocketUplinkItem.tell(serverPlayer, message);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        PocketUplinkItem.appendLink(stack, lines);
        lines.accept(Component.translatable(BeltContent.MARKED_TOOLTIP_KEY, marks(stack).marks().size())
                .withStyle(ChatFormatting.GRAY));
    }
}
