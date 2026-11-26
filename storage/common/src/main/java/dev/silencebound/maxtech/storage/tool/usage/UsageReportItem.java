package dev.silencebound.maxtech.storage.tool.usage;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
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
import org.jspecify.annotations.Nullable;

/**
 * The usage report book: used on a block of a storage network the holder may view, it writes a {@link UsageBook} on
 * that network alone; used anywhere else, on the holder's team's storage networks in this dimension. The report goes
 * into the holder's inventory (or drops at their feet) and the book itself is kept. Explains to players why a network
 * turned devices away (PRD 7.2, Performance: server caps and visibility).
 */
public final class UsageReportItem extends Item {
    static final String DESCRIPTION_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".usage_report_book";
    static final String NO_ACCESS_KEY = "message." + MaxtechStorage.MOD_ID + ".usage.no_access";
    /** Ticks between two reports; holding use repeats every 4 ticks and would fill the inventory with books. */
    static final int COOLDOWN = 40;

    public UsageReportItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        report(level, player, player.getItemInHand(hand), null);
        return InteractionResult.SUCCESS;
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the block's own use, as for the network analyzer.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel level) {
            report(level, player, stack, StorageNetworks.network(level, StorageNetworks.ID, context.getClickedPos()));
        }
        // Never PASS: the block's own use (a terminal's screen) would run instead.
        return InteractionResult.SUCCESS;
    }

    /**
     * Writes the report: on {@code network} when there is one, else on the player's team. Server side; does nothing
     * while the book cools down, and only tells a player without view access to {@code network} so.
     */
    private static void report(Level level, Player player, ItemStack stack, @Nullable NetworkState network) {
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer serverPlayer)
                || player.getCooldowns().isOnCooldown(stack)) {
            return;
        }
        player.getCooldowns().addCooldown(stack, COOLDOWN);
        if (network == null) {
            give(serverPlayer,
                    UsageReport.forTeam(server, serverPlayer.getGameProfile().name(), OwnerRecord.of(serverPlayer)));
        } else if (network.canAccess(player)) {
            give(serverPlayer, UsageReport.forNetwork(network));
        } else {
            serverPlayer.sendOverlayMessage(Component.translatable(NO_ACCESS_KEY));
        }
    }

    /** Puts {@code report} as a written book into {@code player}'s inventory, or at their feet when it is full. */
    static void give(ServerPlayer player, UsageReport report) {
        ItemStack book = UsageBook.write(report, player.getGameProfile().name());
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
