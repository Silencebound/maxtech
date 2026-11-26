package dev.silencebound.maxtech.storage.signal;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.p2p.P2PLinkerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * An antenna of one tier (M6d): a storage device block that links on every side and, paired with the linking tool, to
 * one far antenna or antenna mount ({@link AntennaBlockEntity}). No ticker.
 */
public final class AntennaBlock extends StorageDeviceBlock {
    public static final MapCodec<AntennaBlock> CODEC = RecordCodecBuilder
            .mapCodec(
                    instance -> instance
                            .group(ExtraCodecs.intRange(1, SignalMath.MAX_TIER).fieldOf("tier")
                                    .forGetter(AntennaBlock::tier), propertiesCodec())
                            .apply(instance, AntennaBlock::new));

    private final int tier;

    public AntennaBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }

    /** The linking tool pairs antennas: use picks this one, use on another pairs the two ({@link AntennaLinking}). */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof P2PLinkerItem) {
            return AntennaLinking.use(level, pos, player, stack);
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AntennaBlockEntity(pos, state);
    }
}
