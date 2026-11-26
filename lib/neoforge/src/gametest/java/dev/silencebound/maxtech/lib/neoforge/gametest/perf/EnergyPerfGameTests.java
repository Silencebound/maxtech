package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.energy.EnergyScheduler;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * Budgets of the energy pass (MLE, PRD 8.1), each in its own batch: a full pass over 500 endpoints, and a network of
 * 500 endpoints asleep on a real level, which must run no pass and cost the level's energy tick next to nothing.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class EnergyPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final String PASS = "perf_energy_pass_500";
    private static final String SLEEP = "perf_energy_sleeping_500";

    private static final int ENDPOINTS = 500;
    private static final int WARMUP_PASSES = 20_000;
    private static final int ROUNDS = 15;
    private static final int PASSES_PER_ROUND = 500;
    /** One full pass over 500 endpoints (MLE's budget, owner 2026-10-15). */
    private static final long PASS_BUDGET_NANOS = 100_000;

    private static final int SLEEP_TICKS = 200;
    private static final int IDLE_CALLS = 100_000;
    /**
     * Mean cost of the level's energy tick while its only network sleeps: an early return. MLE's budget is "0 per
     * tick"; 200 ns leaves room for a busy runner, still a thousandth of the pass budget.
     */
    private static final long IDLE_BUDGET_NANOS = 200;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        PerfEnvironment.init();
        FUNCTIONS.register(PASS, () -> EnergyPerfGameTests::fullPass);
        FUNCTIONS.register(SLEEP, () -> EnergyPerfGameTests::sleeping);
    }

    private EnergyPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        for (String name : new String[]{PASS, SLEEP}) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id,
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                            new TestData<>(event.registerEnvironment(id, new PerfEnvironment()), STRUCTURE,
                                    SLEEP_TICKS + 100, 0, true)));
        }
    }

    /**
     * 100 producers, 300 consumers over five priorities and 100 buffers (a quarter leftovers only), with supply short
     * of demand so every branch of the pass runs: brownout, buffers covering the shortfall and the leftovers level.
     */
    private static EnergyEndpoint[] populate(EnergyNetwork network) {
        EnergyEndpoint[] endpoints = new EnergyEndpoint[ENDPOINTS];
        for (int i = 0; i < ENDPOINTS; i++) {
            EnergyEndpoint endpoint;
            if (i % 5 == 0) {
                endpoint = EnergyEndpoint.producer();
                endpoint.setSupply(200 + i);
            } else if (i % 5 == 1) {
                endpoint = EnergyEndpoint.buffer(1_000_000_000L, 500, 10,
                        i % 4 == 1 ? EnergyPriority.LEFTOVERS : i % 3);
                endpoint.setStored(500_000_000L);
            } else {
                endpoint = EnergyEndpoint.consumer(i % 5 - 2);
                endpoint.setDemand(100 + i % 37);
            }
            endpoints[i] = endpoint;
            network.add(endpoint);
        }
        return endpoints;
    }

    private static void fullPass(GameTestHelper helper) {
        long[] clock = {0};
        EnergyScheduler scheduler = new EnergyScheduler(() -> clock[0]);
        EnergyNetwork network = scheduler.createNetwork();
        EnergyEndpoint[] endpoints = populate(network);
        for (int i = 0; i < WARMUP_PASSES; i++) {
            network.wake();
            scheduler.tick();
            clock[0]++;
        }
        helper.assertTrue(network.satisfaction() < 1.0, "the scenario is short of supply");
        helper.assertTrue(network.bufferDischarge() > 0, "buffers cover the shortfall");
        String what = ENDPOINTS + " endpoints, one pass";
        PerfBudget.measureUnder(helper, PASS, PASS_BUDGET_NANOS, () -> {
            long[] nanos = new long[ROUNDS];
            for (int round = 0; round < ROUNDS; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < PASSES_PER_ROUND; i++) {
                    network.wake();
                    scheduler.tick();
                    clock[0]++;
                }
                nanos[round] = (System.nanoTime() - start) / PASSES_PER_ROUND;
            }
            return median(PASS, nanos, PASS_BUDGET_NANOS, what);
        }, median -> what + " takes " + micros(median) + " µs, over the " + micros(PASS_BUDGET_NANOS) + " µs budget");
        helper.assertTrue(endpoints[4].received() > 0, "top-priority consumers are served");
        helper.succeed();
    }

    private static void sleeping(GameTestHelper helper) {
        EnergyScheduler scheduler = EnergyNetworks.of(helper.getLevel());
        EnergyNetwork network = scheduler.createNetwork();
        PerfEnvironment.onTeardown(network::dispose);
        EnergyEndpoint[] endpoints = populate(network);
        long[] passes = new long[1];
        helper.startSequence().thenExecuteAfter(2, () -> {
            helper.assertTrue(network.passCount() > 0, "the level ran the woken network");
            helper.assertTrue(endpoints[4].received() > 0, "top-priority consumers are served");
            helper.assertTrue(network.nextWakeTick() > helper.getLevel().getGameTime() + SLEEP_TICKS,
                    "no buffer changes within the test, wake at " + network.nextWakeTick());
            passes[0] = network.passCount();
        }).thenIdle(SLEEP_TICKS).thenExecute(() -> {
            helper.assertTrue(network.passCount() == passes[0],
                    (network.passCount() - passes[0]) + " passes while nothing changed");
            helper.assertTrue(network.isAsleep(), "the network sleeps");
            PerfBudget.measureUnder(helper, SLEEP, IDLE_BUDGET_NANOS, () -> {
                long start = System.nanoTime();
                for (int i = 0; i < IDLE_CALLS; i++) {
                    scheduler.tick();
                }
                long mean = (System.nanoTime() - start) / IDLE_CALLS;
                LOGGER.info("{}: energy tick with a sleeping {}-endpoint network: mean {} ns (budget {} ns).", SLEEP,
                        ENDPOINTS, mean, IDLE_BUDGET_NANOS);
                return mean;
            }, mean -> "a sleeping network costs " + mean + " ns per tick, over the " + IDLE_BUDGET_NANOS
                    + " ns budget");
            helper.assertTrue(network.passCount() == passes[0], "ticking a sleeping network runs no pass");
        }).thenSucceed();
    }

    private static long median(String name, long[] nanos, long budget, String what) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long median = sorted[sorted.length / 2];
        LOGGER.info("{}: {}: median {} µs, max {} µs (budget {} µs).", name, what, micros(median),
                micros(sorted[sorted.length - 1]), micros(budget));
        return median;
    }

    private static String micros(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000.0);
    }
}
