package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.recorder.AlarmWorker;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPart;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuStats;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuTier;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.job.QueueBackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import dev.silencebound.maxtech.storage.subnet.SubnetMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The crafting CPU and the job queue (M7c area A): cluster forming, a job's whole life against the test pattern host,
 * cancel, partial and resume, the unpowered stall, queue order and CPU choice, classic mode and the job-finished alarm,
 * and the perf of an idle CPU and a running job. A powered controller at x = 1 (an alarm on top), a test storage device
 * at x = 2, a pattern host at x = 3 and a CPU core at x = 4; outputs are offered through {@link CraftingDeliveries} the
 * way a provider does.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CraftingCpuGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PERF = "perf_storage_cpu_idle_and_running";
    private static final String CLASSIC = "cpu_classic_refuses_incomplete";
    private static final String FURNACE = "cpu_furnace_end_to_end";
    private static final String[] NAMES = {"cpu_cluster_forms", "cpu_job_lifecycle", "cpu_cancel_returns",
            "cpu_partial_and_resume", "cpu_unpowered_stalls", "cpu_queue_order_and_fit", "cpu_subnet_shares",
            "cpu_background_job", "cpu_cancel_while_unloaded", "cpu_outputs_reserved",
            "cpu_job_through_controller_join"};
    /** One run of a CPU working a job, mean. */
    private static final long RUN_BUDGET_NANOS = 200_000;
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final StackKey COAL = ItemKey.of(Items.COAL);
    private static final StackKey TORCH = ItemKey.of(Items.TORCH);
    private static final CraftingPattern STICKS = crafting(Items.STICK, 4, List.of(in(Items.OAK_PLANKS, 2)));
    private static final CraftingPattern TORCHES = crafting(Items.TORCH, 4,
            List.of(in(Items.COAL, 1), in(Items.STICK, 1)));

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(NAMES[0], CraftingCpuGameTests::clusterForms);
        register(NAMES[1], CraftingCpuGameTests::lifecycle);
        register(NAMES[2], CraftingCpuGameTests::cancelReturns);
        register(NAMES[3], CraftingCpuGameTests::partialAndResume);
        register(NAMES[4], CraftingCpuGameTests::unpoweredStalls);
        register(NAMES[5], CraftingCpuGameTests::queueOrderAndFit);
        register(NAMES[6], CraftingCpuGameTests::subnetShares);
        register(NAMES[7], CraftingCpuGameTests::backgroundJob);
        register(NAMES[8], CraftingCpuGameTests::cancelWhileUnloaded);
        register(NAMES[9], CraftingCpuGameTests::outputsReserved);
        register(NAMES[10], CraftingCpuGameTests::jobThroughControllerJoin);
        register(CLASSIC, CraftingCpuGameTests::classicRefuses);
        register(FURNACE, CraftingCpuGameTests::furnaceEndToEnd);
        register(PERF, CraftingCpuGameTests::perf);
    }

    private CraftingCpuGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("crafting_cpu"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(CLASSIC),
                        new StorageTestEnvironment("crafting.autocraftingMode", "classic", false)),
                CLASSIC, MaxtechStorage.id("empty_16x12x16"), 200);
        StorageTests.test(event, environment, FURNACE, MaxtechStorage.id("empty_16x12x16"), 900);
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(PERF), StorageTestEnvironment.plain()),
                PERF, MaxtechStorage.id("empty_16x12x16"), 400);
    }

    private static PatternInput in(Item item, long amount) {
        return new PatternInput(new PatternIngredient.Exact(ItemKey.of(item)), amount, Optional.empty(), false);
    }

    private static CraftingPattern crafting(Item output, int count, List<PatternInput> inputs) {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING, inputs,
                List.of(new PatternOutput(ItemKey.of(output), count, 1f)), Optional.empty(), Optional.empty());
    }

    private record Setup(BlockPos core, CraftingTestContent.Host host, BlockPos cpu) {
    }

    /** Controller with {@code fe}, storage device, pattern host with {@code patterns} and a tier-1 CPU core. */
    private static Setup setUp(GameTestHelper helper, long fe, List<CraftingPattern> patterns) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        if (fe > 0) {
            NetworkGameTests.power(helper, core, fe);
        }
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(patterns);
        BlockPos cpu = NetworkGameTests.at(4);
        helper.setBlock(cpu, CpuContent.CORE_1.get());
        return new Setup(core, host, cpu);
    }

    private static NetworkState network(GameTestHelper helper, Setup setup) {
        NetworkState network = setup.host().network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the pattern host is in no network"));
        }
        return network;
    }

    private static AlarmWorker alarm(GameTestHelper helper, BlockPos core) {
        return ControllerAlarmGameTests.alarm(helper, core, 0);
    }

    private static CpuCoreBlockEntity cpu(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, CpuCoreBlockEntity.class);
    }

    /** Plans {@code amount} of {@code key} now and queues it for a player; fails the test on a refusal. */
    private static CraftingJob submit(GameTestHelper helper, NetworkState network, StackKey key, long amount,
            JobQueue.Request request) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, key, amount);
        JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan, request);
        return submission.job()
                .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())));
    }

    private static @Nullable CraftingJob job(GameTestHelper helper, UUID id) {
        return CraftingJobs.get(helper.getLevel()).get(id);
    }

    private static JobState state(GameTestHelper helper, UUID id) {
        CraftingJob job = job(helper, id);
        return job == null ? JobState.DONE : job.state();
    }

    /**
     * Stores {@code amount} of {@code key} as if a provider pulled it out of a machine and reports it to
     * {@link CraftingDeliveries}; returns how much running jobs counted (their main outputs received).
     */
    private static long offer(NetworkState network, StackKey key, long amount, boolean unused) {
        long before = received(network);
        NetworkGameTests.insert(network, key, amount);
        CraftingDeliveries.delivered(network, key, amount);
        return received(network) - before;
    }

    private static long received(NetworkState network) {
        long total = 0;
        for (CraftingJob job : CraftingJobs.get(network.level()).all()) {
            JobRun run = JobQueue.progress(network.level(), job.id());
            for (int i = 0; run != null && i < job.steps().size(); i++) {
                total += run.received(i);
            }
        }
        return total;
    }

    /** The stats worked out here from the config, not through {@link CpuStats#of}, so a broken formula fails. */
    private static CpuStats expected(int storage, int lanes, int caches) {
        CpuStats.Caps caps = CpuStats.Caps.of(CpuTier.TIER_1);
        CpuStats.Parts parts = CpuStats.Parts.fromConfig();
        return new CpuStats(Math.min(caps.maxSteps(), parts.coreSteps() + storage * parts.stepsPerStorage()),
                Math.min(caps.maxLanes(), 1 + lanes), Math.min(caps.maxBatch(), 1 + caches * parts.batchPerCache()),
                caps.muPerLane());
    }

    /**
     * A core alone has the base numbers; storage, lane and cache blocks touching it or each other add to them; breaking
     * one takes its share away; a second core touching the cluster is a conflict for both.
     */
    private static void clusterForms(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 2, 5);
        helper.setBlock(core, CpuContent.CORE_1.get());
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(cpu(helper, core).stats(), expected(0, 0, 0), "a core alone"))
                .thenExecute(() -> {
                    helper.setBlock(core.east(), CpuContent.STORAGE.get());
                    helper.setBlock(core.east(2), CpuContent.STORAGE.get());
                    helper.setBlock(core.north(), CpuContent.LANE.get());
                    // Touches only the lane block: the cluster is free-form.
                    helper.setBlock(core.north().above(), CpuContent.CACHE.get());
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(cpu(helper, core).count(CpuPart.STORAGE), 2, "storage blocks");
                    helper.assertValueEqual(cpu(helper, core).stats(), expected(2, 1, 1), "filled caps");
                }).thenExecute(() -> helper.destroyBlock(core.east(2)))
                .thenWaitUntil(() -> helper.assertValueEqual(cpu(helper, core).stats(), expected(1, 1, 1),
                        "one storage block fewer"))
                .thenExecute(() -> helper.setBlock(core.east(2), CpuContent.CORE_2.get())).thenWaitUntil(() -> {
                    helper.assertTrue(cpu(helper, core).conflict(), "conflict");
                    helper.assertValueEqual(cpu(helper, core).capacity(), 0, "a conflicted core runs nothing");
                }).thenSucceed();
    }

    /**
     * Eight sticks from four planks: the job takes the planks, sends both crafts, counts the sticks offered back (a
     * rolled-back offer counts nothing), delivers them to storage and leaves the level data; the job-finished alarm
     * pulses.
     */
    private static void lifecycle(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS));
        BlockPos alarm = setup.core();
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            ControllerAlarmGameTests.addRule(helper, alarm, new AlarmSettings(AlarmSettings.Type.JOB_FINISHED, 100));
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 4);
            id[0] = submit(helper, network, STICK, 8, JobQueue.Request.player(UUID.randomUUID())).id();
            helper.assertValueEqual(state(helper, id[0]), JobState.RUNNING, "taken by the CPU at once");
            helper.assertTrue(id[0].equals(cpu(helper, setup.cpu()).job()), "on the core");
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.host().pushed, 2, "both crafts sent")).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            helper.assertValueEqual(network.contents().amount(PLANKS), 0L, "planks taken");
            JobRun run = JobQueue.progress(helper.getLevel(), id[0]);
            helper.assertTrue(run != null, "progress kept");
            // As after a restart, before the CPU ran again: the origin network still counts.
            Objects.requireNonNull(run).forgetNetworks();
            helper.assertValueEqual(offer(network, STICK, 10, true), 8L, "only what the job expects");
        }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "done")).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            helper.assertValueEqual(network.contents().amount(STICK), 10L, "sticks delivered, the spare two left");
            helper.assertTrue(JobQueue.progress(helper.getLevel(), id[0]) == null, "progress dropped");
            helper.assertTrue(cpu(helper, setup.cpu()).job() == null, "CPU free");
            helper.assertValueEqual(offer(network, STICK, 4, true), 0L, "nothing expected any more");
        }).thenWaitUntil(() -> helper.assertTrue(alarm(helper, alarm).output(), "alarm pulse"))
                .thenWaitUntil(() -> helper.assertTrue(!alarm(helper, alarm).output(), "pulse over")).thenSucceed();
    }

    /**
     * A running job goes on when a second network with its own controller is cabled in (M7N item 3): the second goes on
     * standby, the job counts the sticks offered after the join and finishes, and nothing is duplicated or lost. The
     * second controller sits at x 1 z 3, joined by a cable at z 2 that touches the first core.
     */
    @SuppressWarnings("ReferenceEquality") // networks and controllers are compared by identity
    private static void jobThroughControllerJoin(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS));
        BlockPos second = new BlockPos(1, 1, 3);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 4);
            id[0] = submit(helper, network, STICK, 8, JobQueue.Request.player(UUID.randomUUID())).id();
            helper.assertValueEqual(state(helper, id[0]), JobState.RUNNING, "taken by the CPU at once");
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.host().pushed, 2, "both crafts sent")).thenExecute(() -> {
            helper.setBlock(second, ControllerContent.CONTROLLER.get());
            NetworkGameTests.power(helper, second, 1_000);
        }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.state(helper, second) != network(helper, setup),
                "the second controller on its own"))
                .thenExecute(
                        () -> ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 2)),
                                CableContent.STORAGE_CABLE_TIER_1.type().get(), null))
                .thenWaitUntil(() -> {
                    helper.assertTrue(NetworkGameTests.state(helper, second) == network(helper, setup), "joined");
                    helper.assertTrue(NetworkGameTests.controllerEntity(helper, second).isStandby(),
                            "the second controller on standby");
                }).thenExecute(() -> {
                    NetworkState network = network(helper, setup);
                    helper.assertValueEqual(state(helper, id[0]), JobState.RUNNING, "still running after the join");
                    helper.assertTrue(
                            network.controllerDevice() == NetworkGameTests.controllerEntity(helper, setup.core()),
                            "the first controller in charge");
                    helper.assertValueEqual(offer(network, STICK, 8, true), 8L, "the sticks counted");
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "done"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, setup);
                    helper.assertValueEqual(network.contents().amount(STICK), 8L, "the sticks, once");
                    helper.assertValueEqual(network.contents().amount(PLANKS), 0L, "the planks used");
                    helper.assertTrue(cpu(helper, setup.cpu()).job() == null, "CPU free");
                }).thenSucceed();
    }

    /**
     * Counted outputs go to the job at once, so an export bus on the same item that runs before the CPU finds none of
     * them in storage and the job still finishes (found by the showcase's stock keeper and export port).
     */
    private static void outputsReserved(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 4);
            id[0] = submit(helper, network, STICK, 8, JobQueue.Request.player(UUID.randomUUID())).id();
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.host().pushed, 2, "both crafts sent")).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            helper.assertValueEqual(offer(network, STICK, 8, true), 8L, "sticks counted");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.extract(STICK, 8, transaction), 0L,
                        "an exporter took the job's sticks");
            }
        }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "done")).thenExecute(
                () -> helper.assertValueEqual(network(helper, setup).contents().amount(STICK), 8L, "sticks delivered"))
                .thenSucceed();
    }

    /** Cancelling a running job returns what the CPU holds; outputs that come later are not counted (C19). */
    private static void cancelReturns(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 8);
            id[0] = submit(helper, network, STICK, 16, JobQueue.Request.player(UUID.randomUUID())).id();
        }).thenWaitUntil(() -> helper.assertTrue(setup.host().pushed >= 1, "a craft sent")).thenExecute(() -> {
            int pushed = setup.host().pushed;
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), id[0]), "cancelled");
            NetworkState network = network(helper, setup);
            helper.assertValueEqual(network.contents().amount(PLANKS), 8L - 2L * pushed, "unsent planks back");
            helper.assertTrue(job(helper, id[0]) == null, "job gone");
            helper.assertTrue(cpu(helper, setup.cpu()).job() == null, "CPU free");
            helper.assertValueEqual(offer(network, STICK, 4, true), 0L, "late outputs go to storage");
            helper.assertTrue(!JobQueue.cancel(helper.getLevel(), id[0]), "cancelling twice does nothing");
        }).thenSucceed();
    }

    /**
     * Torches without coal: the stick sub-tree runs, then the job ends partial, the sticks go back to storage and the
     * report names the coal; storing coal resumes it on its own, and it finishes from the returned sticks.
     */
    private static void partialAndResume(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS, TORCHES));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 2);
            id[0] = submit(helper, network, TORCH, 4, JobQueue.Request.player(UUID.randomUUID())).id();
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.host().pushed, 1, "the stick craft sent"))
                .thenExecute(() -> helper.assertValueEqual(offer(network(helper, setup), STICK, 4, true), 4L,
                        "sticks counted"))
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.PARTIAL, "partial"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, setup);
                    helper.assertValueEqual(network.contents().amount(STICK), 4L, "intermediates back in storage");
                    JobRun run = JobQueue.progress(helper.getLevel(), id[0]);
                    helper.assertTrue(run != null && run.missing().getLong(COAL) == 1, "coal reported missing");
                    helper.assertTrue(cpu(helper, setup.cpu()).job() == null, "CPU free while partial");
                    NetworkGameTests.insert(network, COAL, 1);
                }).thenWaitUntil(() -> helper.assertValueEqual(setup.host().pushed, 2, "resumed: torch craft sent"))
                .thenExecute(() -> {
                    helper.assertValueEqual(state(helper, id[0]), JobState.RUNNING, "running again");
                    helper.assertValueEqual(network(helper, setup).contents().amount(STICK), 3L,
                            "the resumed plan took a stored stick");
                    helper.assertValueEqual(offer(network(helper, setup), TORCH, 4, true), 4L, "torches counted");
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "done"))
                .thenSucceed();
    }

    /** Without FE the CPU takes the job but stalls: nothing taken, nothing sent; with FE it goes on (C5). */
    private static void unpoweredStalls(GameTestHelper helper) {
        Setup setup = setUp(helper, 0, List.of(STICKS));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            // Stored for a player through the creative path would need FE; the device holds it directly.
            NetworkGameTests.power(helper, setup.core(), 1_000);
            NetworkGameTests.insert(network, PLANKS, 4);
            NetworkGameTests.controllerEntity(helper, setup.core()).buffer().load(0);
            id[0] = submit(helper, network, STICK, 8, JobQueue.Request.player(UUID.randomUUID())).id();
        }).thenIdle(40).thenExecute(() -> {
            JobRun run = JobQueue.progress(helper.getLevel(), id[0]);
            helper.assertTrue(run != null && run.stalled(), "stalled");
            helper.assertValueEqual(state(helper, id[0]), JobState.RUNNING, "still running, not failed");
            helper.assertValueEqual(setup.host().pushed, 0, "nothing sent");
            helper.assertValueEqual(network(helper, setup).contents().amount(PLANKS), 4L, "nothing taken");
            NetworkGameTests.power(helper, setup.core(), 1_000_000);
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.host().pushed, 2, "sent once powered")).thenExecute(() -> {
            JobRun run = JobQueue.progress(helper.getLevel(), id[0]);
            helper.assertTrue(run != null && !run.stalled(), "no longer stalled");
        }).thenSucceed();
    }

    /**
     * Without a CPU, a background job queued first waits behind a player job queued later; the player job takes the
     * smallest CPU that fits once CPUs come.
     */
    private static void queueOrderAndFit(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(List.of(STICKS));
        // The big core sits next to the host, the small one beyond it.
        BlockPos big = NetworkGameTests.at(4);
        Setup setup = new Setup(core, host, NetworkGameTests.at(5));
        UUID[] ids = new UUID[2];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 8);
            ids[0] = submit(helper, network, STICK, 4, new JobQueue.Request(Optional.empty(), 5, 0, null)).id();
            ids[1] = submit(helper, network, STICK, 4, JobQueue.Request.player(UUID.randomUUID())).id();
            helper.assertValueEqual(state(helper, ids[0]), JobState.PLANNED, "no CPU yet");
            // The big core goes in first, with two storage blocks above it; then the small one.
            helper.setBlock(big, CpuContent.CORE_1.get());
            helper.setBlock(big.above(), CpuContent.STORAGE.get());
            helper.setBlock(big.above(2), CpuContent.STORAGE.get());
        }).thenWaitUntil(() -> helper.assertTrue(
                cpu(helper, big).capacity() > CpuStats.Parts.fromConfig().coreSteps() && cpu(helper, big).busy(),
                "the big CPU took a job")).thenExecute(() -> {
                    helper.assertTrue(ids[1].equals(cpu(helper, big).job()), "the player's job first");
                    helper.assertValueEqual(state(helper, ids[0]), JobState.PLANNED, "background waits");
                    helper.setBlock(setup.cpu(), CpuContent.CORE_1.get());
                }).thenWaitUntil(() -> helper.assertTrue(ids[0].equals(cpu(helper, setup.cpu()).job()),
                        "the background job on the second CPU"))
                .thenSucceed();
    }

    /**
     * Patterns and CPUs follow the subnet mode (C30): the parent holds the torch pattern, the subnet the stick pattern
     * and the only CPU. Merged, each side plans with both; isolated, each only with its own; sharing up, a parent job
     * runs on the subnet's CPU.
     */
    private static void subnetShares(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        BlockPos parentHostPos = NetworkGameTests.at(2);
        helper.setBlock(parentHostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host parentHost = helper.getBlockEntity(parentHostPos, CraftingTestContent.Host.class);
        parentHost.setPatterns(List.of(TORCHES));
        NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, NetworkGameTests.at(3), Direction.WEST);
        BlockPos childHostPos = NetworkGameTests.at(4);
        helper.setBlock(childHostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host childHost = helper.getBlockEntity(childHostPos, CraftingTestContent.Host.class);
        childHost.setPatterns(List.of(STICKS));
        BlockPos cpu = NetworkGameTests.at(5);
        helper.setBlock(cpu, CpuContent.CORE_1.get());
        long[] generation = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            helper.assertTrue(childHost.network() != null && childHost.network() != parent, "two networks");
            helper.assertTrue(subnet.parent() == parent, "uplink in the parent");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1_000_000);
            subnet.buffer().receive(1_000_000);
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = childHost.network();
            helper.assertTrue(
                    child != null && parent.patterns().isCraftable(STICK) && child.patterns().isCraftable(TORCH),
                    "merged: both ways");
            helper.assertValueEqual(parent.patterns().snapshot().patterns().size(), 2, "the parent plans with both");
            generation[0] = parent.patterns().generation();
            subnet.setMode(SubnetMode.ISOLATED);
        }).thenIdle(2).thenExecute(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = childHost.network();
            helper.assertTrue(
                    child != null && !parent.patterns().isCraftable(STICK) && !child.patterns().isCraftable(TORCH),
                    "isolated: own patterns only");
            helper.assertTrue(parent.patterns().generation() != generation[0], "the generation moved");
            helper.assertValueEqual(parent.patterns().snapshot().patterns().size(), 1, "the parent's own only");
            subnet.setMode(SubnetMode.SHARES_UP);
        }).thenIdle(2).thenExecute(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = childHost.network();
            helper.assertTrue(
                    child != null && parent.patterns().isCraftable(STICK) && !child.patterns().isCraftable(TORCH),
                    "sharing up: the parent sees the subnet's");
            NetworkGameTests.insert(parent, PLANKS, 2);
            CraftingJob job = submit(helper, parent, STICK, 4, JobQueue.Request.player(UUID.randomUUID()));
            helper.assertTrue(job.id().equals(cpu(helper, cpu).job()), "the parent's job on the subnet's CPU");
        }).thenWaitUntil(() -> helper.assertValueEqual(childHost.pushed, 1, "sent to the subnet's provider"))
                .thenSucceed();
    }

    /**
     * A stock keeper's request through the queue's background seam: refused for something nothing makes, planned and
     * run otherwise, with one DONE outcome once the outputs came back.
     */
    private static void backgroundJob(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS));
        List<BackgroundCrafting.Outcome> outcomes = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 2);
            helper.assertTrue(
                    !QueueBackgroundCrafting.INSTANCE.submit(network,
                            new BackgroundCrafting.Request(TORCH, 4, 0, 0, OwnerRecord.UNOWNED), outcomes::add),
                    "nothing makes torches");
            helper.assertTrue(
                    QueueBackgroundCrafting.INSTANCE.submit(network,
                            new BackgroundCrafting.Request(STICK, 4, 3, 0, OwnerRecord.UNOWNED), outcomes::add),
                    "taken");
            helper.assertTrue(outcomes.isEmpty(), "not answered from inside the call");
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.host().pushed, 1, "planned and sent")).thenExecute(
                () -> helper.assertValueEqual(offer(network(helper, setup), STICK, 4, true), 4L, "sticks counted"))
                .thenWaitUntil(() -> helper.assertValueEqual(outcomes, List.of(BackgroundCrafting.Outcome.DONE),
                        "one outcome"))
                .thenSucceed();
    }

    /**
     * The whole chain with real blocks: a pattern provider facing a furnace holds a smelting pattern, the CPU takes the
     * raw iron and coal from storage, the dispatcher puts them into the furnace, the provider pulls the ingots and
     * reports them, the CPU counts and takes them, and the job ends done with both ingots in storage.
     */
    private static void furnaceEndToEnd(GameTestHelper helper) {
        StackKey rawIron = ItemKey.of(Items.RAW_IRON);
        StackKey iron = ItemKey.of(Items.IRON_INGOT);
        CraftingPattern smelt = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(in(Items.RAW_IRON, 1), in(Items.COAL, 1)), List.of(new PatternOutput(iron, 1, 1f)),
                Optional.empty(), Optional.empty());
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        BlockPos providerPos = NetworkGameTests.at(3);
        helper.setBlock(providerPos, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(smelt));
        BlockPos cpu = NetworkGameTests.at(2).above();
        helper.setBlock(cpu, CpuContent.CORE_1.get());
        UUID[] id = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        provider.network() != null && provider.network().patterns().isCraftable(iron),
                        "the provider's pattern on the network"))
                .thenExecute(() -> {
                    NetworkState network = Objects.requireNonNull(provider.network());
                    NetworkGameTests.insert(network, rawIron, 2);
                    NetworkGameTests.insert(network, COAL, 2);
                    id[0] = submit(helper, network, iron, 2, JobQueue.Request.player(UUID.randomUUID())).id();
                    helper.assertTrue(id[0].equals(cpu(helper, cpu).job()), "on the CPU");
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "done"))
                .thenExecute(() -> {
                    NetworkState network = Objects.requireNonNull(provider.network());
                    helper.assertValueEqual(network.contents().amount(iron), 2L, "two ingots, counted once");
                    helper.assertValueEqual(network.contents().amount(rawIron), 0L, "raw iron used");
                    helper.assertTrue(cpu(helper, cpu).job() == null, "CPU free");
                }).thenSucceed();
    }

    /**
     * Cancelling a running job whose CPU is unloaded (taken out of the queue here) and whose origin network is gone
     * keeps what the CPU holds owed on the run; once the CPU runs again it returns it, here by dropping it, since the
     * CPU's own network has no storage.
     */
    private static void cancelWhileUnloaded(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 8);
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, 16);
            // Anchored on the host, which goes away below.
            id[0] = JobQueue.submit(network, setup.host().nodePos(), plan, JobQueue.Request.player(UUID.randomUUID()))
                    .job().orElseThrow().id();
        }).thenWaitUntil(() -> helper.assertTrue(setup.host().pushed >= 1, "a craft sent")).thenExecute(() -> {
            JobQueue.unregister(helper.getLevel(), cpu(helper, setup.cpu()));
            helper.destroyBlock(NetworkGameTests.at(3));
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), id[0]), "cancel taken");
            JobRun run = JobQueue.progress(helper.getLevel(), id[0]);
            helper.assertTrue(run != null && run.cancelling() && !run.buffer().isEmpty(), "the planks stay owed");
            long owed = Objects.requireNonNull(run).buffer().getLong(PLANKS);
            JobQueue.register(helper.getLevel(), cpu(helper, setup.cpu()));
            cpu(helper, setup.cpu()).work(helper.getLevel().getGameTime());
            helper.assertTrue(job(helper, id[0]) == null, "cancelled once the CPU ran");
            // The host was the CPU's only link to storage, so the CPU's own network cannot take them: they drop.
            long dropped = 0;
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(helper.absolutePos(setup.cpu())).inflate(2))) {
                if (item.getItem().is(Items.OAK_PLANKS)) {
                    dropped += item.getItem().getCount();
                }
            }
            helper.assertValueEqual(dropped, owed, "the owed planks dropped at the CPU");
        }).thenSucceed();
    }

    /** Classic mode refuses a plan with anything missing and starts a complete one as usual. */
    private static void classicRefuses(GameTestHelper helper) {
        Setup setup = setUp(helper, 1_000_000, List.of(STICKS));
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            NetworkState network = network(helper, setup);
            NetworkGameTests.insert(network, PLANKS, 2);
            CraftingPlanner.Result incomplete = CraftingPlanner.planNow(network, STICK, 8);
            JobQueue.Submission refused = JobQueue.submit(network, network.nodes().iterator().nextLong(), incomplete,
                    JobQueue.Request.player(UUID.randomUUID()));
            helper.assertTrue(refused.refusal().equals(Optional.of(JobQueue.Refusal.INCOMPLETE)), "refused");
            CraftingJob job = submit(helper, network, STICK, 4, JobQueue.Request.player(UUID.randomUUID()));
            helper.assertValueEqual(state(helper, job.id()), JobState.RUNNING, "a complete plan runs");
            helper.assertTrue(!QueueBackgroundCrafting.INSTANCE.submit(network,
                    new BackgroundCrafting.Request(STICK, 4, 0, 0, OwnerRecord.UNOWNED), outcome -> {
                    }), "no background crafts in classic mode");
        }).thenSucceed();
    }

    /**
     * An idle CPU never runs its task; a CPU working a 31-craft job with two lanes stays within budget per run. Runs in
     * its own batch.
     */
    private static void perf(GameTestHelper helper) {
        Setup setup = setUp(helper, 100_000_000, List.of(STICKS));
        BlockPos idle = NetworkGameTests.at(6);
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup)).thenExecute(() -> {
            helper.setBlock(idle, CpuContent.CORE_1.get());
            helper.setBlock(setup.cpu().above(), CpuContent.STORAGE.get());
            helper.setBlock(setup.cpu().above(2), CpuContent.STORAGE.get());
            helper.setBlock(setup.cpu().above(3), CpuContent.STORAGE.get());
            helper.setBlock(setup.cpu().below(), CpuContent.LANE.get());
        }).thenIdle(5).thenExecute(() -> runs[0] = NetworkGameTests.taskRuns(network(helper, setup))).thenIdle(100)
                .thenExecute(() -> {
                    helper.assertValueEqual(NetworkGameTests.taskRuns(network(helper, setup)), runs[0],
                            "no scheduler runs with idle CPUs");
                    NetworkState network = network(helper, setup);
                    NetworkGameTests.insert(network, PLANKS, 10_000);
                    submit(helper, network, STICK, 124, JobQueue.Request.player(UUID.randomUUID()));
                }).thenExecute(() -> {
                    CpuCoreBlockEntity cpu = cpu(helper, setup.cpu());
                    long[] gameTime = {helper.getLevel().getGameTime()};
                    cpu.work(gameTime[0]);
                    // A retry goes on with the same job, which must still be running.
                    PerfBudget.measureUnder(helper, PERF, RUN_BUDGET_NANOS, () -> {
                        helper.assertTrue(cpu.busy(), "running");
                        long total = 0;
                        int count = 50;
                        for (int i = 0; i < count; i++) {
                            long start = System.nanoTime();
                            cpu.work(++gameTime[0]);
                            total += System.nanoTime() - start;
                        }
                        long mean = total / count;
                        LOGGER.info("{}: mean CPU run {} ms (budget {} ms).", PERF, mean / 1e6, RUN_BUDGET_NANOS / 1e6);
                        return mean;
                    }, mean -> "a CPU run took " + mean / 1e6 + " ms");
                    helper.assertTrue(!cpu(helper, idle).busy(), "the idle CPU stayed idle");
                }).thenSucceed();
    }
}
