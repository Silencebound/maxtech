package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveItem;
import dev.silencebound.maxtech.storage.ioport.IoPortBlockEntity;
import dev.silencebound.maxtech.storage.ioport.IoPortContent;
import dev.silencebound.maxtech.storage.ioport.IoPortMenu;
import dev.silencebound.maxtech.storage.ioport.IoPortMode;
import dev.silencebound.maxtech.storage.ioport.IoPortOutput;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.redstone.RedstoneMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The IO port (B09 item 11): a cell unloads into the network or loads from it in bounded batches with every item
 * accounted for at every step, a partitioned cell takes only what it accepts, cells move to the output by the port's
 * setting, breaking or unloading the port mid-way loses nothing, an offline network moves nothing, an empty port
 * sleeps, and automation can only put cells in and take them out.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class IoPortGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    /** The controller's whole buffer. */
    private static final long POWER = 10_000;
    private static final BlockPos DRIVE = NetworkGameTests.at(2);
    private static final BlockPos PORT = NetworkGameTests.at(3);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("io_port_unload_full_cell", IoPortGameTests::unloadFullCell);
        test("io_port_load_partition", IoPortGameTests::loadPartition);
        test("io_port_break_midway", IoPortGameTests::breakMidway);
        test("io_port_network_offline", IoPortGameTests::networkOffline);
        test("io_port_output_empty", IoPortGameTests::outputEmpty);
        test("io_port_output_full", IoPortGameTests::outputFull);
        test("io_port_output_stays", IoPortGameTests::outputStays);
        test("io_port_output_slots_full", IoPortGameTests::outputSlotsFull);
        test("io_port_chunk_unload", IoPortGameTests::chunkUnload);
        test("io_port_idle_sleeps", IoPortGameTests::idleSleeps);
        test("io_port_automation", IoPortGameTests::automation);
        test("io_port_settings_drop", IoPortGameTests::settingsDrop);
        test("io_port_load_void_overflow", IoPortGameTests::loadVoidOverflow);
        test("io_port_load_deny_list", IoPortGameTests::loadDenyList);
        test("io_port_read_only_load", IoPortGameTests::readOnlyLoad);
        test("io_port_read_only_unload", IoPortGameTests::readOnlyUnload);
        test("io_port_unreadable_to_output", IoPortGameTests::unreadableToOutput);
        test("io_port_kept_only_empty", IoPortGameTests::keptOnlyEmpty);
        test("redstone_io_port_pulse", IoPortGameTests::redstonePulse);
        test("redstone_io_port_with_signal", IoPortGameTests::redstoneWithSignal);
        test("redstone_io_port_pulse_waits_for_power", IoPortGameTests::redstonePulseWaitsForPower);
    }

    private IoPortGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("io_ports"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, StorageTests.SMALL, 400, 0, true)));
        }
    }

    /**
     * Three kinds unload from a cell into the drive's: the first tick moves at most one tick's operations, cell and
     * network add up after every tick, and once the cell is empty it moves to the first output slot, unmounted.
     */
    private static void unloadFullCell(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] firstMove = {-1};
        long[] id = new long[1];
        setup.then(helper, () -> {
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 1_000);
            CellGameTests.insert(cell, STONE, 500);
            CellGameTests.insert(cell, DIRT, 37);
        }).thenWaitUntil(() -> {
            long inPort = stored(helper, id[0]);
            helper.assertValueEqual(inPort + total(setup.target(helper)), 1_537L, "items in both cells");
            if (firstMove[0] < 0) {
                helper.assertTrue(inPort < 1_537, "nothing moved yet");
                firstMove[0] = 1_537 - inPort;
                helper.assertTrue(firstMove[0] <= budget(setup.port), "first tick moved " + firstMove[0]);
            }
            helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output");
        }).thenExecute(() -> {
            helper.assertValueEqual(setup.port.inputCount(), 0, "cells in the input");
            helper.assertValueEqual(stored(helper, id[0]), 0L, "items left in the cell");
            helper.assertTrue(!CellGameTests.storage(helper).isMounted(id[0]), "the output cell is still mounted");
            CellInventory target = setup.target(helper);
            helper.assertValueEqual(target.amount(COBBLE), 1_000L, "cobblestone");
            helper.assertValueEqual(target.amount(STONE), 500L, "stone");
            helper.assertValueEqual(target.amount(DIRT), 37L, "dirt");
            CellData data = CellGameTests.data(helper,
                    setup.port.slots().getResource(IoPortBlockEntity.INPUTS).toStack(1));
            helper.assertValueEqual(data.usedBytes(), 0L, "bytes on the output cell's item");
        }).thenSucceed();
    }

    /**
     * A partitioned cell loading from the network takes only the stone its filter names, every unit of it, and moves to
     * the output once nothing more fits; the cobblestone stays in the network.
     */
    private static void loadPartition(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        CellFilter stone = new CellFilter(List.of(CellFilter.Entry.of(STONE)), false);
        long[] id = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), COBBLE, 300);
            CellGameTests.insert(setup.target(helper), STONE, 200);
            setup.port.setMode(IoPortMode.LOAD);
            id[0] = setup.insertCell(helper, CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                    List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.PARTITION_ADDON.get()), stone))
                    .id();
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(stored(helper, id[0]) + total(setup.target(helper)), 500L, "items in both cells");
            helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output");
        }).thenExecute(() -> {
            helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], STONE), 200L, "stone in the cell");
            helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], COBBLE), 0L, "cobblestone in the cell");
            helper.assertValueEqual(setup.target(helper).amount(COBBLE), 300L, "cobblestone left in the network");
            helper.assertValueEqual(setup.target(helper).amount(STONE), 0L, "stone left in the network");
        }).thenSucceed();
    }

    /**
     * The port is broken while it unloads: it drops itself with the cell, the cell leaves unmounted with every item
     * either in it or in the network, and nothing moves afterwards.
     */
    private static void breakMidway(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        long[] atBreak = new long[1];
        setup.then(helper, () -> {
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 3_000);
        }).thenWaitUntil(() -> helper.assertTrue(total(setup.target(helper)) > 0, "nothing moved yet"))
                .thenExecute(() -> {
                    helper.assertTrue(stored(helper, id[0]) > 0, "finished before the break");
                    ServerLevel level = helper.getLevel();
                    level.destroyBlock(helper.absolutePos(PORT), true);
                    ItemStack item = dropped(helper);
                    helper.assertValueEqual(DriveItem.cells(item), 1, "cells in the dropped port");
                    helper.assertTrue(!CellGameTests.storage(helper).isMounted(id[0]), "mounted after the break");
                    atBreak[0] = stored(helper, id[0]);
                    helper.assertValueEqual(atBreak[0] + total(setup.target(helper)), 3_000L, "items at the break");
                }).thenExecuteAfter(10, () -> {
                    helper.assertValueEqual(stored(helper, id[0]), atBreak[0], "moved after the break");
                    helper.assertValueEqual(atBreak[0] + total(setup.target(helper)), 3_000L, "items after the break");
                }).thenSucceed();
    }

    /**
     * A network without energy moves nothing and keeps the cell in the input; once powered, the port unloads it all.
     */
    private static void networkOffline(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            setup.target(helper);
            helper.assertTrue(setup.port.network() != null, "not in a network");
        }).thenExecute(() -> {
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 500);
        }).thenExecuteAfter(20, () -> {
            helper.assertValueEqual(stored(helper, id[0]), 500L, "items in the cell without power");
            helper.assertValueEqual(total(setup.target(helper)), 0L, "items in the network without power");
            helper.assertValueEqual(setup.port.inputCount(), 1, "cells in the input without power");
            NetworkGameTests.power(helper, setup.controller, POWER);
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output"))
                .thenExecute(() -> helper.assertValueEqual(total(setup.target(helper)), 500L, "items unloaded"))
                .thenSucceed();
    }

    /**
     * "When empty": an empty cell passes straight through even in load mode with items in the network, and an unloading
     * cell moves once it is empty.
     */
    private static void outputEmpty(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), STONE, 50);
            setup.port.setOutput(IoPortOutput.EMPTY);
            setup.port.setMode(IoPortMode.LOAD);
            setup.insertCell(helper, cell(1));
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "the empty cell moved"))
                .thenExecute(() -> {
                    helper.assertValueEqual(setup.target(helper).amount(STONE), 50L, "stone loaded");
                    setup.port.setMode(IoPortMode.UNLOAD);
                    CellInventory cell = setup.insertCell(helper, cell(1));
                    id[0] = cell.id();
                    CellGameTests.insert(cell, COBBLE, 200);
                }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 2, "cells in the output"))
                .thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, id[0]), 0L, "items left in the unloaded cell");
                    helper.assertValueEqual(setup.target(helper).amount(COBBLE), 200L, "cobblestone unloaded");
                }).thenSucceed();
    }

    /** "When full": a loading cell moves once it is full, the rest stays in the network, nothing lost. */
    private static void outputFull(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), COBBLE, 12_000);
            setup.port.setOutput(IoPortOutput.FULL);
            setup.port.setMode(IoPortMode.LOAD);
            id[0] = setup.insertCell(helper, cell(1)).id();
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(stored(helper, id[0]) + total(setup.target(helper)), 12_000L, "cobblestone");
            helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output");
        }).thenExecute(() -> {
            CellData data = CellGameTests.data(helper,
                    setup.port.slots().getResource(IoPortBlockEntity.INPUTS).toStack(1));
            helper.assertValueEqual(data.usedBytes(), data.totalBytes(), "bytes used in the output cell");
            helper.assertTrue(setup.target(helper).amount(COBBLE) > 0, "the network gave everything");
        }).thenSucceed();
    }

    /**
     * "When full" while unloading: the cell is emptied but never full, so it stays in the input, and the port backs off
     * instead of running every tick.
     */
    private static void outputStays(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        long[] runs = new long[1];
        setup.then(helper, () -> {
            setup.port.setOutput(IoPortOutput.FULL);
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 100);
        }).thenWaitUntil(() -> helper.assertValueEqual(stored(helper, id[0]), 0L, "items left in the cell"))
                .thenIdle(20).thenExecute(() -> {
                    runs[0] = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, setup.controller));
                }).thenExecuteAfter(60, () -> {
                    helper.assertValueEqual(setup.port.inputCount(), 1, "cells in the input");
                    helper.assertValueEqual(setup.port.outputCount(), 0, "cells in the output");
                    helper.assertValueEqual(setup.target(helper).amount(COBBLE), 100L, "cobblestone unloaded");
                    long ran = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, setup.controller)) - runs[0];
                    helper.assertTrue(ran < 20, ran + " task runs in 60 ticks: the port does not back off");
                }).thenSucceed();
    }

    /**
     * With every output slot taken, a finished cell waits in the input; taking one output cell out lets it move.
     */
    private static void outputSlotsFull(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        setup.then(helper, () -> {
            for (int i = IoPortBlockEntity.INPUTS; i < IoPortBlockEntity.SLOTS; i++) {
                setup.port.slots().set(i, ItemResource.of(cell(1)), 1);
            }
            setup.insertCell(helper, cell(1));
        }).thenExecuteAfter(10, () -> {
            helper.assertValueEqual(setup.port.inputCount(), 1, "the cell left with the output full");
            ItemResource out = setup.port.slots().getResource(IoPortBlockEntity.INPUTS);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(setup.port.automation().extract(IoPortBlockEntity.INPUTS, out, 1, transaction),
                        1, "cell taken out of the output");
                transaction.commit();
            }
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.inputCount(), 0, "cells in the input"))
                .thenExecute(() -> helper.assertValueEqual(setup.port.outputCount(), IoPortBlockEntity.OUTPUTS,
                        "cells in the output"))
                .thenSucceed();
    }

    /**
     * The port's chunk unloads while it unloads a cell: while it is away nothing moves and nothing is lost; loaded
     * again from its saved data it mounts the cell and finishes.
     */
    private static void chunkUnload(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        CompoundTag[] saved = new CompoundTag[1];
        long[] id = new long[1];
        long[] away = new long[1];
        @Nullable
        IoPortBlockEntity[] again = new IoPortBlockEntity[1];
        setup.then(helper, () -> {
            setup.port.setOutput(IoPortOutput.EMPTY);
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 3_000);
        }).thenWaitUntil(() -> helper.assertTrue(total(setup.target(helper)) > 0, "nothing moved yet"))
                .thenExecute(() -> {
                    helper.assertTrue(total(setup.target(helper)) < 3_000, "finished before the unload");
                    saved[0] = setup.port.saveWithFullMetadata(helper.getLevel().registryAccess());
                    // As a chunk unload: the hook first, then the block entity leaves the chunk (setRemoved).
                    setup.port.onChunkUnloaded();
                    BlockPos abs = helper.absolutePos(PORT);
                    helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
                    helper.assertTrue(!CellGameTests.storage(helper).isMounted(id[0]), "mounted while unloaded");
                    away[0] = stored(helper, id[0]);
                }).thenExecuteAfter(10, () -> {
                    helper.assertValueEqual(stored(helper, id[0]), away[0], "moved while unloaded");
                    helper.assertValueEqual(away[0] + total(setup.target(helper)), 3_000L, "items while unloaded");
                    BlockPos abs = helper.absolutePos(PORT);
                    BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), saved[0],
                            helper.getLevel().registryAccess());
                    if (!(loaded instanceof IoPortBlockEntity port)) {
                        throw helper.assertionException(Component.literal("the port did not load"));
                    }
                    again[0] = port;
                    helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(port);
                    helper.assertValueEqual(port.output(), IoPortOutput.EMPTY, "output setting after loading");
                }).thenWaitUntil(() -> {
                    IoPortBlockEntity port = again[0];
                    helper.assertTrue(port != null && port.outputCount() == 1, "the cell did not finish");
                }).thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, id[0]), 0L, "items left in the cell");
                    helper.assertValueEqual(total(setup.target(helper)), 3_000L, "items unloaded");
                }).thenSucceed();
    }

    /**
     * An empty port's task sleeps: no runs while it holds no cell; a cell put in wakes it, it works the cell through
     * and sleeps again.
     */
    private static void idleSleeps(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] runs = new long[1];
        setup.then(helper, () -> {
        }).thenIdle(10).thenExecute(() -> {
            runs[0] = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, setup.controller));
        }).thenExecuteAfter(40, () -> {
            long ran = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, setup.controller)) - runs[0];
            helper.assertValueEqual(ran, 0L, "task runs of an empty port");
            setup.insertCell(helper, cell(1));
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "the woken port moved the cell"))
                .thenIdle(5).thenExecute(() -> {
                    runs[0] = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, setup.controller));
                }).thenExecuteAfter(40, () -> {
                    long ran = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, setup.controller)) - runs[0];
                    helper.assertValueEqual(ran, 0L, "task runs after the cell moved");
                }).thenSucceed();
    }

    /**
     * Automation (the item capability) puts cells only into the input slots and takes them only out of the output
     * slots; other items are refused.
     */
    private static void automation(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        setup.then(helper, () -> {
            var handler = setup.port.automation();
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(
                        handler.insert(IoPortBlockEntity.INPUTS, ItemResource.of(cell(1)), 1, transaction), 0,
                        "cell into an output slot");
                helper.assertValueEqual(handler.insert(ItemResource.of(Items.STONE), 1, transaction), 0,
                        "stone into the port");
                helper.assertValueEqual(handler.insert(ItemResource.of(cell(1)), 1, transaction), 1,
                        "cell into the port");
                transaction.commit();
            }
            helper.assertValueEqual(setup.port.inputCount(), 1, "cells in the input");
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output"))
                .thenExecute(() -> {
                    var handler = setup.port.automation();
                    setup.port.insertCell(cell(1));
                    // Keep the second cell in the input: an unknown setting would move it at once.
                    setup.port.setOutput(IoPortOutput.FULL);
                    ItemResource input = setup.port.slots().getResource(0);
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(handler.extract(0, input, 1, transaction), 0,
                                "cell out of an input slot");
                        ItemResource output = setup.port.slots().getResource(IoPortBlockEntity.INPUTS);
                        helper.assertValueEqual(handler.extract(output, 1, transaction), 1, "cell out of the port");
                        transaction.commit();
                    }
                    helper.assertValueEqual(setup.port.outputCount(), 0, "cells in the output");
                }).thenSucceed();
    }

    /**
     * Mode, output setting, speed cards and cells travel in the dropped item and come back when it is placed, and the
     * screen's buttons change the settings only for a player with access.
     */
    private static void settingsDrop(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        @Nullable
        IoPortBlockEntity[] again = new IoPortBlockEntity[1];
        setup.then(helper, () -> {
            ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "io_port_stranger");
            IoPortMenu theirs = IoPortMenu.forPort(2, stranger.getInventory(), setup.port);
            helper.assertTrue(!theirs.clickMenuButton(stranger, IoPortMenu.CYCLE_MODE), "a stranger changed the mode");
            IoPortMenu menu = IoPortMenu.forPort(1, setup.owner.getInventory(), setup.port);
            helper.assertTrue(menu.clickMenuButton(setup.owner, IoPortMenu.CYCLE_MODE), "the owner's mode click");
            helper.assertTrue(menu.clickMenuButton(setup.owner, IoPortMenu.CYCLE_OUTPUT), "the owner's output click");
            helper.assertValueEqual(setup.port.mode(), IoPortMode.LOAD, "mode after the click");
            helper.assertValueEqual(setup.port.output(), IoPortOutput.EMPTY, "output setting after the click");
            setup.port.setOutput(IoPortOutput.FULL);
            setup.port.cards().setItem(0, new ItemStack(PortContent.SPEED_CARD.get()));
            setup.port.cards().setItem(1, new ItemStack(PortContent.SPEED_CARD.get()));
            id[0] = setup.insertCell(helper, cell(1)).id();
            helper.assertValueEqual(setup.port.operations(),
                    (int) (StorageServerConfig.IO_PORT_OPERATIONS.getAsInt()
                            * StorageServerConfig.IO_PORT_SPEED_CARD_MULTIPLIER.getAsDouble()
                            * StorageServerConfig.IO_PORT_SPEED_CARD_MULTIPLIER.getAsDouble()),
                    "operations with two cards");
            helper.getLevel().destroyBlock(helper.absolutePos(PORT), true);
            ItemStack item = dropped(helper);
            TypedEntityData<BlockEntityType<?>> data = item.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data == null) {
                throw helper.assertionException(Component.literal("no block entity data on the port item"));
            }
            helper.setBlock(PORT, IoPortContent.IO_PORT.get());
            IoPortBlockEntity placed = helper.getBlockEntity(PORT, IoPortBlockEntity.class);
            again[0] = placed;
            data.loadInto(placed, helper.getLevel().registryAccess());
        }).thenWaitUntil(() -> {
            IoPortBlockEntity placed = again[0];
            CellInventory cell = placed == null ? null : placed.cell(0);
            helper.assertTrue(cell != null && cell.id() == id[0], "the cell did not mount with its id");
        }).thenExecute(() -> {
            IoPortBlockEntity placed = again[0];
            if (placed == null) {
                throw helper.assertionException(Component.literal("the port was not placed"));
            }
            helper.assertValueEqual(placed.mode(), IoPortMode.LOAD, "mode after placing");
            helper.assertValueEqual(placed.output(), IoPortOutput.FULL, "output setting after placing");
            helper.assertValueEqual(placed.operations(), setup.port.operations(), "operations after placing");
            helper.assertValueEqual(placed.inputCount(), 1, "cells after placing");
        }).thenSucceed();
    }

    /**
     * Loading into a void-overflow cell that names cobblestone fills it, then stops: the network keeps the rest, and
     * not one item is destroyed on the way.
     */
    private static void loadVoidOverflow(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        CellFilter cobble = new CellFilter(List.of(CellFilter.Entry.of(COBBLE)), false);
        long[] id = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), COBBLE, 12_000);
            setup.port.setMode(IoPortMode.LOAD);
            id[0] = setup.insertCell(helper, CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                    List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.VOID_OVERFLOW_ADDON.get()), cobble))
                    .id();
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(stored(helper, id[0]) + total(setup.target(helper)), 12_000L,
                    "cobblestone in both cells");
            helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output");
        }).thenExecute(() -> {
            helper.assertTrue(stored(helper, id[0]) > 0, "nothing loaded");
            helper.assertTrue(total(setup.target(helper)) > 0, "the network gave everything");
        }).thenSucceed();
    }

    /**
     * A partitioned cell in deny mode (B09 item 10) that denies stone takes all the cobblestone and none of the stone.
     */
    private static void loadDenyList(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        CellFilter notStone = new CellFilter(List.of(CellFilter.Entry.of(STONE)), false, true);
        long[] id = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), COBBLE, 300);
            CellGameTests.insert(setup.target(helper), STONE, 200);
            setup.port.setMode(IoPortMode.LOAD);
            id[0] = setup.insertCell(helper, CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                    List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.PARTITION_ADDON.get()), notStone))
                    .id();
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(stored(helper, id[0]) + total(setup.target(helper)), 500L, "items in both cells");
            helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output");
        }).thenExecute(() -> {
            helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], COBBLE), 300L,
                    "cobblestone in the cell");
            helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], STONE), 0L, "stone in the cell");
            helper.assertValueEqual(setup.target(helper).amount(STONE), 200L, "stone left in the network");
        }).thenSucceed();
    }

    /** A read-only cell takes nothing when loading and moves on once done; the network keeps everything. */
    private static void readOnlyLoad(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), COBBLE, 300);
            setup.port.setMode(IoPortMode.LOAD);
            id[0] = setup.insertCell(helper, readOnly(helper, 0)).id();
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output"))
                .thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, id[0]), 0L, "items in the read-only cell");
                    helper.assertValueEqual(setup.target(helper).amount(COBBLE), 300L, "cobblestone in the network");
                }).thenSucceed();
    }

    /** The read-only lock only stops inserts: a read-only cell unloads all it holds. */
    private static void readOnlyUnload(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        setup.then(helper, () -> {
            id[0] = setup.insertCell(helper, readOnly(helper, 400)).id();
            helper.assertValueEqual(stored(helper, id[0]), 400L, "items in the read-only cell");
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output"))
                .thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, id[0]), 0L, "items left in the read-only cell");
                    helper.assertValueEqual(setup.target(helper).amount(COBBLE), 400L, "cobblestone unloaded");
                }).thenSucceed();
    }

    /**
     * Cells the port cannot mount go straight to the output untouched, in either mode: one of an unknown type, and a
     * copy of the drive's cell (its id is in use). The drive's cell keeps its contents.
     */
    private static void unreadableToOutput(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), COBBLE, 300);
            setup.port.setMode(IoPortMode.LOAD);
            setup.port.setOutput(IoPortOutput.FULL);
            ItemStack unknown = cell(1);
            unknown.set(ModDataComponents.CELL.get(), CellGameTests.data(helper, unknown).withParts(
                    MaxtechStorage.id("no_such_cell_type"), List.of(CellContent.MEMORY_MODULE_1.get()), List.of()));
            ItemStack copy = setup.drive.cells().getResource(0).toStack(1);
            helper.assertTrue(setup.port.insertCell(unknown), "the unknown cell did not go in");
            helper.assertTrue(setup.port.insertCell(copy), "the copied cell did not go in");
            helper.assertTrue(setup.port.cell(0) == null && setup.port.cell(1) == null, "an unreadable cell mounted");
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 2, "cells in the output"))
                .thenExecute(() -> {
                    helper.assertValueEqual(setup.port.inputCount(), 0, "cells in the input");
                    helper.assertValueEqual(setup.target(helper).amount(COBBLE), 300L, "cobblestone in the drive");
                }).thenSucceed();
    }

    /**
     * A cell holding only entries of a removed mod counts as empty: unloading with "when empty", it moves to the output
     * although its kinds are not 0, and its kept entries stay stored under its id, which the output item still carries.
     * Nothing reaches the network.
     */
    private static void keptOnlyEmpty(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        setup.then(helper, () -> {
            setup.port.setOutput(IoPortOutput.EMPTY);
            id[0] = KeptEntriesGameTests.storedWithUnknown(helper, 0);
            ItemStack stack = cell(1);
            stack.set(ModDataComponents.CELL.get(), CellGameTests.data(helper, stack).withId(id[0]));
            CellInventory cell = setup.insertCell(helper, stack);
            helper.assertValueEqual(cell.kinds(), 1, "kinds of the mounted cell, kept included");
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "cells in the output"))
                .thenExecute(() -> {
                    CellStorage storage = CellGameTests.storage(helper);
                    helper.assertTrue(storage.contains(id[0]), "the cell's kept entries were forgotten");
                    helper.assertTrue(!storage.isMounted(id[0]), "the output cell is still mounted");
                    helper.assertValueEqual(storage.keptKinds(id[0]), 1, "kept kinds after the move");
                    CellData data = CellGameTests.data(helper,
                            setup.port.slots().getResource(IoPortBlockEntity.INPUTS).toStack(1));
                    helper.assertValueEqual(data.id(), id[0], "id on the output cell's item");
                    helper.assertValueEqual(data.kept(), 1, "kept kinds on the output cell's item");
                    helper.assertValueEqual(total(setup.target(helper)), 0L, "items in the network");
                }).thenSucceed();
    }

    /** A small item cell with the read-only add-on and an id whose stored contents are {@code cobble} cobblestone. */
    private static ItemStack readOnly(GameTestHelper helper, long cobble) {
        CellStorage storage = CellGameTests.storage(helper);
        long id = storage.newId();
        if (cobble > 0) {
            CellData plain = CellData.of(CellContent.ITEM_TYPE.id(), List.of(CellContent.MEMORY_MODULE_1.get()))
                    .withId(id);
            CellInventory filling = storage.mount(id, CellContent.ITEM_TYPE.get(), CellCasing.SMALL, plain,
                    IoPortGameTests::nothing);
            if (filling == null) {
                throw helper.assertionException(Component.literal("could not fill the read-only cell"));
            }
            CellGameTests.insert(filling, COBBLE, cobble);
            storage.unmount(filling);
        }
        ItemStack stack = CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.READ_ONLY_ADDON.get()),
                CellFilter.EMPTY);
        stack.set(ModDataComponents.CELL.get(), CellGameTests.data(helper, stack).withId(id));
        return stack;
    }

    /**
     * Pulse mode (B09 item 9): no edge, nothing moves; each rising edge of a redstone block above the port moves
     * exactly one operation (one batch of one kind), and nothing more until the next edge.
     */
    private static void redstonePulse(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        long batch = StorageServerConfig.BATCH_ITEMS.getAsInt();
        BlockPos lever = PORT.above();
        setup.then(helper, () -> {
            setup.port.redstone().setMode(RedstoneMode.PULSE);
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 500);
        }).thenExecuteAfter(30, () -> {
            helper.assertValueEqual(stored(helper, id[0]), 500L, "moved without a pulse");
            helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
        }).thenExecuteAfter(2, () -> helper.setBlock(lever, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(stored(helper, id[0]), 500 - batch, "after one pulse"))
                .thenExecuteAfter(30, () -> {
                    helper.assertValueEqual(stored(helper, id[0]), 500 - batch, "one pulse moved more than a batch");
                    helper.assertValueEqual(total(setup.target(helper)), batch, "in the network after one pulse");
                    helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
                }).thenExecuteAfter(2, () -> helper.setBlock(lever, Blocks.AIR))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(stored(helper, id[0]), 500 - 2 * batch, "after two pulses"))
                .thenExecuteAfter(30, () -> {
                    helper.assertValueEqual(stored(helper, id[0]), 500 - 2 * batch, "two pulses moved more");
                    helper.assertValueEqual(stored(helper, id[0]) + total(setup.target(helper)), 500L, "items");
                }).thenSucceed();
    }

    /**
     * A rising edge while the network has no energy is not lost: the port moves nothing then, and exactly one batch
     * once the network is powered.
     */
    private static void redstonePulseWaitsForPower(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        long batch = StorageServerConfig.BATCH_ITEMS.getAsInt();
        BlockPos lever = PORT.above();
        helper.startSequence().thenWaitUntil(() -> {
            setup.target(helper);
            helper.assertTrue(setup.port.network() != null, "not in a network");
        }).thenExecute(() -> {
            setup.port.redstone().setMode(RedstoneMode.PULSE);
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 500);
            helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
        }).thenExecuteAfter(2, () -> helper.setBlock(lever, Blocks.AIR)).thenExecuteAfter(20, () -> {
            helper.assertValueEqual(stored(helper, id[0]), 500L, "moved without energy");
            NetworkGameTests.power(helper, setup.controller, POWER);
        }).thenWaitUntil(() -> helper.assertValueEqual(stored(helper, id[0]), 500 - batch, "the edge after powering"))
                .thenExecuteAfter(30, () -> helper.assertValueEqual(stored(helper, id[0]), 500 - batch,
                        "one edge moved more than a batch"))
                .thenSucceed();
    }

    /**
     * "With signal": without a signal the port moves nothing and its link is off; a redstone block above it starts the
     * unload. The mode survives a save and load by its id.
     */
    private static void redstoneWithSignal(GameTestHelper helper) {
        Setup setup = setup(helper, 2);
        long[] id = new long[1];
        BlockPos lever = PORT.above();
        setup.then(helper, () -> {
            setup.port.redstone().setMode(RedstoneMode.WITH_SIGNAL);
            CellInventory cell = setup.insertCell(helper, cell(1));
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 300);
        }).thenExecuteAfter(30, () -> {
            helper.assertValueEqual(stored(helper, id[0]), 300L, "moved without a signal");
            helper.assertTrue(setup.port.deviceLink().redstoneOff(), "the link is not off");
            CompoundTag saved = setup.port.saveWithFullMetadata(helper.getLevel().registryAccess());
            BlockPos abs = helper.absolutePos(PORT);
            BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), saved,
                    helper.getLevel().registryAccess());
            helper.assertTrue(
                    loaded instanceof IoPortBlockEntity copy && copy.redstone().mode() == RedstoneMode.WITH_SIGNAL,
                    "the mode did not survive a save");
            helper.setBlock(lever, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.port.outputCount(), 1, "unloaded with a signal"))
                .thenExecute(() -> helper.assertValueEqual(total(setup.target(helper)), 300L, "items unloaded"))
                .thenSucceed();
    }

    private static void nothing() {
    }

    /** Most items one run moves: the port's operations, one network batch each. */
    private static long budget(IoPortBlockEntity port) {
        return (long) port.operations() * StorageServerConfig.BATCH_ITEMS.getAsInt();
    }

    /** The item cell's contents by id in the cell storage, whether mounted or not. */
    private static long stored(GameTestHelper helper, long id) {
        return CellGameTests.storage(helper).amount(id, COBBLE) + CellGameTests.storage(helper).amount(id, STONE)
                + CellGameTests.storage(helper).amount(id, DIRT);
    }

    private static long total(CellInventory cell) {
        return cell.amount(COBBLE) + cell.amount(STONE) + cell.amount(DIRT);
    }

    /** A new small item cell with {@code modules} memory modules I. */
    private static ItemStack cell(int modules) {
        return CellGameTests.cell(CellContent.ITEM_TYPE.id(), modules);
    }

    /** The port item the broken port dropped; the drops are removed. */
    private static ItemStack dropped(GameTestHelper helper) {
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(PORT)).inflate(2));
        ItemStack item = ItemStack.EMPTY;
        for (ItemEntity drop : drops) {
            if (drop.getItem().is(IoPortContent.IO_PORT.item().get())) {
                item = drop.getItem().copy();
            }
            drop.discard();
        }
        if (item.isEmpty()) {
            throw helper.assertionException(Component.literal("the port dropped no item"));
        }
        return item;
    }

    /**
     * A controller, a drive with one item cell of {@code targetModules} memory modules I as the network's storage, and
     * the IO port, owned by one player.
     */
    private static Setup setup(GameTestHelper helper, int targetModules) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity drive = CellGameTests.drive(helper, DRIVE);
        helper.setBlock(PORT, IoPortContent.IO_PORT.get());
        IoPortBlockEntity port = helper.getBlockEntity(PORT, IoPortBlockEntity.class);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "io_port_owner");
        drive.setOwner(OwnerRecord.of(owner));
        port.setOwner(OwnerRecord.of(owner));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), targetModules));
        return new Setup(controller, drive, port, owner);
    }

    private record Setup(BlockPos controller, DriveBlockEntity drive, IoPortBlockEntity port, ServerPlayer owner) {
        CellInventory target(GameTestHelper helper) {
            return CellGameTests.mounted(helper, drive, 0);
        }

        /** Puts {@code stack} into the port's first free input slot and returns it mounted. */
        CellInventory insertCell(GameTestHelper helper, ItemStack stack) {
            int slot = -1;
            for (int i = 0; i < IoPortBlockEntity.INPUTS && slot < 0; i++) {
                if (port.slots().getResource(i).isEmpty()) {
                    slot = i;
                }
            }
            helper.assertTrue(slot >= 0 && port.insertCell(stack), "the cell did not go in");
            CellInventory cell = port.cell(slot);
            if (cell == null) {
                throw helper.assertionException(Component.literal("no cell mounted in input slot " + slot));
            }
            return cell;
        }

        /** Waits for the drive's cell and the network, powers it, then runs {@code start}. */
        GameTestSequence then(GameTestHelper helper, Runnable start) {
            return helper.startSequence().thenWaitUntil(() -> {
                target(helper);
                helper.assertTrue(drive.network() != null && port.network() != null, "not in a network");
            }).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, POWER);
                start.run();
            });
        }
    }
}
