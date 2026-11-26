package dev.silencebound.maxtech.storage.cable;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A storage cable's block: Lib's conduit block plus the {@link CableParts} on its faces. The part under the crosshair
 * takes clicks (its menu), breaking (only the part comes off and drops; the cable stays), pick-block (its item) and a
 * wrench (a sneaking wrench takes it into the inventory). With no part hit, or with a facade on the cable, everything
 * is Lib's. Hit-testing uses the cable's shape, which includes the parts, so what the player sees selected is what is
 * hit. No ticker.
 */
public final class StorageCableBlock extends ConduitBlock {
    private final MapCodec<StorageCableBlock> codec;

    public StorageCableBlock(Properties properties, Supplier<? extends ConduitType> type) {
        super(properties, type);
        this.codec = simpleCodec(p -> new StorageCableBlock(p, type));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return codec;
    }

    /** The parts of the cable at {@code pos}, or null if there is no storage cable there. */
    public static @Nullable CableParts parts(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ConduitBlockEntity cable && cable.facade() == null
                && cable.conduit().state() instanceof CableParts parts ? parts : null;
    }

    /**
     * The parts of the cable at {@code pos} even under a facade, or null if there is no storage cable there: a facade
     * hides parts from clicks and info, but they still hear neighbour changes and their scheduled ticks.
     */
    private static @Nullable CableParts heldParts(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ConduitBlockEntity cable
                && cable.conduit().state() instanceof CableParts parts ? parts : null;
    }

    /** The part {@code hit} landed on, or null (a hit on the bare cable, or a facaded cable). */
    public static @Nullable CablePart partAt(BlockGetter level, BlockPos pos, BlockHitResult hit) {
        CableParts parts = parts(level, pos);
        return parts == null ? null : parts.partAt(hit.getLocation(), hit.getDirection());
    }

    /** The part under {@code player}'s crosshair, traced against the cable's shape; null for none. */
    public static @Nullable CablePart partLookedAt(BlockGetter level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof ConduitBlockEntity cable)) {
            return null;
        }
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getViewVector(1.0F).scale(player.blockInteractionRange() + 1.0));
        BlockHitResult hit = cable.shape().clip(from, to, pos);
        return hit == null ? null : partAt(level, pos, hit);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        CablePart part = partAt(level, pos, hit);
        if (part != null) {
            if (stack.is(ToolTags.ACTS_AS_WRENCH)) {
                InteractionResult wrenched = wrench(level, pos, player, hit, player.isSecondaryUseActive());
                if (wrenched != null) {
                    return wrenched;
                }
            }
            InteractionResult result = part.useItemOn(stack, player, hand);
            if (!(result instanceof InteractionResult.TryEmptyHandInteraction)) {
                return result;
            }
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        CablePart part = partAt(level, pos, hit);
        if (part != null && !player.isSecondaryUseActive()) {
            return part.use(player);
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /**
     * A wrench on a part: sneaking takes the part into the inventory (the owner's security applies), otherwise the part
     * gets the wrench as an item use. A wrench on the bare cable is Lib's.
     */
    @Override
    protected @Nullable InteractionResult wrench(Level level, BlockPos pos, Player player, BlockHitResult hit,
            boolean dismantle) {
        CablePart part = partAt(level, pos, hit);
        if (part == null) {
            return null;
        }
        if (!dismantle) {
            InteractionResult result = part.useItemOn(player.getMainHandItem(), player, InteractionHand.MAIN_HAND);
            // Never Lib's face cycling on a part's face: that would let the face link past the part.
            return result instanceof InteractionResult.TryEmptyHandInteraction ? InteractionResult.SUCCESS : result;
        }
        if (!level.isClientSide() && NetworkAccess.canConfigure(player, part)) {
            CablePart removed = part.host().remove(part.face());
            if (removed != null) {
                player.getInventory().placeItemBackInInventory(removed.toItem());
                for (ItemStack content : removed.takeContents()) {
                    player.getInventory().placeItemBackInInventory(content);
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** A left-click with the crosshair on a part goes to the part ({@link CablePart#attack}); server side. */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        super.attack(state, level, pos, player);
        CablePart part = level.isClientSide() ? null : partLookedAt(level, pos, player);
        if (part != null) {
            part.attack(player);
        }
    }

    /** Breaking with the crosshair on a part takes off only that part; the cable stays. */
    // PLATFORM-LEAK: NeoForge's IBlockExtension hook; vanilla has no way to keep the block when breaking a part of it.
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, ItemStack toolStack,
            boolean willHarvest, FluidState fluid) {
        CablePart part = partLookedAt(level, pos, player);
        if (part == null) {
            return super.onDestroyedByPlayer(state, level, pos, player, toolStack, willHarvest, fluid);
        }
        if (!level.isClientSide()) {
            CablePart removed = part.host().remove(part.face());
            if (removed != null && willHarvest) {
                popResource(level, pos, removed.toItem());
            }
            if (removed != null) {
                // What the part holds drops even when the part itself does not (creative mode), as a chest's does.
                for (ItemStack content : removed.takeContents()) {
                    popResource(level, pos, content);
                }
            }
        }
        return false;
    }

    /** Pick-block on a part gives the part's item. */
    // PLATFORM-LEAK: NeoForge's player-aware IBlockExtension overload, the only one that can tell which part is aimed
    // at.
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData,
            Player player) {
        CablePart part = partLookedAt(level, pos, player);
        return part != null ? part.toItem() : super.getCloneItemStack(level, pos, state, includeData, player);
    }

    /**
     * The weak redstone signal of the part on the face towards the asking block ({@link CablePart#signal()}); vanilla
     * reads it through {@code SignalGetter.getSignal} whatever {@code isSignalSource} says, so the cable stays no
     * signal source for redstone dust's connections.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        CableParts parts = parts(level, pos);
        CablePart part = parts == null ? null : parts.part(direction.getOpposite());
        return part == null ? 0 : part.signal();
    }

    // PLATFORM-LEAK: NeoForge's IBlockExtension hook; vanilla containers call it through their comparator update
    // whenever their contents change, so a port part wakes on that without polling.
    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        super.onNeighborChange(state, level, pos, neighbor);
        CableParts parts = level instanceof ServerLevel ? heldParts(level, pos) : null;
        if (parts != null) {
            parts.neighbourChanged(neighbor);
        }
    }

    /** A tick a part asked for ({@link CableParts#scheduleTick}); never scheduled otherwise. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        super.tick(state, level, pos, random);
        CableParts parts = heldParts(level, pos);
        if (parts != null) {
            parts.scheduledTick();
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        CableParts parts = level.isClientSide() ? null : heldParts(level, pos);
        if (parts != null) {
            parts.neighbourChanged(null);
        }
    }
}
