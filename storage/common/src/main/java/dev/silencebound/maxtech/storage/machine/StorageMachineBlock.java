package dev.silencebound.maxtech.storage.machine;

import com.mojang.serialization.MapCodec;
import java.util.List;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * One of Storage's machines: faces the player who placed it and shows {@link #ACTIVE} while it works, which selects the
 * model with the lit, animated front. Registers no ticker; its block entity's task runs in the level's Lib task group.
 * Drops itself with everything it holds, creative breaking included.
 */
public final class StorageMachineBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Set while the machine works; changed only when that flips. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private final MachineKind kind;
    private final MapCodec<StorageMachineBlock> codec;

    public StorageMachineBlock(MachineKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        this.codec = simpleCodec(p -> new StorageMachineBlock(kind, p));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
    }

    public MachineKind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
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

    /** The placer owns the machine; this runs after a placed item restored the block entity's saved data. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof StorageMachineBlockEntity machine) {
            machine.setOwner(OwnerRecord.of(player));
        }
    }

    @Override
    public StorageMachineBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return kind == MachineKind.FUEL_GENERATOR
                ? new FuelGeneratorBlockEntity(MachineContent.blockEntityType(kind), pos, state)
                : new ProcessingMachineBlockEntity(MachineContent.blockEntityType(kind), pos, state, kind);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof StorageMachineBlockEntity machine) {
            machine.neighbourChanged();
        }
    }

    /** Opens the machine's GUI; both sides report success so the client swings the arm. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof StorageMachineBlockEntity machine) {
            StorageMachineMenu.open(serverPlayer, machine);
        }
        return InteractionResult.SUCCESS;
    }

    /** The machine with its contents ({@link StorageMachineBlockEntity#toItemStack}), without a loot table. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof StorageMachineBlockEntity machine) {
            return List.of(machine.toItemStack(params.getLevel().registryAccess()));
        }
        return List.of(new ItemStack(this));
    }

    /** A creative player gets no drops, so a machine that holds something drops itself here, like a shulker box. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()
                && level.getBlockEntity(pos) instanceof StorageMachineBlockEntity machine && !machine.isPristine()) {
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    machine.toItemStack(level.registryAccess()));
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
