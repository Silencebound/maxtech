package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternHost;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * GameTest-only content for the crafting tests: a pattern host device whose provider runs whatever patterns the test
 * gives it and accepts every push, standing in for M7c's pattern provider.
 */
final class CraftingTestContent {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, HostBlock> HOST = BLOCKS.register("test_pattern_host",
            id -> new HostBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<Host>> HOST_TYPE = BLOCK_ENTITIES
            .register("test_pattern_host", () -> new BlockEntityType<>(Host::new, Set.of(HOST.get())));

    private CraftingTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    static final class HostBlock extends StorageDeviceBlock {
        HostBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(HostBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Host(pos, state);
        }
    }

    /** A device running the patterns a test sets with {@link #setPatterns}. */
    static final class Host extends StorageDeviceBlockEntity implements PatternHost, PatternProvider {
        private List<CraftingPattern> patterns = List.of();
        /** Crafts pushed so far. */
        int pushed;

        Host(BlockPos pos, BlockState state) {
            super(HOST_TYPE.get(), pos, state);
        }

        void setPatterns(List<CraftingPattern> value) {
            patterns = new ArrayList<>(value);
            deviceLink().patternsChanged();
        }

        @Override
        public Role role() {
            return Role.OTHER;
        }

        @Override
        public PatternProvider patternProvider() {
            return this;
        }

        @Override
        public List<CraftingPattern> patterns() {
            return patterns;
        }

        @Override
        public boolean canAccept(CraftingPattern pattern) {
            return patterns.contains(pattern);
        }

        @Override
        public boolean push(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs) {
            pushed += crafts;
            return true;
        }
    }
}
