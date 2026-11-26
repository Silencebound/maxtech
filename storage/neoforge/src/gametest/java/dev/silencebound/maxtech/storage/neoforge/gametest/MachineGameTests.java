package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.FuelGeneratorBlockEntity;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.MachineState;
import dev.silencebound.maxtech.storage.machine.ProcessingMachineBlockEntity;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlock;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.component.TypedEntityData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Storage's machines: each runs its recipes from a hopper into a hopper below it, powered by a fuel generator beside
 * it; a machine sleeps while it has nothing to do and wakes on items and energy; the generator burns fuel, pauses when
 * full and hands back a lava bucket's bucket; a machine drops with what it holds.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class MachineGameTests {
    private static final BlockPos MACHINE = new BlockPos(2, 1, 1);
    private static final BlockPos INPUT_HOPPER = new BlockPos(1, 1, 1);
    private static final BlockPos GENERATOR = new BlockPos(3, 1, 1);
    private static final BlockPos OUTPUT_HOPPER = new BlockPos(2, 0, 1);
    private static final BlockPos CHEST = new BlockPos(3, 0, 1);

    static {
        StorageTests.function("machine_lithograph_from_hopper", helper -> fromHopper(helper, MachineKind.LITHOGRAPH,
                List.of(stack(MaterialContent.SILICON.get(), 2)), MaterialContent.SILICON_WAFER.get(), 6));
        StorageTests.function("machine_fabricator_from_hopper", helper -> fromHopper(helper, MachineKind.FABRICATOR,
                List.of(new ItemStack(Items.COPPER_INGOT, 2)), MaterialContent.COPPER_WIRE.get(), 6));
        StorageTests.function("machine_assembler_from_hopper",
                helper -> fromHopper(helper, MachineKind.ASSEMBLER, List.of(stack(MaterialContent.LOGIC_DIE.get(), 1),
                        stack(MaterialContent.INSULATED_COPPER_WIRE.get(), 2),
                        stack(MaterialContent.OSCILLATOR.get(), 1), stack(MaterialContent.CIRCUIT_BOARD.get(), 1)),
                        MaterialContent.BASIC_LOGIC_PROCESSOR.get(), 1));
        StorageTests.function("machine_sleeps_when_idle", MachineGameTests::sleepsWhenIdle);
        StorageTests.function("machine_generator_burns_fuel", MachineGameTests::generatorBurnsFuel);
        StorageTests.function("machine_drops_with_contents", MachineGameTests::dropsWithContents);
    }

    private MachineGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("machine"));
        for (String name : List.of("machine_lithograph_from_hopper", "machine_fabricator_from_hopper",
                "machine_assembler_from_hopper")) {
            StorageTests.test(event, environment, name, StorageTests.SMALL, 1200);
        }
        StorageTests.test(event, environment, "machine_sleeps_when_idle", StorageTests.SMALL, 400);
        StorageTests.test(event, environment, "machine_generator_burns_fuel", StorageTests.SMALL, 1200);
        StorageTests.test(event, environment, "machine_drops_with_contents", StorageTests.SMALL, 20);
    }

    /**
     * A hopper feeds the machine from the west, a fuel generator with coal powers it from the east, and a hopper under
     * it pulls the outputs into a chest: the outputs arrive and the machine and generator end up idle.
     */
    private static void fromHopper(GameTestHelper helper, MachineKind kind, List<ItemStack> inputs, Item output,
            int count) {
        helper.setBlock(MACHINE, MachineContent.block(kind));
        helper.setBlock(GENERATOR, MachineContent.block(MachineKind.FUEL_GENERATOR));
        helper.setBlock(INPUT_HOPPER, hopper(Direction.EAST));
        helper.setBlock(OUTPUT_HOPPER, hopper(Direction.EAST));
        helper.setBlock(CHEST, Blocks.CHEST);
        Container feed = helper.getBlockEntity(INPUT_HOPPER, HopperBlockEntity.class);
        for (int i = 0; i < inputs.size(); i++) {
            feed.setItem(i, inputs.get(i).copy());
        }
        insert(machine(helper, GENERATOR), new ItemStack(Items.COAL));
        helper.startSequence().thenWaitUntil(() -> {
            Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
            helper.assertValueEqual(chest.countItem(output), count, "outputs in the chest");
        }).thenWaitUntil(() -> {
            StorageMachineBlockEntity machine = machine(helper, MACHINE);
            helper.assertValueEqual(machine.state(), MachineState.IDLE, "state when done");
            helper.assertFalse(machine.isAwake(), "asleep when done");
            helper.assertFalse(helper.getBlockState(MACHINE).getValue(StorageMachineBlock.ACTIVE),
                    "inactive when done");
        }).thenExecute(() -> clear(helper)).thenSucceed();
    }

    /**
     * An empty lithograph sleeps; silicon wakes it, but without energy it sleeps again; energy wakes it and it runs,
     * showing the active state; when the silicon is used up it is idle, inactive and asleep.
     */
    private static void sleepsWhenIdle(GameTestHelper helper) {
        helper.setBlock(MACHINE, MachineContent.block(MachineKind.LITHOGRAPH));
        ProcessingMachineBlockEntity lithograph = (ProcessingMachineBlockEntity) machine(helper, MACHINE);
        long[] runs = new long[1];
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertTrue(lithograph.runs() > 0 && !lithograph.isAwake(), "asleep while empty"))
                .thenExecute(() -> runs[0] = lithograph.runs()).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(lithograph.runs(), runs[0], "runs while asleep");
                    insert(lithograph, stack(MaterialContent.SILICON.get(), 1));
                    helper.assertTrue(lithograph.isAwake(), "woken by an item");
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(lithograph.state(), MachineState.NO_ENERGY, "state without energy");
                    helper.assertFalse(lithograph.isAwake(), "asleep without energy");
                }).thenExecute(() -> runs[0] = lithograph.runs()).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(lithograph.runs(), runs[0], "runs while waiting for energy");
                    charge(lithograph, 5_000);
                    helper.assertTrue(lithograph.isAwake(), "woken by energy");
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(lithograph.state(), MachineState.RUNNING, "state");
                    helper.assertTrue(helper.getBlockState(MACHINE).getValue(StorageMachineBlock.ACTIVE),
                            "active while working");
                }).thenWaitUntil(() -> helper.assertValueEqual(lithograph.outputs().stack(0).getCount(), 3, "wafers"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(lithograph.state(), MachineState.IDLE, "state when done");
                    helper.assertFalse(lithograph.isAwake(), "asleep when done");
                    helper.assertFalse(helper.getBlockState(MACHINE).getValue(StorageMachineBlock.ACTIVE),
                            "inactive when done");
                }).thenExecute(() -> {
                    clear(helper);
                }).thenSucceed();
    }

    /**
     * Coal fills the generator's buffer and the burn pauses there instead of wasting fuel; a lava bucket's bucket comes
     * back in the output slot.
     */
    private static void generatorBurnsFuel(GameTestHelper helper) {
        helper.setBlock(GENERATOR, MachineContent.block(MachineKind.FUEL_GENERATOR));
        FuelGeneratorBlockEntity generator = (FuelGeneratorBlockEntity) machine(helper, GENERATOR);
        insert(generator, new ItemStack(Items.STONE));
        helper.assertTrue(generator.inputs().stack(0).isEmpty(), "stone is no fuel");
        insert(generator, new ItemStack(Items.COAL, 2));
        long capacity = generator.energy().capacity();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(generator.energy().stored(), capacity, "buffer filled"))
                .thenWaitUntil(() -> helper.assertFalse(generator.isAwake(), "asleep when full")).thenExecute(() -> {
                    helper.assertValueEqual(generator.inputs().stack(0).getCount(), 1, "one coal left");
                    helper.assertValueEqual(generator.state(), MachineState.OUTPUT_FULL, "paused while full");
                    helper.assertTrue(generator.progress() < generator.duration(), "the burn is kept");
                    helper.assertValueEqual(generator.energy().stored(), capacity, "never above capacity");
                    // Coal burns 1,600 ticks at the configured rate; the buffer took a part of that.
                    helper.assertValueEqual(generator.progress(),
                            (int) (capacity / StorageServerConfig.GENERATOR_ENERGY_PER_TICK.getAsInt()),
                            "ticks burned");
                    // A neighbour pulling FE wakes the paused generator at once.
                    try (Transaction transaction = Transaction.openRoot()) {
                        generator.energyHandler().extract(100, transaction);
                        transaction.commit();
                    }
                    helper.assertTrue(generator.isAwake(), "woken by a neighbour pulling FE");
                    generator.inputs().set(0, ItemResource.EMPTY, 0);
                    helper.setBlock(GENERATOR, Blocks.AIR);
                    helper.setBlock(GENERATOR, MachineContent.block(MachineKind.FUEL_GENERATOR));
                    insert(machine(helper, GENERATOR), new ItemStack(Items.LAVA_BUCKET));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        ((StorageMachineBlockEntity) machine(helper, GENERATOR)).outputs().stack(0).is(Items.BUCKET),
                        "the bucket comes back"))
                .thenExecute(() -> clear(helper)).thenSucceed();
    }

    /** A machine holding items and energy drops as an item carrying both, and an empty one as a plain item. */
    private static void dropsWithContents(GameTestHelper helper) {
        helper.setBlock(MACHINE, MachineContent.block(MachineKind.FABRICATOR));
        StorageMachineBlockEntity fabricator = machine(helper, MACHINE);
        ItemStack plain = fabricator.toItemStack(helper.getLevel().registryAccess());
        helper.assertTrue(plain.get(DataComponents.BLOCK_ENTITY_DATA) == null, "a fresh machine drops plain");
        insert(fabricator, new ItemStack(Items.COPPER_INGOT, 5));
        charge(fabricator, 100);
        ItemStack full = fabricator.toItemStack(helper.getLevel().registryAccess());
        BlockPos other = new BlockPos(4, 1, 1);
        helper.setBlock(other, MachineContent.block(MachineKind.FABRICATOR));
        StorageMachineBlockEntity restored = machine(helper, other);
        TypedEntityData<?> data = full.get(DataComponents.BLOCK_ENTITY_DATA);
        helper.assertTrue(data != null, "contents kept on the item");
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(),
                java.util.Objects.requireNonNull(data).copyTagWithoutId()));
        helper.assertValueEqual(restored.inputs().stack(0).getCount(), 5, "inputs restored");
        helper.assertValueEqual(restored.energy().stored(), 100L, "energy restored");
        clear(helper);
        helper.succeed();
    }

    private static StorageMachineBlockEntity machine(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, StorageMachineBlockEntity.class);
    }

    /** Inserts through the side handler, as a hopper or pipe does. */
    static void insert(StorageMachineBlockEntity machine, ItemStack stack) {
        try (Transaction transaction = Transaction.openRoot()) {
            ResourceHandlerUtil.insertStacking(machine.items(), ItemResource.of(stack), stack.getCount(), transaction);
            transaction.commit();
        }
    }

    /**
     * Inserts up to {@code amount} FE through the side handler, as a cable does, in as many transfers as the machine's
     * per-transfer limit needs.
     */
    static void charge(StorageMachineBlockEntity machine, int amount) {
        int left = amount;
        while (left > 0) {
            try (Transaction transaction = Transaction.openRoot()) {
                int moved = machine.energyHandler().insert(left, transaction);
                transaction.commit();
                if (moved == 0) {
                    return;
                }
                left -= moved;
            }
        }
    }

    static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    static BlockState hopper(Direction facing) {
        return Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing);
    }

    /** Removes every block the tests place, so no generator keeps burning into later batches. */
    static void clear(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 3; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }
}
