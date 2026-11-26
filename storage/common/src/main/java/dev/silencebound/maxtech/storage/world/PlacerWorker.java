package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
// PLATFORM-LEAK: the item comes out of the network in a NeoForge transaction that the placement commits (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The placer port (PRD 7.2, Devices): places a block from the network into the replaceable space it faces. The filter
 * names which items it may place (a blank filter places nothing); it takes the first exact entry the network holds,
 * then the first block item a tag entry matches. One item comes out of the network in a transaction, the owner's fake
 * player uses it on the faced space ({@code ItemStack.useOn}, which fires the loader's place event that claim mods
 * cancel). The transaction commits whenever the placement happened; anything the item left in the stack (an item that
 * is not used up) goes back into the network in the same transaction, or drops at the target if the network refuses it.
 * A refused placement rolls everything back, so it costs nothing and duplicates nothing.
 *
 * <p>
 * In a drop action (B09 item 25) it drops the filtered item instead, any item rather than only block items: a stack or
 * a single item per operation, as an item entity in the faced space moving gently straight out, without a dropper's
 * random spread. It drops only into a loaded space without a full collision block and pauses while
 * {@code world.placer.dropEntityCap} item entities lie in that space; no event reports items leaving, so a continuous
 * placer looks again after its interval while paused. The items come out in a transaction that commits only when the
 * level took the entity, so a refused spawn (an entity join handler) extracts nothing.
 */
final class PlacerWorker extends TriggeredWorker {
    private static final Predicate<Entity> ALIVE = Entity::isAlive;
    /** Blocks per tick a dropped item starts with along the facing; a dropper's is 0.2 to 0.3 plus its spread. */
    private static final double DROP_SPEED = 0.1;
    /** The item entities lying in front, reused by every count so counting allocates no list. */
    private final List<ItemEntity> lying = new ArrayList<>();

    PlacerWorker(Host host) {
        super(host);
    }

    @Override
    public WorldKind kind() {
        return WorldKind.PLACER;
    }

    @Override
    int interval() {
        return StorageServerConfig.PLACER_INTERVAL.getAsInt();
    }

    @Override
    int act(ServerLevel level, BlockPos target, Direction facing, StorageTransfer transfer) {
        if (action() != 0) {
            return drop(level, target, facing, transfer);
        }
        if (!level.isLoaded(target) || !level.getBlockState(target).canBeReplaced()) {
            return NOTHING_HERE;
        }
        ItemKey key = pick(transfer.network().contents(), true);
        if (key == null) {
            return NOTHING_TO_USE;
        }
        ServerPlayer actor = actor(level, facing);
        if (!level.mayInteract(actor, target)) {
            return NOTHING_HERE;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            if (transfer.extract(key, 1, transaction) != 1) {
                return transfer.canOperate() ? NOTHING_TO_USE : Math.max(2, transfer.retryTicks());
            }
            ItemStack stack = key.toStack(1);
            actor.setItemInHand(InteractionHand.MAIN_HAND, stack);
            InteractionResult result = stack.useOn(new UseOnContext(level, actor, InteractionHand.MAIN_HAND, stack,
                    new BlockHitResult(Vec3.atCenterOf(target), facing.getOpposite(), target, false)));
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (!result.consumesAction()) {
                // Not placed (or refused by a protection mod, which restores the world): the item goes back.
                return NOTHING_HERE;
            }
            // Placed: whatever the item left in the fake player's stack (an item that is not used up) goes back too.
            if (!stack.isEmpty()) {
                giveBack(level, target, stack, transfer, transaction);
            }
            transaction.commit();
        }
        return ACTED;
    }

    /** One drop: a stack or one item of the first filtered item the network holds, as an entity in front. */
    private int drop(ServerLevel level, BlockPos target, Direction facing, StorageTransfer transfer) {
        if (!level.isLoaded(target)) {
            return NOTHING_HERE;
        }
        BlockState state = level.getBlockState(target);
        if (state.isCollisionShapeFullBlock(level, target)) {
            return NOTHING_HERE;
        }
        int cap = StorageServerConfig.PLACER_DROP_ENTITY_CAP.getAsInt();
        lying.clear();
        level.getEntities(EntityType.ITEM, new AABB(target), ALIVE, lying, cap);
        int count = lying.size();
        lying.clear();
        if (count >= cap) {
            return BUSY;
        }
        ItemKey key = pick(transfer.network().contents(), false);
        if (key == null) {
            return NOTHING_TO_USE;
        }
        ItemStack stack = key.toStack(1);
        int wanted = kind().actionId(action()).equals(PlacerAction.DROP_ITEM) ? 1 : stack.getMaxStackSize();
        try (Transaction transaction = Transaction.openRoot()) {
            long taken = transfer.extract(key, wanted, transaction);
            if (taken <= 0) {
                return transfer.canOperate() ? NOTHING_TO_USE : Math.max(2, transfer.retryTicks());
            }
            stack.setCount((int) taken);
            // A dropper's spawn point, without its random spread: a gentle push straight out of the face.
            double y = target.getY() + 0.5 - (facing.getAxis() == Direction.Axis.Y ? 0.125 : 0.15625);
            ItemEntity entity = new ItemEntity(level, target.getX() + 0.5, y, target.getZ() + 0.5, stack,
                    facing.getStepX() * DROP_SPEED, facing.getStepY() * DROP_SPEED, facing.getStepZ() * DROP_SPEED);
            entity.setDefaultPickUpDelay();
            if (!level.addFreshEntity(entity)) {
                // Refused (an entity join handler): the transaction rolls the extraction back.
                return BUSY;
            }
            transaction.commit();
        }
        return ACTED;
    }

    /** Stores a leftover stack as one operation in {@code transaction}; what does not fit drops at the target. */
    private static void giveBack(ServerLevel level, BlockPos target, ItemStack stack, StorageTransfer transfer,
            Transaction transaction) {
        long back = transfer.insert(ItemKey.of(stack), stack.getCount(), transaction);
        stack.shrink((int) back);
        if (!stack.isEmpty()) {
            drop(level, target, stack);
        }
    }

    private static void drop(ServerLevel level, BlockPos target, ItemStack stack) {
        Block.popResource(level, target, stack.copy());
        stack.setCount(0);
    }

    /**
     * The first item the filter names that the network holds, exact entries first, only block items when
     * {@code placeable}; null when none.
     */
    // builtInRegistryHolder is deprecated for registry-agnostic code; items always have one.
    @SuppressWarnings("deprecation")
    private @Nullable ItemKey pick(StorageContents contents, boolean placeable) {
        for (ItemKey key : filter().exactKeys()) {
            if ((!placeable || WorldContent.placeable(key)) && contents.amount(key) > 0) {
                return key;
            }
        }
        TagKey<Item>[] tags = filter().tagEntries();
        if (tags.length == 0) {
            return null;
        }
        for (Int2LongMap.Entry entry : contents.snapshot().totals().int2LongEntrySet()) {
            if (entry.getLongValue() > 0 && contents.key(entry.getIntKey()) instanceof ItemKey key
                    && (!placeable || WorldContent.placeable(key))) {
                var holder = key.item().builtInRegistryHolder();
                for (TagKey<Item> tag : tags) {
                    if (holder.is(tag)) {
                        return key;
                    }
                }
            }
        }
        return null;
    }
}
