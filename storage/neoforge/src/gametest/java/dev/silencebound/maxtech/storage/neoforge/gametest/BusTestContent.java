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
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;

/**
 * GameTest-only blocks for the storage bus tests: a one-tank fluid container of 16,000 mB with a fluid handler on every
 * side, so stores and takes of any amount can be checked exactly (a cauldron only moves whole buckets).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class BusTestContent {
    static final int TANK_CAPACITY = 16_000;
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, TankBlock> TANK = BLOCKS.register("test_bus_tank",
            id -> new TankBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<Tank>> TANK_TYPE = BLOCK_ENTITIES
            .register("test_bus_tank", () -> new BlockEntityType<>(Tank::new, Set.of(TANK.get())));

    private BusTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    @SubscribeEvent
    static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TANK_TYPE.get(), (tank, side) -> tank.handler);
    }

    static final class TankBlock extends Block implements EntityBlock {
        TankBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(TankBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Tank(pos, state);
        }
    }

    static final class Tank extends BlockEntity {
        final FluidStacksResourceHandler handler = new FluidStacksResourceHandler(1, TANK_CAPACITY);

        Tank(BlockPos pos, BlockState state) {
            super(TANK_TYPE.get(), pos, state);
        }
    }
}
