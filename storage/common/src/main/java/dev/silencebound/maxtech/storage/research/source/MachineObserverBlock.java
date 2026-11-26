package dev.silencebound.maxtech.storage.research.source;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.List;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

/**
 * The machine observer: faces the block it was placed against (any of six directions) and, when that is a Storage
 * machine, earns its owner's team points for the machine's finished runs. It hears runs from
 * {@link dev.silencebound.maxtech.storage.machine.MachineRuns} and never ticks.
 */
public final class MachineObserverBlock extends Block implements EntityBlock {
    public static final MapCodec<MachineObserverBlock> CODEC = simpleCodec(MachineObserverBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public MachineObserverBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Faces the block the player clicked, so placing it against a machine points it at the machine. */
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

    /** The placer owns the observer; its points go to the placer's research team. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof MachineObserverBlockEntity observer) {
            observer.setOwner(OwnerRecord.of(player));
        }
    }

    /** The observer with its count and day ({@link MachineObserverBlockEntity#toItemStack}), without a loot table. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params
                .getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof MachineObserverBlockEntity observer) {
            return List.of(observer.toItemStack(params.getLevel().registryAccess()));
        }
        return List.of(new ItemStack(this));
    }

    @Override
    public MachineObserverBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MachineObserverBlockEntity(pos, state);
    }
}
