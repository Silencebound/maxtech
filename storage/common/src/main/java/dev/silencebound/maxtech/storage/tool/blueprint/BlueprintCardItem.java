package dev.silencebound.maxtech.storage.tool.blueprint;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The blueprint card (M6e): copies a device's settings and pastes them onto devices of the same kind. Used on a device
 * while empty, or on a device of another kind, it copies; used on a device of the card's kind it pastes, and sneak-used
 * it pastes along the row of such devices ({@link Blueprints#pasteRow}, at most {@code tools.blueprint.rowLength}).
 * Sneak-used in the air it forgets what it holds. Every copy and paste needs full access to the device.
 */
public final class BlueprintCardItem extends Item {
    public BlueprintCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook; it runs before the device's own use, which would open its screen.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), pos,
                context.isInside());
        return use(context.getLevel(), pos, hit, player, stack);
    }

    /** The card used on the block at {@code pos}; see the class comment. Both sides; changes only on the server. */
    public static InteractionResult use(Level level, BlockPos pos, BlockHitResult hit, Player player, ItemStack stack) {
        SettingsCopyable device = Blueprints.at(level, pos, hit);
        if (device == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Blueprint held = stack.get(BlueprintContent.BLUEPRINT.get());
        boolean fits = held != null && Blueprints.fits(device, held);
        if (held != null && !fits && player.isSecondaryUseActive()) {
            player.sendOverlayMessage(Component.translatable(BlueprintContent.WRONG_KIND_KEY, kindName(held),
                    Component.translatable(DeviceSettings.kindKey(device.copyableSettings().kind()))));
            return InteractionResult.SUCCESS;
        }
        if (held == null || !fits) {
            if (!NetworkAccess.canConfigure(player, device)) {
                player.sendOverlayMessage(Component.translatable(BlueprintContent.DENIED_KEY));
                return InteractionResult.SUCCESS;
            }
            Blueprint copied = Blueprints.copy(device);
            stack.set(BlueprintContent.BLUEPRINT.get(), copied);
            player.sendOverlayMessage(Component.translatable(BlueprintContent.COPIED_KEY, kindName(copied)));
            return InteractionResult.SUCCESS;
        }
        if (player.isSecondaryUseActive()) {
            Blueprints.Result result = Blueprints.pasteRow(player, level, pos, hit.getDirection(), device, held,
                    StorageServerConfig.BLUEPRINT_ROW_LENGTH.getAsInt());
            player.sendOverlayMessage(result.denied() == 0
                    ? Component.translatable(BlueprintContent.PASTED_ROW_KEY, result.pasted())
                    : Component.translatable(BlueprintContent.PASTED_ROW_DENIED_KEY, result.pasted(), result.denied()));
            return InteractionResult.SUCCESS;
        }
        if (!NetworkAccess.canConfigure(player, device)) {
            player.sendOverlayMessage(Component.translatable(BlueprintContent.DENIED_KEY));
            return InteractionResult.SUCCESS;
        }
        Blueprints.paste(device, held);
        player.sendOverlayMessage(Component.translatable(BlueprintContent.PASTED_KEY, kindName(held)));
        return InteractionResult.SUCCESS;
    }

    /** Sneak-use in the air empties the card. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive() || !stack.has(BlueprintContent.BLUEPRINT.get())) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            stack.remove(BlueprintContent.BLUEPRINT.get());
            player.sendOverlayMessage(Component.translatable(BlueprintContent.CLEARED_KEY));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        Blueprint held = stack.get(BlueprintContent.BLUEPRINT.get());
        if (held == null) {
            lines.accept(Component.translatable(BlueprintContent.TOOLTIP_EMPTY_KEY).withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.accept(Component.translatable(BlueprintContent.TOOLTIP_HELD_KEY, kindName(held)));
        lines.accept(Component.translatable(BlueprintContent.TOOLTIP_USE_KEY).withStyle(ChatFormatting.GRAY));
    }

    private static Component kindName(Blueprint blueprint) {
        return Component.translatable(DeviceSettings.kindKey(blueprint.kind()));
    }
}
