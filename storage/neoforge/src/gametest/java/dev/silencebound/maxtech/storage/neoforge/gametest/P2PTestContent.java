package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Set;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

/** GameTest-only content for the P2P tests: a block that takes FE on every side into a plain 100,000 FE store. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class P2PTestContent {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, SinkBlock> SINK = BLOCKS.register("test_fe_sink",
            id -> new SinkBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<Sink>> SINK_TYPE = BLOCK_ENTITIES
            .register("test_fe_sink", () -> new BlockEntityType<>(Sink::new, Set.of(SINK.get())));

    private P2PTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    @SubscribeEvent
    static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, SINK_TYPE.get(), (sink, side) -> sink.energy);
    }

    static final class SinkBlock extends Block implements EntityBlock {
        SinkBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(SinkBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Sink(pos, state);
        }
    }

    static final class Sink extends BlockEntity {
        final SimpleEnergyHandler energy = new SimpleEnergyHandler(100_000);

        Sink(BlockPos pos, BlockState state) {
            super(SINK_TYPE.get(), pos, state);
        }
    }
}
