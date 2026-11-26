package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Set;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import org.jspecify.annotations.Nullable;

/**
 * GameTest-only content for the pack reader tests: a tank with two fluid tanks and a few fields in its saved data
 * ({@code Level} (the fill), {@code Energy.stored}, {@code Active}, {@code Mode}, {@code Ints}) that the test readers
 * in {@code src/gametest/resources/data/maxtech_storage/maxtech_storage/readers/test/} read. It never ticks. Also a
 * MaxTech energy machine for the energy network values (MLE item 3).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class SensorTestContent {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, TankBlock> TANK = BLOCKS.register("test_sensor_tank",
            id -> new TankBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<Tank>> TANK_TYPE = BLOCK_ENTITIES
            .register("test_sensor_tank", () -> new BlockEntityType<>(Tank::new, Set.of(TANK.get())));

    static final RegistryEntry<Block, MachineBlock> MU_MACHINE = BLOCKS.register("test_sensor_mu_machine",
            id -> new MachineBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<Machine>> MU_MACHINE_TYPE = BLOCK_ENTITIES
            .register("test_sensor_mu_machine", () -> new BlockEntityType<>(Machine::new, Set.of(MU_MACHINE.get())));

    private SensorTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    @SubscribeEvent
    static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TANK_TYPE.get(), (tank, side) -> tank.tanks);
        BlockCapability<EnergyEndpoint, @Nullable Direction> endpoint = BlockCapability
                .createSided(EnergyNetworks.ENDPOINT_CAPABILITY, EnergyEndpoint.class);
        event.registerBlockEntity(endpoint, MU_MACHINE_TYPE.get(), (machine, side) -> machine.endpoint);
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

    static final class MachineBlock extends Block implements EntityBlock {
        MachineBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(MachineBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Machine(pos, state);
        }
    }

    /** A MaxTech energy machine (MLE): one consumer endpoint, shown through Lib's endpoint capability; not saved. */
    static final class Machine extends BlockEntity {
        final EnergyEndpoint endpoint = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);

        Machine(BlockPos pos, BlockState state) {
            super(MU_MACHINE_TYPE.get(), pos, state);
        }
    }

    static final class Tank extends BlockEntity {
        final FluidStacksResourceHandler tanks = new FluidStacksResourceHandler(2, 8_000);
        int fill;
        long stored;
        boolean active;
        String mode = "idle";
        /** Times the block entity was saved, so tests see how often a sensor read its saved data. */
        int saves;

        Tank(BlockPos pos, BlockState state) {
            super(TANK_TYPE.get(), pos, state);
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            saves++;
            output.putInt("Level", fill);
            output.child("Energy").putLong("stored", stored);
            output.putBoolean("Active", active);
            output.putString("Mode", mode);
            output.putIntArray("Ints", new int[]{3, 1, 4});
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            fill = input.getIntOr("Level", 0);
            active = input.getBooleanOr("Active", false);
            mode = input.getStringOr("Mode", "idle");
        }
    }
}
