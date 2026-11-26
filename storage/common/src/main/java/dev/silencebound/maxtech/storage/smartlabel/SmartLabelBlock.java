package dev.silencebound.maxtech.storage.smartlabel;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.display.DisplayWalls;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The smart label (M7C item 19): a 12 x 8 px plate stuck on any face of any solid block, showing an item and its count,
 * how full the block is, or the state of the machine it sits on ({@link LabelMode}). Its front ({@link #FACING}) faces
 * away from the block it sits on; it pops off when that block goes. A player who may configure it right-clicks with an
 * item to show that item's count, or empty-handed to switch what it shows. No collision, no ticker: its values come
 * from {@link LabelWorker}, and it drops with its settings through {@link StorageDeviceBlock}.
 */
public final class SmartLabelBlock extends StorageDeviceBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final MapCodec<SmartLabelBlock> CODEC = simpleCodec(SmartLabelBlock::new);
    /** The plate's size in pixels: across, up and deep, seen from the front. */
    public static final int WIDTH = 12;
    public static final int HEIGHT = 8;
    public static final int DEPTH = 1;
    private static final VoxelShape[] SHAPES = new VoxelShape[6];

    static {
        for (Direction facing : Direction.values()) {
            double[] min = new double[3];
            double[] max = new double[3];
            span(min, max, DisplayWalls.right(facing).getAxis(), 8 - WIDTH / 2.0, 8 + WIDTH / 2.0);
            span(min, max, DisplayWalls.up(facing).getAxis(), 8 - HEIGHT / 2.0, 8 + HEIGHT / 2.0);
            // Against the back: the face of the block space towards the block the label sits on.
            boolean positive = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            span(min, max, facing.getAxis(), positive ? 0 : 16 - DEPTH, positive ? DEPTH : 16);
            SHAPES[facing.get3DDataValue()] = Block.box(min[0], min[1], min[2], max[0], max[1], max[2]);
        }
    }

    public SmartLabelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    private static void span(double[] min, double[] max, Direction.Axis axis, double from, double to) {
        int index = switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        min[index] = from;
        max[index] = to;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Faces away from the clicked block, which it sits on; null where it could not stay. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getClickedFace());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get3DDataValue()];
    }

    /** It sits on anything that is not air or replaceable (grass, snow layers, water). */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState support = level.getBlockState(pos.relative(state.getValue(FACING).getOpposite()));
        return !support.isAir() && !support.canBeReplaced() && !support.is(this);
    }

    /** Pops off (and drops with its settings) when the block it sits on goes. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (directionToNeighbour == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
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
        return new SmartLabelBlockEntity(pos, state);
    }

    /** A block update names no position, so any may come from the block it sits on: read it again. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SmartLabelBlockEntity label) {
            label.worker().targetChanged();
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockExtension hook; containers call it through their comparator update whenever
    // their contents change, so a label reads them again without waiting for its interval.
    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        if (level instanceof ServerLevel && neighbor.equals(pos.relative(state.getValue(FACING).getOpposite()))
                && level.getBlockEntity(pos) instanceof SmartLabelBlockEntity label) {
            label.worker().targetChanged();
        }
    }

    /**
     * An item in hand: the label shows that item's count. Another smart label passes, so labels can be placed next to
     * each other; sneaking places blocks against the label as usual.
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (stack.is(asItem())) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SmartLabelBlockEntity label
                && NetworkAccess.canConfigure(player, label)) {
            label.setSettings(label.settings().withItem(stack.getItem()));
            player.sendOverlayMessage(
                    Component.translatable(SmartLabelContent.SHOWS_ITEM_KEY, stack.getItem().getName(stack)));
        }
        return InteractionResult.SUCCESS;
    }

    /** Empty-handed: switches to the next {@link LabelMode}. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SmartLabelBlockEntity label
                && NetworkAccess.canConfigure(player, label)) {
            LabelMode next = label.settings().mode().next();
            label.setSettings(label.settings().withMode(next));
            player.sendOverlayMessage(
                    Component.translatable(SmartLabelContent.SHOWS_KEY, Component.translatable(next.translationKey())));
        }
        return InteractionResult.SUCCESS;
    }
}
