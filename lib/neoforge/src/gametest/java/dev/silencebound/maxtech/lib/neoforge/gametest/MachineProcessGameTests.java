package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.machine.MachineProcess;
import dev.silencebound.maxtech.lib.api.machine.MachineStatus;
import dev.silencebound.maxtech.lib.api.machine.RedstoneControl;
import dev.silencebound.maxtech.lib.api.machine.RedstoneMode;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeShape;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeType;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskSchedulers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * {@link MachineProcess} end to end, through the api only, on a demo machine with one input slot, one catalyst slot,
 * two output slots, a water and a lava tank, an energy consumer, a redstone control and modes {@code a} and {@code b}
 * (the {@code test_process} recipes in {@code src/gametest/resources}): runs, sleeps and wakes, all-or-nothing outputs,
 * energy shortage and brownout, redstone modes, catalysts, saves mid-run, a saved recipe that is gone and a datapack
 * reload.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MachineProcessGameTests {
    /** One input, one catalyst, two outputs, one input and one output tank, modes a and b. */
    public static final MachineRecipeType TYPE = MachineRecipeType.define(MaxtechLib.MOD_ID, "test_process",
            MachineRecipeShape.items(1, 2).withCatalysts(1).withTanks(1, 1).withModes("a", "b"));

    private static final String[] NAMES = {"machine_process_run", "machine_process_sleeps",
            "machine_process_output_full", "machine_process_all_or_nothing", "machine_process_energy",
            "machine_process_redstone", "machine_process_pulse", "machine_process_save", "machine_process_recipe_gone",
            "machine_process_tanks", "machine_process_high_pause", "machine_process_pulse_drop",
            "machine_process_catalyst", "machine_process_half_energy", "machine_process_output_tank_wakes",
            "machine_process_input_taken"};
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MaxtechLib.MOD_ID,
            Registries.TEST_FUNCTION);
    private static final Registrar<Block> BLOCKS = Registrars.create(MaxtechLib.MOD_ID, Registries.BLOCK);
    static final RegistryEntry<Block, MachineBlock> BLOCK = BLOCKS.register("test_process_machine",
            id -> new MachineBlock(
                    BlockBehaviour.Properties.of().strength(1.5F).setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final Registrar<BlockEntityType<?>> ENTITIES = Registrars.create(MaxtechLib.MOD_ID,
            Registries.BLOCK_ENTITY_TYPE);
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<MachineEntity>> ENTITY = ENTITIES
            .register("test_process_machine", () -> new BlockEntityType<>(MachineEntity::new, Set.of(BLOCK.get())));

    /** Reloads the server's datapacks, so it runs in a batch of its own. */
    private static final String RELOAD = "machine_process_reload";
    private static final String PACK = "maxtech_lib_machine_reload";
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final BlockPos MACHINE = new BlockPos(1, 0, 1);
    private static final BlockPos LEVER = MACHINE.east();
    /** Ticks a sleeping machine is watched for runs; an IDLE backoff would run about five times in it. */
    private static final int QUIET = 40;

    static {
        FUNCTIONS.register(NAMES[0], () -> MachineProcessGameTests::run);
        FUNCTIONS.register(NAMES[1], () -> MachineProcessGameTests::sleeps);
        FUNCTIONS.register(NAMES[2], () -> MachineProcessGameTests::outputFull);
        FUNCTIONS.register(NAMES[3], () -> MachineProcessGameTests::allOrNothing);
        FUNCTIONS.register(NAMES[4], () -> MachineProcessGameTests::energy);
        FUNCTIONS.register(NAMES[5], () -> MachineProcessGameTests::redstone);
        FUNCTIONS.register(NAMES[6], () -> MachineProcessGameTests::pulse);
        FUNCTIONS.register(NAMES[7], () -> MachineProcessGameTests::save);
        FUNCTIONS.register(NAMES[8], () -> MachineProcessGameTests::recipeGone);
        FUNCTIONS.register(NAMES[9], () -> MachineProcessGameTests::tanks);
        FUNCTIONS.register(NAMES[10], () -> MachineProcessGameTests::highPause);
        FUNCTIONS.register(NAMES[11], () -> MachineProcessGameTests::pulseDrop);
        FUNCTIONS.register(NAMES[12], () -> MachineProcessGameTests::catalyst);
        FUNCTIONS.register(NAMES[13], () -> MachineProcessGameTests::halfEnergy);
        FUNCTIONS.register(NAMES[14], () -> MachineProcessGameTests::outputTankWakes);
        FUNCTIONS.register(NAMES[15], () -> MachineProcessGameTests::inputTaken);
        FUNCTIONS.register(RELOAD, () -> MachineProcessGameTests::reload);
    }

    private MachineProcessGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("machine_process"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, STRUCTURE, 300, 0, true)));
        }
        Identifier reload = MaxtechLib.id(RELOAD);
        event.registerTest(reload, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, reload),
                new TestData<>(event.registerEnvironment(reload), STRUCTURE, 600, 0, true)));
    }

    private static MachineEntity place(GameTestHelper helper) {
        helper.setBlock(MACHINE, BLOCK.get().defaultBlockState());
        return machine(helper);
    }

    private static MachineEntity machine(GameTestHelper helper) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(MACHINE)) instanceof MachineEntity machine)) {
            throw helper.assertionException("No machine block entity");
        }
        return machine;
    }

    private static void placeLever(GameTestHelper helper) {
        helper.setBlock(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, Direction.EAST));
    }

    private static void assertSlot(GameTestHelper helper, MachineEntity machine, int slot, Item item, int count) {
        ItemStack stack = machine.slots.stack(slot);
        int held = stack.is(item) ? stack.getCount() : 0;
        helper.assertTrue(held == count && (count > 0 || stack.isEmpty()),
                "slot " + slot + " holds " + stack + ", expected " + count + " " + item);
    }

    private static void assertStatus(GameTestHelper helper, MachineEntity machine, MachineStatus status) {
        helper.assertTrue(machine.process.started(), "process not started");
        helper.assertValueEqual(machine.process.status(), status, "status");
    }

    /** Started, in {@code status}, and not queued to run. */
    private static void assertAsleep(GameTestHelper helper, MachineEntity machine, MachineStatus status) {
        assertStatus(helper, machine, status);
        helper.assertValueEqual(machine.group().queued(), 0, "queued runs");
    }

    /** A run takes its duration, then takes the inputs and makes every output; an empty machine is idle. */
    private static void run(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 2));
        helper.startSequence().thenExecuteAfter(5, () -> {
            assertStatus(helper, machine, MachineStatus.RUNNING);
            helper.assertValueEqual(machine.process.progressTotal(), 10L, "ticks per run");
            helper.assertTrue(machine.process.progress() > 0 && machine.process.progress() < 10,
                    "progress " + machine.process.progress() + " mid-run");
            assertSlot(helper, machine, 0, Items.COBBLESTONE, 2);
            assertSlot(helper, machine, 2, Items.GRAVEL, 0);
        }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 1)).thenExecute(() -> {
            assertSlot(helper, machine, 0, Items.COBBLESTONE, 0);
            assertSlot(helper, machine, 3, Items.FLINT, 1);
        }).thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.IDLE))
                .thenExecute(() -> helper.assertValueEqual(machine.process.progress(), 0L, "progress after the run"))
                .thenSucceed();
    }

    /** Without inputs, and with inputs that match nothing, the task never runs until a slot changes. */
    private static void sleeps(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.IDLE))
                .thenExecute(() -> runs[0] = machine.group().runs()).thenIdle(QUIET)
                .thenExecute(() -> helper.assertValueEqual(machine.group().runs(), runs[0], "runs while idle"))
                .thenExecute(() -> machine.slots.set(0, new ItemStack(Items.DIRT)))
                .thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.NO_RECIPE))
                .thenExecute(() -> runs[0] = machine.group().runs()).thenIdle(QUIET)
                .thenExecute(() -> helper.assertValueEqual(machine.group().runs(), runs[0], "runs without a recipe"))
                .thenExecute(() -> machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 2)))
                .thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.RUNNING))
                .thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 1)).thenSucceed();
    }

    /**
     * A run starts only with room for a chance output too, however unlikely, and starts once room is made; a full
     * machine does not run.
     */
    private static void outputFull(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.slots.set(2, new ItemStack(Items.GLASS, 63));
        machine.slots.set(3, new ItemStack(Items.STONE, 64));
        machine.slots.set(0, new ItemStack(Items.SAND));
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.OUTPUT_FULL))
                .thenExecute(() -> runs[0] = machine.group().runs()).thenIdle(QUIET).thenExecute(() -> {
                    helper.assertValueEqual(machine.group().runs(), runs[0], "runs while full");
                    helper.assertValueEqual(machine.process.progress(), 0L, "progress while full");
                    assertSlot(helper, machine, 0, Items.SAND, 1);
                    assertSlot(helper, machine, 2, Items.GLASS, 63);
                    assertSlot(helper, machine, 3, Items.STONE, 64);
                    machine.slots.set(3, ItemStack.EMPTY);
                }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GLASS, 64)).thenExecute(() -> {
                    assertSlot(helper, machine, 0, Items.SAND, 0);
                }).thenSucceed();
    }

    /**
     * Outputs filled up mid-run leave room for the first output but not the second: the finished run moves nothing,
     * holds at its end without running, and finishes whole once room is made.
     */
    private static void allOrNothing(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 2));
        long[] runs = new long[1];
        helper.startSequence().thenExecuteAfter(3, () -> {
            assertStatus(helper, machine, MachineStatus.RUNNING);
            machine.slots.set(2, new ItemStack(Items.GRAVEL, 63));
            machine.slots.set(3, new ItemStack(Items.STONE, 64));
        }).thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.OUTPUT_FULL)).thenExecute(() -> {
            assertSlot(helper, machine, 0, Items.COBBLESTONE, 2);
            assertSlot(helper, machine, 2, Items.GRAVEL, 63);
            assertSlot(helper, machine, 3, Items.STONE, 64);
            helper.assertValueEqual(machine.process.progress(), 10L, "progress held at the end");
            runs[0] = machine.group().runs();
        }).thenIdle(QUIET).thenExecute(() -> {
            helper.assertValueEqual(machine.group().runs(), runs[0], "runs while held");
            machine.slots.set(3, ItemStack.EMPTY);
        }).thenWaitUntil(() -> assertSlot(helper, machine, 3, Items.FLINT, 1)).thenExecute(() -> {
            assertSlot(helper, machine, 0, Items.COBBLESTONE, 0);
            assertSlot(helper, machine, 2, Items.GRAVEL, 64);
        }).thenSucceed();
    }

    /**
     * A powered recipe moves on by the energy it gets: with the supply cut it pauses at its progress, keeps its inputs
     * and its demand, and goes on when energy comes back. An idle machine draws nothing.
     */
    private static void energy(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        EnergyNetwork network = EnergyNetworks.of(helper.getLevel()).createNetwork();
        EnergyEndpoint producer = EnergyEndpoint.producer();
        producer.setSupply(100);
        network.add(producer);
        network.add(machine.energy);
        machine.slots.set(0, new ItemStack(Items.IRON_INGOT, 2));
        long[] paused = new long[1];
        helper.startSequence().thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.IRON_NUGGET, 9))
                .thenWaitUntil(() -> {
                    assertStatus(helper, machine, MachineStatus.RUNNING);
                    helper.assertTrue(machine.process.progress() > 0, "second run has not started");
                }).thenExecute(() -> {
                    helper.assertValueEqual(machine.process.progressTotal(), 50L, "energy per run");
                    helper.assertValueEqual(machine.energy.demand(), 10L, "demand while running");
                    producer.setSupply(0);
                }).thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.NO_ENERGY))
                .thenExecute(() -> paused[0] = machine.process.progress()).thenIdle(10).thenExecute(() -> {
                    assertStatus(helper, machine, MachineStatus.NO_ENERGY);
                    helper.assertValueEqual(machine.process.progress(), paused[0], "progress without energy");
                    helper.assertTrue(paused[0] < 50, "the run finished before the cut");
                    helper.assertValueEqual(machine.energy.demand(), 10L, "demand without energy");
                    assertSlot(helper, machine, 0, Items.IRON_INGOT, 1);
                    producer.setSupply(100);
                }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.IRON_NUGGET, 18))
                .thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.IDLE)).thenExecute(() -> {
                    helper.assertValueEqual(machine.energy.demand(), 0L, "demand while idle");
                    assertSlot(helper, machine, 0, Items.IRON_INGOT, 0);
                    network.dispose();
                }).thenSucceed();
    }

    /** High mode runs only with the lever on; never mode does not run with it on either. */
    private static void redstone(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        placeLever(helper);
        machine.redstone.setMode(RedstoneMode.HIGH);
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 4));
        helper.startSequence().thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.REDSTONE_OFF))
                .thenIdle(15).thenExecute(() -> {
                    helper.assertValueEqual(machine.process.progress(), 0L, "progress while off");
                    assertSlot(helper, machine, 2, Items.GRAVEL, 0);
                    helper.pullLever(LEVER);
                }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 1)).thenExecute(() -> {
                    machine.redstone.setMode(RedstoneMode.NEVER);
                    helper.assertTrue(machine.redstone.powered(), "lever off");
                }).thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.REDSTONE_OFF)).thenIdle(15)
                .thenExecute(() -> {
                    assertSlot(helper, machine, 0, Items.COBBLESTONE, 2);
                    assertSlot(helper, machine, 2, Items.GRAVEL, 1);
                    helper.assertValueEqual(machine.process.progress(), 0L, "progress in never mode");
                }).thenSucceed();
    }

    /** Pulse mode runs once per rising edge: a signal that stays on and a falling edge start nothing. */
    private static void pulse(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        placeLever(helper);
        machine.redstone.setMode(RedstoneMode.PULSE);
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 6));
        helper.startSequence().thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.REDSTONE_OFF))
                .thenIdle(15).thenExecute(() -> {
                    assertSlot(helper, machine, 2, Items.GRAVEL, 0);
                    helper.pullLever(LEVER);
                }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 1)).thenIdle(25).thenExecute(() -> {
                    assertSlot(helper, machine, 2, Items.GRAVEL, 1);
                    assertSlot(helper, machine, 0, Items.COBBLESTONE, 4);
                    assertStatus(helper, machine, MachineStatus.REDSTONE_OFF);
                    helper.pullLever(LEVER);
                }).thenIdle(25).thenExecute(() -> {
                    assertSlot(helper, machine, 2, Items.GRAVEL, 1);
                    helper.pullLever(LEVER);
                }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 2)).thenIdle(25).thenExecute(() -> {
                    assertSlot(helper, machine, 2, Items.GRAVEL, 2);
                    assertSlot(helper, machine, 0, Items.COBBLESTONE, 2);
                }).thenSucceed();
    }

    /**
     * A machine saved mid-run, removed and loaded again goes on from its progress and makes the outputs once; the
     * removed one stopped.
     */
    private static void save(GameTestHelper helper) {
        MachineEntity first = place(helper);
        first.slots.set(0, new ItemStack(Items.COBBLESTONE, 2));
        @Nullable
        MachineEntity[] loaded = new MachineEntity[1];
        long[] saved = new long[1];
        helper.startSequence().thenExecuteAfter(5, () -> {
            saved[0] = first.process.progress();
            helper.assertTrue(saved[0] > 0 && saved[0] < 10, "progress " + saved[0] + " mid-run");
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            CompoundTag tag = first.saveWithoutMetadata(registries);
            helper.setBlock(MACHINE, Blocks.AIR);
            helper.assertFalse(first.process.started(), "removed machine still started");
            MachineEntity again = place(helper);
            again.process.stop();
            again.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
            again.process.start(helper.getLevel(), helper.absolutePos(MACHINE));
            helper.assertValueEqual(again.process.progress(), saved[0], "progress after loading");
            assertSlot(helper, again, 0, Items.COBBLESTONE, 2);
            loaded[0] = again;
        }).thenExecuteAfter(1, () -> {
            helper.assertTrue(loaded(helper, loaded).process.progress() > saved[0], "the loaded run started over");
        }).thenWaitUntil(() -> assertSlot(helper, loaded(helper, loaded), 2, Items.GRAVEL, 1)).thenIdle(15)
                .thenExecute(() -> {
                    assertSlot(helper, loaded(helper, loaded), 0, Items.COBBLESTONE, 0);
                    assertSlot(helper, loaded(helper, loaded), 2, Items.GRAVEL, 1);
                    assertSlot(helper, loaded(helper, loaded), 3, Items.FLINT, 1);
                    assertSlot(helper, first, 2, Items.GRAVEL, 0);
                }).thenSucceed();
    }

    /** The machine loaded in {@link #save}; fails the test, not the server, when an earlier step failed first. */
    private static MachineEntity loaded(GameTestHelper helper, @Nullable MachineEntity[] loaded) {
        MachineEntity machine = loaded[0];
        if (machine == null) {
            throw helper.assertionException("No machine loaded");
        }
        return machine;
    }

    /**
     * A saved run whose recipe is gone after loading starts over on what the inputs match, and with inputs that match
     * nothing it resets; the items stay.
     */
    private static void recipeGone(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        out.putLong("progress", 8);
        out.putBoolean("active", true);
        out.putString("recipe", MaxtechLib.id("test_process/gone").toString());
        CompoundTag tag = out.buildResult();
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MACHINE);
        machine.process.stop();
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 2));
        machine.process.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
        helper.assertValueEqual(machine.process.progress(), 8L, "progress as loaded");
        machine.process.start(level, pos);
        helper.startSequence().thenExecuteAfter(1, () -> {
            helper.assertValueEqual(machine.process.progress(), 1L, "progress after the first step");
            helper.assertValueEqual(machine.process.recipe().map(holder -> holder.id().identifier()),
                    Optional.of(MaxtechLib.id("test_process/plain")), "recipe");
            assertSlot(helper, machine, 0, Items.COBBLESTONE, 2);
            machine.process.stop();
            machine.slots.set(0, new ItemStack(Items.DIRT, 3));
            machine.process.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
            machine.process.start(level, pos);
        }).thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.NO_RECIPE)).thenExecute(() -> {
            helper.assertValueEqual(machine.process.progress(), 0L, "progress without a recipe");
            assertSlot(helper, machine, 0, Items.DIRT, 3);
            assertSlot(helper, machine, 2, Items.GRAVEL, 0);
        }).thenSucceed();
    }

    /** Tank recipes run in their mode only: water to lava in mode b. */
    private static void tanks(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.water.set(FluidKey.of(Fluids.WATER), 100);
        helper.startSequence().thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.NO_RECIPE))
                .thenExecute(() -> {
                    helper.assertValueEqual(machine.process.mode(), "a", "default mode");
                    machine.process.setMode("b");
                }).thenWaitUntil(() -> helper.assertValueEqual(machine.lava.amount(), 100L, "lava made"))
                .thenExecute(() -> {
                    helper.assertValueEqual(machine.water.amount(), 0L, "water left");
                    helper.assertTrue(machine.lava.resource().equals(Optional.of(FluidKey.of(Fluids.LAVA))),
                            "lava in the output tank");
                }).thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.IDLE)).thenSucceed();
    }

    /** A high-mode run paused mid-way by the signal going off keeps its progress and finishes once it is back. */
    private static void highPause(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        placeLever(helper);
        machine.redstone.setMode(RedstoneMode.HIGH);
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 2));
        long[] paused = new long[1];
        helper.startSequence().thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> helper.assertTrue(machine.process.progress() >= 4, "run not under way"))
                .thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.REDSTONE_OFF))
                .thenExecute(() -> paused[0] = machine.process.progress()).thenIdle(15).thenExecute(() -> {
                    helper.assertTrue(paused[0] > 0 && paused[0] < 10, "progress " + paused[0] + " when paused");
                    helper.assertValueEqual(machine.process.progress(), paused[0], "progress while paused");
                    assertSlot(helper, machine, 0, Items.COBBLESTONE, 2);
                    helper.pullLever(LEVER);
                })
                .thenExecuteAfter(1,
                        () -> helper.assertTrue(machine.process.progress() > paused[0],
                                "the run started over or did not go on"))
                .thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 1)).thenSucceed();
    }

    /** A pulse-mode run goes on to its end after the signal drops again. */
    private static void pulseDrop(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        placeLever(helper);
        machine.redstone.setMode(RedstoneMode.PULSE);
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 4));
        helper.startSequence().thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.REDSTONE_OFF))
                .thenExecute(() -> helper.pullLever(LEVER))
                .thenWaitUntil(() -> assertStatus(helper, machine, MachineStatus.RUNNING))
                .thenExecute(() -> helper.pullLever(LEVER)).thenExecuteAfter(3, () -> {
                    helper.assertFalse(machine.redstone.powered(), "lever still on");
                    assertStatus(helper, machine, MachineStatus.RUNNING);
                }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 1)).thenIdle(15).thenExecute(() -> {
                    assertSlot(helper, machine, 0, Items.COBBLESTONE, 2);
                    assertStatus(helper, machine, MachineStatus.REDSTONE_OFF);
                }).thenSucceed();
    }

    /** A catalyst is needed for its recipe and never taken. */
    private static void catalyst(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.slots.set(0, new ItemStack(Items.CLAY_BALL, 2));
        helper.startSequence().thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.NO_RECIPE))
                .thenExecute(() -> machine.slots.set(1, new ItemStack(Items.DIAMOND)))
                .thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.BRICK, 2))
                .thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.NO_RECIPE)).thenExecute(() -> {
                    assertSlot(helper, machine, 0, Items.CLAY_BALL, 0);
                    assertSlot(helper, machine, 1, Items.DIAMOND, 1);
                }).thenSucceed();
    }

    /** With half the energy it asks for, a run moves on at half speed. */
    private static void halfEnergy(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        EnergyNetwork network = EnergyNetworks.of(helper.getLevel()).createNetwork();
        EnergyEndpoint producer = EnergyEndpoint.producer();
        producer.setSupply(5);
        network.add(producer);
        network.add(machine.energy);
        machine.slots.set(0, new ItemStack(Items.IRON_INGOT));
        long[] start = new long[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(machine.process.progress() > 0, "no progress"))
                .thenExecute(() -> start[0] = machine.process.progress()).thenExecuteAfter(4, () -> {
                    helper.assertValueEqual(machine.process.progress() - start[0], 20L, "energy in 4 ticks at 5 MU");
                    assertStatus(helper, machine, MachineStatus.RUNNING);
                }).thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.IRON_NUGGET, 9))
                .thenExecute(network::dispose).thenSucceed();
    }

    /** Draining the output tank wakes a machine that slept because its tank output did not fit. */
    private static void outputTankWakes(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.process.setMode("b");
        machine.lava.set(FluidKey.of(Fluids.LAVA), 950);
        machine.water.set(FluidKey.of(Fluids.WATER), 100);
        helper.startSequence().thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.OUTPUT_FULL))
                .thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(machine.water.amount(), 100L, "water while full");
                    machine.lava.extract(FluidKey.of(Fluids.LAVA), 500, false);
                }).thenWaitUntil(() -> helper.assertValueEqual(machine.lava.amount(), 550L, "lava after the run"))
                .thenExecute(() -> helper.assertValueEqual(machine.water.amount(), 0L, "water after the run"))
                .thenSucceed();
    }

    /** Taking an input out mid-run resets the run and loses nothing; put back, the run starts from the beginning. */
    private static void inputTaken(GameTestHelper helper) {
        MachineEntity machine = place(helper);
        machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 2));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(machine.process.progress() >= 4, "run not under way"))
                .thenExecute(() -> machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 1)))
                .thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.NO_RECIPE)).thenExecute(() -> {
                    helper.assertValueEqual(machine.process.progress(), 0L, "progress after taking an input");
                    assertSlot(helper, machine, 0, Items.COBBLESTONE, 1);
                    assertSlot(helper, machine, 2, Items.GRAVEL, 0);
                    machine.slots.set(0, new ItemStack(Items.COBBLESTONE, 2));
                })
                .thenExecuteAfter(2,
                        () -> helper.assertTrue(machine.process.progress() <= 2,
                                "progress " + machine.process.progress() + " did not start over"))
                .thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.GRAVEL, 1)).thenSucceed();
    }

    /**
     * A datapack reload wakes a machine asleep without a recipe: a world datapack adds a recipe for its input, and the
     * machine runs it without any slot change; the pack goes again at the end.
     */
    private static void reload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
        PackRepository packs = server.getPackRepository();
        String id = "file/" + PACK;
        List<String> before = new ArrayList<>(packs.getSelectedIds());
        before.remove(id);
        List<CompletableFuture<Void>> reloads = new ArrayList<>();
        MachineEntity machine = place(helper);
        machine.slots.set(0, new ItemStack(Items.DIRT));
        helper.startSequence().thenExecute(() -> {
            // A run that failed half-way may have left the pack on: switch it off first.
            delete(pack);
            if (packs.getSelectedIds().contains(id)) {
                packs.reload();
                reloads.add(server.reloadResources(before));
            } else {
                reloads.add(CompletableFuture.completedFuture(null));
            }
        }).thenWaitUntil(() -> helper.assertTrue(reloads.get(0).isDone(), "clean-up reload running"))
                .thenWaitUntil(() -> assertAsleep(helper, machine, MachineStatus.NO_RECIPE)).thenExecute(() -> {
                    write(pack.resolve("pack.mcmeta"),
                            "{\"pack\": {\"description\": \"machine reload test\", \"min_format\": "
                                    + SharedConstants.DATA_PACK_FORMAT_MAJOR + ", \"max_format\": "
                                    + SharedConstants.DATA_PACK_FORMAT_MAJOR + "}}");
                    write(pack.resolve("data/maxtech_lib/recipe/test_process/reloaded.json"),
                            "{\"type\": \"maxtech_lib:test_process\", \"mode\": \"a\", \"inputs\": [{\"ingredient\": "
                                    + "\"minecraft:dirt\"}], \"outputs\": [{\"item\": {\"id\": "
                                    + "\"minecraft:coarse_dirt\"}}], \"duration\": 2}");
                    packs.reload();
                    List<String> with = new ArrayList<>(before);
                    with.add(id);
                    reloads.add(server.reloadResources(with));
                }).thenWaitUntil(() -> helper.assertTrue(reloads.size() > 1 && reloads.get(1).isDone(), "reload"))
                .thenWaitUntil(() -> assertSlot(helper, machine, 2, Items.COARSE_DIRT, 1)).thenExecute(() -> {
                    assertSlot(helper, machine, 0, Items.DIRT, 0);
                    delete(pack);
                    packs.reload();
                    reloads.add(server.reloadResources(before));
                }).thenWaitUntil(() -> helper.assertTrue(reloads.size() > 2 && reloads.get(2).isDone(), "reload"))
                .thenSucceed();
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Forwards neighbour updates to the redstone control and wakes the process. */
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

    /** The demo machine: wires a process the way the api's class comment says, in a task group of its own. */
    static final class MachineEntity extends BlockEntity {
        final EnergyEndpoint energy = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        final ItemSlots slots = ItemSlots.builder(4).input(0, 2).output(2, 4).build(this::slotChanged);
        final FluidTank water = FluidTank.create(1000, () -> tankChanged(this.water));
        final FluidTank lava = FluidTank.create(1000, () -> tankChanged(this.lava));
        final RedstoneControl redstone = new RedstoneControl(this::setChanged, this::wake);
        final MachineProcess process;
        private @Nullable TaskGroup group;

        MachineEntity(BlockPos pos, BlockState state) {
            super(ENTITY.get(), pos, state);
            process = MachineProcess.builder(TYPE).items(slots, 0, 2).inputTank(water).outputTank(lava).energy(energy)
                    .redstone(redstone).tasks(this::group).onChange(this::setChanged).build();
        }

        /** The machine's own group, so a test counts this machine's runs alone. */
        TaskGroup group() {
            if (!(level instanceof ServerLevel server)) {
                throw new IllegalStateException("Not in a server level");
            }
            return group(server);
        }

        private TaskGroup group(ServerLevel level) {
            TaskGroup current = group;
            if (current == null || current.isClosed()) {
                current = TaskSchedulers.newGroup(level);
                group = current;
            }
            return current;
        }

        @SuppressWarnings("NullAway") // the slots and tanks call back only after construction
        private void slotChanged(int slot) {
            setChanged();
            process.slotChanged(slot);
        }

        @SuppressWarnings("NullAway") // see slotChanged
        private void tankChanged(ResourceTank<?> tank) {
            setChanged();
            process.tankChanged(tank);
        }

        @SuppressWarnings("NullAway") // see slotChanged
        private void wake() {
            process.wake();
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level instanceof ServerLevel server) {
                redstone.start(server, worldPosition);
                process.start(server, worldPosition);
            }
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            process.stop();
            TaskGroup current = group;
            if (current != null) {
                current.close();
            }
        }

        @Override
        protected void saveAdditional(ValueOutput out) {
            super.saveAdditional(out);
            slots.save(out.child("items"));
            water.save(out.child("water"));
            lava.save(out.child("lava"));
            redstone.save(out.child("redstone"));
            process.save(out.child("process"));
        }

        @Override
        protected void loadAdditional(ValueInput in) {
            super.loadAdditional(in);
            in.child("items").ifPresent(slots::load);
            in.child("water").ifPresent(water::load);
            in.child("lava").ifPresent(lava::load);
            in.child("redstone").ifPresent(redstone::load);
            in.child("process").ifPresent(process::load);
        }
    }
}
