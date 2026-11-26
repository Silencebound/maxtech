package dev.silencebound.maxtech.storage.bridge;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.port.PortTier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jspecify.annotations.Nullable;

/**
 * The network bridge of one {@link PortTier} (M7b, B1 and B3): its front ({@link #FACING}) touches a block of another
 * mod's storage network, its back links to the Storage network. Placed against such a block, it faces it. It drops
 * itself, with anything it holds ({@link BridgeStorage}), creative breaking included. No ticker: its block entity's
 * task runs on the network's scheduler and wakes on neighbour changes.
 */
public final class BridgeBlock extends StorageDeviceBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final MapCodec<BridgeBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(PortTier.CODEC.fieldOf("tier").forGetter(BridgeBlock::tier), propertiesCodec())
                    .apply(instance, BridgeBlock::new));
    private final PortTier tier;

    public BridgeBlock(PortTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(FACING, Direction.NORTH));
    }

    public PortTier tier() {
        return tier;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LinkLook.PROPERTY);
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
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BridgeBlockEntity(pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BridgeBlockEntity bridge) {
            bridge.neighbourChanged();
        }
    }

    /**
     * The loot table's bridge, carrying what it holds ({@link BridgeBlockEntity#writeHeld}). While moves through the
     * bridge are open, the bridge item is left out and dropped fresh once they close
     * ({@link BridgeBlockEntity#deferDrop}).
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof BridgeBlockEntity bridge) {
            List<ItemStack> kept = new ArrayList<>(drops.size());
            for (ItemStack drop : drops) {
                if (!drop.is(asItem())) {
                    kept.add(drop);
                } else if (!bridge.deferDrop(true)) {
                    bridge.writeHeld(drop, params.getLevel().registryAccess());
                    kept.add(drop);
                }
            }
            return kept;
        }
        return drops;
    }

    /**
     * A creative player gets no drops, so a bridge holding something drops itself here, like a shulker box; with moves
     * through it open, that waits until they close ({@link BridgeBlockEntity#deferDrop}).
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()
                && level.getBlockEntity(pos) instanceof BridgeBlockEntity bridge && !bridge.deferDrop(false)
                && bridge.holdsAnything()) {
            ItemStack stack = new ItemStack(this);
            stack.applyComponents(bridge.collectComponents());
            bridge.writeHeld(stack, level.registryAccess());
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
