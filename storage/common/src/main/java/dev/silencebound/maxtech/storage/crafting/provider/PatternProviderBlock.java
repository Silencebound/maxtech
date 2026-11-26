package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
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
 * The pattern provider as a full block. {@link #FACING} points at the machine it runs patterns on (placed against a
 * furnace, it works on the furnace); it links to the network on every other side. Drops with its patterns and setting.
 * Registers no ticker: its output task runs on the network's scheduler and wakes on the machine's changes.
 */
public final class PatternProviderBlock extends StorageDeviceBlock {
    public static final MapCodec<PatternProviderBlock> CODEC = simpleCodec(PatternProviderBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public PatternProviderBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(FACING, Direction.NORTH));
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
        return new PatternProviderBlockEntity(pos, state);
    }

    /** The loot table's provider, carrying its patterns and setting ({@link ProviderLogic#write}). */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof PatternProviderBlockEntity entity) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(entity.collectComponents());
                }
            }
        }
        return drops;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level.getBlockEntity(pos) instanceof PatternProviderBlockEntity entity) {
            entity.logic().targetChanged();
            // The lock mode may read the redstone signal (B09 item 26).
            entity.logic().redstoneChanged();
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockExtension hook; vanilla machines call it through their comparator update
    // whenever their contents change, so the provider sees results come out without polling.
    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        if (level instanceof ServerLevel && neighbor.equals(pos.relative(state.getValue(FACING)))
                && level.getBlockEntity(pos) instanceof PatternProviderBlockEntity entity) {
            entity.logic().targetChanged();
        }
    }

    /** Opens the provider's GUI for players its owner lets in. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof PatternProviderBlockEntity entity
                && NetworkAccess.canConfigure(player, entity)) {
            ProviderMenu.open(serverPlayer, entity, pos, getName());
        }
        return InteractionResult.SUCCESS;
    }
}
