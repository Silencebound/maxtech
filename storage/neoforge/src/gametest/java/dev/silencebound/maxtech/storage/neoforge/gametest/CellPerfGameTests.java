package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.CellType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * Saving 100 changed cells (PRD 7.2, Performance: the perf GameTests): a storage of 1,000 cells of 64 kinds each (a
 * quarter of them with components), of which 100 change before each save. The median time to encode the save must stay
 * under budget, and only the changed cells may be encoded. Runs on its own storage, not the level's, in its own batch.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CellPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SAVE = "perf_cells_save_changed";
    private static final int CELLS = 1000;
    private static final int KINDS = 64;
    private static final int CHANGED = 100;
    private static final int ROUNDS = 9;
    /** Median time to encode a save with 100 changed cells among 1,000. */
    private static final long BUDGET_NANOS = 10_000_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        StorageTestEnvironment.init();
        FUNCTIONS.register(SAVE, () -> StorageTestEnvironment.clearing(CellPerfGameTests::saveChanged));
    }

    private CellPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(SAVE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.plain()),
                                StorageTests.SMALL, 100, 0, true)));
    }

    private static void saveChanged(GameTestHelper helper) {
        List<StackKey> keys = new ArrayList<>(KINDS);
        for (Item item : BuiltInRegistries.ITEM) {
            if (keys.size() == KINDS) {
                break;
            }
            if (!new ItemStack(item).isEmpty()) {
                keys.add(keys.size() % 4 == 0 ? CellGameTests.named("perf " + keys.size()) : ItemKey.of(item));
            }
        }
        CellStorage storage = new CellStorage();
        CellType type = CellContent.ITEM_TYPE.get();
        CellData data = CellData.of(CellContent.ITEM_TYPE.id(),
                Collections.nCopies(4, CellContent.MEMORY_MODULE_1.get()));
        List<CellInventory> cells = new ArrayList<>(CELLS);
        for (int i = 0; i < CELLS; i++) {
            CellInventory cell = storage.mount(storage.newId(), type, CellCasing.SMALL, data, () -> {
            });
            if (cell == null) {
                throw helper.assertionException(Component.literal("cell " + i + " not mounted"));
            }
            try (Transaction transaction = Transaction.openRoot()) {
                for (StackKey key : keys) {
                    cell.insert(key, 1 + i % 8, transaction);
                }
                transaction.commit();
            }
            cells.add(cell);
        }
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        long start = System.nanoTime();
        CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
        long full = System.nanoTime() - start;
        helper.assertValueEqual(storage.lastEncodedCells(), CELLS, "cells in the first save");

        PerfBudget.measureUnder(helper, SAVE, BUDGET_NANOS, () -> {
            long median = changedSaves(helper, storage, cells, keys, ops);
            LOGGER.info(
                    "{}: {} cells of {} kinds; full save {} ms, {} changed cells {} ms (median of {}, budget {} ms).",
                    SAVE, CELLS, KINDS, millis(full), CHANGED, millis(median), ROUNDS, millis(BUDGET_NANOS));
            return median;
        }, median -> "Saving " + CHANGED + " changed cells takes " + millis(median) + " ms, over the "
                + millis(BUDGET_NANOS) + " ms budget");
        helper.succeed();
    }

    /** {@link #ROUNDS} saves after changing {@link #CHANGED} cells each; the median save time. */
    private static long changedSaves(GameTestHelper helper, CellStorage storage, List<CellInventory> cells,
            List<StackKey> keys, RegistryOps<Tag> ops) {
        long[] times = new long[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < CHANGED; i++) {
                CellInventory cell = cells.get((round * CHANGED + i * 7) % CELLS);
                try (Transaction transaction = Transaction.openRoot()) {
                    cell.insert(keys.get(round % KINDS), 1, transaction);
                    transaction.commit();
                }
            }
            long start = System.nanoTime();
            CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
            times[round] = System.nanoTime() - start;
            helper.assertValueEqual(storage.lastEncodedCells(), CHANGED, "cells encoded in round " + round);
        }
        Arrays.sort(times);
        return times[ROUNDS / 2];
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}
