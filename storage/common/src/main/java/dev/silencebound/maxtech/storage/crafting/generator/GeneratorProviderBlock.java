package dev.silencebound.maxtech.storage.crafting.generator;

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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The generator provider (M7C item 11). {@link #FACING} points at the machine it switches on and collects from; it
 * links to the network on every other side. While an order is open it is {@link #POWERED} and gives a weak redstone
 * signal of its set strength toward the machine, or on every side when set so. Drops with its settings. Registers no
 * ticker: its pull task runs on the network's scheduler and wakes on the machine's changes.
 */
public final class GeneratorProviderBlock extends StorageDeviceBlock {
    public static final MapCodec<GeneratorProviderBlock> CODEC = simpleCodec(GeneratorProviderBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public GeneratorProviderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE)
                .setValue(FACING, Direction.NORTH).setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, LinkLook.PROPERTY);
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
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * The set strength toward the machine (the asking block's direction to this one is the opposite of
     * {@link #FACING}), or on every side when set so; 0 while no order is open. A client copy knows no settings and
     * reads 15 toward the machine.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (!state.getValue(POWERED)) {
            return 0;
        }
        GeneratorSettings settings = level.getBlockEntity(pos) instanceof GeneratorProviderBlockEntity entity
                ? entity.logic().settings()
                : GeneratorSettings.DEFAULT;
        boolean toward = settings.allSides() || direction == state.getValue(FACING).getOpposite();
        return toward ? settings.strength() : 0;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GeneratorProviderBlockEntity(pos, state);
    }

    /** The loot table's generator, carrying its settings. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params
                .getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof GeneratorProviderBlockEntity entity) {
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
        if (level.getBlockEntity(pos) instanceof GeneratorProviderBlockEntity entity) {
            entity.logic().targetChanged();
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockExtension hook; vanilla machines call it through their comparator update
    // whenever their contents change, so the generator sees output arrive without polling.
    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        if (level instanceof ServerLevel && neighbor.equals(pos.relative(state.getValue(FACING)))
                && level.getBlockEntity(pos) instanceof GeneratorProviderBlockEntity entity) {
            entity.logic().targetChanged();
        }
    }

    /** Opens the generator's GUI for players its owner lets in. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof GeneratorProviderBlockEntity entity
                && NetworkAccess.canConfigure(player, entity)) {
            GeneratorMenu.open(serverPlayer, entity, pos, getName());
        }
        return InteractionResult.SUCCESS;
    }
}
