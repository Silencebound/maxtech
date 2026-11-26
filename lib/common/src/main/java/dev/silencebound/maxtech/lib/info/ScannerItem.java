package dev.silencebound.maxtech.lib.info;

import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Prints what a block's {@link BlockInfoProvider} says, at {@link BlockInfoRequest.Detail#FULL}, to the player's chat.
 * Creative-only; it has no recipe.
 */
public final class ScannerItem extends Item {
    public static final String HEADER_KEY = "message.maxtech.scanner.header";
    public static final String NOTHING_KEY = "message.maxtech.scanner.nothing";
    public static final String DESCRIPTION_KEY = "tooltip.maxtech.scanner";
    /** Holding use repeats every 4 ticks; this keeps a held click from flooding the chat. */
    static final int COOLDOWN_TICKS = 10;

    public ScannerItem(Properties properties) {
        super(properties);
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the block's own use. Vanilla's useOn comes after
    // useWithoutItem, so a block with an empty-hand action (the creative source and sink) would take the click.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.getCooldowns().isOnCooldown(stack)) {
            // Not PASS: the block's own use would run instead.
            return InteractionResult.FAIL;
        }
        if (context.getLevel() instanceof ServerLevel level) {
            player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
            List<Component> lines = scan(new BlockInfoRequest(level, context.getClickedPos(), context.getClickedFace(),
                    context.getClickLocation(), BlockInfoRequest.Detail.FULL));
            for (Component line : lines) {
                player.sendSystemMessage(line);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The chat lines for {@code request}: a header naming the block, then the provider's lines and the block's energy
     * lines ({@link EnergyInfo}), or a "nothing to report" line when there are none.
     */
    public static List<Component> scan(BlockInfoRequest request) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(HEADER_KEY, request.level().getBlockState(request.pos()).getBlock().getName())
                .withStyle(ChatFormatting.GOLD));
        BlockInfoProvider provider = InfoLookups.BLOCK.find(request.level(), request.pos(), request.side());
        if (provider != null) {
            provider.addInfo(request, lines::add);
        }
        EnergyInfo.addLines(request, lines::add);
        if (lines.size() == 1) {
            lines.add(Component.translatable(NOTHING_KEY).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
