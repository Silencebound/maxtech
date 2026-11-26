package dev.silencebound.maxtech.lib.facade;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A facade: used on a conduit block without one, it covers the whole block with the block in its
 * {@link ModDataComponents#FACADE} component and is used up. A sneak-used wrench takes it off again
 * ({@code ConduitBlock.useWrench}).
 */
public final class FacadeItem extends Item {
    /** Tooltip line naming the mimicked block. */
    public static final String LOOKS_LIKE_KEY = "tooltip.maxtech.facade.looks_like";

    public FacadeItem(Properties properties) {
        super(properties);
    }

    /** The registered facade item, {@link FacadeContent#FACADE}. */
    public static FacadeItem item() {
        return FacadeContent.FACADE.get();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();
        BlockState facade = stack.get(ModDataComponents.FACADE.get());
        // The client decides from its synced copy of the conduit, so the hand swings only when the server will accept.
        if (facade == null || !Facades.canMimic(facade)
                || !(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduitEntity)
                || conduitEntity.facade() != null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            if (!Facades.set(level, pos, conduitEntity, facade)) {
                return InteractionResult.FAIL;
            }
            // Vanilla's getSoundType(), deprecated by NeoForge for a positional overload; common stays loader-free.
            SoundType sound = facade.getSoundType();
            level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                    sound.getPitch() * 0.8F);
            stack.consume(1, context.getPlayer());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        BlockState facade = stack.get(ModDataComponents.FACADE.get());
        if (facade != null) {
            builder.accept(
                    Component.translatable(LOOKS_LIKE_KEY, facade.getBlock().getName()).withStyle(ChatFormatting.GRAY));
        }
    }
}
