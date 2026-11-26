package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.lib.sync.SyncedListType;
import dev.silencebound.maxtech.lib.sync.SyncedListUpdate;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.PlanView;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Crafting requests from the storage terminal (M7c area C): who may plan, start and cancel (C17), the plan payload's
 * round trip in extended and classic mode, the running job's progress as step deltas and the job list, and the
 * terminal's craftable list following the network's patterns. Menus run with recording sinks on the CPU's job queue. A
 * powered controller at x = 1, a test storage device at x = 2, a pattern host at x = 3 and a terminal at x = 4; the
 * furnace test puts a provider at x = 3, a furnace at x = 4, a CPU above the device and the terminal at x = 0.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CraftRequestGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String PERMISSIONS = "craft_request_permissions";
    private static final String ROUND_TRIP = "craft_request_plan_round_trip";
    private static final String FURNACE = "craft_request_furnace_job";
    private static final String CRAFTABLES = "craft_request_craftables";
    private static final String PARTIAL = "craft_request_partial_start";
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final StackKey LOG = ItemKey.of(Items.OAK_LOG);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(PERMISSIONS, CraftRequestGameTests::permissions);
        register(ROUND_TRIP, CraftRequestGameTests::roundTrip);
        register(FURNACE, CraftRequestGameTests::furnaceJob);
        register(CRAFTABLES, CraftRequestGameTests::craftables);
        register(PARTIAL, CraftRequestGameTests::partialStart);
    }

    private CraftRequestGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("craft_request"),
                StorageTestEnvironment.plain());
        for (String name : List.of(PERMISSIONS, ROUND_TRIP, CRAFTABLES, PARTIAL)) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
        StorageTests.test(event, environment, FURNACE, STRUCTURE, 900);
    }

    private static PatternInput in(PatternIngredient ingredient, long amount) {
        return new PatternInput(ingredient, amount, Optional.empty(), false);
    }

    private static CraftingPattern crafting(Item output, int count, Item input, int amount) {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(in(new PatternIngredient.Exact(ItemKey.of(input)), amount)),
                List.of(new PatternOutput(ItemKey.of(output), count, 1f)), Optional.empty(), Optional.empty());
    }

    /** Sticks from planks from oak logs. */
    private static List<CraftingPattern> sticksFromLogs() {
        return List.of(crafting(Items.OAK_PLANKS, 4, Items.OAK_LOG, 1), crafting(Items.STICK, 4, Items.OAK_PLANKS, 2));
    }

    private static CraftingTestContent.Host setUp(GameTestHelper helper, List<CraftingPattern> patterns) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(patterns);
        helper.setBlock(NetworkGameTests.at(4), TerminalContent.TERMINAL.get());
        return host;
    }

    private static TerminalBlockEntity terminal(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, TerminalBlockEntity.class);
    }

    private static NetworkState network(GameTestHelper helper) {
        return network(helper, NetworkGameTests.at(4));
    }

    private static NetworkState network(GameTestHelper helper, BlockPos terminal) {
        NetworkState network = terminal(helper, terminal).network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the terminal is in no network"));
        }
        return network;
    }

    /** A menu for {@code player} whose payloads land in {@code sent}, opened as the screen would open it. */
    private static TerminalMenu open(GameTestHelper helper, ServerPlayer player, List<CustomPacketPayload> sent,
            int containerId) {
        return open(helper, NetworkGameTests.at(4), player, sent, containerId);
    }

    private static TerminalMenu open(GameTestHelper helper, BlockPos terminal, ServerPlayer player,
            List<CustomPacketPayload> sent, int containerId) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(), terminal(helper, terminal),
                update -> {
                }, sent::add);
        player.containerMenu = menu;
        menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.REFRESH, -1).buttonId());
        menu.broadcastChanges();
        return menu;
    }

    private static CraftingSession session(GameTestHelper helper, TerminalMenu menu) {
        CraftingSession session = menu.crafting();
        if (session == null) {
            throw helper.assertionException(Component.literal("the server menu has no crafting session"));
        }
        return session;
    }

    private static RequestPayloads.Request plan(TerminalMenu menu, StackKey key, long amount) {
        return new RequestPayloads.Request(menu.containerId, RequestPayloads.Action.PLAN, 7, Optional.of(key), amount,
                Optional.empty());
    }

    private static RequestPayloads.Request onJob(TerminalMenu menu, RequestPayloads.Action action, UUID job) {
        return new RequestPayloads.Request(menu.containerId, action, 0, Optional.empty(), 0, Optional.of(job));
    }

    private static <T> List<T> of(List<CustomPacketPayload> sent, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (CustomPacketPayload payload : sent) {
            if (type.isInstance(payload)) {
                found.add(type.cast(payload));
            }
        }
        return found;
    }

    /** The key of the last notice sent, "" when none was. */
    private static String lastNotice(List<CustomPacketPayload> sent) {
        List<RequestPayloads.Notice> notices = of(sent, RequestPayloads.Notice.class);
        return notices.isEmpty() ? "" : notices.getLast().key();
    }

    /** The recorded updates of synced list {@code list}, decoded through its codec as a client gets them. */
    private static List<SyncedListUpdate<StackKey>> updates(GameTestHelper helper, List<CustomPacketPayload> sent,
            SyncedListType<StackKey> list) {
        List<SyncedListUpdate<StackKey>> found = new ArrayList<>();
        for (CustomPacketPayload payload : sent) {
            if (payload.type().equals(list.payloadType())) {
                @SuppressWarnings("unchecked") // the type id names the list kind
                SyncedListUpdate<StackKey> update = (SyncedListUpdate<StackKey>) payload;
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                        helper.getLevel().registryAccess());
                list.codec().encode(buf, update);
                found.add(list.codec().decode(buf));
            }
        }
        return found;
    }

    /**
     * Planning, starting and cancelling need craft access: a depositor is refused with a notice, a crafter plans and
     * starts (the job names them), another crafter may not cancel their job, the owner (full access) may. Amounts out
     * of range are refused without a plan.
     */
    private static void permissions(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "cr_owner");
        ServerPlayer depositor = NetworkGameTests.player(helper.getLevel(), "cr_depositor");
        ServerPlayer crafter = NetworkGameTests.player(helper.getLevel(), "cr_crafter");
        ServerPlayer other = NetworkGameTests.player(helper.getLevel(), "cr_other");
        setUp(helper, sticksFromLogs());
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core)
                .setSecurity(SecuritySettings.DEFAULT
                        .withPlayer(depositor.getUUID(), depositor.getGameProfile().name(), AccessLevel.DEPOSIT)
                        .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT)
                        .withPlayer(other.getUUID(), other.getGameProfile().name(), AccessLevel.CRAFT));
        List<CustomPacketPayload> toDepositor = new ArrayList<>();
        List<CustomPacketPayload> toCrafter = new ArrayList<>();
        List<CustomPacketPayload> toOther = new ArrayList<>();
        List<CustomPacketPayload> toOwner = new ArrayList<>();
        TerminalMenu[] menus = new TerminalMenu[4];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper);
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active");
        }).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 10);
            // Sticks on hand must not shrink the request: asking for 4 crafts 4 (owner decision 2026-10-05).
            NetworkGameTests.insert(network(helper), STICK, 3);
            menus[0] = open(helper, depositor, toDepositor, 1);
            menus[1] = open(helper, crafter, toCrafter, 2);
            menus[2] = open(helper, other, toOther, 3);
            menus[3] = open(helper, owner, toOwner, 4);
            helper.assertFalse(session(helper, menus[0]).handle(plan(menus[0], STICK, 4)), "depositor plans");
            helper.assertValueEqual(lastNotice(toDepositor), RequestContent.NO_ACCESS_KEY, "depositor's notice");
            helper.assertFalse(session(helper, menus[1]).handle(plan(menus[1], STICK, 0)), "zero sticks");
        }).thenIdle(1).thenExecute(() -> {
            helper.assertFalse(session(helper, menus[1]).handle(plan(menus[1], STICK, CraftingSession.MAX_AMOUNT + 1)),
                    "too many sticks");
        }).thenIdle(1).thenExecute(() -> {
            helper.assertTrue(session(helper, menus[1]).handle(plan(menus[1], STICK, 4)), "crafter plans");
        }).thenWaitUntil(() -> helper.assertValueEqual(of(toCrafter, RequestPayloads.Plan.class).size(), 1, "plans"))
                .thenExecute(() -> {
                    RequestPayloads.Plan answer = of(toCrafter, RequestPayloads.Plan.class).getFirst();
                    helper.assertValueEqual(answer.request(), 7, "the request number comes back");
                    helper.assertTrue(answer.view().complete(), "planks on hand: " + answer.view().missing());
                    PlanView.Node root = answer.view().nodes().getFirst();
                    helper.assertValueEqual(root.stored(), 0L, "stored sticks are not taken for the root");
                    helper.assertValueEqual(root.toCraft(), 4L, "a request for 4 crafts 4");
                    helper.assertFalse(
                            session(helper, menus[0]).handle(
                                    RequestPayloads.Request.of(menus[0].containerId, RequestPayloads.Action.START)),
                            "depositor starts");
                    helper.assertTrue(
                            session(helper, menus[1]).handle(
                                    RequestPayloads.Request.of(menus[1].containerId, RequestPayloads.Action.START)),
                            "crafter starts");
                    List<CraftingJob> queued = JobQueue.jobsOn(network(helper));
                    helper.assertValueEqual(queued.size(), 1, "queued jobs");
                    CraftingJob job = queued.getFirst();
                    helper.assertValueEqual(job.requester(), Optional.of(crafter.getUUID()), "requester");
                    helper.assertValueEqual(job.state(), JobState.PLANNED, "no CPU, so it waits");
                    List<RequestPayloads.Job> watched = of(toCrafter, RequestPayloads.Job.class);
                    helper.assertTrue(!watched.isEmpty() && watched.getLast().id().equals(job.id()),
                            "the new job is watched");
                    helper.assertTrue(session(helper, menus[1]).lastPlan() == null, "the plan was used up");
                    helper.assertFalse(
                            session(helper, menus[2])
                                    .handle(onJob(menus[2], RequestPayloads.Action.CANCEL_JOB, job.id())),
                            "another crafter cancels");
                    helper.assertValueEqual(lastNotice(toOther), RequestContent.NOT_YOURS_KEY, "other's notice");
                    helper.assertTrue(session(helper, menus[3])
                            .handle(onJob(menus[3], RequestPayloads.Action.CANCEL_JOB, job.id())), "owner cancels");
                    helper.assertTrue(JobQueue.jobsOn(network(helper)).isEmpty(), "cancelled");
                    helper.assertTrue(RequestContent.session(crafter, menus[1].containerId + 1) == null,
                            "a payload for another container finds no session");
                }).thenSucceed();
    }

    /**
     * A partial plan encodes and decodes to the same payload; its root is made by a crafting assembler, nodes carry the
     * planner's numbers and the missing logs. Classic mode sends no nodes but the same status and missing list. A
     * smelting pattern with its recipe gets the furnace and the recipe's cooking time.
     */
    private static void roundTrip(GameTestHelper helper) {
        CraftingPattern smelting = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(in(new PatternIngredient.Exact(ItemKey.of(Items.RAW_IRON)), 1)),
                List.of(new PatternOutput(ItemKey.of(Items.IRON_INGOT), 1, 1f)),
                Optional.of(Identifier.withDefaultNamespace("smelting")),
                Optional.of(Identifier.withDefaultNamespace("iron_ingot_from_smelting_raw_iron")));
        List<CraftingPattern> patterns = new ArrayList<>(sticksFromLogs());
        patterns.add(smelting);
        setUp(helper, patterns);
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenExecute(() -> {
            NetworkState network = network(helper);
            NetworkGameTests.insert(network, PLANKS, 1);
            NetworkGameTests.insert(network, ItemKey.of(Items.RAW_IRON), 3);
            CraftingPlanner.Result result = CraftingPlanner.planNow(network, STICK, 16);
            PlanView view = PlanView.of(result, helper.getLevel(), true);
            helper.assertValueEqual(view.status(), PlanView.Status.MISSING, "status");
            helper.assertValueEqual(view.nodes().size(), result.tree().steps().size(), "every node shown");
            helper.assertValueEqual(view.hidden(), 0, "nothing hidden");
            helper.assertValueEqual(view.nodes().getFirst().crafts(), result.tree().steps().getFirst().crafts(),
                    "root crafts");
            helper.assertValueEqual(view.nodeKey(0), STICK, "root key");
            helper.assertValueEqual(view.providers().get(view.nodes().getFirst().provider()),
                    Component.translatable(RequestContent.PROVIDER_CRAFTING_KEY), "root provider");
            Set<StackKey> missing = new ObjectOpenHashSet<>();
            for (PlanView.Missing entry : view.missing()) {
                missing.add(entry.key());
            }
            helper.assertTrue(missing.contains(LOG), "logs are missing: " + view.missing());
            helper.assertValueEqual(view.ticks(), -1L, "crafting has no known duration");
            helper.assertTrue(view.extended(), "planned in extended mode");
            PlanView.Tile sticks = view.tiles().getFirst();
            helper.assertValueEqual(sticks.key(), STICK, "the request's tile comes first");
            helper.assertValueEqual(sticks.crafting(), result.tree().steps().getFirst().toCraft(), "sticks to craft");
            long logsMissing = 0;
            long planksStored = 0;
            for (PlanView.Tile tile : view.tiles()) {
                if (tile.key().equals(LOG)) {
                    logsMissing = tile.missing();
                } else if (tile.key().equals(PLANKS)) {
                    planksStored = tile.stored();
                }
            }
            long logsExpected = 0;
            for (PlanView.Missing entry : view.missing()) {
                if (entry.key().equals(LOG)) {
                    logsExpected = entry.count();
                }
            }
            helper.assertValueEqual(logsMissing, logsExpected, "the log tile's missing count");
            helper.assertValueEqual(planksStored, 1L, "the plank on hand is taken from storage");

            RequestPayloads.Plan payload = new RequestPayloads.Plan(5, 3, view);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            RequestPayloads.Plan.CODEC.encode(buf, payload);
            helper.assertValueEqual(RequestPayloads.Plan.CODEC.decode(buf), payload, "decoded plan");

            PlanView classic = PlanView.of(result, helper.getLevel(), false);
            helper.assertTrue(classic.nodes().isEmpty(), "classic sends no nodes");
            helper.assertValueEqual(classic.hidden(), result.tree().steps().size(), "classic hides every node");
            helper.assertValueEqual(classic.missing(), view.missing(), "classic still names what is missing");
            helper.assertValueEqual(classic.status(), view.status(), "classic status");
            helper.assertValueEqual(classic.tiles(), view.tiles(), "classic shows the same tiles");
            helper.assertFalse(classic.extended(), "classic plan");

            PlanView ingots = PlanView.of(CraftingPlanner.planNow(network, ItemKey.of(Items.IRON_INGOT), 3),
                    helper.getLevel(), true);
            helper.assertTrue(ingots.complete(), "raw iron on hand");
            helper.assertValueEqual(ingots.nodes().getFirst().ticks(), 600L, "three smelts of 200 ticks");
            helper.assertValueEqual(ingots.ticks(), 600L, "the whole job");
            helper.assertValueEqual(ingots.providers().getFirst(),
                    Component.translatable(Blocks.FURNACE.getDescriptionId()), "made in a furnace");
        }).thenSucceed();
    }

    /**
     * A request through the terminal's crafting session runs on a real CPU: the plan comes back, the start queues the
     * job on the CPU's queue and watches it, a pattern provider hands raw iron and coal to a furnace, and the watched
     * job's tree and step deltas follow it to done; the client's progress copy ends empty and the job list ends empty.
     */
    private static void furnaceJob(GameTestHelper helper) {
        StackKey rawIron = ItemKey.of(Items.RAW_IRON);
        StackKey iron = ItemKey.of(Items.IRON_INGOT);
        CraftingPattern smelt = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(in(new PatternIngredient.Exact(rawIron), 1),
                        in(new PatternIngredient.Exact(ItemKey.of(Items.COAL)), 1)),
                List.of(new PatternOutput(iron, 1, 1f)), Optional.empty(), Optional.empty());
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        BlockPos providerPos = NetworkGameTests.at(3);
        helper.setBlock(providerPos, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(smelt));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        BlockPos terminalPos = NetworkGameTests.at(0);
        helper.setBlock(terminalPos, TerminalContent.TERMINAL.get());
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "cr_furnace");
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(network(helper, terminalPos).patterns().isCraftable(iron),
                        "the provider's pattern on the terminal's network"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, terminalPos);
                    NetworkGameTests.insert(network, rawIron, 2);
                    NetworkGameTests.insert(network, ItemKey.of(Items.COAL), 2);
                    menu[0] = open(helper, terminalPos, player, sent, 1);
                    helper.assertTrue(session(helper, menu[0]).handle(plan(menu[0], iron, 2)), "plan");
                }).thenWaitUntil(() -> helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).size(), 1, "plans"))
                .thenExecute(() -> {
                    helper.assertTrue(of(sent, RequestPayloads.Plan.class).getFirst().view().complete(),
                            "everything on hand");
                    helper.assertTrue(
                            session(helper, menu[0]).handle(
                                    RequestPayloads.Request.of(menu[0].containerId, RequestPayloads.Action.START)),
                            "start");
                    List<CraftingJob> jobs = JobQueue.jobsOn(network(helper, terminalPos));
                    helper.assertValueEqual(jobs.size(), 1, "queued on the CPU's queue");
                    helper.assertValueEqual(jobs.getFirst().state(), JobState.RUNNING, "a CPU took it");
                    List<RequestPayloads.Job> watched = of(sent, RequestPayloads.Job.class);
                    helper.assertTrue(!watched.isEmpty() && watched.getLast().id().equals(jobs.getFirst().id()),
                            "the new job is watched");
                    List<RequestPayloads.JobStatus> status = of(sent, RequestPayloads.JobStatus.class);
                    helper.assertTrue(!status.isEmpty() && !status.getFirst().tiles().isEmpty(),
                            "the watched job's tiles");
                    PlanView.Tile ingots = status.getFirst().tiles().getFirst();
                    helper.assertValueEqual(ingots.key(), iron, "the ingot tile first");
                    helper.assertValueEqual(ingots.crafting() + ingots.scheduled(), 2L,
                            "two ingots in flight or still to send");
                }).thenIdle(1)
                .thenExecute(() -> helper.assertTrue(
                        session(helper, menu[0]).handle(
                                RequestPayloads.Request.of(menu[0].containerId, RequestPayloads.Action.WATCH_JOBS)),
                        "watch the list"))
                .thenWaitUntil(() -> {
                    menu[0].broadcastChanges();
                    List<RequestPayloads.Job> watched = of(sent, RequestPayloads.Job.class);
                    helper.assertValueEqual(watched.getLast().state(), JobState.DONE, "the view reaches done");
                }).thenIdle(25).thenExecute(() -> {
                    menu[0].broadcastChanges();
                    List<SyncedListUpdate<StackKey>> progress = updates(helper, sent, RequestContent.PROGRESS);
                    helper.assertTrue(progress.getFirst().reset(), "the progress dump");
                    helper.assertTrue(progress.getFirst().countCount() > 0, "crafts to run at the start");
                    helper.assertTrue(progress.size() > 1 && !progress.getLast().reset(), "deltas followed");
                    ClientSyncedList<StackKey> client = new ClientSyncedList<>();
                    for (SyncedListUpdate<StackKey> update : progress) {
                        client.apply(update);
                    }
                    helper.assertValueEqual(client.size(), 0, "every step done on the client");
                    List<RequestPayloads.Jobs> lists = of(sent, RequestPayloads.Jobs.class);
                    helper.assertTrue(!lists.isEmpty() && lists.getLast().jobs().isEmpty(), "the list ends empty");
                    long corePos = helper.absolutePos(NetworkGameTests.at(2).above()).asLong();
                    boolean listedOnCpu = false;
                    for (RequestPayloads.Jobs list : lists) {
                        for (RequestPayloads.JobSummary job : list.jobs()) {
                            listedOnCpu |= job.onCpu() && job.cpuTier() == 1 && job.cpu() == corePos;
                        }
                    }
                    helper.assertTrue(listedOnCpu, "the list names the tier 1 CPU running the job");
                    List<RequestPayloads.JobStatus> status = of(sent, RequestPayloads.JobStatus.class);
                    helper.assertTrue(status.getLast().tiles().isEmpty(), "no tiles once done");
                    helper.assertValueEqual(network(helper, terminalPos).contents().amount(iron), 2L, "two ingots");
                }).thenSucceed();
    }

    /**
     * A plan with missing logs starts as a partial job in extended mode, on the CPU tier the request names; classic
     * mode refuses it with a notice, and a tier outside 0 to 3 is refused.
     */
    private static void partialStart(GameTestHelper helper) {
        setUp(helper, sticksFromLogs());
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "cr_partial");
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenExecute(() -> {
            menu[0] = open(helper, player, sent, 1);
            helper.assertTrue(session(helper, menu[0]).handle(plan(menu[0], STICK, 4)), "plan");
        }).thenWaitUntil(() -> helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).size(), 1, "plans"))
                .thenExecute(() -> {
                    helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).getFirst().view().status(),
                            PlanView.Status.MISSING, "no logs");
                    helper.assertFalse(session(helper, menu[0]).handle(start(menu[0], 4)), "tier 4");
                }).thenIdle(1).thenExecute(() -> {
                    helper.assertTrue(session(helper, menu[0]).handle(start(menu[0], 2)), "a partial start");
                    List<CraftingJob> jobs = JobQueue.jobsOn(network(helper));
                    helper.assertValueEqual(jobs.size(), 1, "queued");
                    JobRun run = JobQueue.progress(helper.getLevel(), jobs.getFirst().id());
                    helper.assertTrue(run != null && run.cpuTier() == 2, "on tier 2 CPUs only");
                    helper.assertTrue(run != null && !run.missing().isEmpty(), "the job knows what it lacks");
                    helper.assertTrue(JobQueue.cancel(helper.getLevel(), jobs.getFirst().id()), "cancel");
                    session(helper, menu[0]).useMode(() -> false);
                }).thenIdle(1).thenExecute(() -> {
                    helper.assertTrue(session(helper, menu[0]).handle(plan(menu[0], STICK, 4)), "plan in classic");
                }).thenWaitUntil(() -> helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).size(), 2, "plans"))
                .thenExecute(() -> {
                    helper.assertFalse(of(sent, RequestPayloads.Plan.class).getLast().view().extended(), "classic");
                    helper.assertFalse(session(helper, menu[0]).handle(start(menu[0], 0)), "classic start");
                    helper.assertValueEqual(lastNotice(sent), RequestContent.INCOMPLETE_KEY, "classic notice");
                    helper.assertTrue(JobQueue.jobsOn(network(helper)).isEmpty(), "nothing queued");
                }).thenSucceed();
    }

    private static RequestPayloads.Request start(TerminalMenu menu, long cpuTier) {
        return new RequestPayloads.Request(menu.containerId, RequestPayloads.Action.START, 0, Optional.empty(), cpuTier,
                Optional.empty());
    }

    /**
     * The terminal's craftable list starts with the network's craftable keys, gains a key when a pattern is added and
     * loses one when its pattern goes; a client replaying the updates holds exactly the craftable keys.
     */
    private static void craftables(GameTestHelper helper) {
        CraftingTestContent.Host host = setUp(helper, List.of(crafting(Items.STICK, 4, Items.OAK_PLANKS, 2)));
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "cr_viewer");
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenWaitUntil(() -> helper
                .assertTrue(network(helper).patterns().isCraftable(STICK), "the host's pattern is registered"))
                .thenExecute(() -> {
                    menu[0] = open(helper, player, sent, 1);
                    List<SyncedListUpdate<StackKey>> updates = updates(helper, sent, RequestContent.CRAFTABLES);
                    helper.assertValueEqual(updates.size(), 1, "the dump");
                    helper.assertTrue(updates.getFirst().reset(), "a dump resets");
                    helper.assertValueEqual(updates.getFirst().definitionCount(), 1, "one craftable key");
                    helper.assertValueEqual(updates.getFirst().definition(0), STICK, "sticks");
                    host.setPatterns(List.of(crafting(Items.STICK, 4, Items.OAK_PLANKS, 2),
                            crafting(Items.OAK_PLANKS, 4, Items.OAK_LOG, 1)));
                }).thenIdle(25).thenExecute(() -> {
                    menu[0].broadcastChanges();
                    host.setPatterns(List.of(crafting(Items.OAK_PLANKS, 4, Items.OAK_LOG, 1)));
                }).thenIdle(25).thenExecute(() -> {
                    menu[0].broadcastChanges();
                    List<SyncedListUpdate<StackKey>> updates = updates(helper, sent, RequestContent.CRAFTABLES);
                    helper.assertTrue(updates.size() >= 3, "dump, an addition and a removal: " + updates.size());
                    ClientSyncedList<StackKey> client = new ClientSyncedList<>();
                    for (SyncedListUpdate<StackKey> update : updates) {
                        client.apply(update);
                    }
                    Set<StackKey> keys = new ObjectOpenHashSet<>();
                    for (int id : client.ids()) {
                        StackKey key = client.entry(id);
                        if (key != null) {
                            keys.add(key);
                        }
                    }
                    helper.assertValueEqual(keys, Set.of(PLANKS), "the client's craftable keys");
                }).thenSucceed();
    }
}
