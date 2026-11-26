package dev.silencebound.maxtech.storage.tool.zone;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.ZoneBorders;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * The zone painter (M6e, decision E4): used on a storage cable it paints the cable as a zone border, or clears the
 * paint; a painted cable always starts a zone of its own at the network's next rebuild ({@link ZoneBorders}). Changing
 * routing needs full access to the network. Each use, and a use in the air, lights the painted borders near the player
 * through walls, since the cable itself looks the same.
 */
public final class ZonePainterItem extends Item {
    /** The painted borders highlight's channel. */
    public static final Identifier CHANNEL = MaxtechStorage.id("zone_painter");
    /** How far from the player painted borders are shown, per axis. */
    public static final int RADIUS = 32;
    /** Ticks the borders stay lit: 10 seconds. */
    public static final int HIGHLIGHT_TICKS = 200;
    static final int BORDER_COLOUR = 0xFFFFA000;
    private static final int COOLDOWN = 10;

    /** What one use on a block did; the message key says it to the player. */
    public enum Outcome {
        PAINTED(ZonePainterContent.PAINTED_KEY), CLEARED(ZonePainterContent.CLEARED_KEY), NOT_CABLE(
                ZonePainterContent.NOT_CABLE_KEY), NO_NETWORK(
                        ZonePainterContent.NO_NETWORK_KEY), DENIED(ZonePainterContent.DENIED_KEY);

        private final String key;

        Outcome(String key) {
            this.key = key;
        }

        /** The message key. */
        public String key() {
            return key;
        }
    }

    public ZonePainterItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the cable's own use (part placement), as for the
    // analyzer.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.getCooldowns().isOnCooldown(stack)) {
            return InteractionResult.FAIL;
        }
        if (context.getLevel() instanceof ServerLevel level && player instanceof ServerPlayer serverPlayer) {
            player.getCooldowns().addCooldown(stack, COOLDOWN);
            Outcome outcome = paint(level, serverPlayer, context.getClickedPos(), StorageNetworks.ID);
            tell(serverPlayer, Component.translatable(outcome.key()));
            Highlights.send(serverPlayer, borders(level, player.blockPosition()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
            HighlightRequest request = borders(server, player.blockPosition());
            Highlights.send(serverPlayer, request);
            tell(serverPlayer, Component.translatable(ZonePainterContent.SHOWN_KEY,
                    InfoFormat.number(request.boxCount()), InfoFormat.number(RADIUS)));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Paints the storage cable at {@code pos} as a zone border, or clears it if painted, for {@code player} in the
     * network type {@code networkId}. Server thread.
     */
    public static Outcome paint(ServerLevel level, ServerPlayer player, BlockPos pos, Identifier networkId) {
        if (!(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)
                || !(conduit.type() instanceof StorageCableConduitType)) {
            return Outcome.NOT_CABLE;
        }
        NetworkState network = StorageNetworks.network(level, networkId, pos);
        if (network == null) {
            // Not joined yet (placed this tick or a chunk edge): nothing to check access against, so refuse.
            return Outcome.NO_NETWORK;
        }
        if (!network.accessLevel(player).atLeast(AccessLevel.FULL)) {
            return Outcome.DENIED;
        }
        boolean painted = !ZoneBorders.of(level).isBorder(pos.asLong());
        ZoneBorders.set(level, networkId, pos, painted);
        return painted ? Outcome.PAINTED : Outcome.CLEARED;
    }

    private static void tell(ServerPlayer player, Component message) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            player.sendOverlayMessage(message);
        }
    }

    /** A box on every painted border within {@link #RADIUS} of {@code centre}, up to Lib's shape limit. */
    public static HighlightRequest borders(ServerLevel level, BlockPos centre) {
        HighlightRequest.Builder builder = HighlightRequest.builder(CHANNEL).duration(HIGHLIGHT_TICKS)
                .throughWalls(true);
        LongIterator it = ZoneBorders.of(level).painted().iterator();
        while (it.hasNext() && builder.size() < HighlightRequest.MAX_SHAPES) {
            long pos = it.nextLong();
            if (Math.abs(BlockPos.getX(pos) - centre.getX()) <= RADIUS
                    && Math.abs(BlockPos.getY(pos) - centre.getY()) <= RADIUS
                    && Math.abs(BlockPos.getZ(pos) - centre.getZ()) <= RADIUS) {
                builder.block(BlockPos.of(pos), BORDER_COLOUR);
            }
        }
        return builder.build();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(ZonePainterContent.DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
