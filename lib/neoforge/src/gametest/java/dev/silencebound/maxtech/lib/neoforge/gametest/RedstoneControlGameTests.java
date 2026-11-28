package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.machine.RedstoneControl;
import dev.silencebound.maxtech.lib.api.machine.RedstoneMode;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * {@link RedstoneControl} through the api against real redstone: a test machine block whose {@code neighborChanged}
 * calls the control, with a lever on its side. Modes follow the lever, pulse mode counts one run per rising edge and
 * nothing for a signal that stays on, and a save keeps the mode, the reading and the waiting pulses.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class RedstoneControlGameTests {
    private static final String[] NAMES = {"redstone_control_modes", "redstone_control_pulse", "redstone_control_save",
            "redstone_control_wakes"};
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MaxtechLib.MOD_ID,
            Registries.TEST_FUNCTION);
    private static final Registrar<Block> BLOCKS = Registrars.create(MaxtechLib.MOD_ID, Registries.BLOCK);
    static final RegistryEntry<Block, MachineBlock> BLOCK = BLOCKS.register("test_redstone_machine",
            id -> new MachineBlock(
                    BlockBehaviour.Properties.of().strength(1.5F).setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final Registrar<BlockEntityType<?>> ENTITIES = Registrars.create(MaxtechLib.MOD_ID,
            Registries.BLOCK_ENTITY_TYPE);
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<MachineEntity>> ENTITY = ENTITIES
            .register("test_redstone_machine", () -> new BlockEntityType<>(MachineEntity::new, Set.of(BLOCK.get())));

    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final BlockPos MACHINE = new BlockPos(1, 0, 1);
    /** On the machine's east side, attached to it. */
    private static final BlockPos LEVER = MACHINE.east();

    static {
        FUNCTIONS.register(NAMES[0], () -> RedstoneControlGameTests::modes);
        FUNCTIONS.register(NAMES[1], () -> RedstoneControlGameTests::pulse);
        FUNCTIONS.register(NAMES[2], () -> RedstoneControlGameTests::save);
        FUNCTIONS.register(NAMES[3], () -> RedstoneControlGameTests::wakes);
    }

    private RedstoneControlGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("redstone_control"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, STRUCTURE, 20, 0, true)));
        }
    }

    /** Places the machine with an unpulled lever on its side. */
    private static MachineEntity place(GameTestHelper helper) {
        helper.setBlock(MACHINE, BLOCK.get().defaultBlockState());
        helper.setBlock(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, Direction.EAST));
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(MACHINE)) instanceof MachineEntity machine)) {
            throw helper.assertionException("No machine block entity");
        }
        helper.assertFalse(machine.redstone.powered(), "powered before the lever");
        return machine;
    }

    /** Each mode allows a run by the lever's signal, read on the neighbour update the lever sends. */
    private static void modes(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        RedstoneControl control = machine.redstone;
        helper.assertTrue(control.allowsRun(), "always, unpowered");
        control.setMode(RedstoneMode.HIGH);
        helper.assertFalse(control.allowsRun(), "high, unpowered");
        control.setMode(RedstoneMode.LOW);
        helper.assertTrue(control.allowsRun(), "low, unpowered");
        helper.pullLever(LEVER);
        helper.assertTrue(control.powered(), "powered by the lever");
        helper.assertFalse(control.allowsRun(), "low, powered");
        control.setMode(RedstoneMode.HIGH);
        helper.assertTrue(control.allowsRun(), "high, powered");
        control.setMode(RedstoneMode.NEVER);
        helper.assertFalse(control.allowsRun(), "never, powered");
        control.setMode(RedstoneMode.ALWAYS);
        helper.assertTrue(control.allowsRun(), "always, powered");
        control.setMode(RedstoneMode.HIGH);
        helper.pullLever(LEVER);
        helper.assertFalse(control.powered(), "lever off");
        helper.assertFalse(control.allowsRun(), "high after the lever went off");
        helper.assertFalse(control.consumePulse(), "a pulse outside pulse mode");
        helper.succeed();
    }

    /**
     * Every rising edge counts one run; falling edges, a signal that stays on and other neighbour updates count none,
     * and each counted run is used once.
     */
    private static void pulse(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        RedstoneControl control = machine.redstone;
        control.setMode(RedstoneMode.PULSE);
        helper.assertFalse(control.allowsRun(), "pulse mode before any edge");
        helper.pullLever(LEVER);
        helper.assertValueEqual(control.pendingPulses(), 1, "pulses after one rising edge");
        // Another neighbour update while the signal stays on.
        helper.setBlock(MACHINE.north(), Blocks.STONE);
        helper.assertValueEqual(control.pendingPulses(), 1, "pulses after an update with the signal on");
        helper.pullLever(LEVER);
        helper.assertValueEqual(control.pendingPulses(), 1, "pulses after the falling edge");
        helper.pullLever(LEVER);
        helper.assertValueEqual(control.pendingPulses(), 2, "pulses after a second rising edge");
        helper.assertTrue(control.allowsRun(), "a run waits");
        helper.assertTrue(control.consumePulse(), "first run");
        helper.assertTrue(control.allowsRun(), "a second run waits");
        helper.assertTrue(control.consumePulse(), "second run");
        helper.assertFalse(control.allowsRun(), "no third run");
        helper.assertFalse(control.consumePulse(), "no third pulse");
        for (int i = 0; i < 2 * RedstoneControl.MAX_PENDING + 2; i++) {
            helper.pullLever(LEVER);
        }
        helper.assertValueEqual(control.pendingPulses(), RedstoneControl.MAX_PENDING, "pulses capped");
        control.setMode(RedstoneMode.ALWAYS);
        helper.assertValueEqual(control.pendingPulses(), 0, "pulses dropped by a mode change");
        helper.succeed();
    }

    /**
     * The mode, the reading and the waiting pulses survive a save; a machine that loads with the lever still on counts
     * no new edge, and the next rising edge counts one more.
     */
    private static void save(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.redstone.setMode(RedstoneMode.PULSE);
        helper.pullLever(LEVER);
        helper.pullLever(LEVER);
        helper.pullLever(LEVER);
        helper.assertValueEqual(machine.redstone.pendingPulses(), 2, "pulses before saving");
        var registries = helper.getLevel().registryAccess();
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        machine.redstone.save(out);
        RedstoneControl loaded = new RedstoneControl(() -> {
        }, () -> {
        });
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, out.buildResult()));
        helper.assertValueEqual(loaded.mode(), RedstoneMode.PULSE, "mode after loading");
        helper.assertValueEqual(loaded.pendingPulses(), 2, "pulses after loading");
        helper.assertTrue(loaded.powered(), "reading after loading");
        loaded.start(helper.getLevel(), helper.absolutePos(MACHINE));
        helper.assertValueEqual(loaded.pendingPulses(), 2, "pulses after starting with the lever on");
        loaded.neighbourChanged(helper.getLevel(), helper.absolutePos(MACHINE));
        helper.assertValueEqual(loaded.pendingPulses(), 2, "pulses after an update with the lever on");

        TagValueOutput defaults = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        new RedstoneControl(() -> {
        }, () -> {
        }).save(defaults);
        helper.assertTrue(defaults.buildResult().isEmpty(), "defaults saved: " + defaults.buildResult());
        helper.succeed();
    }

    /** The control wakes the machine when a run becomes allowed or a pulse is counted, and marks it for saving. */
    private static void wakes(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        RedstoneControl control = machine.redstone;
        control.setMode(RedstoneMode.HIGH);
        int woken = machine.wakes;
        helper.pullLever(LEVER);
        helper.assertValueEqual(machine.wakes, woken + 1, "wakes when high mode gets its signal");
        control.setMode(RedstoneMode.PULSE);
        helper.pullLever(LEVER);
        woken = machine.wakes;
        int saved = machine.saves;
        helper.pullLever(LEVER);
        helper.assertValueEqual(machine.wakes, woken + 1, "wakes for a pulse");
        helper.assertTrue(machine.saves > saved, "saves for a pulse");
        helper.succeed();
    }

    /** Calls the control on neighbour updates. */
    static final class MachineBlock extends Block implements EntityBlock {
        static final MapCodec<MachineBlock> CODEC = simpleCodec(MachineBlock::new);

        MachineBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new MachineEntity(pos, state);
        }

        @Override
        protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                @Nullable Orientation orientation, boolean movedByPiston) {
            if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof MachineEntity machine) {
                machine.redstone.neighbourChanged(level, pos);
            }
        }
    }

    /** Holds the control and counts its wakes and saves. */
    static final class MachineEntity extends BlockEntity {
        int wakes;
        int saves;
        final RedstoneControl redstone = new RedstoneControl(this::changed, () -> wakes++);

        MachineEntity(BlockPos pos, BlockState state) {
            super(ENTITY.get(), pos, state);
        }

        private void changed() {
            saves++;
            setChanged();
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level instanceof ServerLevel server) {
                redstone.start(server, worldPosition);
            }
        }

        @Override
        protected void saveAdditional(ValueOutput out) {
            super.saveAdditional(out);
            redstone.save(out.child("redstone"));
        }

        @Override
        protected void loadAdditional(ValueInput in) {
            super.loadAdditional(in);
            in.child("redstone").ifPresent(redstone::load);
        }
    }
}
