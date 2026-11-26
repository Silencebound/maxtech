package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
// PLATFORM-LEAK: the drops go into the network in a NeoForge transaction before the block is broken (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The breaker port (PRD 7.2, Devices): breaks the block it faces and stores the drops in its network. It breaks any
 * block up to {@code world.breaker.maxHardness} and gets what the block's loot table gives without a tool; a tool or an
 * enchanted book in its slot lends the loot tables its enchantments (silk touch, fortune) and its kind (a pickaxe for
 * amethyst clusters). The tool never wears: each break costs FE through the network instead. Unbreakable blocks,
 * liquids and the {@code maxtech_storage:breaker_blacklist} tag are never broken; a filter limits it to the blocks
 * whose items it names. The owner's fake player must pass the loader's break event (claims) and spawn protection. The
 * drops are stored first, all in one transaction, and the block breaks only when every drop fit, so a full network
 * leaves the block standing and loses nothing.
 */
final class BreakerWorker extends TriggeredWorker {
    private final SimpleContainer tool = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            host.saved().run();
        }
    };

    BreakerWorker(Host host) {
        super(host);
    }

    @Override
    public WorldKind kind() {
        return WorldKind.BREAKER;
    }

    @Override
    public Container tool() {
        return tool;
    }

    @Override
    int interval() {
        return StorageServerConfig.BREAKER_INTERVAL.getAsInt();
    }

    @Override
    int act(ServerLevel level, BlockPos target, Direction facing, StorageTransfer transfer) {
        if (!level.isLoaded(target)) {
            return NOTHING_HERE;
        }
        BlockState state = level.getBlockState(target);
        if (!breakable(level, target, state)
                || (!filter().isBlank() && !filter().matches(new ItemStack(state.getBlock())))) {
            return NOTHING_HERE;
        }
        ServerPlayer actor = actor(level, facing);
        if (!level.mayInteract(actor, target) || !WorldActors.get().mayBreak(level, target, state, actor)) {
            return NOTHING_HERE;
        }
        ItemStack used = virtualTool();
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(target) : null;
        List<ItemStack> drops = Block.getDrops(state, level, target, blockEntity, actor, used);
        try (Transaction transaction = Transaction.openRoot()) {
            for (ItemStack drop : drops) {
                if (!drop.isEmpty()
                        && transfer.insert(ItemKey.of(drop), drop.getCount(), transaction) < drop.getCount()) {
                    return transfer.canOperate() ? NOTHING_TO_USE : Math.max(2, transfer.retryTicks());
                }
            }
            level.destroyBlock(target, false, actor);
            state.spawnAfterBreak(level, target, used, true);
            transaction.commit();
        }
        return ACTED;
    }

    /** Whether the breaker may break {@code state} at all: hardness within the cap, no liquid, not blacklisted. */
    static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.liquid() || state.is(WorldContent.BREAKER_BLACKLIST)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0 && hardness <= StorageServerConfig.BREAKER_MAX_HARDNESS.getAsDouble();
    }

    /**
     * What the loot tables see as the tool: nothing, a copy of the kept tool, or for an enchanted book a stick carrying
     * the book's enchantments, so silk touch and fortune work without a tool type.
     */
    ItemStack virtualTool() {
        ItemStack kept = tool.getItem(0);
        if (kept.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (kept.is(Items.ENCHANTED_BOOK)) {
            ItemStack stick = new ItemStack(Items.STICK);
            ItemEnchantments stored = kept.get(DataComponents.STORED_ENCHANTMENTS);
            if (stored != null) {
                stick.set(DataComponents.ENCHANTMENTS, stored);
            }
            return stick;
        }
        return kept.copy();
    }
}
