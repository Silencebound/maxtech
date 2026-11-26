package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * GameTest-only blocks for the port tests: an inventory whose extraction breaks its promise (every second call takes
 * half), and a storage device whose provider is the chest above it, read into the network's index once when it joins
 * and never told about changes made to the chest by hand (a stale view, as an external inventory can be).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class PortTestContent {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, FlakyBlock> FLAKY = BLOCKS.register("test_flaky_inventory",
            id -> new FlakyBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<FlakyInventory>> FLAKY_TYPE = BLOCK_ENTITIES
            .register("test_flaky_inventory", () -> new BlockEntityType<>(FlakyInventory::new, Set.of(FLAKY.get())));
    static final RegistryEntry<Block, ChestDeviceBlock> CHEST_DEVICE = BLOCKS.register("test_chest_storage_device",
            id -> new ChestDeviceBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ChestDevice>> CHEST_DEVICE_TYPE = BLOCK_ENTITIES
            .register("test_chest_storage_device",
                    () -> new BlockEntityType<>(ChestDevice::new, Set.of(CHEST_DEVICE.get())));

    private PortTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    @SubscribeEvent
    static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, FLAKY_TYPE.get(), (inventory, side) -> inventory.handler);
    }

    static final class FlakyBlock extends Block implements EntityBlock {
        FlakyBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(FlakyBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new FlakyInventory(pos, state);
        }
    }

    /**
     * Four slots; every second extraction from the flaky slot (any slot while {@code flakySlot} is -1) takes half of
     * what it was asked for, whatever a simulation said before. The other slots behave.
     */
    static final class FlakyInventory extends BlockEntity {
        final Handler handler = new Handler();

        FlakyInventory(BlockPos pos, BlockState state) {
            super(FLAKY_TYPE.get(), pos, state);
        }

        static final class Handler extends ItemStacksResourceHandler {
            private int calls;
            int flakySlot = -1;

            Handler() {
                super(4);
            }

            @Override
            public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
                if (flakySlot >= 0 && index != flakySlot) {
                    return super.extract(index, resource, amount, transaction);
                }
                int asked = calls++ % 2 == 0 ? amount : amount / 2;
                return super.extract(index, resource, asked, transaction);
            }
        }
    }

    static final class ChestDeviceBlock extends StorageDeviceBlock {
        ChestDeviceBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(ChestDeviceBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new ChestDevice(pos, state);
        }
    }

    /** A drive-like device whose storage is the chest on top of it. */
    static final class ChestDevice extends StorageDeviceBlockEntity {
        final ChestProvider provider = new ChestProvider(this);

        ChestDevice(BlockPos pos, BlockState state) {
            super(CHEST_DEVICE_TYPE.get(), pos, state);
        }

        @Override
        public Role role() {
            return Role.STORAGE;
        }

        @Override
        public List<StorageProvider> providers() {
            return List.of(provider);
        }

        @Nullable
        ResourceHandler<ItemResource> chest() {
            return level == null
                    ? null
                    : level.getCapability(Capabilities.Item.BLOCK, worldPosition.above(), Direction.DOWN);
        }
    }

    /**
     * The chest above a {@link ChestDevice} as a provider: inserts and extracts go to the chest's handler in the
     * caller's transaction, and what committed is reported to the network after the root commit. Changes made to the
     * chest directly are never reported.
     */
    static final class ChestProvider extends SnapshotJournal<Object2LongOpenHashMap<StackKey>>
            implements
                StorageProvider {
        private final ChestDevice device;
        private final Object2LongOpenHashMap<StackKey> pending = new Object2LongOpenHashMap<>();
        private @Nullable Listener listener;

        ChestProvider(ChestDevice device) {
            this.device = device;
        }

        @Override
        public long insert(StackKey key, long amount, TransactionContext transaction) {
            ResourceHandler<ItemResource> chest = device.chest();
            if (!(key instanceof ItemKey item) || chest == null || amount <= 0) {
                return 0;
            }
            int moved = ResourceHandlerUtil.insertStacking(chest, StackKeys.toResource(item),
                    (int) Math.min(amount, Integer.MAX_VALUE), transaction);
            record(key, moved, transaction);
            return moved;
        }

        @Override
        public long extract(StackKey key, long amount, TransactionContext transaction) {
            ResourceHandler<ItemResource> chest = device.chest();
            if (!(key instanceof ItemKey item) || chest == null || amount <= 0) {
                return 0;
            }
            int moved = chest.extract(StackKeys.toResource(item), (int) Math.min(amount, Integer.MAX_VALUE),
                    transaction);
            record(key, -moved, transaction);
            return moved;
        }

        private void record(StackKey key, long delta, TransactionContext transaction) {
            if (delta != 0) {
                updateSnapshots(transaction);
                pending.addTo(key, delta);
            }
        }

        @Override
        public long amount(StackKey key) {
            ResourceHandler<ItemResource> chest = device.chest();
            if (!(key instanceof ItemKey item) || chest == null) {
                return 0;
            }
            ItemResource resource = StackKeys.toResource(item);
            long total = 0;
            for (int i = 0; i < chest.size(); i++) {
                if (chest.getResource(i).equals(resource)) {
                    total += chest.getAmountAsLong(i);
                }
            }
            return total;
        }

        @Override
        public void forEachStored(Visitor visitor) {
            ResourceHandler<ItemResource> chest = device.chest();
            if (chest == null) {
                return;
            }
            Object2LongOpenHashMap<StackKey> totals = new Object2LongOpenHashMap<>();
            for (int i = 0; i < chest.size(); i++) {
                ItemResource resource = chest.getResource(i);
                if (!resource.isEmpty()) {
                    totals.addTo(StackKeys.of(resource), chest.getAmountAsLong(i));
                }
            }
            for (Object2LongMap.Entry<StackKey> entry : totals.object2LongEntrySet()) {
                visitor.visit(entry.getKey(), entry.getLongValue());
            }
        }

        @Override
        public void setListener(@Nullable Listener listener) {
            this.listener = listener;
        }

        @Override
        protected Object2LongOpenHashMap<StackKey> createSnapshot() {
            return new Object2LongOpenHashMap<>(pending);
        }

        @Override
        protected void revertToSnapshot(Object2LongOpenHashMap<StackKey> snapshot) {
            pending.clear();
            pending.putAll(snapshot);
        }

        @Override
        protected void onRootCommit(Object2LongOpenHashMap<StackKey> original) {
            Listener current = listener;
            if (current != null) {
                for (Object2LongMap.Entry<StackKey> entry : pending.object2LongEntrySet()) {
                    if (entry.getLongValue() != 0) {
                        current.changed(entry.getKey(), entry.getLongValue());
                    }
                }
            }
            pending.clear();
        }
    }
}
