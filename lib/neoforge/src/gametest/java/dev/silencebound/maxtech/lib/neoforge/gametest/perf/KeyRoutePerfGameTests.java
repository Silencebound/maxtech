package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.api.network.RouteTree;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.resource.KeyRegistry;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
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
import org.slf4j.Logger;

/**
 * Budgets of Lib's storage data structures (PRD 7.2, Performance), each in its own batch so nothing else runs in its
 * ticks: interning 100,000 keys, and a route tree over 10,000 nodes. Both are repeated and their median is asserted,
 * since CI runners are shared.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class KeyRoutePerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final String KEYS = "perf_resource_keys_intern";
    private static final String ROUTES = "perf_route_tree_10k";

    private static final int KEY_COUNT = 100_000;
    private static final int KEY_ROUNDS = 7;
    /**
     * Interning, counting, looking up and releasing 100,000 keys, the full turnover of a large network's index: about 1
     * µs per key for four map operations. Measured at 26 to 46 ms on a Mac at 4 to 5 load per CPU; a scan per key would
     * take minutes.
     */
    private static final long KEYS_BUDGET_NANOS = 100_000_000;

    private static final int SIDE = 100;
    private static final int ROUTE_ROUNDS = 15;
    /** A full widest-path rebuild of a 10,000-node zone; zones are capped smaller than this in Storage. */
    private static final long ROUTES_BUDGET_NANOS = 8_000_000;
    /** Attaching one new node to that tree, what a placed cable costs. */
    private static final long ATTACH_BUDGET_NANOS = 20_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        PerfEnvironment.init();
        FUNCTIONS.register(KEYS, () -> KeyRoutePerfGameTests::internKeys);
        FUNCTIONS.register(ROUTES, () -> KeyRoutePerfGameTests::routeTree);
    }

    private KeyRoutePerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        for (String name : new String[]{KEYS, ROUTES}) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(event.registerEnvironment(id, new PerfEnvironment()), STRUCTURE, 200, 0, true)));
        }
    }

    private static void internKeys(GameTestHelper helper) {
        StackKey[] keys = new StackKey[KEY_COUNT];
        for (int i = 0; i < KEY_COUNT; i++) {
            keys[i] = ItemKey.of(Items.DIAMOND_PICKAXE,
                    DataComponentPatch.builder().set(DataComponents.DAMAGE, i).build());
        }
        String what = KEY_COUNT + " keys interned, counted, found and released";
        PerfBudget.measureUnder(helper, KEYS, KEYS_BUDGET_NANOS,
                () -> median(KEYS, internRounds(helper, keys), KEYS_BUDGET_NANOS, what),
                median -> failure(what, median, KEYS_BUDGET_NANOS));
        helper.succeed();
    }

    private static long[] internRounds(GameTestHelper helper, StackKey[] keys) {
        long[] nanos = new long[KEY_ROUNDS];
        for (int round = 0; round < KEY_ROUNDS; round++) {
            KeyRegistry<StackKey> registry = KeyRegistry.stackKeys();
            long start = System.nanoTime();
            for (StackKey key : keys) {
                registry.add(registry.intern(key), 1);
            }
            long found = 0;
            for (StackKey key : keys) {
                found += registry.total(registry.id(key));
            }
            int variants = registry.variants(Items.DIAMOND_PICKAXE).size();
            for (int id = 0; id < KEY_COUNT; id++) {
                registry.add(id, -1);
            }
            int released = registry.releaseUnused();
            nanos[round] = System.nanoTime() - start;
            helper.assertValueEqual(found, (long) KEY_COUNT, "counted keys found");
            helper.assertValueEqual(variants, KEY_COUNT, "variants of the pickaxe");
            helper.assertValueEqual(released, KEY_COUNT, "keys released");
        }
        return nanos;
    }

    /** A 100 x 100 grid of cables with widths 1 to 4 by hash, built from the corner. */
    private static void routeTree(GameTestHelper helper) {
        String what = "route tree over " + SIDE * SIDE + " nodes";
        PerfBudget.measureUnder(helper, ROUTES, ROUTES_BUDGET_NANOS,
                () -> median(ROUTES, routeRounds(helper)[0], ROUTES_BUDGET_NANOS, what),
                median -> failure(what, median, ROUTES_BUDGET_NANOS));
        PerfBudget.measureUnder(helper, ROUTES + "_attach", ATTACH_BUDGET_NANOS,
                () -> median(ROUTES + "_attach", routeRounds(helper)[1], ATTACH_BUDGET_NANOS, "one node attached"),
                median -> failure("one node attached", median, ATTACH_BUDGET_NANOS));
        helper.succeed();
    }

    /** Build and attach times of each round. */
    private static long[][] routeRounds(GameTestHelper helper) {
        RouteTree.Links grid = KeyRoutePerfGameTests::gridLinks;
        RouteTree.EdgeWidth width = (a, b) -> 1 + (HashCommon.mix(a ^ (b * 31)) & 3);
        long root = BlockPos.asLong(0, 0, 0);
        long[] nanos = new long[ROUTE_ROUNDS];
        long[] attachNanos = new long[ROUTE_ROUNDS];
        long leaf = BlockPos.asLong(SIDE, 0, SIDE - 1);
        RouteTree.Links withLeaf = (pos, out) -> {
            if (pos == leaf) {
                out.add(BlockPos.asLong(SIDE - 1, 0, SIDE - 1));
            } else {
                gridLinks(pos, out);
            }
        };
        for (int round = 0; round < ROUTE_ROUNDS; round++) {
            long start = System.nanoTime();
            RouteTree tree = RouteTree.build(root, grid, width, SIDE * SIDE);
            nanos[round] = System.nanoTime() - start;
            helper.assertValueEqual(tree.size(), SIDE * SIDE, "nodes in the tree");
            start = System.nanoTime();
            boolean attached = tree.attach(leaf, withLeaf, width);
            attachNanos[round] = System.nanoTime() - start;
            helper.assertTrue(attached, "the new node did not attach");
        }
        return new long[][]{nanos, attachNanos};
    }

    private static void gridLinks(long pos, LongList out) {
        int x = BlockPos.getX(pos);
        int z = BlockPos.getZ(pos);
        if (x < 0 || x >= SIDE || z < 0 || z >= SIDE || BlockPos.getY(pos) != 0) {
            return;
        }
        if (x + 1 < SIDE) {
            out.add(BlockPos.asLong(x + 1, 0, z));
        }
        if (x > 0) {
            out.add(BlockPos.asLong(x - 1, 0, z));
        }
        if (z + 1 < SIDE) {
            out.add(BlockPos.asLong(x, 0, z + 1));
        }
        if (z > 0) {
            out.add(BlockPos.asLong(x, 0, z - 1));
        }
    }

    private static long median(String name, long[] nanos, long budget, String what) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long median = sorted[sorted.length / 2];
        LOGGER.info("{}: {}: median {} ms, max {} ms (budget {} ms).", name, what, millis(median),
                millis(sorted[sorted.length - 1]), millis(budget));
        return median;
    }

    private static String failure(String what, long median, long budget) {
        return what + " takes " + millis(median) + " ms, over the " + millis(budget) + " ms budget";
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
