package dev.silencebound.maxtech.storage.tool.uplink;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * The pocket uplink (M6e): carried anywhere in the inventory, it stores picked-up items in its bound network when the
 * inventory is full ({@link PickupOverflow}). Sneak-use on a storage controller binds it, as the wireless terminal. Use
 * with an item in the other hand puts that item on the filter or takes it off; use with an empty other hand turns
 * overflow on or off; sneak-use in the air clears the filter. No GUI: the tooltip shows the settings.
 */
public final class PocketUplinkItem extends Item {
    public PocketUplinkItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** {@code stack}'s settings; {@link UplinkSettings#DEFAULT} when never changed. */
    public static UplinkSettings settings(ItemStack stack) {
        return stack.getOrDefault(UplinkContent.SETTINGS.get(), UplinkSettings.DEFAULT);
    }

    /** Most filter entries, from config; any thread. */
    public static int filterSize() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.UPLINK_FILTER_SIZE.getAsInt(), 0);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            ItemStack uplink = player.getItemInHand(hand);
            ItemStack other = player.getItemInHand(
                    hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            tell(serverPlayer, configure(uplink, other, player.isSecondaryUseActive()));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Applies one use in the air to {@code uplink}'s settings and returns the message for the player: clear the filter
     * when sneaking, else toggle {@code other}'s item on the filter, else toggle overflow.
     */
    public static Component configure(ItemStack uplink, ItemStack other, boolean sneaking) {
        UplinkSettings settings = settings(uplink);
        if (sneaking) {
            uplink.set(UplinkContent.SETTINGS.get(), settings.cleared());
            return Component.translatable(UplinkContent.FILTER_CLEARED_KEY);
        }
        if (!other.isEmpty()) {
            UplinkSettings changed = settings.toggled(other.getItem(), filterSize());
            if (changed.equals(settings)) {
                return Component.translatable(UplinkContent.FILTER_FULL_KEY, filterSize());
            }
            uplink.set(UplinkContent.SETTINGS.get(), changed);
            return Component.translatable(changed.filter().contains(other.getItem())
                    ? UplinkContent.FILTER_ADDED_KEY
                    : UplinkContent.FILTER_REMOVED_KEY, other.getHoverName());
        }
        UplinkSettings changed = settings.withActive(!settings.active());
        uplink.set(UplinkContent.SETTINGS.get(), changed);
        return Component.translatable(changed.active() ? UplinkContent.ON_KEY : UplinkContent.OFF_KEY);
    }

    /** Sneak-use on a storage controller core binds the uplink to it. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()
                || !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ControllerBlockEntity)) {
            return InteractionResult.PASS;
        }
        bind(context);
        return InteractionResult.SUCCESS;
    }

    /** Binds the used stack to the clicked controller and tells the player; shared with the restock belt. */
    public static void bind(UseOnContext context) {
        if (context.getLevel() instanceof ServerLevel server) {
            BlockPos pos = context.getClickedPos();
            context.getItemInHand().set(ModDataComponents.WIRELESS_LINK.get(), GlobalPos.of(server.dimension(), pos));
            if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
                tell(serverPlayer,
                        Component.translatable(SignalContent.WIRELESS_BOUND_KEY, pos.getX(), pos.getY(), pos.getZ()));
            }
        }
    }

    /** Shows {@code message} in the action bar; a GameTest player has no connection to send it to. */
    public static void tell(ServerPlayer player, Component message) {
        if (player.connection != null) {
            player.sendOverlayMessage(message);
        }
    }

    /** The bound-to line every pocket tool shows, as the wireless terminal's. */
    public static void appendLink(ItemStack stack, Consumer<Component> lines) {
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        lines.accept(
                (link == null
                        ? Component.translatable(SignalContent.WIRELESS_TOOLTIP_UNBOUND_KEY)
                        : Component.translatable(SignalContent.WIRELESS_TOOLTIP_KEY, link.pos().getX(),
                                link.pos().getY(), link.pos().getZ(), link.dimension().identifier().toString()))
                        .withStyle(ChatFormatting.GRAY));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        appendLink(stack, lines);
        UplinkSettings settings = settings(stack);
        lines.accept(Component.translatable(settings.active() ? UplinkContent.ON_KEY : UplinkContent.OFF_KEY)
                .withStyle(ChatFormatting.GRAY));
        if (settings.filter().isEmpty()) {
            lines.accept(Component.translatable(UplinkContent.FILTER_ANY_KEY).withStyle(ChatFormatting.GRAY));
        } else {
            lines.accept(Component.translatable(UplinkContent.FILTER_KEY).withStyle(ChatFormatting.GRAY));
            for (Item item : settings.filter()) {
                lines.accept(Component.literal("  ").append(item.getDefaultInstance().getHoverName())
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }
}
