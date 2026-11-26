package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
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
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;

/**
 * GameTest-only content for the provider tests: a fluid machine with an input tank on top and an output tank on every
 * other side. It never ticks; a test calls {@link FluidMachine#process} to turn its water into as much lava.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class ProviderTestContent {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, FluidMachineBlock> FLUID_MACHINE = BLOCKS.register("test_fluid_machine",
            id -> new FluidMachineBlock(
                    BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<FluidMachine>> FLUID_MACHINE_TYPE = BLOCK_ENTITIES
            .register("test_fluid_machine",
                    () -> new BlockEntityType<>(FluidMachine::new, Set.of(FLUID_MACHINE.get())));

    private ProviderTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    @SubscribeEvent
    static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, FLUID_MACHINE_TYPE.get(),
                (machine, side) -> side == Direction.UP ? machine.input : machine.output);
    }

    static final class FluidMachineBlock extends Block implements EntityBlock {
        FluidMachineBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(FluidMachineBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new FluidMachine(pos, state);
        }
    }

    static final class FluidMachine extends BlockEntity {
        final FluidStacksResourceHandler input = new FluidStacksResourceHandler(1, 16_000);
        final FluidStacksResourceHandler output = new FluidStacksResourceHandler(1, 16_000);

        FluidMachine(BlockPos pos, BlockState state) {
            super(FLUID_MACHINE_TYPE.get(), pos, state);
        }

        /** Turns the input's water into lava in the output. */
        void process() {
            int water = input.getAmountAsInt(0);
            input.set(0, FluidResource.EMPTY, 0);
            output.set(0, FluidResource.of(Fluids.LAVA), output.getAmountAsInt(0) + water);
            setChanged();
        }
    }
}
