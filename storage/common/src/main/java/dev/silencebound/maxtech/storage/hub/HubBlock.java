package dev.silencebound.maxtech.storage.hub;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A hub (M7N item 2, PRD 7.2 Channels with hubs): a storage device that links on every side, has no screen and no
 * facing, and gives the devices routed through it a channel budget of its tier. Registers no ticker.
 */
public final class HubBlock extends StorageDeviceBlock {
    public static final MapCodec<HubBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(HubTier.CODEC.fieldOf("tier").forGetter(HubBlock::tier), propertiesCodec())
                    .apply(instance, HubBlock::new));

    private final HubTier tier;

    public HubBlock(HubTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public HubTier tier() {
        return tier;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HubBlockEntity(pos, state);
    }
}
