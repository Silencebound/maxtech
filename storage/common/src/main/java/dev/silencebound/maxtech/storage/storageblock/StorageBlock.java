package dev.silencebound.maxtech.storage.storageblock;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A storage block of one tier (B09 item 27), facing the player who placed it. Using it with an empty hand opens its
 * screen with the priority tab, for players with full access. It always drops itself with its cell id and priority
 * (broken, blown up, picked up with the wrench, or broken in creative mode while it holds something), never through a
 * loot table, so its contents are never cut off from an item. No ticker.
 */
public final class StorageBlock extends StorageDeviceBlock {
    public static final MapCodec<StorageBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(StorageBlockTier.CODEC.fieldOf("tier").forGetter(StorageBlock::tier), propertiesCodec())
            .apply(instance, StorageBlock::new));
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final StorageBlockTier tier;

    public StorageBlock(StorageBlockTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(FACING, Direction.NORTH));
    }

    /** The block's tier. */
    public StorageBlockTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LinkLook.PROPERTY);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorageBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof StorageBlockEntity block)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!NetworkAccess.canConfigure(player, block)) {
            return InteractionResult.FAIL;
        }
        if (player instanceof ServerPlayer server) {
            StorageBlockMenu.open(server, block, getName());
        }
        return InteractionResult.SUCCESS;
    }

    /** The block with its cell id and priority ({@link StorageBlockEntity#toItemStack}), without a loot table. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof StorageBlockEntity block) {
            return List.of(block.toItemStack());
        }
        return List.of(new ItemStack(this));
    }

    /** A creative player gets no drops, so a block holding something drops itself here, like a shulker box. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()
                && level.getBlockEntity(pos) instanceof StorageBlockEntity block && block.holdsAnything()) {
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    block.toItemStack());
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
