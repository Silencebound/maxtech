package dev.silencebound.maxtech.storage.part;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.tool.setup.SetupGhosts;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * The item of a cable part. Used on a storage cable it puts the part on the face that was clicked (the face of the arm,
 * or the clicked side of the core), if that face has none. Used on anything else it does nothing and says where it
 * goes: the block form is a separate item (a shapeless recipe converts between the two), so a part item never places a
 * block by surprise.
 */
public final class PartItem extends Item {
    public static final String NOT_A_CABLE_KEY = "message." + MaxtechStorage.MOD_ID + ".part.not_a_cable";
    public static final String OCCUPIED_KEY = "message." + MaxtechStorage.MOD_ID + ".part.occupied";

    private final Supplier<PartType> type;

    /**
     * @param type
     *            resolved lazily: the type names this item, so it is built after the item's registration is queued
     */
    public PartItem(Properties properties, Supplier<PartType> type) {
        super(properties);
        this.type = type;
    }

    public PartType partType() {
        return type.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        partType().appendTooltip(stack, lines);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!(level.getBlockEntity(pos) instanceof ConduitBlockEntity cable)
                || !(cable.conduit().state() instanceof CableParts parts)) {
            if (player != null && !level.isClientSide()) {
                player.sendOverlayMessage(Component.translatable(NOT_A_CABLE_KEY));
            }
            // FAIL, not PASS: the click must not fall through to the item's use in the air.
            return InteractionResult.FAIL;
        }
        BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), pos,
                context.isInside());
        Direction side = cable.faceAt(hit);
        if (parts.partAt(hit.getLocation(), hit.getDirection()) != null || parts.occupies(side)) {
            if (player != null && !level.isClientSide()) {
                player.sendOverlayMessage(Component.translatable(OCCUPIED_KEY));
            }
            return InteractionResult.FAIL;
        }
        if (player != null
                && (!player.mayUseItemAt(pos, side, context.getItemInHand()) || !level.mayInteract(player, pos))) {
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide()) {
            ItemStack stack = context.getItemInHand();
            if (!parts.place(side, partType(), player, stack)) {
                return InteractionResult.FAIL;
            }
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            CablePart placed = parts.part(side);
            if (player != null && placed != null && level instanceof ServerLevel server) {
                // A part placed into the player's setup blueprint ghost takes its settings (M7C item 21).
                SetupGhosts.get(server.getServer()).partPlaced(player, server, pos, side, placed);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
