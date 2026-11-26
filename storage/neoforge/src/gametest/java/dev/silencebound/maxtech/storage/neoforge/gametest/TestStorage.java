package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.content.Content;
import dev.silencebound.maxtech.lib.network.NetworkType;
import dev.silencebound.maxtech.lib.network.NodeConnectivity;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * GameTest-only content: a storage device holding a {@link MemoryProvider}, on the real storage network type and on a
 * test network type ({@link #SIM_ID}) whose connectivity treats positions in {@link #UNLOADED} as unloaded, with tier-1
 * cables of that type. Registered in the mod's namespace from the gametest source set, so it never ships.
 */
final class TestStorage {
    static final Identifier SIM_ID = MaxtechStorage.id("gametest_storage");
    static final LongOpenHashSet UNLOADED = new LongOpenHashSet();
    static final NetworkType SIM = StorageNetworks.register(SIM_ID, Simulated::new);
    /** Units a test device's provider holds at most. */
    static final long DEVICE_CAPACITY = 100_000;

    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final Content TYPES = Content.create(MaxtechStorage.MOD_ID);

    static final RegistryEntry<Block, TestDeviceBlock> DEVICE = BLOCKS.register("test_storage_device",
            id -> new TestDeviceBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)),
                    StorageNetworks.ID));
    static final RegistryEntry<Block, TestDeviceBlock> SIM_DEVICE = BLOCKS.register("test_sim_storage_device",
            id -> new TestDeviceBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)),
                    SIM_ID));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<TestDevice>> DEVICE_TYPE = BLOCK_ENTITIES.register(
            "test_storage_device",
            () -> new BlockEntityType<>(TestDevice::new, Set.of(DEVICE.get(), SIM_DEVICE.get())));
    static final RegistryEntry<ConduitType, StorageCableConduitType> SIM_CABLE = TYPES
            .conduitWithoutItem("test_sim_storage_cable", () -> new StorageCableConduitType(SIM_ID, 1,
                    CableContent.TIER_1_COLOUR, CableContent.THICKNESS, () -> Items.STRING), StorageCableBlock::new);

    private TestStorage() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    /** A test device block on the storage network type {@code networkId}. */
    static final class TestDeviceBlock extends StorageDeviceBlock {
        private final Identifier networkId;
        private final MapCodec<TestDeviceBlock> codec;

        TestDeviceBlock(Properties properties, Identifier networkId) {
            super(properties);
            this.networkId = networkId;
            this.codec = simpleCodec(p -> new TestDeviceBlock(p, networkId));
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return codec;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new TestDevice(pos, state);
        }
    }

    /** A storage device with one in-memory provider; its role is a drive's. */
    static final class TestDevice extends StorageDeviceBlockEntity {
        final MemoryProvider provider = new MemoryProvider(DEVICE_CAPACITY, 0);

        TestDevice(BlockPos pos, BlockState state) {
            super(DEVICE_TYPE.get(), pos, state);
        }

        @Override
        public Identifier networkId() {
            return getBlockState().getBlock() instanceof TestDeviceBlock block ? block.networkId : StorageNetworks.ID;
        }

        @Override
        public Role role() {
            return Role.STORAGE;
        }

        @Override
        public List<StorageProvider> providers() {
            return List.of(provider);
        }
    }

    /** Storage connectivity, except that positions in {@link #UNLOADED} count as unloaded. */
    private record Simulated(NodeConnectivity delegate) implements NodeConnectivity {
        @Override
        public void connections(long pos, LongList out) {
            if (!UNLOADED.contains(pos)) {
                delegate.connections(pos, out);
            }
        }

        @Override
        public boolean isLoaded(long pos) {
            return !UNLOADED.contains(pos) && delegate.isLoaded(pos);
        }
    }
}
