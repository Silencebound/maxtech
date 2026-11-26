package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * GameTest-only content for the network operation tests: a relay, a storage device whose item handler stores whatever
 * is inserted into it straight into its network through {@code DeviceLink.transfer()}, inside the inserter's
 * transaction, the way an interface answers a pipe.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class NetworkOpsTestContent {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, RelayBlock> RELAY = BLOCKS.register("test_relay_device",
            id -> new RelayBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<RelayDevice>> RELAY_TYPE = BLOCK_ENTITIES
            .register("test_relay_device", () -> new BlockEntityType<>(RelayDevice::new, Set.of(RELAY.get())));

    private NetworkOpsTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    @SubscribeEvent
    static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, RELAY_TYPE.get(), (relay, side) -> relay.handler);
    }

    static final class RelayBlock extends StorageDeviceBlock {
        RelayBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(RelayBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new RelayDevice(pos, state);
        }
    }

    /** A device whose one empty slot passes every insert on to the network as one operation of its own. */
    static final class RelayDevice extends StorageDeviceBlockEntity {
        final Handler handler = new Handler();
        /** Inserts the relay passed on, rolled-back simulations included. */
        int forwarded;

        RelayDevice(BlockPos pos, BlockState state) {
            super(RELAY_TYPE.get(), pos, state);
        }

        @Override
        public Role role() {
            return Role.TRANSFER;
        }

        final class Handler extends ItemStacksResourceHandler {
            Handler() {
                super(1);
            }

            @Override
            public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
                StorageTransfer transfer = deviceLink().transfer();
                if (transfer == null || resource.isEmpty() || amount <= 0) {
                    return 0;
                }
                forwarded++;
                return (int) transfer.insert(StackKeys.of(resource), amount, transaction);
            }
        }
    }
}
