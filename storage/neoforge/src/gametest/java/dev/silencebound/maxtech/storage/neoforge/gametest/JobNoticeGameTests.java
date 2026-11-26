package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.LockMode;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import dev.silencebound.maxtech.storage.crafting.job.JobNotice;
import dev.silencebound.maxtech.storage.crafting.job.JobNotices;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Who hears about crafting jobs (B09 item 21d): the requester gets each live state once (waiting for a CPU, running)
 * and a toasting notice when the job ends done, stops for missing items, or is cancelled by someone else; their own
 * cancel reaches them without a toast. With {@code crafting.teamToasts} on, an online team member gets the ending
 * notice naming the requester; a player outside the team never hears anything. Layout along x at y = 1, z = 1: powered
 * controller, test storage device (a CPU core on top when the test runs jobs), pattern host with the sticks pattern,
 * terminal.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class JobNoticeGameTests {
    private static final String[] PLAIN = {"job_notice_done", "job_notice_missing", "job_notice_cancel",
            "job_notice_lock"};
    private static final String TEAM = "job_notice_team";
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final StackKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final CraftingPattern STICKS = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
            List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
            List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    private static final BlockPos TERMINAL = NetworkGameTests.at(4);
    /** The players the notices find online; tests of one batch run side by side, each with its own players. */
    private static final Map<UUID, ServerPlayer> ONLINE = new ConcurrentHashMap<>();
    /** What each player was sent. */
    private static final Map<UUID, List<JobToastPayload>> SENT = new ConcurrentHashMap<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(PLAIN[0], JobNoticeGameTests::done);
        register(PLAIN[1], JobNoticeGameTests::missing);
        register(PLAIN[2], JobNoticeGameTests::cancel);
        register(PLAIN[3], JobNoticeGameTests::lock);
        register(TEAM, JobNoticeGameTests::team);
    }

    private JobNoticeGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("job_notice"),
                StorageTestEnvironment.plain());
        for (String name : PLAIN) {
            StorageTests.test(event, plain, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
        Holder<TestEnvironmentDefinition<?>> team = event.registerEnvironment(MaxtechStorage.id("job_notice_team"),
                new StorageTestEnvironment("crafting.teamToasts", "true", false));
        StorageTests.test(event, team, TEAM, MaxtechStorage.id("empty_16x12x16"), 400);
    }

    /** Records notices instead of sending them, and finds only this class's players online. */
    private static void install() {
        JobNotices.install((player, payload) -> SENT
                .computeIfAbsent(player.getUUID(), id -> new CopyOnWriteArrayList<>()).add(payload),
                () -> new ArrayList<>(ONLINE.values()));
    }

    private static ServerPlayer online(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        ONLINE.put(player.getUUID(), player);
        return player;
    }

    private static void offline(ServerPlayer... players) {
        for (ServerPlayer player : players) {
            ONLINE.remove(player.getUUID());
            SENT.remove(player.getUUID());
        }
    }

    private static List<JobToastPayload> sent(ServerPlayer player) {
        return SENT.getOrDefault(player.getUUID(), List.of());
    }

    private static List<JobNotice> notices(ServerPlayer player) {
        List<JobNotice> out = new ArrayList<>();
        for (JobToastPayload payload : sent(player)) {
            out.add(payload.notice());
        }
        return out;
    }

    private static CraftingTestContent.Host setUp(GameTestHelper helper, boolean cpu) {
        install();
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(List.of(STICKS));
        helper.setBlock(TERMINAL, TerminalContent.TERMINAL.get());
        if (cpu) {
            helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        }
        return host;
    }

    private static NetworkState network(GameTestHelper helper) {
        NetworkState network = helper.getBlockEntity(TERMINAL, TerminalBlockEntity.class).network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the terminal is in no network"));
        }
        return network;
    }

    /** Submits {@code amount} sticks for {@code requester}; returns the job's id. */
    private static UUID submit(GameTestHelper helper, ServerPlayer requester, long amount) {
        NetworkState network = network(helper);
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, amount);
        return JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                JobQueue.Request.player(requester.getUUID())).job().orElseThrow().id();
    }

    /** The sticks come back as a provider would offer them. */
    private static void offerSticks(GameTestHelper helper, long amount) {
        NetworkState network = network(helper);
        NetworkGameTests.insert(network, STICK, amount);
        CraftingDeliveries.delivered(network, STICK, amount);
    }

    /** How often {@code player} heard a job held by a lock. */
    private static int held(ServerPlayer player) {
        int held = 0;
        for (JobNotice notice : notices(player)) {
            held += notice == JobNotice.PROVIDER_LOCKED ? 1 : 0;
        }
        return held;
    }

    private static JobToastPayload last(GameTestHelper helper, ServerPlayer player) {
        List<JobToastPayload> list = sent(player);
        if (list.isEmpty()) {
            throw helper.assertionException(Component.literal("nothing sent to " + player.getGameProfile().name()));
        }
        return list.getLast();
    }

    /**
     * A job that runs to the end: its requester hears it waiting, running and done (the last with a toast), each once;
     * a player in no team with them hears nothing.
     */
    private static void done(GameTestHelper helper) {
        CraftingTestContent.Host host = setUp(helper, true);
        ServerPlayer requester = online(helper, "notice_done");
        ServerPlayer other = online(helper, "notice_other");
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 4);
            id[0] = submit(helper, requester, 8);
        }).thenWaitUntil(() -> helper.assertValueEqual(host.pushed, 2, "both crafts sent"))
                .thenExecute(() -> offerSticks(helper, 8))
                .thenWaitUntil(() -> helper.assertValueEqual(last(helper, requester).notice(), JobNotice.DONE, "done"))
                .thenExecute(() -> {
                    helper.assertValueEqual(notices(requester),
                            List.of(JobNotice.WAITING_CPU, JobNotice.RUNNING, JobNotice.DONE),
                            "each state once, in order");
                    JobToastPayload done = last(helper, requester);
                    helper.assertTrue(done.toast(), "done toasts");
                    helper.assertValueEqual(done.job(), id[0], "names the job");
                    helper.assertValueEqual(done.requester(), "", "the requester's own notice");
                    helper.assertTrue(!sent(requester).getFirst().toast(), "a live state does not toast");
                    helper.assertValueEqual(sent(other).size(), 0, "nothing for others");
                    helper.assertTrue(!JobNotices.tracked(helper.getLevel(), id[0]), "an ended job is forgotten");
                    offline(requester, other);
                }).thenSucceed();
    }

    /**
     * Two of four planks for eight sticks: the job crafts what it can and stops; the requester's notice says the planks
     * are missing and how many.
     */
    private static void missing(GameTestHelper helper) {
        CraftingTestContent.Host host = setUp(helper, true);
        ServerPlayer requester = online(helper, "notice_missing");
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 2);
            submit(helper, requester, 8);
        }).thenWaitUntil(() -> helper.assertValueEqual(host.pushed, 1, "the one craft sent"))
                .thenExecute(() -> offerSticks(helper, 4))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(last(helper, requester).notice(), JobNotice.MISSING, "stopped"))
                .thenExecute(() -> {
                    JobToastPayload stopped = last(helper, requester);
                    helper.assertTrue(stopped.toast(), "a stop toasts");
                    helper.assertValueEqual(stopped.missing(), Optional.of(new CraftingPlanner.Root(PLANKS, 2)),
                            "names the missing planks");
                    UUID id = stopped.job();
                    helper.assertTrue(!JobNotices.tracked(helper.getLevel(), id), "a stopped job keeps no live notice");
                    // A relog: the stopped job's notice comes again, quietly, so the badge is right.
                    SENT.remove(requester.getUUID());
                    JobNotices.loggedIn(requester);
                    helper.assertValueEqual(notices(requester), List.of(JobNotice.MISSING), "resent on login");
                    helper.assertTrue(!last(helper, requester).toast(), "without a toast");
                    helper.assertValueEqual(last(helper, requester).missing(), stopped.missing(), "with the planks");
                    offline(requester);
                }).thenSucceed();
    }

    /**
     * Jobs no CPU can take: the requester hears them waiting; a logout forgets their notices and a login sends them
     * again without toasts. One cancelled by another player reaches them with a toast naming who; one they cancel
     * themself reaches them without a toast (it only clears their badge). The canceller hears nothing.
     */
    private static void cancel(GameTestHelper helper) {
        setUp(helper, false);
        ServerPlayer requester = online(helper, "notice_owner");
        ServerPlayer canceller = online(helper, "notice_canceller");
        UUID[] ids = new UUID[2];
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 8);
            ids[0] = submit(helper, requester, 4);
            ids[1] = submit(helper, requester, 4);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertValueEqual(notices(requester), List.of(JobNotice.WAITING_CPU, JobNotice.WAITING_CPU),
                    "both wait for a CPU");
            helper.assertTrue(JobNotices.tracked(helper.getLevel(), ids[0]), "a live notice is kept");
            JobNotices.loggedOut(requester);
            helper.assertTrue(
                    !JobNotices.tracked(helper.getLevel(), ids[0]) && !JobNotices.tracked(helper.getLevel(), ids[1]),
                    "forgotten on logout");
            SENT.remove(requester.getUUID());
            JobNotices.loggedIn(requester);
            helper.assertValueEqual(notices(requester), List.of(JobNotice.WAITING_CPU, JobNotice.WAITING_CPU),
                    "resent on login");
            helper.assertTrue(!sent(requester).getFirst().toast(), "without a toast");
            helper.assertTrue(JobNotices.tracked(helper.getLevel(), ids[0]), "kept again");
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), ids[0], canceller.getUUID()), "cancelled by another");
            JobToastPayload other = last(helper, requester);
            helper.assertValueEqual(other.notice(), JobNotice.CANCELLED, "cancelled");
            helper.assertTrue(other.toast(), "someone else's cancel toasts");
            helper.assertValueEqual(other.by(), "notice_canceller", "names who");
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), ids[1], requester.getUUID()), "cancelled by self");
            JobToastPayload own = last(helper, requester);
            helper.assertValueEqual(own.notice(), JobNotice.CANCELLED, "cancelled too");
            helper.assertTrue(!own.toast(), "their own cancel does not toast");
            helper.assertValueEqual(sent(canceller).size(), 0, "nothing for the canceller");
            offline(requester, canceller);
        }).thenSucceed();
    }

    /**
     * A provider whose lock mode lets one batch through per redstone pulse holds the job twice: the requester hears it
     * held each time but gets one toast, hears it running again after each pulse, then done.
     */
    private static void lock(GameTestHelper helper) {
        install();
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        BlockPos providerPos = NetworkGameTests.at(2).south();
        BlockPos signal = providerPos.east();
        helper.setBlock(providerPos.south(), AssemblerContent.ASSEMBLER.get());
        helper.setBlock(providerPos, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.SOUTH));
        PatternProviderBlockEntity provider = helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(LOG), 1, Optional.empty(), false)),
                List.of(new PatternOutput(PLANKS, 4, 1f)), Optional.of(Identifier.withDefaultNamespace("crafting")),
                Optional.of(Identifier.withDefaultNamespace("oak_planks")))));
        provider.logic().setLockMode(LockMode.UNTIL_PULSE);
        ServerPlayer requester = online(helper, "notice_locked");
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = provider.network();
            helper.assertTrue(network != null && network.patterns().isCraftable(PLANKS), "the pattern joined");
        }).thenExecute(() -> {
            NetworkState network = Objects.requireNonNull(provider.network());
            NetworkGameTests.insert(network, LOG, 3);
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, PLANKS, 12);
            JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                    JobQueue.Request.player(requester.getUUID()));
        }).thenWaitUntil(() -> helper.assertValueEqual(last(helper, requester).notice(), JobNotice.PROVIDER_LOCKED,
                "held by the lock")).thenExecute(() -> {
                    helper.assertTrue(last(helper, requester).toast(), "the first hold toasts");
                    helper.setBlock(signal, Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertValueEqual(held(requester), 2, "held a second time"))
                .thenExecute(() -> {
                    helper.assertTrue(!last(helper, requester).toast(), "the second hold of the same job is quiet");
                    helper.setBlock(signal, Blocks.AIR);
                }).thenIdle(5).thenExecute(() -> helper.setBlock(signal, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertValueEqual(last(helper, requester).notice(), JobNotice.DONE, "done"))
                .thenExecute(() -> {
                    List<JobNotice> heard = notices(requester);
                    int first = heard.indexOf(JobNotice.PROVIDER_LOCKED);
                    helper.assertValueEqual(heard.get(first + 1), JobNotice.RUNNING, "running again after the hold");
                    int toasts = 0;
                    for (JobToastPayload payload : sent(requester)) {
                        toasts += payload.notice() == JobNotice.PROVIDER_LOCKED && payload.toast() ? 1 : 0;
                    }
                    helper.assertValueEqual(toasts, 1, "one hold toast for the job: " + heard);
                    offline(requester);
                }).thenSucceed();
    }

    /**
     * With team toasts on, a team member hears a cancel not made by a player, naming the requester; a player outside
     * the team, though online, hears nothing, and the team member gets no live states.
     */
    private static void team(GameTestHelper helper) {
        setUp(helper, false);
        LogicActionGameTests.installFakeTeams();
        ServerPlayer requester = online(helper, "notice_lead");
        ServerPlayer member = online(helper, "notice_member");
        ServerPlayer outsider = online(helper, "notice_outsider");
        UUID team = UUID.randomUUID();
        LogicActionGameTests.FAKE_TEAMS.put(requester.getUUID(), team);
        LogicActionGameTests.FAKE_TEAMS.put(member.getUUID(), team);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 4);
            id[0] = submit(helper, requester, 4);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertValueEqual(sent(member).size(), 0, "no live states for the team");
            // Not by a player (logic, a lost CPU): the toast goes to the whole team.
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), id[0]), "cancelled");
            helper.assertValueEqual(last(helper, requester).notice(), JobNotice.CANCELLED, "the requester hears it");
            JobToastPayload shared = last(helper, member);
            helper.assertValueEqual(shared.notice(), JobNotice.CANCELLED, "the team member hears it");
            helper.assertTrue(shared.toast(), "with a toast");
            helper.assertValueEqual(shared.requester(), "notice_lead", "naming whose job");
            helper.assertValueEqual(sent(outsider).size(), 0, "nothing for the outsider");
            LogicActionGameTests.FAKE_TEAMS.remove(requester.getUUID());
            LogicActionGameTests.FAKE_TEAMS.remove(member.getUUID());
            offline(requester, member, outsider);
        }).thenSucceed();
    }
}
