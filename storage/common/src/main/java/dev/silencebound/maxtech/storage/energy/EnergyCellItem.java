package dev.silencebound.maxtech.storage.energy;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * An energy cell's item: shows the FE it carries (a bar and a tooltip line), and refuses to place a tier the placing
 * player's team has not researched yet. A cell placed without a player (a dispenser or another mod's placer) is not
 * checked.
 */
public final class EnergyCellItem extends BlockItem {
    private static final int BAR_COLOUR = 0x3CB8D0;

    private final EnergyCellTier tier;

    public EnergyCellItem(EnergyCellBlock block, Properties properties) {
        super(block, properties);
        this.tier = block.tier();
    }

    /** MU the item carries. */
    public static long stored(ItemStack stack) {
        return ItemEnergy.get(stack, ModDataComponents.STORED_ENERGY.get());
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Identifier unlock = tier.unlock();
        Player player = context.getPlayer();
        if (unlock != null && player != null && !ResearchUnlocks.isUnlocked(player, unlock)) {
            // A GameTest player has no connection to send the message to.
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
                serverPlayer.sendOverlayMessage(Component.translatable(EnergyContent.NEEDS_RESEARCH_KEY,
                        Component.translatable(ResearchNode.unlockKey(unlock))));
            }
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return stored(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        long capacity = tier.capacity();
        return capacity <= 0 ? 0 : Mth.clamp((int) Math.round(13.0 * stored(stack) / capacity), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOUR;
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(EnergyContent.STORED_KEY, InfoFormat.number(stored(stack)),
                InfoFormat.number(tier.capacity())).withStyle(ChatFormatting.GRAY));
    }
}
