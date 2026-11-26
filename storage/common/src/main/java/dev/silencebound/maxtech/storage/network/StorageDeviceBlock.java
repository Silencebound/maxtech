package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jspecify.annotations.Nullable;

/**
 * The block every storage device extends. Its block entity is a {@link StorageDeviceBlockEntity}; the player who places
 * it becomes its owner. Storage's NeoForge glue gives every block of this class the FE input and the block info
 * capability, so devices of later areas get both without registering anything. The block's own item among its drops
 * carries the block entity's components (at least its label), so a device keeps its label when broken. No ticker.
 */
public abstract class StorageDeviceBlock extends Block implements EntityBlock {
    protected StorageDeviceBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity device) {
            device.setOwner(OwnerRecord.of(player));
        }
    }

    /**
     * Tells the device's network about the update (redstone into a labelled device, for logic graphs; M7L). Subclasses
     * that react to neighbours call {@code super}.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity device) {
            NetworkState.neighbourChanged(device);
            device.onNeighbourChanged();
        }
    }

    /** The loot table's drops, with the block entity's components on the block's own item. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof StorageDeviceBlockEntity device) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(device.collectComponents());
                }
            }
        }
        return drops;
    }
}
