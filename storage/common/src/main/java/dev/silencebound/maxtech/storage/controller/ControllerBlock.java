package dev.silencebound.maxtech.storage.controller;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerTexts;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A storage controller block (PRD 7.2, Controller; M7R item 1): identical blocks placed face to face form one
 * multiblock controller that powers its network with FE and roots its routes through every face that touches a cable or
 * device. Its {@link #STATUS} shows whether the network works; the block entity sets it only when that changes. No
 * ticker: the multiblock is found in a block tick scheduled when a controller block or a module is placed or broken, or
 * when research raises the size cap; the same tick works out a network anchor's chunks after a network change. Its item
 * keeps the stored FE and, from a controller's only block, the name, security rules and redstone alarm rules. While one
 * of the network's redstone alarm rules holds (M7R item 3), every active block gives a signal of 15 on every side.
 */
public final class ControllerBlock extends StorageDeviceBlock {
    public static final MapCodec<ControllerBlock> CODEC = simpleCodec(ControllerBlock::new);
    public static final EnumProperty<ControllerStatus> STATUS = EnumProperty.create("status", ControllerStatus.class);

    public ControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STATUS, ControllerStatus.OFF));
    }

    /** Light per status, for {@code Properties.lightLevel}. */
    public static int light(BlockState state) {
        return state.getValue(STATUS).light();
    }

    /** A new core looks for its cluster on the next tick (its block entity does not exist yet here). */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !oldState.is(this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof ControllerBlockEntity controller) {
            controller.detectCluster(level);
            controller.updateAnchor(level);
        }
    }

    /**
     * Opens the controller's screen (its Network tab) for players with any access to it, from any active block of the
     * multiblock; a dark block or one whose primary is not loaded says why it shows nothing.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ControllerBlockEntity block) {
            ControllerBlockEntity primary = block.primary();
            ControllerBlockEntity core = block.isDark() ? null : primary;
            if (core == null) {
                BlockPos at = BlockPos.of(block.primaryPos());
                serverPlayer.sendOverlayMessage(block.isDark()
                        ? Component.translatable(ControllerContent.DARK_KEY,
                                Integer.toString(primary != null ? primary.cap() : 0))
                        : Component.translatable(ControllerContent.MEMBER_KEY,
                                at.getX() + " " + at.getY() + " " + at.getZ()));
            } else if (ControllerMenu.mayOpen(serverPlayer, core)) {
                ControllerMenu.open(serverPlayer, core, getName());
            } else {
                serverPlayer.sendOverlayMessage(Component.translatable(AnalyzerTexts.DENIED));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** The redstone alarm rules give their signal through the controller's blocks (M7R item 3). */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /** 15 on every side while one of the network's alarm rules holds; 0 otherwise and on a dark block. */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof ControllerBlockEntity block ? block.signal() : 0;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STATUS);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ControllerBlockEntity(pos, state);
    }
}
