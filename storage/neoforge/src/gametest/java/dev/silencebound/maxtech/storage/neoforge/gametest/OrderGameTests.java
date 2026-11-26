package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.job.JobNotice;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import io.netty.buffer.Unpooled;
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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The order list (M7C item 6): several items planned as one tree against one stock, the whole list run as one job
 * through the terminal's crafting session with a real provider and assembler, the session's refusals, the list code
 * against the registries and the job's codec. A terminal at x = 0, a powered controller at x = 1, a test storage device
 * at x = 2 with a CPU above it, the provider at x = 3 facing east and the assembler at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class OrderGameTests {
    private static final String[] NAMES = {"order_shared_stock", "order_job_through_terminal", "order_refusals",
            "order_list_code_and_codec", "order_resume_partial", "order_duplicates_and_toast"};
    private static final StackKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final List<CraftingPlanner.Root> ORDER = List.of(new CraftingPlanner.Root(PLANKS, 8),
            new CraftingPlanner.Root(STICK, 8));

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], OrderGameTests::sharedStock);
        register(NAMES[1], OrderGameTests::jobThroughTerminal);
        register(NAMES[2], OrderGameTests::refusals);
        register(NAMES[3], OrderGameTests::listCodeAndCodec);
        register(NAMES[4], OrderGameTests::resumePartial);
        register(NAMES[5], OrderGameTests::duplicatesAndToast);
    }

    private OrderGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("order"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 600);
        }
    }

    private static PatternInput in(StackKey key) {
        return new PatternInput(new PatternIngredient.Exact(key), 1, Optional.empty(), false);
    }

    private static CraftingPattern crafting(List<PatternInput> inputs, StackKey output, long amount, String recipe) {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING, inputs,
                List.of(new PatternOutput(output, amount, 1f)),
                Optional.of(Identifier.withDefaultNamespace("crafting")),
                Optional.of(Identifier.withDefaultNamespace(recipe)));
    }

    /** Terminal, controller, storage, CPU, the provider with planks and stick patterns and its assembler. */
    private static BlockPos setUp(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(4), AssemblerContent.ASSEMBLER.get());
        helper.setBlock(NetworkGameTests.at(3), ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(NetworkGameTests.at(3),
                PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(crafting(List.of(in(LOG)), PLANKS, 4, "oak_planks")));
        provider.logic().slots().setItem(1,
                PatternItem.encode(crafting(List.of(in(PLANKS), in(PLANKS)), STICK, 4, "stick")));
        BlockPos terminal = NetworkGameTests.at(0);
        helper.setBlock(terminal, TerminalContent.TERMINAL.get());
        return terminal;
    }

    private static NetworkState network(GameTestHelper helper, BlockPos terminal) {
        NetworkState network = helper.getBlockEntity(terminal, TerminalBlockEntity.class).network();
        if (network == null || !network.patterns().isCraftable(STICK)) {
            throw helper.assertionException(Component.literal("the patterns are not on the terminal's network"));
        }
        return network;
    }

    private static TerminalMenu open(GameTestHelper helper, BlockPos terminal, ServerPlayer player,
            List<CustomPacketPayload> sent, int containerId) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(),
                helper.getBlockEntity(terminal, TerminalBlockEntity.class), update -> {
                }, sent::add);
        player.containerMenu = menu;
        return menu;
    }

    private static <T> List<T> of(List<CustomPacketPayload> sent, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (CustomPacketPayload payload : sent) {
            if (type.isInstance(payload)) {
                out.add(type.cast(payload));
            }
        }
        return out;
    }

    /**
     * Eight planks and eight sticks from oak logs: 3 logs cover both together (8 planks need 2, the sticks' 4 planks 1
     * more), so the list plans complete with 3 and one log short with 2, though each item alone would plan complete
     * with 2.
     */
    private static void sharedStock(GameTestHelper helper) {
        BlockPos terminal = setUp(helper);
        helper.startSequence().thenWaitUntil(() -> network(helper, terminal)).thenExecute(() -> {
            NetworkState network = network(helper, terminal);
            NetworkGameTests.insert(network, LOG, 2);
            PlanTree<StackKey> shortTree = CraftingPlanner.planOrderNow(network, ORDER).tree();
            helper.assertValueEqual(shortTree.missing().getLong(LOG), 1L, "one log short together");
            helper.assertTrue(CraftingPlanner.planOrderNow(network, List.of(ORDER.get(1))).tree().complete(),
                    "sticks alone are fine");
            NetworkGameTests.insert(network, LOG, 1);
            CraftingPlanner.Result plan = CraftingPlanner.planOrderNow(network, ORDER);
            helper.assertTrue(plan.tree().complete(), "complete with three: " + plan.tree().missing());
            helper.assertValueEqual(plan.tree().used().getLong(LOG), 3L, "three logs");
            helper.assertValueEqual(plan.roots(), ORDER, "the roots in order");
        }).thenSucceed();
    }

    /**
     * The list goes through the session: one plan comes back, the start queues one job holding both requests, and once
     * it is done the network holds both items and no log.
     */
    private static void jobThroughTerminal(GameTestHelper helper) {
        BlockPos terminal = setUp(helper);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "order_player");
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, terminal)).thenExecute(() -> {
            NetworkState network = network(helper, terminal);
            NetworkGameTests.insert(network, LOG, 3);
            menu[0] = open(helper, terminal, player, sent, 3);
            CraftingSession session = Objects.requireNonNull(menu[0].crafting());
            helper.assertTrue(session.planOrder(9, ORDER), "the order plans");
        }).thenWaitUntil(() -> helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).size(), 1, "one plan"))
                .thenExecute(() -> {
                    RequestPayloads.Plan plan = of(sent, RequestPayloads.Plan.class).getFirst();
                    helper.assertValueEqual(plan.request(), 9, "the request number");
                    helper.assertTrue(plan.view().complete(), "complete");
                    CraftingSession session = Objects.requireNonNull(menu[0].crafting());
                    helper.assertTrue(
                            session.handle(
                                    RequestPayloads.Request.of(menu[0].containerId, RequestPayloads.Action.START)),
                            "start");
                    List<CraftingJob> jobs = JobQueue.jobsOn(network(helper, terminal));
                    helper.assertValueEqual(jobs.size(), 1, "one job");
                    helper.assertValueEqual(jobs.getFirst().roots(), ORDER, "both requests in it");
                }).thenWaitUntil(() -> {
                    NetworkState network = network(helper, terminal);
                    helper.assertTrue(JobQueue.jobsOn(network).isEmpty(), "the job finished");
                    helper.assertValueEqual(network.contents().amount(PLANKS), 8L, "eight planks");
                    helper.assertValueEqual(network.contents().amount(STICK), 8L, "eight sticks");
                    helper.assertValueEqual(network.contents().amount(LOG), 0L, "no log left");
                }).thenSucceed();
    }

    /** An empty list, a zero amount, too many entries, a second action in the same tick and a busy session. */
    private static void refusals(GameTestHelper helper) {
        BlockPos terminal = setUp(helper);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "order_refusals");
        List<CustomPacketPayload> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> network(helper, terminal)).thenExecute(() -> {
            TerminalMenu menu = open(helper, terminal, player, sent, 4);
            CraftingSession session = Objects.requireNonNull(menu.crafting());
            helper.assertFalse(session.planOrder(1, List.of()), "an empty list");
            helper.assertFalse(session.planOrder(1, List.of(new CraftingPlanner.Root(STICK, 0))), "zero sticks");
            List<CraftingPlanner.Root> many = new ArrayList<>();
            for (int i = 0; i <= CraftingPlanner.MAX_ROOTS; i++) {
                many.add(new CraftingPlanner.Root(STICK, 1));
            }
            helper.assertFalse(session.planOrder(1, many), "too many entries");
            helper.assertTrue(session.planOrder(1, ORDER), "a good list");
            helper.assertFalse(session.planOrder(2, ORDER), "a second action in the same tick");
        }).thenIdle(1).thenExecute(() -> {
            CraftingSession session = Objects.requireNonNull(((TerminalMenu) player.containerMenu).crafting());
            if (session.planning()) {
                helper.assertFalse(session.planOrder(3, ORDER), "still planning the first");
            }
        }).thenSucceed();
    }

    /**
     * A code resolves to keys (a fluid with {@code f/}), unknown ids and air are reported, the payload and the job's
     * codec keep every request.
     */
    private static void listCodeAndCodec(GameTestHelper helper) {
        BlockPos terminal = setUp(helper);
        helper.startSequence().thenWaitUntil(() -> network(helper, terminal)).thenExecute(() -> {
            OrderList.Resolved resolved = OrderList
                    .resolve("mtlist1:minecraft:stick*8,f/minecraft:water*1000,minecraft:air*1,nothing:here*2");
            helper.assertValueEqual(resolved.roots(), List.of(new CraftingPlanner.Root(STICK, 8),
                    new CraftingPlanner.Root(FluidKey.of(Fluids.WATER), 1000)), "stick and water");
            helper.assertValueEqual(resolved.errors(), List.of("minecraft:air", "nothing:here"), "the unknown ones");
            helper.assertValueEqual(OrderList.resolve(OrderList.encode(ORDER)).roots(), ORDER, "code round trip");

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            OrderList.Plan payload = new OrderList.Plan(5, 2, ORDER);
            OrderList.Plan.CODEC.encode(buffer, payload);
            helper.assertValueEqual(OrderList.Plan.CODEC.decode(buffer), payload, "payload codec");

            NetworkState network = network(helper, terminal);
            NetworkGameTests.insert(network, LOG, 3);
            CraftingJob job = CraftingJob.planned(CraftingPlanner.planOrderNow(network, ORDER),
                    Optional.of(UUID.randomUUID()), 1);
            RegistryOps<JsonElement> ops = helper.getLevel().registryAccess()
                    .createSerializationContext(JsonOps.INSTANCE);
            JsonElement json = CraftingJob.CODEC.encodeStart(ops, job).getOrThrow();
            helper.assertValueEqual(CraftingJob.CODEC.parse(ops, json).getOrThrow(), job, "job codec");
            helper.assertValueEqual(job.withState(job.state()).roots(), ORDER, "state changes keep the list");
        }).thenSucceed();
    }

    /**
     * With two logs the order is one log short and the job stops partial. After one more log a resume plans what each
     * request still owes, and the job ends with both items made and no log left.
     */
    private static void resumePartial(GameTestHelper helper) {
        BlockPos terminal = setUp(helper);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, terminal)).thenExecute(() -> {
            NetworkState network = network(helper, terminal);
            NetworkGameTests.insert(network, LOG, 2);
            CraftingPlanner.Result plan = CraftingPlanner.planOrderNow(network, ORDER);
            helper.assertTrue(!plan.tree().complete(), "one log short");
            id[0] = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                    JobQueue.Request.player(UUID.randomUUID())).job().orElseThrow().id();
        }).thenWaitUntil(() -> {
            CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id[0]);
            helper.assertTrue(job != null && job.state() == JobState.PARTIAL, "stopped partial");
        }).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, terminal), LOG, 1);
            helper.assertTrue(JobQueue.resume(helper.getLevel(), id[0]), "resumed");
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, terminal);
            helper.assertTrue(CraftingJobs.get(helper.getLevel()).get(id[0]) == null, "the job finished");
            helper.assertValueEqual(network.contents().amount(STICK), 8L, "eight sticks");
            helper.assertValueEqual(network.contents().amount(PLANKS), 8L, "the delivered planks kept");
            helper.assertValueEqual(network.contents().amount(LOG), 0L, "the new log used");
        }).thenSucceed();
    }

    /**
     * A hand-built list naming sticks twice plans them once with the sum; a finished order's toast names the first
     * requests and the count, and survives its codec.
     */
    private static void duplicatesAndToast(GameTestHelper helper) {
        BlockPos terminal = setUp(helper);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "order_duplicates");
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, terminal)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, terminal), LOG, 3);
            menu[0] = open(helper, terminal, player, sent, 6);
            helper.assertTrue(
                    Objects.requireNonNull(menu[0].crafting())
                            .planOrder(1, List.of(new CraftingPlanner.Root(STICK, 4),
                                    new CraftingPlanner.Root(PLANKS, 8), new CraftingPlanner.Root(STICK, 4))),
                    "planned");
        }).thenWaitUntil(
                () -> helper.assertTrue(Objects.requireNonNull(menu[0].crafting()).lastPlan() != null, "plan arrived"))
                .thenExecute(() -> {
                    CraftingPlanner.Result plan = Objects
                            .requireNonNull(Objects.requireNonNull(menu[0].crafting()).lastPlan());
                    helper.assertValueEqual(plan.roots(),
                            List.of(new CraftingPlanner.Root(STICK, 8), new CraftingPlanner.Root(PLANKS, 8)),
                            "sticks once, summed");
                    List<CraftingPlanner.Root> four = List.of(new CraftingPlanner.Root(STICK, 1),
                            new CraftingPlanner.Root(PLANKS, 2), new CraftingPlanner.Root(LOG, 3),
                            new CraftingPlanner.Root(ItemKey.of(Items.TORCH), 4));
                    CraftingJob job = new CraftingJob(UUID.randomUUID(), Optional.empty(), STICK, 1, List.of(),
                            List.of(), JobState.DONE, 0, four.subList(1, 4));
                    // The codec carries the notice's extras (B09 item 21d) too.
                    JobToastPayload toast = JobToastPayload.of(job, JobNotice.MISSING).withMissing(PLANKS, 3)
                            .withBy("someone").forTeam("teammate");
                    helper.assertValueEqual(toast.total(), 4, "four requests");
                    helper.assertValueEqual(toast.named(), four.subList(0, 3), "the first three named");
                    RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                            helper.getLevel().registryAccess());
                    JobToastPayload.STREAM_CODEC.encode(buffer, toast);
                    helper.assertValueEqual(JobToastPayload.STREAM_CODEC.decode(buffer), toast, "toast codec");
                    Component summary = OrderList.summary(toast.named(), toast.total());
                    helper.assertTrue(summary.getContents() instanceof TranslatableContents contents
                            && contents.getKey().equals(OrderList.SUMMARY_KEY) && contents.getArgs()[0].equals(4)
                            && contents.getArgs()[1].toString().contains("…"),
                            "the summary names the count and ends with an ellipsis: " + summary);
                    helper.assertValueEqual(JobToastPayload.of(job.withState(JobState.DONE), JobNotice.MISSING).total(),
                            4, "state keeps the list");
                }).thenSucceed();
    }
}
