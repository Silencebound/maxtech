package dev.silencebound.maxtech.lib.facade;

import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Setting, clearing and dropping a conduit's facade. There is one facade per conduit block (EnderIO-style); its block
 * entity stores it and the conduit block state's {@link ConduitBlock#FACADE} flag mirrors it, since light and shape
 * decisions that vanilla caches per block state need it there.
 */
public final class Facades {
    private Facades() {
    }

    /**
     * Whether a facade may look like {@code state}: a full, solid, opaque cube drawn by a block model, without a block
     * entity. That excludes glass, slabs, leaves, chests and conduits themselves.
     */
    public static boolean canMimic(BlockState state) {
        return state.isSolidRender() && !state.hasBlockEntity() && state.getRenderShape() == RenderShape.MODEL
                && !(state.getBlock() instanceof ConduitBlock);
    }

    /** The state a facade crafted from {@code stack} looks like, or {@code null} if the stack is no usable block. */
    public static @Nullable BlockState mimicOf(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return canMimic(state) ? state : null;
    }

    /** One facade item looking like {@code state}. */
    public static ItemStack stack(BlockState state) {
        ItemStack stack = new ItemStack(FacadeItem.item());
        stack.set(ModDataComponents.FACADE.get(), state);
        return stack;
    }

    /**
     * Covers the conduit at {@code pos} with {@code facade} on the server. Returns false if the conduit already has a
     * facade or {@code facade} is not allowed; the caller consumes the item.
     */
    public static boolean set(Level level, BlockPos pos, ConduitBlockEntity conduitEntity, BlockState facade) {
        if (conduitEntity.facade() != null || !canMimic(facade) || !conduitEntity.setFacade(facade)) {
            return false;
        }
        mirror(level, pos, true);
        return true;
    }

    /**
     * Takes the facade off the conduit at {@code pos} on the server and returns what it looked like, or {@code null} if
     * there was none. The caller hands out the item.
     */
    public static @Nullable BlockState remove(Level level, BlockPos pos, ConduitBlockEntity conduitEntity) {
        BlockState facade = conduitEntity.facade();
        if (facade == null || !conduitEntity.setFacade(null)) {
            return null;
        }
        mirror(level, pos, false);
        return facade;
    }

    /**
     * Sets the block state's flag. The block stays the same, so the block entity survives the change
     * ({@code LevelChunk.setBlockState} only drops it when the block changes).
     */
    private static void mirror(Level level, BlockPos pos, boolean covered) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(ConduitBlock.FACADE) && state.getValue(ConduitBlock.FACADE) != covered) {
            level.setBlock(pos, state.setValue(ConduitBlock.FACADE, covered), Block.UPDATE_ALL);
        }
    }
}
