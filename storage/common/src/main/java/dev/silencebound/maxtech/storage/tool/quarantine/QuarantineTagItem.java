package dev.silencebound.maxtech.storage.tool.quarantine;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The quarantine tag (decision E9): used on a block of a storage network by a game master or the network's owner, it
 * freezes that network (no operations, devices and contents stay) or, used again, lifts the freeze. The tag is not used
 * up. Everyone else gets a message and nothing changes.
 */
public final class QuarantineTagItem extends Item {
    static final String DESCRIPTION_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".quarantine_tag";
    static final String ON_KEY = "message." + MaxtechStorage.MOD_ID + ".quarantine.on";
    static final String OFF_KEY = "message." + MaxtechStorage.MOD_ID + ".quarantine.off";
    static final String DENIED_KEY = "message." + MaxtechStorage.MOD_ID + ".quarantine.denied";

    public QuarantineTagItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the block's own use, as for the network analyzer.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !(context.getLevel() instanceof ServerLevel level)) {
            return player == null ? InteractionResult.PASS : InteractionResult.SUCCESS;
        }
        NetworkState network = StorageNetworks.network(level, StorageNetworks.ID, context.getClickedPos());
        if (network == null) {
            return InteractionResult.PASS;
        }
        toggle(player, network);
        // Never PASS on a network block: its own use (a terminal's screen) would run instead.
        return InteractionResult.SUCCESS;
    }

    /**
     * Flips {@code network}'s quarantine for {@code player} when allowed and tells them the outcome; true if changed.
     */
    public static boolean toggle(Player player, NetworkState network) {
        if (!mayTag(player, network.owner())) {
            player.sendOverlayMessage(Component.translatable(DENIED_KEY));
            return false;
        }
        boolean on = !network.quarantined();
        network.setQuarantined(on);
        player.sendOverlayMessage(Component.translatable(on ? ON_KEY : OFF_KEY));
        return true;
    }

    /** Game masters, and the owner of an owned network. */
    public static boolean mayTag(Player player, OwnerRecord owner) {
        return (owner.isOwned() && owner.isOwner(player.getUUID()))
                || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
