package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * A network with 200 energy cells and an empty controller (PRD 7.2, Performance): 2,000 one-item operations that drain
 * every cell in turn, then 2,000 FE inserts that fill them again and, once every cell is full, 2,000 more. The mean
 * time per operation must stay within budget, and the cells looked at must stay a small constant per call: both only
 * hold if paying and filling do not walk the cells each time. Runs in its own batch with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class EnergyCellPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String NAME = "perf_storage_energy_cells";
    private static final int COLUMNS = 20;
    private static final int ROWS = 10;
    private static final int OPERATIONS = 2000;
    /** FE per one-item operation at the default config: 4 per operation, nothing per item. */
    private static final int OPERATION_FE = 4;
    /** The cells hold exactly what the operations cost. */
    private static final long CELL_FE = (long) OPERATIONS * OPERATION_FE / (COLUMNS * ROWS);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** Mean per operation or FE insert, with the network's whole path; measured about 1 to 2 microseconds. */
    private static final long BUDGET_NANOS = 10_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(NAME, () -> StorageTestEnvironment.clearing(EnergyCellPerfGameTests::cells));
    }

    private EnergyCellPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(NAME);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                STRUCTURE, 400, 0, true)));
    }

    private static void cells(GameTestHelper helper) {
        BlockPos controller = new BlockPos(1, 1, 1);
        helper.setBlock(controller, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        List<EnergyCellBlockEntity> cells = new ArrayList<>(COLUMNS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                cells.add(EnergyCellGameTests.cell(helper, new BlockPos(2 + column, 1, 1 + row),
                        EnergyContent.TIER_1.get()));
            }
        }
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(network.energyCells().size(), COLUMNS * ROWS, "cells in the network");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            EnergyHandler input = device.energyInput();
            // Warm up the paths once, then start again from cells holding exactly what the operations cost.
            reset(cells, NetworkGameTests.controllerEntity(helper, controller).buffer());
            run(network, input, OPERATIONS, cells);
            PerfBudget.measureUnder(helper, NAME, BUDGET_NANOS, () -> {
                reset(cells, NetworkGameTests.controllerEntity(helper, controller).buffer());
                helper.assertValueEqual(network.storedEnergy(), CELL_FE * COLUMNS * ROWS, "starting FE");
                long visitsBefore = network.energyCells().visits();
                long[] times = run(network, input, OPERATIONS, cells);
                long visits = network.energyCells().visits() - visitsBefore;
                // Each pay or fill looks at one or two cells, plus one pass over the pool when the level turns.
                helper.assertTrue(visits <= 3L * OPERATIONS + 2L * cells.size(),
                        visits + " cells looked at for " + 3 * OPERATIONS + " calls: the pool is walked per call");
                helper.assertValueEqual(network.energyCells().stored(), network.energyCells().capacity(), "full");
                long mean = (times[0] + times[1] + times[2]) / (3L * OPERATIONS);
                LOGGER.info(
                        "{}: {} cells, {} operations {} ms, {} fills {} ms, {} inserts into full {} ms; mean {} ms "
                                + "(budget {} ms).",
                        NAME, cells.size(), OPERATIONS, millis(times[0]), OPERATIONS, millis(times[1]), OPERATIONS,
                        millis(times[2]), millis(mean), millis(BUDGET_NANOS));
                return mean;
            }, mean -> "an operation or FE insert with 200 energy cells takes " + millis(mean)
                    + " ms on average, over the " + millis(BUDGET_NANOS) + " ms budget");
        }).thenSucceed();
    }

    /**
     * {@code count} one-item operations, {@code count} FE inserts of one operation's cost and {@code count} inserts
     * into the full network; returns the nanoseconds of each run. The operations must all go through, so the cells
     * really paid.
     */
    private static void reset(List<EnergyCellBlockEntity> cells, FeBuffer primary) {
        primary.tryConsume(primary.stored());
        for (EnergyCellBlockEntity cell : cells) {
            cell.cellStore().tryConsume(cell.cellStore().stored());
            cell.cellStore().receive(CELL_FE);
        }
    }

    private static long[] run(NetworkState network, EnergyHandler input, int count, List<EnergyCellBlockEntity> cells) {
        long[] times = new long[3];
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (NetworkGameTests.insert(network, COBBLE, 1) != 1) {
                throw new IllegalStateException(
                        "operation " + i + " was refused with " + network.storedEnergy() + " FE");
            }
        }
        times[0] = System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            insertFe(input, OPERATION_FE);
        }
        times[1] = System.nanoTime() - start;
        if (network.storedEnergy() != (long) count * OPERATION_FE) {
            throw new IllegalStateException(
                    "the network took " + network.storedEnergy() + " FE of " + (long) count * OPERATION_FE);
        }
        for (EnergyCellBlockEntity cell : cells) {
            cell.cellStore().receive(cell.cellStore().capacity());
        }
        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            insertFe(input, OPERATION_FE);
        }
        times[2] = System.nanoTime() - start;
        return times;
    }

    private static void insertFe(EnergyHandler input, int fe) {
        try (Transaction transaction = Transaction.openRoot()) {
            input.insert(fe, transaction);
            transaction.commit();
        }
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
