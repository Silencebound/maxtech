package dev.silencebound.maxtech.storage.display;

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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A display panel (PRD 7.2, Devices; replaces the storage monitor): a 3 px plate against the block behind it with its
 * screen towards {@link #FACING}. Panels that touch in one plane and face the same way merge into wall screens
 * ({@link DisplayWalls}), re-formed one block tick after a panel is placed or broken. A storage device on every side
 * but its screen, so a wall needs one cable touching any of its panels. No ticker.
 *
 * <p>
 * The frame properties say which edges of the panel (as the viewer sees it) lie on its screen's outer edge; the model
 * draws the frame there only, so a wall reads as one screen. {@link DisplayWalls} sets them when it re-forms the walls;
 * a fresh panel is framed all round until then.
 */
public final class DisplayPanelBlock extends StorageDeviceBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty FRAME_LEFT = BooleanProperty.create("frame_left");
    public static final BooleanProperty FRAME_RIGHT = BooleanProperty.create("frame_right");
    public static final BooleanProperty FRAME_BOTTOM = BooleanProperty.create("frame_bottom");
    public static final BooleanProperty FRAME_TOP = BooleanProperty.create("frame_top");
    public static final MapCodec<DisplayPanelBlock> CODEC = simpleCodec(DisplayPanelBlock::new);
    private static final VoxelShape[] SHAPES = {Block.box(0, 13, 0, 16, 16, 16), Block.box(0, 0, 0, 16, 3, 16),
            Block.box(0, 0, 13, 16, 16, 16), Block.box(0, 0, 0, 16, 16, 3), Block.box(13, 0, 0, 16, 16, 16),
            Block.box(0, 0, 0, 3, 16, 16)};

    public DisplayPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(withFrame(stateDefinition.any().setValue(FACING, Direction.NORTH), WallLayout.ALL));
    }

    /** {@code state} with the frame on the edges in {@code edges} ({@link WallLayout#frame} bits). */
    public static BlockState withFrame(BlockState state, int edges) {
        return state.setValue(FRAME_LEFT, (edges & WallLayout.LEFT) != 0)
                .setValue(FRAME_RIGHT, (edges & WallLayout.RIGHT) != 0)
                .setValue(FRAME_BOTTOM, (edges & WallLayout.BOTTOM) != 0)
                .setValue(FRAME_TOP, (edges & WallLayout.TOP) != 0);
    }

    /** The edges of {@code state} that carry the frame, as {@link WallLayout#frame} bits. */
    public static int frame(BlockState state) {
        return (state.getValue(FRAME_LEFT) ? WallLayout.LEFT : 0) | (state.getValue(FRAME_RIGHT) ? WallLayout.RIGHT : 0)
                | (state.getValue(FRAME_BOTTOM) ? WallLayout.BOTTOM : 0)
                | (state.getValue(FRAME_TOP) ? WallLayout.TOP : 0);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FRAME_LEFT, FRAME_RIGHT, FRAME_BOTTOM, FRAME_TOP);
    }

    /** The screen faces away from the clicked block, the plate lies against it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get3DDataValue()];
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
        return new DisplayPanelBlockEntity(pos, state);
    }

    /** The block entity does not exist yet here, so the wall re-forms on the next block tick. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !oldState.is(this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        DisplayWalls.reform(level, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        DisplayWalls.removed(level, pos, state.getValue(FACING));
    }

    /**
     * A fresh panel placed next to a wall takes the wall's settings, so it extends the wall instead of resetting it.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && DisplayItems.read(stack) == null
                && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel) {
            Direction facing = state.getValue(FACING);
            for (Direction step : Direction.values()) {
                DisplayPanelBlockEntity neighbour = step.getAxis() == facing.getAxis()
                        ? null
                        : DisplayWalls.panel(level, pos.relative(step), facing);
                if (neighbour != null) {
                    panel.setSettings(neighbour.settings());
                    break;
                }
            }
        }
    }

    /**
     * The item a wall showing one item's count shows, in hand: stores it in the network ({@link DisplayTerminal}).
     * Anything else falls through to the empty-hand use.
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel)
                || !DisplayTerminal.shows(panel.settings(), stack)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        // A refused store (no access, out of reach, full network) falls through, so the settings still open.
        return level.isClientSide() || DisplayTerminal.store(player, hand, panel, panel.settings(), pos) != 0
                ? InteractionResult.SUCCESS
                : InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    /**
     * On a wall showing a list, a click on a row goes to the list (a task ticked, an alarm acknowledged) unless the
     * player sneaks. Right after storing on this wall, stores every matching stack of the inventory (a double-click,
     * {@link DisplayTerminal#storeAll}); otherwise opens the settings GUI of the panel's wall for players who may
     * configure it ({@link NetworkAccess}).
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel && !player.isShiftKeyDown()
                && panel.clickRow(serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel
                && !DisplayTerminal.storeAll(player, panel, panel.settings(), pos)
                && NetworkAccess.canConfigure(player, panel)) {
            DisplayMenu.open(serverPlayer, panel, pos, -1, getName());
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * A left-click on a wall showing one item's count takes one of it, sneaking a stack ({@link DisplayTerminal}).
     * Vanilla calls this on the server when a survival or adventure player starts breaking the block.
     */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        super.attack(state, level, pos, player);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel) {
            DisplayTerminal.take(player, panel, panel.settings(), pos, player.isShiftKeyDown());
        }
    }

    /** The loot table's panel carries its settings, like the part's item. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof DisplayPanelBlockEntity panel) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    DisplayItems.write(drop, panel.settings());
                }
            }
        }
        return drops;
    }
}
