package dev.silencebound.maxtech.storage.controller.module;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import dev.silencebound.maxtech.storage.controller.recorder.RecordTexts;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A controller expansion of one {@link ModuleKind} (PRD 7.2, Controller): it joins the cluster of the core it touches,
 * directly or through other expansions. Its {@link #LOAD} shows how busy the part of the network it serves is: 0 dark
 * (no cluster, or over the cap), 1 to 3 rising load, set by the core every few seconds and only when the band changes.
 * No block entity renderer, no ticker; placing one asks the neighbouring core (or the core of a neighbouring expansion)
 * to find its cluster again on the next tick.
 */
public final class ExpansionBlock extends StorageDeviceBlock implements ModuleBlock {
    public static final MapCodec<ExpansionBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(ModuleKind.CODEC.fieldOf("module").forGetter(ExpansionBlock::kind), propertiesCodec())
            .apply(instance, ExpansionBlock::new));
    public static final IntegerProperty LOAD = IntegerProperty.create("load", 0, 3);
    /** Block light per load band. */
    private static final int[] LIGHT = {0, 4, 7, 10};
    private static final Direction[] SIDES = Direction.values();

    private final ModuleKind kind;

    public ExpansionBlock(ModuleKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(LOAD, 0));
    }

    @Override
    public ModuleKind kind() {
        return kind;
    }

    /** Light per load band, for {@code Properties.lightLevel}. */
    public static int light(BlockState state) {
        return LIGHT[state.getValue(LOAD)];
    }

    /** The band of a load in percent for an active expansion: 1 up to a third, 2 up to two thirds, else 3. */
    public static int band(int loadPercent) {
        if (loadPercent > 66) {
            return 3;
        }
        return loadPercent > 33 ? 2 : 1;
    }

    /**
     * Sets the expansion at {@code pos} to {@code band} if it is one and shows another; clients only, no neighbours.
     */
    public static void showLoad(Level level, BlockPos pos, int band) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ExpansionBlock && state.getValue(LOAD) != band) {
            level.setBlock(pos, state.setValue(LOAD, band), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            askCore(server, pos);
        }
    }

    /**
     * A cluster block was placed at {@code pos}: the neighbouring core, or the core of a neighbouring expansion, finds
     * its cluster again on the next tick.
     */
    static void askCore(ServerLevel server, BlockPos pos) {
        for (Direction side : SIDES) {
            BlockPos next = pos.relative(side);
            if (!server.isLoaded(next)) {
                continue;
            }
            BlockEntity entity = server.getBlockEntity(next);
            if (entity instanceof ControllerBlockEntity core) {
                core.requestDetect();
            } else if (entity instanceof ExpansionBlockEntity expansion) {
                ControllerBlockEntity core = expansion.coreEntity();
                if (core != null) {
                    core.requestDetect();
                }
            }
        }
    }

    /**
     * The old ledger and alarm modules say that the controller has them built in now (M7R item 3); other expansions
     * have no screen.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (kind.builtIn()) {
            if (player instanceof ServerPlayer serverPlayer) {
                tell(serverPlayer, RecordTexts.RETIRED_INFO);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static void tell(ServerPlayer player, String key) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            player.sendOverlayMessage(Component.translatable(key));
        }
    }

    /** The drop of an old alarm module carries its settings until a controller has taken them. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof ExpansionBlockEntity expansion) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.applyComponents(expansion.collectComponents());
                }
            }
        }
        return drops;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LOAD);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExpansionBlockEntity(pos, state);
    }
}
