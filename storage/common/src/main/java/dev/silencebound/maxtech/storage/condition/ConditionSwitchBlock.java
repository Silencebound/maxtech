package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The condition switch as a block: a storage device that gives a weak redstone signal of 15 on every side while its
 * condition holds ({@link #POWERED}, which also lights its faces), like a redstone block. Registers no ticker: its
 * block entity's evaluations run on the network's scheduler when an event asks for one.
 */
public final class ConditionSwitchBlock extends StorageDeviceBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final MapCodec<ConditionSwitchBlock> CODEC = simpleCodec(ConditionSwitchBlock::new);

    public ConditionSwitchBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, LinkLook.PROPERTY);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (!state.getValue(POWERED)) {
            return 0;
        }
        // Logic mode may give less than 15. A client-side copy knows no strength (0) and reads 15.
        int strength = level.getBlockEntity(pos) instanceof ConditionSwitchBlockEntity entity
                ? entity.worker().strength()
                : 0;
        return strength > 0 ? strength : 15;
    }

    /** A neighbour changed: a switch writing a redstone channel reads its input again (M7L2). */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ConditionSwitchBlockEntity entity) {
            entity.worker().inputChanged();
        }
    }

    /** The loot table's switch carries its settings, like the part's item. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof ConditionSwitchBlockEntity entity) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(entity.collectComponents());
                }
            }
        }
        return drops;
    }

    /** Opens the GUI for players the owner lets in. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ConditionSwitchBlockEntity entity
                && NetworkAccess.canConfigure(player, entity)) {
            ConditionMenu.open(serverPlayer, entity, pos, getName());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConditionSwitchBlockEntity(pos, state);
    }
}
