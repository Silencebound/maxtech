package dev.silencebound.maxtech.storage.crafting.assembler;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** The crafting assembler block: no GUI and no ticker; it drops with its result buffer. */
public final class CraftingAssemblerBlock extends Block implements EntityBlock {
    public static final MapCodec<CraftingAssemblerBlock> CODEC = simpleCodec(CraftingAssemblerBlock::new);

    public CraftingAssemblerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CraftingAssemblerBlockEntity(pos, state);
    }

    /** The loot table's assembler, carrying the results still in its buffer. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params
                .getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof CraftingAssemblerBlockEntity entity) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(entity.collectComponents());
                }
            }
        }
        return drops;
    }
}
