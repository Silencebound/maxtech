package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import java.util.List;
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
 * A storage port of one {@link PortTier} (PRD 7.2, Devices): a solid block whose front faces an inventory or tank and
 * whose back, marked with a socket, links to the network. {@link #FACING} points at the inventory, {@link #MODE} says
 * whether it imports, exports or mounts the inventory as storage; a wrench or the GUI cycles the mode. Registers no
 * ticker: its block entity's task runs on the network's scheduler and wakes on neighbour changes.
 */
public final class PortBlock extends StorageDeviceBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final EnumProperty<PortMode> MODE = EnumProperty.create("mode", PortMode.class);
    public static final MapCodec<PortBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(PortTier.CODEC.fieldOf("tier").forGetter(PortBlock::tier), propertiesCodec())
                    .apply(instance, PortBlock::new));
    private final PortTier tier;

    public PortBlock(PortTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE)
                .setValue(FACING, Direction.NORTH).setValue(MODE, PortMode.IMPORT));
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
        builder.add(FACING, MODE, LinkLook.PROPERTY);
    }

    /** Faces the block that was clicked: placed against a chest, it works on the chest. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite()).setValue(MODE,
                PortItems.mode(context.getItemInHand()));
    }

    /** The loot table's port, carrying the filter and mode ({@link PortItems}) like the part's item. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof PortBlockEntity port) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(port.collectComponents());
                }
            }
        }
        return drops;
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
        return new PortBlockEntity(pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level.getBlockEntity(pos) instanceof PortBlockEntity port) {
            port.inventoryChanged();
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockExtension hook; vanilla containers call it through their comparator update
    // whenever their contents change, so a port wakes on that without polling.
    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        if (level instanceof ServerLevel && neighbor.equals(pos.relative(state.getValue(FACING)))
                && level.getBlockEntity(pos) instanceof PortBlockEntity port) {
            port.inventoryChanged();
            // The faced machine's contents changed: a logic pool reading it directly looks again (M7L2).
            dev.silencebound.maxtech.storage.network.NetworkState.neighbourChanged(port);
        }
    }

    /** A wrench switches the mode; anything else falls through to opening the GUI. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(ToolTags.ACTS_AS_WRENCH)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PortBlockEntity port
                && NetworkAccess.canConfigure(player, port)) {
            port.setMode(state.getValue(MODE).next());
        }
        return InteractionResult.SUCCESS;
    }

    /** Opens the port's GUI for players its owner lets in. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof PortBlockEntity port
                && NetworkAccess.canConfigure(player, port)) {
            PortMenu.open(serverPlayer, port, pos, getName());
        }
        return InteractionResult.SUCCESS;
    }
}
