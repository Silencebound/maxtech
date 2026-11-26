package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.job.QueueBackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * A network's limits for automated crafting (M7L2 chunk 20): CPUs kept free for players, player requests ahead of
 * queued automation, the per-job maximum for background requests and stock keepers, the logic request's why lines, and
 * the settings kept through the controller's screen, a save and its item. A powered controller at x = 1, a test storage
 * device at x = 2, a pattern host with the stick pattern at x = 3 and CPU cores from x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CraftLimitsGameTests {
    private static final String[] NAMES = {"craft_limits_keep_cpus_for_players", "craft_limits_players_jump_queue",
            "craft_limits_cap_per_job", "craft_limits_stock_keeper", "craft_limits_saved_and_screen",
            "craft_limits_logic_why", "craft_limits_logic_reads_auto_cpus", "craft_limits_data_slots_sync"};
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final CraftingPattern STICKS = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
            List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
            List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(NAMES[0], CraftLimitsGameTests::keepCpusForPlayers);
        register(NAMES[1], CraftLimitsGameTests::playersJumpQueue);
        register(NAMES[2], CraftLimitsGameTests::capPerJob);
        register(NAMES[3], CraftLimitsGameTests::stockKeeper);
        register(NAMES[4], CraftLimitsGameTests::savedAndScreen);
        register(NAMES[5], CraftLimitsGameTests::logicWhy);
        register(NAMES[6], CraftLimitsGameTests::logicReadsAutoCpus);
        register(NAMES[7], CraftLimitsGameTests::dataSlotsSync);
    }

    private CraftLimitsGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("craft_limits"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    /** Controller, storage device, pattern host and {@code cpus} tier-1 CPU cores in a row; the controller's entity. */
    private static ControllerBlockEntity setUp(GameTestHelper helper, int cpus) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        helper.getBlockEntity(hostPos, CraftingTestContent.Host.class).setPatterns(List.of(STICKS));
        for (int i = 0; i < cpus; i++) {
            helper.setBlock(NetworkGameTests.at(4 + i), CpuContent.CORE_1.get());
        }
        return NetworkGameTests.controllerEntity(helper, core);
    }

    private static NetworkState network(GameTestHelper helper, ControllerBlockEntity controller) {
        NetworkState network = controller.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the controller is in no network"));
        }
        return network;
    }

    private static void cpusReady(GameTestHelper helper, ControllerBlockEntity controller, int cpus) {
        helper.assertValueEqual(JobQueue.freeCpus(network(helper, controller)), cpus, "free CPUs");
    }

    /** Plans 4 sticks now and queues them as a background job, or for a player with {@code player}. */
    private static UUID submit(GameTestHelper helper, NetworkState network, boolean player) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, 4);
        JobQueue.Request request = player
                ? JobQueue.Request.player(UUID.randomUUID())
                : new JobQueue.Request(Optional.empty(), 0, 0, null);
        JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan, request);
        return submission.job()
                .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())))
                .id();
    }

    private static JobState state(GameTestHelper helper, UUID id) {
        CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id);
        return job == null ? JobState.DONE : job.state();
    }

    /** The unfinished background jobs for sticks on the controller's network (other tests share the level). */
    private static List<CraftingJob> backgroundSticks(GameTestHelper helper, ControllerBlockEntity controller) {
        List<CraftingJob> jobs = new ArrayList<>();
        for (CraftingJob job : JobQueue.jobsOn(network(helper, controller))) {
            if (job.key().equals(STICK) && job.requester().isEmpty()) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    /**
     * Two CPUs, one kept free: the first background job takes a CPU, the second waits although one is free, and a
     * player's job takes that last one. With the player's job cancelled the background job still waits; with no CPU
     * kept free it starts at once.
     */
    private static void keepCpusForPlayers(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper, 2);
        UUID[] ids = new UUID[3];
        helper.startSequence().thenWaitUntil(() -> cpusReady(helper, controller, 2)).thenExecute(() -> {
            NetworkState network = network(helper, controller);
            NetworkGameTests.insert(network, PLANKS, 16);
            controller.setCraftLimits(new CraftLimits(CraftLimits.FOLLOW_CONFIG, 1));
            helper.assertValueEqual(JobQueue.freeForAutomation(network), 1, "two free, one kept");
            ids[0] = submit(helper, network, false);
            helper.assertValueEqual(state(helper, ids[0]), JobState.RUNNING, "the first background job runs");
            ids[1] = submit(helper, network, false);
            helper.assertValueEqual(state(helper, ids[1]), JobState.PLANNED, "the second waits for the kept CPU");
            helper.assertValueEqual(JobQueue.freeCpus(network), 1, "one CPU still free");
            helper.assertValueEqual(JobQueue.freeForAutomation(network), 0, "none for automation");
            ids[2] = submit(helper, network, true);
            helper.assertValueEqual(state(helper, ids[2]), JobState.RUNNING, "the player's job takes the last CPU");
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), ids[2]), "player's job cancelled");
            helper.assertValueEqual(state(helper, ids[1]), JobState.PLANNED, "still waiting with one CPU free");
            controller.setCraftLimits(new CraftLimits(CraftLimits.FOLLOW_CONFIG, 0));
            helper.assertValueEqual(state(helper, ids[1]), JobState.RUNNING, "starts once none is kept");
        }).thenSucceed();
    }

    /**
     * One CPU, busy with a background job; a second background job queues, then a player's. When the CPU frees, the
     * player's job takes it and the earlier background job keeps waiting.
     */
    private static void playersJumpQueue(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper, 1);
        helper.startSequence().thenWaitUntil(() -> cpusReady(helper, controller, 1)).thenExecute(() -> {
            NetworkState network = network(helper, controller);
            NetworkGameTests.insert(network, PLANKS, 16);
            controller.setCraftLimits(new CraftLimits(CraftLimits.FOLLOW_CONFIG, 0));
            UUID running = submit(helper, network, false);
            UUID background = submit(helper, network, false);
            UUID player = submit(helper, network, true);
            helper.assertValueEqual(state(helper, running), JobState.RUNNING, "the first job runs");
            helper.assertValueEqual(state(helper, player), JobState.PLANNED, "the player's job waits for the CPU");
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), running), "cancelled");
            helper.assertValueEqual(state(helper, player), JobState.RUNNING, "the player's job jumps ahead");
            helper.assertValueEqual(state(helper, background), JobState.PLANNED, "the background job still waits");
        }).thenSucceed();
    }

    /** A background request for 40 sticks with at most 16 per job queues a job for 16. */
    private static void capPerJob(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper, 0);
        List<BackgroundCrafting.Outcome> outcomes = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> network(helper, controller)).thenExecute(() -> {
            controller.setCraftLimits(new CraftLimits(16, CraftLimits.FOLLOW_CONFIG));
            helper.assertTrue(
                    QueueBackgroundCrafting.INSTANCE.submit(network(helper, controller),
                            new BackgroundCrafting.Request(STICK, 40, 0, 0, OwnerRecord.UNOWNED), outcomes::add),
                    "taken");
        }).thenWaitUntil(
                () -> helper.assertValueEqual(backgroundSticks(helper, controller).size(), 1, "one job queued"))
                .thenExecute(() -> helper.assertValueEqual(backgroundSticks(helper, controller).getFirst().amount(),
                        16L, "capped to the most per job"))
                .thenSucceed();
    }

    /**
     * A stock keeper keeping 40 sticks on the real queue, at most 16 per job and the only CPU kept free: its job asks
     * for 16 and waits; with no CPU kept free it runs.
     */
    private static void stockKeeper(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(3), StockKeeperBlockEntity.class);
        helper.setBlock(NetworkGameTests.at(4), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(4), CraftingTestContent.Host.class).setPatterns(List.of(STICKS));
        helper.setBlock(NetworkGameTests.at(5), CpuContent.CORE_1.get());
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        helper.startSequence().thenWaitUntil(() -> cpusReady(helper, controller, 1)).thenExecute(() -> {
            NetworkState network = network(helper, controller);
            NetworkGameTests.insert(network, PLANKS, 40);
            controller.setCraftLimits(new CraftLimits(16, 1));
            StockKeepingGameTests.QUEUE.real(network);
            keeper.worker().setRule(0, StockRule.EMPTY.withKey(Optional.of(STICK)).withKeep(40));
        }).thenWaitUntil(
                () -> helper.assertValueEqual(backgroundSticks(helper, controller).size(), 1, "the keeper's job"))
                .thenIdle(5).thenExecute(() -> {
                    CraftingJob job = backgroundSticks(helper, controller).getFirst();
                    helper.assertValueEqual(job.amount(), 16L, "capped to the most per job");
                    helper.assertValueEqual(job.state(), JobState.PLANNED, "the only CPU is kept free");
                    controller.setCraftLimits(new CraftLimits(16, 0));
                    helper.assertValueEqual(state(helper, job.id()), JobState.RUNNING, "runs with none kept");
                }).thenSucceed();
    }

    /**
     * The Crafting tab's buttons step the limits for the owner and do nothing for a stranger; the menu's data shows
     * them; a save and reload and the broken controller's item keep them.
     */
    private static void savedAndScreen(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper, 0);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "limits_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "limits_stranger");
        controller.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> network(helper, controller)).thenExecute(() -> {
            ControllerMenu menu = ControllerMenu.server(1, controller, owner, payload -> {
            });
            controller.setCraftLimits(new CraftLimits(1024, 0));
            helper.assertTrue(!menu.clickMenuButton(stranger, ControllerMenu.KEPT_FREE_UP), "stranger's click");
            helper.assertValueEqual(controller.craftLimits(), new CraftLimits(1024, 0), "unchanged");
            helper.assertTrue(menu.clickMenuButton(owner, ControllerMenu.MAX_PER_JOB_DOWN), "down");
            helper.assertTrue(menu.clickMenuButton(owner, ControllerMenu.KEPT_FREE_UP), "kept up");
            helper.assertTrue(menu.clickMenuButton(owner, ControllerMenu.KEPT_FREE_UP), "kept up again");
            CraftLimits set = new CraftLimits(256, 2);
            helper.assertValueEqual(controller.craftLimits(), set, "stepped");
            helper.assertValueEqual(menu.limitData(ControllerMenu.DATA_MAX_PER_JOB), 256, "data: per job");
            helper.assertValueEqual(menu.limitData(ControllerMenu.DATA_KEPT_FREE), 2, "data: kept free");
            helper.assertValueEqual(network(helper, controller).craftLimits(), set, "the network's limits");

            CompoundTag saved = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
            ControllerBlockEntity copy = new ControllerBlockEntity(controller.getBlockPos(),
                    controller.getBlockState());
            copy.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(copy.craftLimits(), set, "after a reload");

            List<ItemStack> drops = Block.getDrops(controller.getBlockState(), helper.getLevel(),
                    controller.getBlockPos(), controller);
            ItemStack item = drops.getFirst();
            helper.assertTrue(set.equals(item.get(ModDataComponents.CRAFT_LIMITS.get())), "in the item");
            BlockPos elsewhere = NetworkGameTests.at(8);
            helper.setBlock(elsewhere, controller.getBlockState().getBlock());
            ControllerBlockEntity placed = NetworkGameTests.controllerEntity(helper, elsewhere);
            placed.applyComponentsFromItemStack(item);
            helper.assertValueEqual(placed.craftLimits(), set, "back from the item");

            helper.assertTrue(menu.clickMenuButton(owner, ControllerMenu.LIMITS_DEFAULT), "defaults");
            helper.assertValueEqual(controller.craftLimits(), CraftLimits.DEFAULT, "back to the config");
        }).thenSucceed();
    }

    /** The why reasons the last run of graph 0 gave for node 2. */
    private static List<String> reasons(LogicControllerBlockEntity entity) {
        List<String> reasons = new ArrayList<>();
        RunTrace trace = entity.worker().evaluator(0).orElseThrow().trace();
        for (int i = 0; i < trace.size(); i++) {
            if (trace.node(i) == 2) {
                reasons.add(trace.reason(i));
            }
        }
        return reasons;
    }

    /**
     * A logic rule asks for 5000 stone (the stand-in queue takes anything; sticks are the player's job) on a network
     * with at most 64 per job. With both of its CPUs kept free the stand-in queue gets 64 and the why line says the
     * amount was capped and that automation can use no CPU; with one kept and the other busy with a player's job it
     * says the job will wait for the kept CPU. Logic controller at x = 3, CPUs at x = 4 and 6, the stick pattern host
     * at x = 5.
     */
    private static void logicWhy(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "limits_logic");
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(5), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(5), CraftingTestContent.Host.class).setPatterns(List.of(STICKS));
        helper.setBlock(NetworkGameTests.at(6), CpuContent.CORE_1.get());
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, NetworkGameTests.at(1));
        BlockPos top = NetworkGameTests.at(3).above();
        helper.startSequence().thenWaitUntil(() -> cpusReady(helper, controller, 2)).thenExecute(() -> {
            controller.setCraftLimits(new CraftLimits(64, 2));
            entity.worker()
                    .setGraph(0, "Craft",
                            new LogicControllerGameTests.Graph()
                                    .node(1, NodeTypes.REDSTONE_CHANGES,
                                            s -> s)
                                    .node(2, NodeTypes.REQUEST_CRAFT,
                                            s -> s.withItem("item", LogicKey.of(ItemKey.of(Items.STONE)))
                                                    .withNumber("amount", 5000))
                                    .wire(1, "changed", 2, "do").build());
        }).thenWaitUntil(() -> helper.assertTrue(
                entity.worker().evaluator(0).map(evaluator -> evaluator.runs() > 0).orElse(false), "ran once"))
                .thenExecute(() -> helper.setBlock(top, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        StockKeepingGameTests.QUEUE.calls(network(helper, controller)).size(), 1, "one request"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, controller);
                    helper.assertValueEqual(StockKeepingGameTests.QUEUE.calls(network).getFirst().request().amount(),
                            64L, "capped");
                    helper.assertValueEqual(reasons(entity),
                            List.of(LogicLang.WHY_CRAFT_CAPPED, LogicLang.WHY_CRAFT_NO_CPU), "why lines, all kept");
                    StockKeepingGameTests.QUEUE.calls(network).getFirst().done()
                            .accept(BackgroundCrafting.Outcome.DONE);
                    controller.setCraftLimits(new CraftLimits(64, 1));
                    NetworkGameTests.insert(network, PLANKS, 8);
                    submit(helper, network, true);
                    helper.assertValueEqual(JobQueue.freeCpus(network), 1, "one CPU busy, one free");
                    helper.setBlock(top, Blocks.AIR);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        StockKeepingGameTests.QUEUE.calls(network(helper, controller)).size(), 2, "second request"))
                .thenExecute(() -> helper.assertValueEqual(reasons(entity),
                        List.of(LogicLang.WHY_CRAFT_CAPPED, LogicLang.WHY_CRAFT_KEPT_FREE), "why lines, one kept"))
                .thenSucceed();
    }

    /**
     * Crafting status's "free CPUs for automation" follows a limits edit at once, with no job changing: one CPU, none
     * kept reads 1; one kept reads 0 while "free CPUs" stays 1.
     */
    private static void logicReadsAutoCpus(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper, 1);
        helper.setBlock(NetworkGameTests.at(5), LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(5));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "limits_reader")));
        helper.startSequence().thenWaitUntil(() -> cpusReady(helper, controller, 1)).thenExecute(() -> {
            controller.setCraftLimits(new CraftLimits(CraftLimits.FOLLOW_CONFIG, 0));
            entity.worker().setGraph(0, "CPUs",
                    new LogicControllerGameTests.Graph().node(1, NodeTypes.CRAFT_STATUS, s -> s).build());
        }).thenWaitUntil(() -> helper.assertValueEqual(cpuOut(helper, entity, "auto_cpus"), 1L, "none kept"))
                .thenIdle(40)
                // Settled: nothing else changes now, so only the limits edit can update the read value.
                .thenExecute(() -> controller.setCraftLimits(new CraftLimits(CraftLimits.FOLLOW_CONFIG, 1))).thenIdle(3)
                .thenExecute(() -> {
                    helper.assertValueEqual(cpuOut(helper, entity, "auto_cpus"), 0L, "one kept");
                    helper.assertValueEqual(cpuOut(helper, entity, "free_cpus"), 1L, "still free");
                }).thenSucceed();
    }

    private static long cpuOut(GameTestHelper helper, LogicControllerBlockEntity entity, String output) {
        LogicEvaluator<LogicKey> evaluator = entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
        return evaluator.value(evaluator.graph().slot(1, output));
    }

    /**
     * The Crafting tab's data slots reach the client whole: the menu's values go through NeoForge's data payload (a
     * VAR_INT value, unlike vanilla's 16-bit packet) into a client menu, the 1,000,000 maximum included, and the "all
     * CPUs kept" case shows as more kept than CPUs.
     */
    private static void dataSlotsSync(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper, 1);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "limits_sync");
        helper.startSequence().thenWaitUntil(() -> cpusReady(helper, controller, 1)).thenExecute(() -> {
            controller.setCraftLimits(new CraftLimits(CraftLimits.MAX_PER_JOB, 3));
            ControllerMenu server = ControllerMenu.server(7, controller, owner, payload -> {
            });
            int[][] sent = new int[1][];
            server.setSynchronizer(new ContainerSynchronizer() {
                @Override
                public void sendInitialData(AbstractContainerMenu container, List<ItemStack> slotItems,
                        ItemStack carried, int[] dataSlots) {
                    sent[0] = dataSlots.clone();
                }

                @Override
                public void sendSlotChange(AbstractContainerMenu container, int slotIndex, ItemStack itemStack) {
                }

                @Override
                public void sendCarriedChange(AbstractContainerMenu container, ItemStack itemStack) {
                }

                @Override
                public void sendDataChange(AbstractContainerMenu container, int id, int value) {
                }

                @Override
                public RemoteSlot createSlot() {
                    return RemoteSlot.PLACEHOLDER;
                }
            });
            int[] values = sent[0];
            if (values == null) {
                throw helper.assertionException(Component.literal("no initial data"));
            }
            RegistryFriendlyByteBuf open = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            open.writeBlockPos(controller.getBlockPos());
            ControllerMenu client = ControllerMenu.fromNetwork(7, owner.getInventory(), open);
            for (int id = 0; id < values.length; id++) {
                RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                        helper.getLevel().registryAccess());
                AdvancedContainerSetDataPayload.STREAM_CODEC.encode(wire,
                        new AdvancedContainerSetDataPayload((byte) 7, (short) id, values[id]));
                AdvancedContainerSetDataPayload got = AdvancedContainerSetDataPayload.STREAM_CODEC.decode(wire);
                client.setData(got.dataId(), got.value());
            }
            helper.assertValueEqual(client.limitData(ControllerMenu.DATA_MAX_PER_JOB), CraftLimits.MAX_PER_JOB,
                    "the maximum arrives whole");
            helper.assertValueEqual(client.limitData(ControllerMenu.DATA_KEPT_FREE), 3, "kept free");
            helper.assertValueEqual(client.limitData(ControllerMenu.DATA_CPUS), 1, "the network's CPUs");
            helper.assertValueEqual(client.limitData(ControllerMenu.DATA_AUTO_CPUS), 0, "none for automation");
        }).thenSucceed();
    }
}
