package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
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
import net.minecraft.world.item.Item;
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
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Blocks for the transfer port GameTests: a port with a pulling input buffer, a pushing output buffer and a plain item
 * capability of its own (which the port never uses, so a test can tell port-to-port moves from capability moves), a
 * sink that takes at most 7 items, and a stingy source that gives at most 3 items per extract.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TestPorts {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry().registrar(MaxtechLib.MOD_ID,
            Registries.BLOCK);
    public static final RegistryEntry<Block, PortBlock> PORT = BLOCKS.register("test_port",
            id -> new PortBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    public static final RegistryEntry<Block, SinkBlock> SINK = BLOCKS.register("test_port_sink",
            id -> new SinkBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    public static final RegistryEntry<Block, StingyBlock> STINGY = BLOCKS.register("test_port_stingy_source",
            id -> new StingyBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<PortEntity>> PORT_ENTITY = BLOCK_ENTITIES
            .register("test_port", () -> new BlockEntityType<>(PortEntity::new, Set.of(PORT.get())));
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<SinkEntity>> SINK_ENTITY = BLOCK_ENTITIES
            .register("test_port_sink", () -> new BlockEntityType<>(SinkEntity::new, Set.of(SINK.get())));
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<StingyEntity>> STINGY_ENTITY = BLOCK_ENTITIES
            .register("test_port_stingy_source", () -> new BlockEntityType<>(StingyEntity::new, Set.of(STINGY.get())));

    /** The group the next port block entity made joins; Lib's transfer group when null. */
    private static @Nullable TaskGroup nextGroup;

    private TestPorts() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, PORT_ENTITY.get(), (entity, side) -> entity.plain);
        event.registerBlockEntity(Capabilities.Item.BLOCK, SINK_ENTITY.get(), (entity, side) -> entity.handler);
        event.registerBlockEntity(Capabilities.Item.BLOCK, STINGY_ENTITY.get(), (entity, side) -> entity.handler);
    }

    /** Places a port at {@code pos} that runs in Lib's transfer group. */
    public static PortEntity place(ServerLevel level, BlockPos pos, int flags) {
        level.setBlock(pos, PORT.get().defaultBlockState(), flags);
        return (PortEntity) Objects.requireNonNull(level.getBlockEntity(pos), "no port block entity");
    }

    /** Places a port at {@code pos} whose task joins {@code group}, so a test can count its runs. */
    public static PortEntity place(ServerLevel level, BlockPos pos, TaskGroup group, int flags) {
        nextGroup = group;
        try {
            level.setBlock(pos, PORT.get().defaultBlockState(), flags);
        } finally {
            nextGroup = null;
        }
        return (PortEntity) Objects.requireNonNull(level.getBlockEntity(pos), "no port block entity");
    }

    /** Counts {@code item} in the first {@code size} slots of {@code slots}. */
    public static int count(ItemSlots slots, Item item) {
        int total = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.stack(i).is(item)) {
                total += slots.stack(i).getCount();
            }
        }
        return total;
    }

    /** Counts {@code item} in {@code handler}. */
    public static int count(ItemStacksResourceHandler handler, Item item) {
        int total = 0;
        for (int i = 0; i < handler.size(); i++) {
            if (handler.getResource(i).is(item)) {
                total += handler.getAmountAsInt(i);
            }
        }
        return total;
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
     * A pulling buffer of two input slots and one output slot, a pushing buffer of two output slots and one input slot
     * (so the slot roles alone would let the port push from the first and pull into the second), and three plain slots
     * as its capability. Saves the buffers and the port's settings, not the plain slots.
     */
    public static final class PortEntity extends BlockEntity {
        public final ItemSlots input = ItemSlots.builder(3).input(0, 2).output(2, 3).build(slot -> changed());
        public final ItemSlots output = ItemSlots.builder(3).output(0, 2).input(2, 3).build(slot -> changed());
        public final ItemStacksResourceHandler plain = new ItemStacksResourceHandler(3);
        public final TransferPort port;
        public int settingsChanges;
        /**
         * Saves settings changes with {@code Level.blockEntityChanged} instead of {@code setChanged}, which would send
         * every neighbour an update, as an owner may.
         */
        public boolean quietSettings;

        PortEntity(BlockPos pos, BlockState state) {
            super(PORT_ENTITY.get(), pos, state);
            TransferPorts.Builder builder = TransferPorts.builder().items(input, Set.of(PortFaceMode.PULL))
                    .items(output, Set.of(PortFaceMode.PUSH)).onSettingsChanged(() -> {
                        settingsChanges++;
                        if (quietSettings && level != null) {
                            level.blockEntityChanged(worldPosition);
                        } else {
                            setChanged();
                        }
                    });
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
            input.save(output.child("input"));
            this.output.save(output.child("output"));
            port.save(output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            this.input.load(input.childOrEmpty("input"));
            output.load(input.childOrEmpty("output"));
            port.load(input);
        }
    }

    /** A sink for any item that takes at most 7 items in all, so it accepts less than a port offers. */
    public static final class SinkBlock extends Block implements EntityBlock {
        static final MapCodec<SinkBlock> CODEC = simpleCodec(SinkBlock::new);

        SinkBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new SinkEntity(pos, state);
        }
    }

    /** The sink's one slot of 7. */
    public static final class SinkEntity extends BlockEntity {
        public final ItemStacksResourceHandler handler = new ItemStacksResourceHandler(1) {
            @Override
            protected int getCapacity(int index, ItemResource resource) {
                return 7;
            }
        };

        SinkEntity(BlockPos pos, BlockState state) {
            super(SINK_ENTITY.get(), pos, state);
        }
    }

    /** A source that reports what it holds but gives at most 3 items per extract. */
    public static final class StingyBlock extends Block implements EntityBlock {
        static final MapCodec<StingyBlock> CODEC = simpleCodec(StingyBlock::new);

        StingyBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new StingyEntity(pos, state);
        }
    }

    /** The stingy source's one slot. */
    public static final class StingyEntity extends BlockEntity {
        public final ItemStacksResourceHandler handler = new ItemStacksResourceHandler(1) {
            @Override
            public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
                return super.extract(index, resource, Math.min(amount, 3), transaction);
            }
        };

        StingyEntity(BlockPos pos, BlockState state) {
            super(STINGY_ENTITY.get(), pos, state);
        }
    }
}
