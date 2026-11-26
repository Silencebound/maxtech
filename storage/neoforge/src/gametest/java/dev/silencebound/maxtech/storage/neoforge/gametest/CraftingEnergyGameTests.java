package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.CraftingRuns;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * A job start whose inputs cost more FE than the network holds (M7E item B): the CPU takes them in several runs as FE
 * comes in and the job finishes complete, not partial; a save and load between two runs keeps every count. The
 * network's FE is held at {@link #CAP} and refilled by 1 FE a tick, so one run takes two of the five input kinds at 4
 * FE an operation. A powered controller at x = 1, a test storage device at x = 2, a pattern host at x = 3, a CPU core
 * at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CraftingEnergyGameTests {
    private static final String RUNS = "cpu_inputs_in_runs";
    private static final String RELOAD = "cpu_inputs_in_runs_reload";
    /** The most FE the network holds while the input is slow: two operations, less than the start's five. */
    private static final long CAP = 10;
    private static final long EACH = 16;
    private static final StackKey TORCH = ItemKey.of(Items.TORCH);
    private static final List<StackKey> INPUTS = List.of(ItemKey.of(Items.COAL), ItemKey.of(Items.STICK),
            ItemKey.of(Items.OAK_PLANKS), ItemKey.of(Items.COBBLESTONE), ItemKey.of(Items.DIRT));
    private static final CraftingPattern PATTERN = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
            List.of(in(Items.COAL), in(Items.STICK), in(Items.OAK_PLANKS), in(Items.COBBLESTONE), in(Items.DIRT)),
            List.of(new PatternOutput(TORCH, 1, 1f)), Optional.empty(), Optional.empty());

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        StorageTests.function(RUNS, StorageTestEnvironment.clearing(h -> inputsInRuns(h, false)));
        StorageTests.function(RELOAD, StorageTestEnvironment.clearing(h -> inputsInRuns(h, true)));
    }

    private CraftingEnergyGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("crafting_energy"), StorageTestEnvironment.plain());
        StorageTests.test(event, environment, RUNS, MaxtechStorage.id("empty_16x12x16"), 600);
        // Its own batch: the reload replaces every run of the level, so no other test may run beside it.
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(RELOAD), StorageTestEnvironment.plain()),
                RELOAD, MaxtechStorage.id("empty_16x12x16"), 600);
    }

    private static PatternInput in(Item item) {
        return new PatternInput(new PatternIngredient.Exact(ItemKey.of(item)), EACH, Optional.empty(), false);
    }

    private static NetworkState network(GameTestHelper helper, CraftingTestContent.Host host) {
        NetworkState network = host.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the pattern host is in no network"));
        }
        return network;
    }

    private static JobRun run(GameTestHelper helper, UUID id) {
        JobRun run = JobQueue.progress(helper.getLevel(), id);
        if (run == null) {
            throw helper.assertionException(Component.literal("no progress for the job"));
        }
        return run;
    }

    private static JobState state(GameTestHelper helper, UUID id) {
        CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id);
        return job == null ? JobState.DONE : job.state();
    }

    /**
     * Every input is either in storage or in the CPU's buffer, never both or neither, and what the start still has to
     * take is exactly what the buffer lacks.
     */
    private static void assertCounts(GameTestHelper helper, NetworkState network, JobRun run, String when) {
        for (StackKey key : INPUTS) {
            long held = run.buffer().getLong(key);
            helper.assertValueEqual(network.contents().amount(key) + held, EACH, key + " stored or held " + when);
            helper.assertValueEqual(run.need().getLong(key) + held, EACH, key + " held or still needed " + when);
        }
    }

    /**
     * A torch from 16 of each of five kinds: the start costs 20 FE against a 10 FE cap, so it takes the inputs in three
     * runs, stalled between them but never missing anything; once sent and the torch is offered back the job ends done.
     * With {@code reload}, the level's runs are saved and loaded again after the first run.
     */
    private static void inputsInRuns(GameTestHelper helper, boolean reload) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(List.of(PATTERN));
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        boolean[] slow = new boolean[1];
        UUID[] id = new UUID[1];
        helper.onEachTick(() -> {
            if (slow[0]) {
                FeBuffer buffer = NetworkGameTests.controllerEntity(helper, core).buffer();
                buffer.load(Math.min(buffer.stored() + 1, CAP));
            }
        });
        helper.startSequence().thenWaitUntil(() -> network(helper, host)).thenExecute(() -> {
            helper.assertValueEqual(StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(), 4L, "FE per operation");
            helper.assertValueEqual(StorageServerConfig.ENERGY_PER_UNIT.getAsLong(), 0L, "FE per item");
            NetworkState network = network(helper, host);
            for (StackKey key : INPUTS) {
                NetworkGameTests.insert(network, key, EACH);
            }
            NetworkGameTests.controllerEntity(helper, core).buffer().load(0);
            slow[0] = true;
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, TORCH, 1);
            JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                    JobQueue.Request.player(UUID.randomUUID()));
            id[0] = submission.job()
                    .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())))
                    .id();
        }).thenWaitUntil(() -> helper.assertValueEqual(run(helper, id[0]).need().size(), 3, "two kinds taken"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, host);
                    JobRun run = run(helper, id[0]);
                    helper.assertTrue(run.stalled(), "waits for FE");
                    helper.assertTrue(run.missing().isEmpty(), "nothing reported missing");
                    helper.assertValueEqual(state(helper, id[0]), JobState.RUNNING, "still running");
                    assertCounts(helper, network, run, "after the first run");
                    if (reload) {
                        reload(helper);
                        JobRun loaded = run(helper, id[0]);
                        helper.assertTrue(loaded != run, "a loaded copy");
                        helper.assertValueEqual(loaded.need(), run.need(), "need after the load");
                        helper.assertValueEqual(loaded.buffer(), run.buffer(), "buffer after the load");
                        assertCounts(helper, network, loaded, "after the load");
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(run(helper, id[0]).need().size(), 1, "four kinds taken"))
                .thenExecute(() -> assertCounts(helper, network(helper, host), run(helper, id[0]), "mid-way"))
                .thenWaitUntil(() -> helper.assertValueEqual(host.pushed, 1, "sent once every input was in"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, host);
                    JobRun run = run(helper, id[0]);
                    helper.assertTrue(run.missing().isEmpty(), "nothing missing");
                    helper.assertTrue(run.need().isEmpty(), "nothing left to take");
                    for (StackKey key : INPUTS) {
                        helper.assertValueEqual(network.contents().amount(key), 0L, key + " all taken");
                    }
                    // The running lane costs more than the cap; full power from here on.
                    slow[0] = false;
                    NetworkGameTests.power(helper, core, 1_000_000);
                    NetworkGameTests.insert(network, TORCH, 1);
                    CraftingDeliveries.delivered(network, TORCH, 1);
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "done"))
                .thenExecute(() -> helper.assertValueEqual(network(helper, host).contents().amount(TORCH), 1L,
                        "the torch delivered"))
                .thenSucceed();
    }

    /**
     * Saves the level's crafting runs and loads them back in place of the live ones, as a restart would. The old run
     * stays in the queue's running list, but it expects no outputs before the start finished, so it counts nothing.
     */
    private static void reload(GameTestHelper helper) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        Tag saved = CraftingRuns.CODEC.encodeStart(ops, CraftingRuns.get(helper.getLevel())).getOrThrow();
        CraftingRuns loaded = CraftingRuns.CODEC.parse(ops, saved).getOrThrow();
        helper.getLevel().getDataStorage().set(CraftingRuns.TYPE, loaded);
    }
}
