package dev.silencebound.maxtech.storage.drive;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
 * The drive block, facing the player who placed it. Using it with a cell puts the cell in the first free slot; using it
 * with an empty hand opens its screen ({@link DriveMenu}); sneak-using it with an empty hand takes out the cell in the
 * highest filled slot. Each needs full access to the drive. It drops itself with its cells and priority, creative
 * breaking included, like a machine. No ticker.
 */
public final class DriveBlock extends StorageDeviceBlock {
    public static final MapCodec<DriveBlock> CODEC = simpleCodec(DriveBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public DriveBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(FACING, Direction.NORTH));
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
        return new DriveBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof StorageCellItem)
                || !(level.getBlockEntity(pos) instanceof DriveBlockEntity drive)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (level.isClientSide()) {
            // The client knows which slots are filled from the synced bands.
            for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
                if (DriveBlockEntity.band(drive.bands(), slot) == DriveBlockEntity.BAND_EMPTY) {
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        }
        if (!NetworkAccess.canConfigure(player, drive)) {
            return InteractionResult.FAIL;
        }
        return drive.insertCell(stack) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !(level.getBlockEntity(pos) instanceof DriveBlockEntity drive)) {
            return InteractionResult.PASS;
        }
        boolean takeOut = player.isSecondaryUseActive();
        if (level.isClientSide()) {
            return takeOut && drive.bands() == 0 ? InteractionResult.PASS : InteractionResult.SUCCESS;
        }
        if (!NetworkAccess.canConfigure(player, drive)) {
            return InteractionResult.FAIL;
        }
        if (!takeOut) {
            if (player instanceof ServerPlayer server) {
                DriveMenu.open(server, drive, getName());
            }
            return InteractionResult.SUCCESS;
        }
        ItemStack cell = drive.removeCell();
        if (cell.isEmpty()) {
            return InteractionResult.PASS;
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, cell);
        return InteractionResult.SUCCESS;
    }

    /** The drive with its cells ({@link DriveBlockEntity#toItemStack}), without a loot table. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof DriveBlockEntity drive) {
            return List.of(drive.toItemStack(params.getLevel().registryAccess()));
        }
        return List.of(new ItemStack(this));
    }

    /** A creative player gets no drops, so a drive holding cells drops itself here, like a shulker box. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()
                && level.getBlockEntity(pos) instanceof DriveBlockEntity drive && drive.hasContents()) {
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    drive.toItemStack(level.registryAccess()));
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
