package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import dev.silencebound.maxtech.lib.api.transfer.TransferPorts;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * Blocks for the energy port GameTests: a port with a pulling and a pushing standalone MU buffer and a plain FE handler
 * of its own as its capability (which the port never uses), and a plain NeoForge FE store of settable capacity.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TestEnergy {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry().registrar(MaxtechLib.MOD_ID,
            Registries.BLOCK);
    public static final RegistryEntry<Block, PortBlock> PORT = BLOCKS.register("test_energy_port",
            id -> new PortBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    public static final RegistryEntry<Block, StoreBlock> STORE = BLOCKS.register("test_energy_store",
            id -> new StoreBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<PortEntity>> PORT_ENTITY = BLOCK_ENTITIES
            .register("test_energy_port", () -> new BlockEntityType<>(PortEntity::new, Set.of(PORT.get())));
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<StoreEntity>> STORE_ENTITY = BLOCK_ENTITIES
            .register("test_energy_store", () -> new BlockEntityType<>(StoreEntity::new, Set.of(STORE.get())));

    /** The group the next port block entity made joins; Lib's transfer group when null. */
    private static @Nullable TaskGroup nextGroup;

    private TestEnergy() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, PORT_ENTITY.get(), (entity, side) -> entity.plain);
        event.registerBlockEntity(Capabilities.Energy.BLOCK, STORE_ENTITY.get(), (entity, side) -> entity.handler);
    }

    /** Places an energy port at {@code pos} that runs in Lib's transfer group. */
    public static PortEntity place(ServerLevel level, BlockPos pos, int flags) {
        level.setBlock(pos, PORT.get().defaultBlockState(), flags);
        return (PortEntity) Objects.requireNonNull(level.getBlockEntity(pos), "no port block entity");
    }

    /** Places an energy port at {@code pos} whose task joins {@code group}, so a test can count its runs. */
    public static PortEntity place(ServerLevel level, BlockPos pos, TaskGroup group, int flags) {
        nextGroup = group;
        try {
            return place(level, pos, flags);
        } finally {
            nextGroup = null;
        }
    }

    /** Places a plain FE store of {@code capacity} FE holding {@code amount} at {@code pos}. */
    public static StoreEntity store(ServerLevel level, BlockPos pos, int capacity, int amount, int flags) {
        level.setBlock(pos, STORE.get().defaultBlockState(), flags);
        StoreEntity store = (StoreEntity) Objects.requireNonNull(level.getBlockEntity(pos), "no store block entity");
        store.handler.setCapacity(capacity);
        store.handler.set(amount);
        return store;
    }

    /** Forwards both neighbour callbacks to the port, as a real host must. */
    public static final class PortBlock extends Block implements EntityBlock {
        static final MapCodec<PortBlock> CODEC = simpleCodec(PortBlock::new);

        PortBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new PortEntity(pos, state);
        }

        @Override
        protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                @Nullable Orientation orientation, boolean movedByPiston) {
            if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof PortEntity port) {
                port.port.neighbourChanged();
            }
        }

        @Override
        public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
            if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof PortEntity port) {
                port.port.neighbourChanged();
            }
        }
    }

    /**
     * Standalone MU buffers {@code input} (pull) and {@code output} (push) of 10,000 MU each, and a plain FE handler of
     * 10,000 FE as its capability. {@code moved} counts the port's change callbacks. Saves the buffers and the port.
     */
    public static final class PortEntity extends BlockEntity {
        public final EnergyEndpoint input = EnergyEndpoint.buffer(10_000, 0, 0, EnergyPriority.DEFAULT);
        public final EnergyEndpoint output = EnergyEndpoint.buffer(10_000, 0, 0, EnergyPriority.DEFAULT);
        public final SimpleEnergyHandler plain = new SimpleEnergyHandler(10_000);
        public final TransferPort port;
        public int moved;

        PortEntity(BlockPos pos, BlockState state) {
            super(PORT_ENTITY.get(), pos, state);
            TransferPorts.Builder builder = TransferPorts.builder()
                    .energy(input, Set.of(PortFaceMode.PULL), this::changed)
                    .energy(output, Set.of(PortFaceMode.PUSH), this::changed).onSettingsChanged(this::setChanged);
            TaskGroup group = nextGroup;
            if (group != null) {
                builder.tasks(level -> group);
            }
            port = builder.build();
        }

        /** MU in both buffers. */
        public long stored() {
            return input.stored() + output.stored();
        }

        private void changed() {
            moved++;
            setChanged();
            port.bufferChanged();
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level instanceof ServerLevel server) {
                port.start(server, worldPosition);
            }
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            port.stop();
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            output.putLong("input", input.stored());
            output.putLong("output", this.output.stored());
            port.save(output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            this.input.setStored(input.getLongOr("input", 0L));
            output.setStored(input.getLongOr("output", 0L));
            port.load(input);
        }
    }

    /** A holder for the plain FE store. */
    public static final class StoreBlock extends Block implements EntityBlock {
        static final MapCodec<StoreBlock> CODEC = simpleCodec(StoreBlock::new);

        StoreBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new StoreEntity(pos, state);
        }
    }

    /** A plain NeoForge FE store. */
    public static final class StoreEntity extends BlockEntity {
        public final Store handler = new Store();

        StoreEntity(BlockPos pos, BlockState state) {
            super(STORE_ENTITY.get(), pos, state);
        }
    }

    /** NeoForge's FE store with a capacity a test sets, taking and giving any amount per call. */
    public static final class Store extends SimpleEnergyHandler {
        Store() {
            super(100_000);
        }

        void setCapacity(int value) {
            capacity = value;
            maxInsert = value;
            maxExtract = value;
        }
    }
}
