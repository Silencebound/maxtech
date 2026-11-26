package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * A P2P link end as a full block of one {@link P2PKind}: its front ({@link #FACING}, toward the block that was clicked)
 * works on the block it faces, every other side links to the network. The linking tool sets its role and frequency. A
 * redstone end gives its output signal on the front only. Drops with its link; registers no ticker.
 */
public final class P2PBlock extends StorageDeviceBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    private final P2PKind kind;
    private final MapCodec<P2PBlock> codec;

    public P2PBlock(Properties properties, P2PKind kind) {
        super(properties);
        this.kind = kind;
        this.codec = simpleCodec(p -> new P2PBlock(p, kind));
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(FACING, Direction.NORTH));
    }

    public P2PKind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LinkLook.PROPERTY);
    }

    /** Faces the block that was clicked. */
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
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new P2PBlockEntity(pos, state);
    }

    /** The loot table's end carries its link, like the part's item. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof P2PBlockEntity entity) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(entity.collectComponents());
                }
            }
        }
        return drops;
    }

    /** The linking tool sets the link; anything else does nothing here. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof P2PLinkerItem && level.getBlockEntity(pos) instanceof P2PBlockEntity entity) {
            return P2PLinkerItem.use(entity, player, stack);
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof P2PBlockEntity entity) {
            entity.endpoint().neighbourChanged();
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return kind == P2PKind.REDSTONE;
    }

    /** A redstone output's signal, toward the block it faces only. */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (kind != P2PKind.REDSTONE || direction != state.getValue(FACING).getOpposite()
                || !(level.getBlockEntity(pos) instanceof P2PBlockEntity entity)) {
            return 0;
        }
        return entity.endpoint().outputSignal();
    }
}
