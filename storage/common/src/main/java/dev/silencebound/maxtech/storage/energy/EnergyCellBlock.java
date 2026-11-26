package dev.silencebound.maxtech.storage.energy;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * An energy cell (PRD 7.2, energy storage): a storage device that holds FE for its network. {@link #CHARGE} shows how
 * full it is (0 empty, 4 full) through block state models, so no block entity renderer is needed. Registers no ticker.
 */
public final class EnergyCellBlock extends StorageDeviceBlock {
    /** Charge shown on the sides: 0 empty, 1 to 3 the thirds in between, 4 full. */
    public static final IntegerProperty CHARGE = IntegerProperty.create("charge", 0, 4);
    public static final int FULL = 4;
    public static final MapCodec<EnergyCellBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(EnergyCellTier.CODEC.fieldOf("tier").forGetter(EnergyCellBlock::tier), propertiesCodec())
            .apply(instance, EnergyCellBlock::new));

    private final EnergyCellTier tier;

    public EnergyCellBlock(EnergyCellTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(CHARGE, 0));
    }

    public EnergyCellTier tier() {
        return tier;
    }

    /** The {@link #CHARGE} value for {@code stored} of {@code capacity} FE. */
    public static int charge(long stored, long capacity) {
        if (stored <= 0 || capacity <= 0) {
            return 0;
        }
        if (stored >= capacity) {
            return FULL;
        }
        // Thirds of the range between empty and full; long maths, since 3 x 10 million FE fits easily.
        return 1 + (int) Math.min(2, stored * 3 / capacity);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CHARGE);
    }

    /** The loot table's cell carries its FE, like the item it was placed from. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof EnergyCellBlockEntity cell) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(cell.collectComponents());
                }
            }
        }
        return drops;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergyCellBlockEntity(pos, state);
    }
}
