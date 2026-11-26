package dev.silencebound.maxtech.storage.tool.wrench;

import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Storage's wrench (M6e). Sneak-used on a storage device it picks the device up into the inventory as the item it would
 * drop, with its contents and settings in the item's components (each device's own drop, so a drive keeps its cells and
 * a port its filter); that needs full access to the device ({@link NetworkAccess#canConfigure}). Sneak-used on a
 * conduit it does what Lib's wrench does there: a part on a storage cable comes off, otherwise the conduit. Plain use
 * falls through to the block, which treats it as any wrench ({@code maxtech_lib:acts_as/wrench}): a port switches its
 * mode, a conduit cycles the face. Never wears out.
 */
public final class StorageWrenchItem extends Item {
    public StorageWrenchItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook. Vanilla's useOn runs inside NeoForge's block snapshot capture on
    // the server, which would restore a picked-up device after it went into the inventory if a protection mod cancels
    // the place event; this hook has no capture.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ConduitBlock) {
            BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), pos,
                    context.isInside());
            return ConduitBlock.useWrench(level, pos, player, hit, true);
        }
        if (state.getBlock() instanceof StorageDeviceBlock) {
            return pickUp(level, pos, player, stack);
        }
        return InteractionResult.PASS;
    }

    /**
     * Takes the device at {@code pos} into {@code player}'s inventory (what does not fit drops at the player's feet),
     * as the block's own drops with {@code tool} as the tool. Refused with a message without full access or where the
     * player may not build (spawn protection). Both sides return success; only the server changes anything.
     */
    public static InteractionResult pickUp(Level level, BlockPos pos, Player player, ItemStack tool) {
        if (!(level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity device)) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        if (!server.mayInteract(player, pos) || !NetworkAccess.canConfigure(player, device)) {
            player.sendOverlayMessage(Component.translatable(WrenchContent.DENIED_KEY));
            return InteractionResult.SUCCESS;
        }
        BlockState state = level.getBlockState(pos);
        List<ItemStack> drops = Block.getDrops(state, server, pos, device, player, tool);
        server.removeBlock(pos, false);
        for (ItemStack drop : drops) {
            player.getInventory().placeItemBackInInventory(drop);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        lines.accept(Component.translatable(WrenchContent.DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
