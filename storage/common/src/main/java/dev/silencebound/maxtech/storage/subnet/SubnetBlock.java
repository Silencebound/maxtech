package dev.silencebound.maxtech.storage.subnet;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
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
 * The subnet controller block. {@link #FACING} points at the parent network: placed against a cable or device, it faces
 * it. Using it opens its settings for players with full access. It drops itself with its settings and FE. No ticker.
 */
public final class SubnetBlock extends StorageDeviceBlock {
    public static final MapCodec<SubnetBlock> CODEC = simpleCodec(SubnetBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public SubnetBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
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
        return new SubnetBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof SubnetBlockEntity subnet)) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer server) {
            if (!NetworkAccess.canConfigure(player, subnet)) {
                return InteractionResult.FAIL;
            }
            SubnetMenu.open(server, subnet, getName());
        }
        return InteractionResult.SUCCESS;
    }

    /** The block with its settings ({@link SubnetBlockEntity#toItemStack}), without a loot table. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof SubnetBlockEntity subnet) {
            return List.of(subnet.toItemStack(params.getLevel().registryAccess()));
        }
        return List.of(new ItemStack(this));
    }
}
