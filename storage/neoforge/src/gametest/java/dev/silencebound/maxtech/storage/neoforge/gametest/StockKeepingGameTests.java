package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafts;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.crafting.job.QueueBackgroundCrafting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Stock keeping and the crafting card (M7c area D) against a stand-in job queue: the keeper's restart threshold,
 * batches one after the other, priority and redstone, research gating, the Crafting research branch, and the card's
 * request rate. The stand-in records every request per network and lets a test finish it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StockKeepingGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    static final FakeQueue QUEUE = new FakeQueue();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        BackgroundCrafts.install(QUEUE);
        register("crafting_stock_threshold_and_batches", StockKeepingGameTests::thresholdAndBatches);
        register("crafting_stock_priority_and_redstone", StockKeepingGameTests::priorityAndRedstone);
        register("crafting_stock_retry_after_refusal", StockKeepingGameTests::retryAfterRefusal);
        register("crafting_stock_needs_research", StockKeepingGameTests::needsResearch);
        register("crafting_stock_ignores_other_research", StockKeepingGameTests::ignoresOtherResearch);
        register("crafting_stock_rules_in_item", StockKeepingGameTests::rulesInItem);
        register("crafting_research_branch", StockKeepingGameTests::researchBranch);
        register("crafting_card_request_rate", StockKeepingGameTests::cardRequestRate);
        register("crafting_card_needs_card_and_research", StockKeepingGameTests::cardNeedsCardAndResearch);
    }

    private StockKeepingGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("stock_keeping"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 600);
        }
    }

    /** One request the stand-in queue took or refused. */
    record Call(NetworkState network, BackgroundCrafting.Request request, Consumer<BackgroundCrafting.Outcome> done,
            boolean taken, long time) {
    }

    /**
     * Takes every request unless its network is refusing; nothing runs until a test finishes a call. Requests from a
     * network marked {@link #real} go to area A's job queue instead (and are recorded too).
     */
    static final class FakeQueue implements BackgroundCrafting {
        private final List<Call> calls = new ArrayList<>();
        private final List<NetworkState> refusing = new ArrayList<>();
        private final Set<NetworkState> real = Collections.newSetFromMap(new WeakHashMap<>());
        private final List<AABB> realAreas = new ArrayList<>();

        @Override
        public boolean submit(NetworkState network, Request request, Consumer<Outcome> done) {
            return submit(network, request, done, id -> {
            });
        }

        @Override
        public boolean submit(NetworkState network, Request request, Consumer<Outcome> done,
                Consumer<java.util.UUID> queued) {
            boolean taken = (real.contains(network) || inRealArea(network))
                    ? QueueBackgroundCrafting.INSTANCE.submit(network, request, done, queued)
                    : !refusing.contains(network);
            calls.add(new Call(network, request, done, taken, network.level().getGameTime()));
            return taken;
        }

        /** Sends {@code network}'s requests to the real job queue from now on. */
        void real(NetworkState network) {
            real.add(network);
        }

        /**
         * Sends the requests of networks in {@code area} to the real job queue until {@link #forget}, for networks that
         * ask before a test can name them (the showcase's logic graphs ask on their first run).
         */
        void realIn(AABB area) {
            realAreas.add(area);
        }

        void forget(AABB area) {
            realAreas.remove(area);
        }

        /** Forgets every area of {@link #realIn}; batch teardown, for tests that failed before their own forget. */
        void forgetAll() {
            realAreas.clear();
        }

        private boolean inRealArea(NetworkState network) {
            if (realAreas.isEmpty() || network.nodes().isEmpty()) {
                return false;
            }
            Vec3 node = Vec3.atCenterOf(BlockPos.of(network.nodes().iterator().nextLong()));
            for (AABB area : realAreas) {
                if (area.contains(node)) {
                    return true;
                }
            }
            return false;
        }

        List<Call> calls(NetworkState network) {
            List<Call> mine = new ArrayList<>();
            for (Call call : calls) {
                if (call.network() == network) {
                    mine.add(call);
                }
            }
            return mine;
        }

        void refuse(NetworkState network, boolean refuse) {
            refusing.remove(network);
            if (refuse) {
                refusing.add(network);
            }
        }
    }

    /** A powered controller at x 1, a test drive at x 2 and the keeper at x 3. */
    private static StockKeeperBlockEntity keeper(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), StockKeeperContent.KEEPER.get());
        NetworkGameTests.power(helper, controller, 1_000_000);
        return helper.getBlockEntity(NetworkGameTests.at(3), StockKeeperBlockEntity.class);
    }

    private static NetworkState network(GameTestHelper helper, StockKeeperBlockEntity keeper) {
        NetworkState network = keeper.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the keeper is in no network"));
        }
        return network;
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static StockRule rule(StackKey key, long keep, long restart, long batch) {
        return StockRule.EMPTY.withKey(Optional.of(key)).withKeep(keep).withRestart(restart).withBatch(batch);
    }

    private static List<Long> amounts(List<Call> calls) {
        List<Long> amounts = new ArrayList<>();
        for (Call call : calls) {
            amounts.add(call.request().amount());
        }
        return amounts;
    }

    /** Finishes the last call: the "job" puts what it asked for into the network and reports done. */
    private static void finishLast(NetworkState network) {
        List<Call> calls = QUEUE.calls(network);
        Call call = calls.get(calls.size() - 1);
        NetworkGameTests.insert(network, call.request().key(), call.request().amount());
        call.done().accept(BackgroundCrafting.Outcome.DONE);
    }

    /**
     * Keep 100, restart below 40, batches of 32: 50 stored asks for nothing; taken down to 30 the keeper asks for 32,
     * then (each job done) 32 and 6, one job at a time, and then nothing; idle, it runs no evaluation at all.
     */
    private static void thresholdAndBatches(GameTestHelper helper) {
        StockKeeperBlockEntity keeper = keeper(helper);
        int[] evaluations = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, keeper)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, keeper), COBBLE, 50);
            keeper.worker().setRule(0, rule(COBBLE, 100, 40, 32));
        }).thenIdle(10).thenExecute(() -> {
            helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 0, "requests at 50");
            extract(network(helper, keeper), COBBLE, 20);
        }).thenWaitUntil(() -> helper.assertValueEqual(amounts(QUEUE.calls(network(helper, keeper))), List.of(32L),
                "first batch")).thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 1, "one job out at a time");
                    finishLast(network(helper, keeper));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(amounts(QUEUE.calls(network(helper, keeper))),
                        List.of(32L, 32L), "second batch"))
                .thenExecute(() -> finishLast(network(helper, keeper)))
                .thenWaitUntil(() -> helper.assertValueEqual(amounts(QUEUE.calls(network(helper, keeper))),
                        List.of(32L, 32L, 6L), "last batch"))
                .thenExecute(() -> finishLast(network(helper, keeper))).thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(network(helper, keeper).contents().amount(COBBLE), 100L, "stocked");
                    helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 3, "requests when full");
                    helper.assertFalse(keeper.worker().filling(0), "still filling at keep");
                    evaluations[0] = keeper.worker().evaluations();
                    NetworkGameTests.insert(network(helper, keeper), DIRT, 10);
                    extract(network(helper, keeper), COBBLE, 30);
                }).thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 3,
                            "requests above the threshold");
                    helper.assertTrue(keeper.worker().evaluations() - evaluations[0] <= 1,
                            "evaluations while idle: " + (keeper.worker().evaluations() - evaluations[0]));
                }).thenSucceed();
    }

    /**
     * Two short rules submit higher priority first; a rule that wants a redstone signal waits for one, and the request
     * carries the rule's priority and CPU tier.
     */
    private static void priorityAndRedstone(GameTestHelper helper) {
        StockKeeperBlockEntity keeper = keeper(helper);
        helper.startSequence().thenWaitUntil(() -> network(helper, keeper)).thenExecute(() -> {
            keeper.worker().setRule(2, rule(DIRT, 10, 0, 0).withRedstone(StockRule.Redstone.HIGH));
            keeper.worker().setRule(0, rule(COBBLE, 10, 0, 0).withPriority(1));
            keeper.worker().setRule(1, rule(ItemKey.of(Items.STONE), 10, 0, 0).withPriority(5).withCpuTier(2));
        }).thenWaitUntil(() -> helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 2, "requests"))
                .thenIdle(10).thenExecute(() -> {
                    List<Call> calls = QUEUE.calls(network(helper, keeper));
                    helper.assertValueEqual(calls.size(), 2, "requests without a signal");
                    helper.assertValueEqual(calls.get(0).request().key(), ItemKey.of(Items.STONE), "first request");
                    helper.assertValueEqual(calls.get(0).request().priority(), 5, "priority sent");
                    helper.assertValueEqual(calls.get(0).request().cpuTier(), 2, "CPU tier sent");
                    helper.setBlock(NetworkGameTests.at(3).above(), Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> {
                    List<Call> calls = QUEUE.calls(network(helper, keeper));
                    helper.assertValueEqual(calls.size(), 3, "requests with a signal");
                    helper.assertValueEqual(calls.get(2).request().key(), DIRT, "redstone rule");
                }).thenSucceed();
    }

    /** A refused request waits {@code crafting.stockRetryTicks} (200) before the rule asks again. */
    private static void retryAfterRefusal(GameTestHelper helper) {
        StockKeeperBlockEntity keeper = keeper(helper);
        helper.startSequence().thenWaitUntil(() -> network(helper, keeper)).thenExecute(() -> {
            QUEUE.refuse(network(helper, keeper), true);
            keeper.worker().setRule(0, rule(COBBLE, 10, 0, 0));
        }).thenWaitUntil(() -> helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 1, "first try"))
                .thenIdle(150).thenExecute(() -> {
                    helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 1, "tries within the wait");
                    QUEUE.refuse(network(helper, keeper), false);
                }).thenWaitUntil(() -> {
                    List<Call> calls = QUEUE.calls(network(helper, keeper));
                    helper.assertValueEqual(calls.size(), 2, "tries after the wait");
                    helper.assertTrue(calls.get(1).taken(), "second try taken");
                }).thenSucceed();
    }

    /** An owned keeper asks for nothing until its owner's team researches stock keeping. */
    private static void needsResearch(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "stock_locked");
        ResearchTeam team = Research.team(player, true);
        StockKeeperBlockEntity keeper = keeper(helper);
        keeper.setOwner(OwnerRecord.of(player));
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence().thenWaitUntil(() -> network(helper, keeper))
                .thenExecute(() -> keeper.worker().setRule(0, rule(COBBLE, 10, 0, 0))).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 0,
                            "requests without research");
                    Research.addPoints(server, team, 1_000_000);
                    for (String node : List.of("port_tier_2", "interface", "stock_keeping")) {
                        helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id(node)),
                                Research.Result.DONE, "researching " + node);
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 1,
                        "requests after research"))
                .thenSucceed();
    }

    /**
     * Research that cannot start a keeper does not wake it: another team completing stock keeping, and the keeper
     * owner's own team completing a node that does not unlock the keeper (regression: every completion anywhere on the
     * server woke every keeper).
     */
    private static void ignoresOtherResearch(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "stock_quiet");
        ServerPlayer other = NetworkGameTests.player(helper.getLevel(), "stock_quiet_other");
        MinecraftServer server = helper.getLevel().getServer();
        ResearchTeam team = Research.team(player, true);
        ResearchTeam otherTeam = Research.team(other, true);
        Research.addPoints(server, team, 1_000_000);
        Research.addPoints(server, otherTeam, 1_000_000);
        for (String node : List.of("port_tier_2", "interface", "stock_keeping")) {
            Research.spendPoints(server, team, MaxtechStorage.id(node));
        }
        StockKeeperBlockEntity keeper = keeper(helper);
        keeper.setOwner(OwnerRecord.of(player));
        int[] evaluations = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, keeper)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, keeper), COBBLE, 50);
            keeper.worker().setRule(0, rule(COBBLE, 10, 0, 0));
        }).thenIdle(20).thenExecute(() -> {
            evaluations[0] = keeper.worker().evaluations();
            for (String node : List.of("port_tier_2", "interface", "stock_keeping")) {
                helper.assertValueEqual(Research.spendPoints(server, otherTeam, MaxtechStorage.id(node)),
                        Research.Result.DONE, "other team researching " + node);
            }
            helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id("condition_switch")),
                    Research.Result.DONE, "own team's unrelated node");
        }).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(keeper.worker().evaluations(), evaluations[0], "evaluations after the research");
            helper.assertValueEqual(QUEUE.calls(network(helper, keeper)).size(), 0, "requests while stocked");
        }).thenSucceed();
    }

    /** The rules travel in the dropped item and come back when it is placed. */
    private static void rulesInItem(GameTestHelper helper) {
        StockKeeperBlockEntity keeper = keeper(helper);
        StockRule kept = rule(COBBLE, 500, 100, 64).withPriority(3).withRedstone(StockRule.Redstone.LOW);
        keeper.worker().setRule(1, kept);
        ItemStack item = new ItemStack(StockKeeperContent.KEEPER.item().get());
        item.applyComponents(keeper.collectComponents());
        helper.assertValueEqual(item.getOrDefault(ModDataComponents.STOCK_RULES.get(), List.of()),
                List.of(StockRule.EMPTY, kept), "rules in the item");
        BlockPos other = NetworkGameTests.at(6);
        helper.setBlock(other, StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity placed = helper.getBlockEntity(other, StockKeeperBlockEntity.class);
        placed.applyComponentsFromItemStack(item);
        helper.assertValueEqual(placed.worker().rule(1), kept, "rule after placing");
        helper.succeed();
    }

    /**
     * The Crafting branch: its root is open (CPU tier 1), CPU tiers 2 and 3, the stock keeper, the crafting card and
     * extra provider slots are gated, and every node asks the mask or byte puzzle as one way through.
     */
    private static void researchBranch(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "crafting_branch");
        ResearchTeam team = Research.team(player, true);
        ResearchTree tree = Research.tree(server);
        helper.assertTrue(ResearchUnlocks.isUnlocked(server, team, ResearchGates.CPU_TIER_1), "CPU tier 1 open");
        for (Identifier gate : List.of(ResearchGates.CPU_TIER_2, ResearchGates.CPU_TIER_3, ResearchGates.STOCK_KEEPER,
                ResearchGates.CRAFTING_CARD, ResearchGates.PROVIDER_SLOTS)) {
            helper.assertFalse(ResearchUnlocks.isUnlocked(server, team, gate), gate + " open without research");
            List<ResearchTree.Entry> granting = tree.granting(gate);
            helper.assertValueEqual(granting.size(), 1, "nodes granting " + gate);
            ResearchTree.Entry entry = granting.get(0);
            helper.assertValueEqual(entry.node().branch(), "crafting", "branch of " + entry.id());
            helper.assertTrue(entry.node().anyOf().stream().anyMatch(
                    option -> option instanceof dev.silencebound.maxtech.storage.research.ResearchOption.Puzzle puzzle
                            && (puzzle.puzzle().getPath().equals("mask_alignment")
                                    || puzzle.puzzle().getPath().equals("byte_packing"))),
                    entry.id() + " offers a mask or byte puzzle");
        }
        helper.succeed();
    }

    /** A powered network with a test drive, an export port at x 3 facing a chest at x 4, cobblestone in its filter. */
    private static PortBlockEntity exportPort(GameTestHelper helper, boolean card) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        NetworkGameTests.power(helper, controller, 1_000_000);
        helper.setBlock(NetworkGameTests.at(3), PortContent.PORT.get().defaultBlockState()
                .setValue(PortBlock.FACING, Direction.EAST).setValue(PortBlock.MODE, PortMode.EXPORT));
        helper.setBlock(NetworkGameTests.at(4), Blocks.CHEST);
        PortBlockEntity port = helper.getBlockEntity(NetworkGameTests.at(3), PortBlockEntity.class);
        port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        if (card) {
            port.worker().cards().setItem(0, new ItemStack(PortContent.CRAFTING_CARD.get()));
        }
        return port;
    }

    private static NetworkState network(GameTestHelper helper, PortBlockEntity port) {
        NetworkState network = port.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the port is in no network"));
        }
        return network;
    }

    /**
     * With the network out of cobblestone, the card asks for one craft of {@code crafting.cardMaxRequest} (64) and no
     * second one while it is out; refused requests come at most once per {@code crafting.cardIntervalTicks} (100).
     */
    private static void cardRequestRate(GameTestHelper helper) {
        PortBlockEntity port = exportPort(helper, true);
        int[] mark = new int[1];
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(QUEUE.calls(network(helper, port)).size(), 1, "first request"))
                .thenIdle(150).thenExecute(() -> {
                    List<Call> calls = QUEUE.calls(network(helper, port));
                    helper.assertValueEqual(calls.size(), 1, "requests while one is out");
                    helper.assertValueEqual(calls.get(0).request().key(), COBBLE, "requested key");
                    helper.assertValueEqual(calls.get(0).request().amount(), 64L, "requested amount");
                    QUEUE.refuse(network(helper, port), true);
                    calls.get(0).done().accept(BackgroundCrafting.Outcome.FAILED);
                    mark[0] = calls.size();
                }).thenIdle(300).thenExecute(() -> {
                    int refused = QUEUE.calls(network(helper, port)).size() - mark[0];
                    helper.assertTrue(refused >= 1 && refused <= 4, "refused requests in 300 ticks: " + refused);
                    helper.assertValueEqual(port.worker().cardRequests(), 1, "requests taken");
                }).thenSucceed();
    }

    /** Without a crafting card, or with one but an owner who lacks the research, the port asks for nothing. */
    private static void cardNeedsCardAndResearch(GameTestHelper helper) {
        PortBlockEntity port = exportPort(helper, false);
        helper.startSequence().thenIdle(60).thenExecute(() -> {
            helper.assertValueEqual(QUEUE.calls(network(helper, port)).size(), 0, "requests without a card");
            ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "card_locked");
            Research.team(player, true);
            port.setOwner(OwnerRecord.of(player));
            port.worker().cards().setItem(0, new ItemStack(PortContent.CRAFTING_CARD.get()));
        }).thenIdle(150).thenExecute(() -> helper.assertValueEqual(QUEUE.calls(network(helper, port)).size(), 0,
                "requests without research")).thenSucceed();
    }
}
