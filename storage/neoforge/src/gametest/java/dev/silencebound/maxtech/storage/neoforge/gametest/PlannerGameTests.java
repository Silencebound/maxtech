package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * The planner against a real network (M7a): tag substitution from real stock, the worker-pool round trip, a partial
 * report, the remainder of a crafting pattern, jobs saved in level data, and the perf budget for a large tree. A
 * powered controller at x = 1, a test storage device at x = 2 and a pattern host at x = 3.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PlannerGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PERF = "perf_storage_planner_tree";
    private static final String[] NAMES = {"planner_network_round_trip", "planner_partial_and_remainder",
            "planner_jobs_saved", "planner_discard_answers"};
    /** A 1,023-node tree, capture and plan together. */
    private static final long BUDGET_NANOS = 50_000_000;
    /**
     * Items in the perf tree. An odd count gives a full binary tree (item i crafts from items 2i + 1 and 2i + 2); an
     * even count leaves the last item out of the tree, which is why an early run with every registry item counted one
     * step fewer than items.
     */
    private static final int PERF_ITEMS = 1023;
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(NAMES[0], PlannerGameTests::roundTrip);
        register(NAMES[1], PlannerGameTests::partialAndRemainder);
        register(NAMES[2], PlannerGameTests::jobsSaved);
        register(NAMES[3], PlannerGameTests::discardAnswers);
        register(PERF, PlannerGameTests::perf);
    }

    private PlannerGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("planner"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(PERF), StorageTestEnvironment.plain()),
                PERF, MaxtechStorage.id("empty_16x12x16"), 400);
    }

    private static PatternInput in(PatternIngredient ingredient, long amount) {
        return new PatternInput(ingredient, amount, Optional.empty(), false);
    }

    private static PatternIngredient exact(Item item) {
        return new PatternIngredient.Exact(ItemKey.of(item));
    }

    private static CraftingPattern crafting(Item output, int count, List<PatternInput> inputs) {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING, inputs,
                List.of(new PatternOutput(ItemKey.of(output), count, 1f)), Optional.empty(), Optional.empty());
    }

    /** Controller, storage device and pattern host; the host gets {@code patterns}. */
    private static CraftingTestContent.Host setUp(GameTestHelper helper, List<CraftingPattern> patterns) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(patterns);
        return host;
    }

    private static NetworkState network(GameTestHelper helper, CraftingTestContent.Host host) {
        NetworkState network = host.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the pattern host is in no network"));
        }
        return network;
    }

    /**
     * Sticks from planks from any log: the worker pool plans, the log the network holds most of wins, and the result
     * comes back on the server thread with the stock it takes.
     */
    private static void roundTrip(GameTestHelper helper) {
        CraftingTestContent.Host host = setUp(helper,
                List.of(crafting(Items.OAK_PLANKS, 4, List.of(in(new PatternIngredient.ItemTag(ItemTags.LOGS), 1))),
                        crafting(Items.STICK, 4, List.of(in(exact(Items.OAK_PLANKS), 2)))));
        AtomicReference<CraftingPlanner.Result> result = new AtomicReference<>();
        helper.startSequence().thenWaitUntil(() -> network(helper, host)).thenExecute(() -> {
            NetworkState network = network(helper, host);
            NetworkGameTests.insert(network, ItemKey.of(Items.OAK_LOG), 1);
            NetworkGameTests.insert(network, ItemKey.of(Items.BIRCH_LOG), 9);
            NetworkGameTests.insert(network, PLANKS, 1);
            helper.assertTrue(CraftingPlanner.submit(network, STICK, 16, result::set), "submitted");
        }).thenWaitUntil(() -> helper.assertTrue(result.get() != null, "plan arrived")).thenExecute(() -> {
            PlanTree<StackKey> tree = result.get().tree();
            helper.assertTrue(tree.complete(), "complete: " + tree.missing());
            helper.assertValueEqual(tree.steps().getFirst().crafts(), 4L, "four stick crafts");
            helper.assertValueEqual(tree.steps().get(1).stored(), 1L, "the stored plank");
            helper.assertValueEqual(tree.used().getLong(ItemKey.of(Items.BIRCH_LOG)), 2L, "birch, the most stored");
            helper.assertTrue(!tree.used().containsKey(ItemKey.of(Items.OAK_LOG)), "no oak");
        }).thenSucceed();
    }

    /**
     * A shortfall is reported per leaf, and a water bucket's crafting remainder (the bucket) covers a later input.
     */
    private static void partialAndRemainder(GameTestHelper helper) {
        CraftingTestContent.Host host = setUp(helper,
                List.of(crafting(Items.MUD, 1, List.of(in(exact(Items.WATER_BUCKET), 1), in(exact(Items.DIRT), 1))),
                        crafting(Items.PACKED_MUD, 1, List.of(in(exact(Items.MUD), 1), in(exact(Items.BUCKET), 1))),
                        crafting(Items.STICK, 4, List.of(in(exact(Items.OAK_PLANKS), 2)))));
        helper.startSequence().thenWaitUntil(() -> network(helper, host)).thenExecute(() -> {
            NetworkState network = network(helper, host);
            NetworkGameTests.insert(network, ItemKey.of(Items.WATER_BUCKET), 1);
            NetworkGameTests.insert(network, ItemKey.of(Items.DIRT), 1);
            PlanTree<StackKey> mud = CraftingPlanner.planNow(network, ItemKey.of(Items.PACKED_MUD), 1).tree();
            helper.assertTrue(mud.complete(), "the bucket comes back: " + mud.missing());
            PlanTree<StackKey> sticks = CraftingPlanner.planNow(network, STICK, 8).tree();
            helper.assertTrue(!sticks.complete() && sticks.failure().isEmpty(), "partial");
            helper.assertValueEqual(sticks.missing().getLong(PLANKS), 4L, "four planks missing");
        }).thenSucceed();
    }

    /** A planned job keeps only its patterns, survives the codec and moves through its states in level data. */
    private static void jobsSaved(GameTestHelper helper) {
        CraftingTestContent.Host host = setUp(helper,
                List.of(crafting(Items.STICK, 4, List.of(in(exact(Items.OAK_PLANKS), 2))),
                        crafting(Items.TORCH, 4, List.of(in(exact(Items.COAL), 1), in(exact(Items.STICK), 1)))));
        helper.startSequence().thenWaitUntil(() -> network(helper, host)).thenExecute(() -> {
            NetworkState network = network(helper, host);
            NetworkGameTests.insert(network, PLANKS, 2);
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, 4);
            UUID player = UUID.randomUUID();
            CraftingJob job = CraftingJob.planned(plan, Optional.of(player), 5);
            helper.assertValueEqual(job.patterns().size(), 1, "only the stick pattern");
            helper.assertValueEqual(job.steps().getFirst().pattern(), 0, "renumbered");
            helper.assertValueEqual(job.subTree(0).size(), job.steps().size(), "the root's sub-tree");
            RegistryOps<JsonElement> ops = helper.getLevel().registryAccess()
                    .createSerializationContext(JsonOps.INSTANCE);
            JsonElement json = CraftingJob.CODEC.encodeStart(ops, job).getOrThrow();
            helper.assertValueEqual(CraftingJob.CODEC.parse(ops, json).getOrThrow(), job, "codec round trip");
            CraftingJobs jobs = CraftingJobs.get(helper.getLevel());
            jobs.put(job);
            CraftingJob running = jobs.setState(job.id(), JobState.RUNNING);
            helper.assertTrue(running != null && running.state() == JobState.RUNNING, "running");
            helper.assertTrue(jobs.setState(UUID.randomUUID(), JobState.DONE) == null, "unknown id");
            JsonElement all = CraftingJobs.CODEC.encodeStart(ops, jobs).getOrThrow();
            CraftingJobs loaded = CraftingJobs.CODEC.parse(ops, all).getOrThrow();
            CraftingJob back = loaded.get(job.id());
            helper.assertTrue(back != null && back.state() == JobState.RUNNING, "state saved");
            helper.assertTrue(jobs.remove(job.id()) && jobs.get(job.id()) == null, "removed");
            helper.assertTrue(JobState.DONE.finished() && !JobState.PARTIAL.finished(), "finished states");
        }).thenSucceed();
    }

    /**
     * Results the pool did not commit still reach the requester exactly once: a computation that threw as an error
     * result, a stale one after the retries (or when the pool refuses a retry) marked stale, and none while a retry is
     * queued.
     */
    private static void discardAnswers(GameTestHelper helper) {
        List<CraftingPlanner.Result> answers = new ArrayList<>();
        CraftingPlanner.discard(null, STICK, 4, 0, () -> true, answers::add);
        helper.assertValueEqual(answers.size(), 1, "one answer for a throw");
        helper.assertTrue(answers.getFirst().tree().failure().equals(Optional.of(PlanTree.Failure.ERROR)),
                "an error result");
        CraftingPlanner.Result plan = new CraftingPlanner.Result(STICK, 4, answers.getFirst().tree(), List.of(), false);
        answers.clear();
        CraftingPlanner.discard(plan, STICK, 4, 0, () -> true, answers::add);
        helper.assertTrue(answers.isEmpty(), "retried, no answer yet");
        CraftingPlanner.discard(plan, STICK, 4, 0, () -> false, answers::add);
        CraftingPlanner.discard(plan, STICK, 4, 2, () -> true, answers::add);
        helper.assertValueEqual(answers.size(), 2, "answers after a refused retry and the last attempt");
        helper.assertTrue(answers.get(0).stale() && answers.get(1).stale(), "marked stale");
        helper.succeed();
    }

    /**
     * A binary tree of 1,023 crafting steps over registry items, its leaves stored as far as the test device takes
     * them: capturing the snapshot and planning must stay within budget. Runs in its own batch.
     */
    private static void perf(GameTestHelper helper) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!item.equals(Items.AIR) && items.size() < PERF_ITEMS) {
                items.add(item);
            }
        }
        int inner = (items.size() - 1) / 2;
        List<CraftingPattern> patterns = new ArrayList<>(inner);
        for (int i = 0; i < inner; i++) {
            patterns.add(crafting(items.get(i), 1,
                    List.of(in(exact(items.get(2 * i + 1)), 1), in(exact(items.get(2 * i + 2)), 1))));
        }
        CraftingTestContent.Host host = setUp(helper, patterns);
        helper.startSequence().thenWaitUntil(() -> network(helper, host)).thenExecute(() -> {
            NetworkState network = network(helper, host);
            for (int i = inner; i < items.size(); i++) {
                NetworkGameTests.insert(network, ItemKey.of(items.get(i)), 64);
            }
            StackKey root = ItemKey.of(items.getFirst());
            CraftingPlanner.planNow(network, root, 1);
            PerfBudget.measureUnder(helper, PERF, BUDGET_NANOS, () -> {
                long start = System.nanoTime();
                CraftingPlanner.Result result = CraftingPlanner.planNow(network, root, 1);
                long took = System.nanoTime() - start;
                helper.assertTrue(result.tree().failure().isEmpty(), "no failure");
                helper.assertValueEqual(result.tree().steps().size(), items.size(), "one step per item");
                LOGGER.info("{}: {} steps against {} stored kinds in {} ms (budget {} ms).", PERF,
                        result.tree().steps().size(), network.contents().size(), took / 1e6, BUDGET_NANOS / 1e6);
                return took;
            }, took -> "planning " + items.size() + " steps took " + took / 1e6 + " ms");
        }).thenSucceed();
    }
}
