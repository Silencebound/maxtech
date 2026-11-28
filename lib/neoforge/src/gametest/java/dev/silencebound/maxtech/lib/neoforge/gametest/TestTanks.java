package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import dev.silencebound.maxtech.lib.api.transfer.TransferPorts;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridge;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridges;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindHandler;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Blocks for the tank port GameTests: a port with two pulling fluid tanks, a pushing fluid tank, a plain fluid
 * capability of its own (which the port never uses) and a pulling and a pushing tank of the GameTest kind
 * {@code maxtech_lib:test_glow}; a plain fluid tank of settable capacity; and a glow store without transactions whose
 * execute moves half of what it simulated, reached through a test {@link KindBridge} for that kind (fluid handlers
 * always take part in transactions, so only another kind can stand in for one that does not).
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TestTanks {
    /** The glow store's capability. */
    @SuppressWarnings("unchecked") // the capability's type is the generic handler
    static final BlockCapability<KindHandler<Integer>, @Nullable Direction> GLOW = BlockCapability.createSided(
            MaxtechLib.id("test_glow_handler"), (Class<KindHandler<Integer>>) (Class<?>) KindHandler.class);

    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry().registrar(MaxtechLib.MOD_ID,
            Registries.BLOCK);
    public static final RegistryEntry<Block, PortBlock> PORT = BLOCKS.register("test_tank_port",
            id -> new PortBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    public static final RegistryEntry<Block, PlainBlock> TANK = BLOCKS.register("test_tank",
            id -> new PlainBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)), 0));
    public static final RegistryEntry<Block, PlainBlock> FOREIGN = BLOCKS.register("test_foreign_glow",
            id -> new PlainBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)), 1));
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<PortEntity>> PORT_ENTITY = BLOCK_ENTITIES
            .register("test_tank_port", () -> new BlockEntityType<>(PortEntity::new, Set.of(PORT.get())));
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<TankEntity>> TANK_ENTITY = BLOCK_ENTITIES
            .register("test_tank", () -> new BlockEntityType<>(TankEntity::new, Set.of(TANK.get())));
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ForeignEntity>> FOREIGN_ENTITY = BLOCK_ENTITIES
            .register("test_foreign_glow", () -> new BlockEntityType<>(ForeignEntity::new, Set.of(FOREIGN.get())));

    static {
        KindBridges.register(ConduitApiGameTests.TEST_KIND, new GlowBridge());
    }

    /** The group the next port block entity made joins; Lib's transfer group when null. */
    private static @Nullable TaskGroup nextGroup;

    private TestTanks() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, PORT_ENTITY.get(), (entity, side) -> entity.plain);
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TANK_ENTITY.get(), (entity, side) -> entity.handler);
        event.registerBlockEntity(GLOW, FOREIGN_ENTITY.get(), (entity, side) -> entity.handler);
    }

    /** Places a tank port at {@code pos} that runs in Lib's transfer group. */
    public static PortEntity place(ServerLevel level, BlockPos pos, int flags) {
        level.setBlock(pos, PORT.get().defaultBlockState(), flags);
        return (PortEntity) Objects.requireNonNull(level.getBlockEntity(pos), "no port block entity");
    }

    /** Places a tank port at {@code pos} whose task joins {@code group}, so a test can count its runs. */
    public static PortEntity place(ServerLevel level, BlockPos pos, TaskGroup group, int flags) {
        nextGroup = group;
        try {
            return place(level, pos, flags);
        } finally {
            nextGroup = null;
        }
    }

    /** Places a plain fluid tank of {@code capacity} mB at {@code pos}. */
    public static TankEntity tank(ServerLevel level, BlockPos pos, int capacity, int flags) {
        level.setBlock(pos, TANK.get().defaultBlockState(), flags);
        TankEntity tank = (TankEntity) Objects.requireNonNull(level.getBlockEntity(pos), "no tank block entity");
        tank.handler.setCapacity(capacity);
        return tank;
    }

    /** Places a glow store without transactions at {@code pos}, holding {@code amount} of glow {@code key}. */
    public static ForeignEntity foreign(ServerLevel level, BlockPos pos, int key, long amount, long capacity) {
        level.setBlock(pos, FOREIGN.get().defaultBlockState(), Block.UPDATE_ALL);
        ForeignEntity foreign = (ForeignEntity) Objects.requireNonNull(level.getBlockEntity(pos), "no glow store");
        foreign.handler.key = key;
        foreign.handler.amount = amount;
        foreign.handler.capacity = capacity;
        return foreign;
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
     * Fluid tanks {@code inputA} and {@code inputB} (pull, 4,000 mB each, added in that order), {@code output} (push,
     * 4,000 mB), a plain fluid handler of 4,000 mB as its capability, and glow tanks {@code glowIn} (pull) and
     * {@code glowOut} and {@code glowOut2} (push) of 4,000. Saves the tanks and the port's settings.
     */
    public static final class PortEntity extends BlockEntity {
        public final FluidTank inputA = FluidTank.create(4000, this::changed);
        public final FluidTank inputB = FluidTank.create(4000, this::changed);
        public final FluidTank output = FluidTank.create(4000, this::changed);
        public final ResourceTank<Integer> glowIn = ResourceTank.create(ConduitApiGameTests.TEST_KIND, 4000,
                this::changed);
        public final ResourceTank<Integer> glowOut = ResourceTank.create(ConduitApiGameTests.TEST_KIND, 4000,
                this::changed);
        public final ResourceTank<Integer> glowOut2 = ResourceTank.create(ConduitApiGameTests.TEST_KIND, 4000,
                this::changed);
        public final FluidStacksResourceHandler plain = new FluidStacksResourceHandler(1, 4000);
        public final TransferPort port;

        PortEntity(BlockPos pos, BlockState state) {
            super(PORT_ENTITY.get(), pos, state);
            TransferPorts.Builder builder = TransferPorts.builder().fluids(inputA, Set.of(PortFaceMode.PULL))
                    .fluids(inputB, Set.of(PortFaceMode.PULL)).fluids(output, Set.of(PortFaceMode.PUSH))
                    .tank(glowIn, Set.of(PortFaceMode.PULL)).tank(glowOut, Set.of(PortFaceMode.PUSH))
                    .tank(glowOut2, Set.of(PortFaceMode.PUSH)).onSettingsChanged(this::setChanged);
            TaskGroup group = nextGroup;
            if (group != null) {
                builder.tasks(level -> group);
            }
            port = builder.build();
        }

        private void changed() {
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
            inputA.save(output.child("input_a"));
            inputB.save(output.child("input_b"));
            this.output.save(output.child("output"));
            port.save(output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            inputA.load(input.childOrEmpty("input_a"));
            inputB.load(input.childOrEmpty("input_b"));
            output.load(input.childOrEmpty("output"));
            port.load(input);
        }
    }

    /** A block entity holder for the plain tank and the glow store. */
    public static final class PlainBlock extends Block implements EntityBlock {
        static final MapCodec<PlainBlock> CODEC = simpleCodec(properties -> new PlainBlock(properties, 0));
        private final int type;

        PlainBlock(Properties properties, int type) {
            super(properties);
            this.type = type;
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return type == 0 ? new TankEntity(pos, state) : new ForeignEntity(pos, state);
        }
    }

    /** A plain one-index fluid tank. */
    public static final class TankEntity extends BlockEntity {
        public final Tank handler = new Tank();

        TankEntity(BlockPos pos, BlockState state) {
            super(TANK_ENTITY.get(), pos, state);
        }
    }

    /** NeoForge's fluid tank with a capacity a test sets. */
    public static final class Tank extends FluidStacksResourceHandler {
        Tank() {
            super(1, 16000);
        }

        void setCapacity(int value) {
            capacity = value;
        }
    }

    /** The glow store's block entity. */
    public static final class ForeignEntity extends BlockEntity {
        public final Foreign handler = new Foreign();

        ForeignEntity(BlockPos pos, BlockState state) {
            super(FOREIGN_ENTITY.get(), pos, state);
        }
    }

    /**
     * One index of one glow key, without transactions (like a mod with simulate and execute calls): it changes at once
     * and ignores the transaction, its simulations report the full amount, and an execute moves only half (rounded up)
     * of what it was asked. {@code executes} counts executed calls.
     */
    public static final class Foreign implements KindHandler<Integer> {
        public int key;
        public long amount;
        public long capacity;
        public int executes;

        @Override
        public int size() {
            return 1;
        }

        @Override
        public Optional<Integer> resourceAt(int index) {
            return amount > 0 ? Optional.of(key) : Optional.empty();
        }

        @Override
        public long amountAt(int index) {
            return amount;
        }

        @Override
        public long insert(Integer resource, long wanted, TransactionContext transaction) {
            long moved = (simulateInsert(resource, wanted, transaction) + 1) / 2;
            if (moved > 0) {
                key = resource;
                amount += moved;
                executes++;
            }
            return moved;
        }

        @Override
        public long extract(Integer resource, long wanted, TransactionContext transaction) {
            long moved = (simulateExtract(resource, wanted, transaction) + 1) / 2;
            if (moved > 0) {
                amount -= moved;
                executes++;
            }
            return moved;
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public long simulateInsert(Integer resource, long wanted, TransactionContext transaction) {
            return amount > 0 && key != resource ? 0 : Math.max(0, Math.min(wanted, capacity - amount));
        }

        @Override
        public long simulateExtract(Integer resource, long wanted, TransactionContext transaction) {
            return amount > 0 && key == resource ? Math.min(wanted, amount) : 0;
        }
    }

    /** The test kind's bridge, over the glow capability. */
    private static final class GlowBridge implements KindBridge<Integer> {
        @Override
        public Cache<Integer> cache(ServerLevel level, BlockPos pos, Direction side, BooleanSupplier isValid,
                Runnable onInvalidate) {
            BlockCapabilityCache<KindHandler<Integer>, @Nullable Direction> cache = BlockCapabilityCache.create(GLOW,
                    level, pos, side, isValid, onInvalidate);
            return () -> Optional.ofNullable(cache.getCapability());
        }
    }
}
