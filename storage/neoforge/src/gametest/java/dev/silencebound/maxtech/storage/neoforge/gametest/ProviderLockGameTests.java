package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterBlock;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterBlockEntity;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDispatcher;
import dev.silencebound.maxtech.storage.crafting.provider.DispatchStrategy;
import dev.silencebound.maxtech.storage.crafting.provider.LockMode;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderPart;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Pattern provider lock modes (B09 item 26) with real jobs: locked by a signal and without one (the job waits, names
 * the lock and runs on once it lets go), one batch per redstone pulse, one batch at a time until its result returns, a
 * job that uses a second provider while a part is locked, and the mode and a holding lock across a save, the item and
 * the blueprint card. A controller at x = 1, a storage device at x = 2 with a CPU core above it (batch 1), the provider
 * south of the storage device facing south, its assembler or barrel beyond it, and the redstone block east of the
 * provider.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ProviderLockGameTests {
    private static final String[] NAMES = {"provider_lock_signal", "provider_lock_no_signal", "provider_lock_pulse",
            "provider_lock_result", "provider_lock_result_job", "provider_lock_second_provider",
            "provider_lock_persistence", "provider_lock_busy_frees", "provider_lock_crafter"};
    private static final StackKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey RAW_IRON = ItemKey.of(Items.RAW_IRON);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final BlockPos PROVIDER = NetworkGameTests.at(2).south();
    private static final BlockPos MACHINE = NetworkGameTests.at(2).south(2);
    private static final BlockPos SIGNAL = NetworkGameTests.at(2).south().east();
    /** Ticks a held job is watched before the lock lets go; a free provider finishes the job well within. */
    private static final int HOLD_TICKS = 40;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], ProviderLockGameTests::signal);
        register(NAMES[1], ProviderLockGameTests::noSignal);
        register(NAMES[2], ProviderLockGameTests::pulse);
        register(NAMES[3], ProviderLockGameTests::result);
        register(NAMES[4], ProviderLockGameTests::resultJob);
        register(NAMES[5], ProviderLockGameTests::secondProvider);
        register(NAMES[6], ProviderLockGameTests::persistence);
        register(NAMES[7], ProviderLockGameTests::busyFrees);
        register(NAMES[8], ProviderLockGameTests::crafter);
    }

    private ProviderLockGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("provider_lock"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    /** One oak log to four oak planks. */
    private static CraftingPattern planks() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(LOG), 1, Optional.empty(), false)),
                List.of(new PatternOutput(PLANKS, 4, 1f)), Optional.of(Identifier.withDefaultNamespace("crafting")),
                Optional.of(Identifier.withDefaultNamespace("oak_planks")));
    }

    /** Raw iron into a barrel standing in for a machine, an ingot expected back. */
    private static CraftingPattern smelting() {
        return new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(new PatternInput(new PatternIngredient.Exact(RAW_IRON), 1, Optional.empty(), false)),
                List.of(new PatternOutput(IRON, 1, 1f)), Optional.empty(), Optional.empty());
    }

    /** Controller, power, storage device, CPU core and the provider at {@link #PROVIDER} facing {@code machine}. */
    private static PatternProviderBlockEntity setUp(GameTestHelper helper, CraftingPattern pattern,
            net.minecraft.world.level.block.Block machine) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        helper.setBlock(MACHINE, machine);
        helper.setBlock(PROVIDER, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.SOUTH));
        PatternProviderBlockEntity provider = helper.getBlockEntity(PROVIDER, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(pattern));
        return provider;
    }

    private static PatternProviderBlockEntity planksProvider(GameTestHelper helper) {
        return setUp(helper, planks(), AssemblerContent.ASSEMBLER.get());
    }

    private static NetworkState network(GameTestHelper helper, PatternProviderBlockEntity provider, StackKey output) {
        NetworkState network = provider.network();
        if (network == null || !network.patterns().isCraftable(output)) {
            throw helper.assertionException(Component.literal("the provider's pattern is not on the network"));
        }
        return network;
    }

    /** Puts {@code logs} logs into storage and starts a job for four planks each; returns its id. */
    private static UUID job(GameTestHelper helper, NetworkState network, long logs) {
        NetworkGameTests.insert(network, LOG, logs);
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, PLANKS, logs * 4);
        helper.assertTrue(plan.tree().complete(), "the plan is complete");
        JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                JobQueue.Request.player(UUID.randomUUID()));
        return submission.job()
                .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())))
                .id();
    }

    /** Crafts of the job's root step handed to providers so far. */
    private static long sent(GameTestHelper helper, UUID id) {
        JobRun run = JobQueue.progress(helper.getLevel(), id);
        if (run == null) {
            throw helper.assertionException(Component.literal("the job has no progress"));
        }
        return run.sent(0);
    }

    /** The job still runs (not partial, not done) and names {@code reason} as what holds it up. */
    private static void held(GameTestHelper helper, NetworkState network, UUID id, StuckReason reason) {
        CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id);
        if (job == null) {
            throw helper.assertionException(Component.literal("the job ended"));
        }
        helper.assertValueEqual(job.state(), JobState.RUNNING, "the job waits, still running");
        List<StuckReason> reasons = new ArrayList<>();
        for (var stuck : CraftingSession.stuck(network, job, JobQueue.progress(helper.getLevel(), id))) {
            reasons.add(stuck.reason());
        }
        helper.assertTrue(reasons.contains(reason), "the job names " + reason + ", got " + reasons);
    }

    private static void finished(GameTestHelper helper, NetworkState network, UUID id, long planks) {
        helper.assertTrue(CraftingJobs.get(helper.getLevel()).get(id) == null, "the job finished");
        helper.assertValueEqual(network.contents().amount(PLANKS), planks, "the planks stored");
    }

    /** Locked while the provider gets a signal: the job waits and says why; without the signal it finishes. */
    private static void signal(GameTestHelper helper) {
        PatternProviderBlockEntity provider = planksProvider(helper);
        provider.logic().setLockMode(LockMode.WITH_SIGNAL);
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, PLANKS)).thenExecute(() -> {
            helper.assertTrue(provider.logic().locked() == StuckReason.LOCKED_SIGNAL, "locked by the signal");
            id[0] = job(helper, network(helper, provider, PLANKS), 2);
        }).thenIdle(HOLD_TICKS).thenExecute(() -> {
            helper.assertValueEqual(sent(helper, id[0]), 0L, "nothing pushed while locked");
            held(helper, network(helper, provider, PLANKS), id[0], StuckReason.LOCKED_SIGNAL);
            helper.setBlock(SIGNAL, Blocks.AIR);
        }).thenWaitUntil(() -> finished(helper, network(helper, provider, PLANKS), id[0], 8)).thenSucceed();
    }

    /** Locked without a signal: the job waits and says why; a signal lets it finish. */
    private static void noSignal(GameTestHelper helper) {
        PatternProviderBlockEntity provider = planksProvider(helper);
        provider.logic().setLockMode(LockMode.WITHOUT_SIGNAL);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, PLANKS)).thenExecute(() -> {
            helper.assertTrue(provider.logic().locked() == StuckReason.LOCKED_NO_SIGNAL, "locked without a signal");
            id[0] = job(helper, network(helper, provider, PLANKS), 2);
        }).thenIdle(HOLD_TICKS).thenExecute(() -> {
            helper.assertValueEqual(sent(helper, id[0]), 0L, "nothing pushed while locked");
            held(helper, network(helper, provider, PLANKS), id[0], StuckReason.LOCKED_NO_SIGNAL);
            helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> finished(helper, network(helper, provider, PLANKS), id[0], 8)).thenSucceed();
    }

    /**
     * Until a pulse: three one-craft batches (the core's batch is 1). The first goes at once, each later one only after
     * a rising edge; a falling edge or a steady signal lets nothing through.
     */
    private static void pulse(GameTestHelper helper) {
        PatternProviderBlockEntity provider = planksProvider(helper);
        provider.logic().setLockMode(LockMode.UNTIL_PULSE);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, PLANKS)).thenExecute(() -> {
            helper.assertTrue(provider.logic().locked() == null, "free before the first batch");
            id[0] = job(helper, network(helper, provider, PLANKS), 3);
        }).thenIdle(HOLD_TICKS).thenExecute(() -> {
            helper.assertValueEqual(sent(helper, id[0]), 1L, "one batch before any pulse");
            held(helper, network(helper, provider, PLANKS), id[0], StuckReason.LOCKED_PULSE);
            helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        }).thenIdle(HOLD_TICKS).thenExecute(() -> {
            helper.assertValueEqual(sent(helper, id[0]), 2L,
                    "one more batch for the pulse, none for the steady signal");
            helper.setBlock(SIGNAL, Blocks.AIR);
        }).thenIdle(HOLD_TICKS).thenExecute(() -> {
            helper.assertValueEqual(sent(helper, id[0]), 2L, "nothing for the falling edge");
            held(helper, network(helper, provider, PLANKS), id[0], StuckReason.LOCKED_PULSE);
            helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> finished(helper, network(helper, provider, PLANKS), id[0], 12)).thenSucceed();
    }

    /**
     * Until the result: after a batch into the barrel the provider takes nothing more and names the lock; once the
     * ingot is in the barrel and pulled into the network it takes the next batch.
     */
    private static void result(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper, smelting(), Blocks.BARREL);
        provider.logic().setLockMode(LockMode.UNTIL_RESULT);
        provider.logic().setBlocking(false);
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, IRON)).thenExecute(() -> {
            NetworkState network = network(helper, provider, IRON);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, smelting(), 1, inputs(RAW_IRON, 1), DispatchStrategy.FASTEST),
                    1, "the first batch");
            helper.assertTrue(provider.logic().stuck() == StuckReason.LOCKED_RESULT, "waits for the result");
            Object2LongOpenHashMap<StackKey> held = inputs(RAW_IRON, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, smelting(), 1, held, DispatchStrategy.FASTEST),
                    0, "no second batch before the result");
            helper.assertValueEqual(held.getLong(RAW_IRON), 1L, "the input stays with the caller");
            helper.getBlockEntity(MACHINE, BarrelBlockEntity.class).setItem(20, new ItemStack(Items.IRON_INGOT));
        }).thenWaitUntil(() -> helper.assertValueEqual(network(helper, provider, IRON).contents().amount(IRON), 1L,
                "the ingot pulled into the network")).thenExecute(() -> {
                    helper.assertTrue(provider.logic().locked() == null, "unlocked by the result");
                    helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider, IRON), smelting(), 1,
                            inputs(RAW_IRON, 1), DispatchStrategy.FASTEST), 1, "the next batch goes");
                }).thenSucceed();
    }

    /** Until the result with a job of three batches: each batch waits for the one before and the job finishes. */
    private static void resultJob(GameTestHelper helper) {
        PatternProviderBlockEntity provider = planksProvider(helper);
        provider.logic().setLockMode(LockMode.UNTIL_RESULT);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, PLANKS))
                .thenExecute(() -> id[0] = job(helper, network(helper, provider, PLANKS), 3))
                .thenWaitUntil(() -> finished(helper, network(helper, provider, PLANKS), id[0], 12))
                .thenExecute(
                        () -> helper.assertTrue(provider.logic().locked() == null, "free once the last result came"))
                .thenSucceed();
    }

    /**
     * A provider part on a cable, locked by a signal, holds the same pattern as a free provider block: the job runs on
     * the block alone and the part's assembler never crafts.
     */
    private static void secondProvider(GameTestHelper helper) {
        PatternProviderBlockEntity free = planksProvider(helper);
        NetworkGameTests.cables(helper, 3, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "provider_lock_part");
        player.setPos(helper.absoluteVec(NetworkGameTests.at(3).above().getCenter()));
        PartGameTests.usePart(helper, player, 3, Direction.NORTH,
                new ItemStack(ProviderContent.PROVIDER_PART_ITEM.get()));
        helper.setBlock(NetworkGameTests.at(3).north(), AssemblerContent.ASSEMBLER.get());
        PatternProviderPart[] part = new PatternProviderPart[1];
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> {
            if (!(PartGameTests.part(helper, PartGameTests.parts(helper, 3),
                    Direction.NORTH) instanceof PatternProviderPart found)) {
                throw helper.assertionException(Component.literal("no provider part"));
            }
            part[0] = found;
        }).thenExecute(() -> {
            part[0].logic().slots().setItem(0, PatternItem.encode(planks()));
            part[0].logic().setLockMode(LockMode.WITH_SIGNAL);
            // East of the free provider and south of the part's cable: only the part's mode reads it.
            helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, free, PLANKS);
            helper.assertValueEqual(network.patterns().patternsFor(PLANKS).size(), 2, "both hold the pattern");
            helper.assertTrue(part[0].logic().locked() == StuckReason.LOCKED_SIGNAL, "the part is locked");
        }).thenExecute(() -> id[0] = job(helper, network(helper, free, PLANKS), 3))
                .thenWaitUntil(() -> finished(helper, network(helper, free, PLANKS), id[0], 12)).thenExecute(() -> {
                    helper.assertValueEqual(part[0].logic().lastDispatch(), 0L, "the locked part got nothing");
                    helper.assertTrue(free.logic().lastDispatch() > 0, "the free provider did the work");
                }).thenSucceed();
    }

    /**
     * The mode and a holding result lock come back after a save; the item keeps the mode but not the lock; the
     * blueprint card copies the mode onto another provider.
     */
    private static void persistence(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper, smelting(), Blocks.BARREL);
        provider.logic().setLockMode(LockMode.UNTIL_RESULT);
        helper.startSequence().thenWaitUntil(() -> network(helper, provider, IRON)).thenExecute(() -> {
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider, IRON), smelting(), 1,
                    inputs(RAW_IRON, 1), DispatchStrategy.FASTEST), 1, "a batch went");
            helper.assertTrue(provider.logic().locked() == StuckReason.LOCKED_RESULT, "locked");
            CompoundTag saved = provider.saveWithoutMetadata(helper.getLevel().registryAccess());
            PatternProviderBlockEntity copy = new PatternProviderBlockEntity(provider.getBlockPos(),
                    provider.getBlockState());
            copy.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(copy.logic().lockMode(), LockMode.UNTIL_RESULT, "the mode after a reload");
            helper.assertTrue(copy.logic().locked() == StuckReason.LOCKED_RESULT, "the lock after a reload");

            ItemStack drop = new ItemStack(ProviderContent.PROVIDER.item().get());
            drop.applyComponents(provider.collectComponents());
            helper.assertTrue(drop.get(ModDataComponents.PATTERN_PROVIDER_LOCK.get()) == LockMode.UNTIL_RESULT,
                    "the mode in the item");
            BlockPos other = NetworkGameTests.at(4);
            helper.setBlock(other, ProviderContent.PROVIDER.get());
            PatternProviderBlockEntity placed = helper.getBlockEntity(other, PatternProviderBlockEntity.class);
            placed.applyComponents(drop.getComponents(), DataComponentPatch.EMPTY);
            helper.assertValueEqual(placed.logic().lockMode(), LockMode.UNTIL_RESULT, "the mode when placed");
            helper.assertTrue(placed.logic().locked() == null, "a placed provider is not locked");

            provider.logic().setLockMode(LockMode.UNTIL_PULSE);
            placed.copyableSettings().paste().accept(provider.copyableSettings().copy().get());
            helper.assertValueEqual(placed.logic().lockMode(), LockMode.UNTIL_PULSE, "the blueprint copies the mode");
        }).thenSucceed();
    }

    /**
     * Two providers hold the iron pattern: one locked by a signal, the other busy (blocking) with a gold batch no job
     * waits for. The iron job is held; once the busy provider's gold comes back it is woken and sends its batch there,
     * the locked one staying locked and unused.
     */
    private static void busyFrees(GameTestHelper helper) {
        StackKey rawGold = ItemKey.of(Items.RAW_GOLD);
        CraftingPattern gold = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(new PatternInput(new PatternIngredient.Exact(rawGold), 1, Optional.empty(), false)),
                List.of(new PatternOutput(ItemKey.of(Items.GOLD_INGOT), 1, 1f)), Optional.empty(), Optional.empty());
        PatternProviderBlockEntity locked = setUp(helper, smelting(), Blocks.BARREL);
        locked.logic().setLockMode(LockMode.WITH_SIGNAL);
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        BlockPos busyPos = NetworkGameTests.at(3);
        BlockPos busyBarrel = NetworkGameTests.at(4);
        helper.setBlock(busyBarrel, Blocks.BARREL);
        helper.setBlock(busyPos, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity busy = helper.getBlockEntity(busyPos, PatternProviderBlockEntity.class);
        busy.logic().slots().setItem(0, PatternItem.encode(smelting()));
        busy.logic().slots().setItem(1, PatternItem.encode(gold));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = network(helper, locked, IRON);
            helper.assertValueEqual(network.patterns().patternsFor(IRON).size(), 2, "both hold the iron pattern");
            helper.assertTrue(locked.logic().locked() == StuckReason.LOCKED_SIGNAL, "one is locked");
        }).thenExecute(() -> {
            NetworkState network = network(helper, locked, IRON);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, gold, 1, inputs(rawGold, 1), DispatchStrategy.FASTEST), 1,
                    "the gold batch");
            helper.assertTrue(!busy.logic().canAccept(smelting()), "blocking keeps the other one busy");
            NetworkGameTests.insert(network, RAW_IRON, 1);
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, IRON, 1);
            JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                    JobQueue.Request.player(UUID.randomUUID()));
            id[0] = submission.job()
                    .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())))
                    .id();
        }).thenIdle(HOLD_TICKS).thenExecute(() -> {
            helper.assertValueEqual(sent(helper, id[0]), 0L, "nothing sent while one is locked and one busy");
            held(helper, network(helper, locked, IRON), id[0], StuckReason.LOCKED_SIGNAL);
            BarrelBlockEntity barrel = helper.getBlockEntity(busyBarrel, BarrelBlockEntity.class);
            barrel.setItem(0, ItemStack.EMPTY);
            barrel.setItem(5, new ItemStack(Items.GOLD_INGOT));
        }).thenWaitUntil(() -> helper.assertValueEqual(sent(helper, id[0]), 1L, "the job woke and sent its batch"))
                .thenExecute(() -> {
                    helper.assertValueEqual(locked.logic().lastDispatch(), 0L, "the locked provider got nothing");
                    helper.assertTrue(locked.logic().locked() == StuckReason.LOCKED_SIGNAL, "and stays locked");
                    helper.assertTrue(
                            helper.getBlockEntity(busyBarrel, BarrelBlockEntity.class).getItem(0).is(Items.RAW_IRON),
                            "the batch went to the freed provider");
                }).thenSucceed();
    }

    /**
     * The crafter's lock: a job waits while the signal locks it and finishes once it is gone; the blueprint card copies
     * the mode onto another crafter but never its patterns or results.
     */
    private static void crafter(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        BlockPos at = NetworkGameTests.at(3);
        helper.setBlock(at,
                CrafterContent.CRAFTER.get().defaultBlockState().setValue(CrafterBlock.FACING, Direction.SOUTH));
        CrafterBlockEntity crafter = helper.getBlockEntity(at, CrafterBlockEntity.class);
        crafter.logic().slots().setItem(0, PatternItem.encode(planks()));
        crafter.logic().setLockMode(LockMode.WITH_SIGNAL);
        // South of the crafter.
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> crafterNetwork(helper, crafter)).thenExecute(() -> {
            helper.assertTrue(crafter.logic().locked() == StuckReason.LOCKED_SIGNAL, "the crafter is locked");
            id[0] = job(helper, crafterNetwork(helper, crafter), 2);
        }).thenIdle(HOLD_TICKS).thenExecute(() -> {
            helper.assertValueEqual(sent(helper, id[0]), 0L, "nothing crafted while locked");
            held(helper, crafterNetwork(helper, crafter), id[0], StuckReason.LOCKED_SIGNAL);
            BlockPos otherPos = NetworkGameTests.at(6);
            helper.setBlock(otherPos, CrafterContent.CRAFTER.get());
            CrafterBlockEntity other = helper.getBlockEntity(otherPos, CrafterBlockEntity.class);
            var copied = crafter.copyableSettings().copy().get();
            helper.assertTrue(!copied.has(DataComponents.CONTAINER), "the patterns are not copied");
            helper.assertTrue(!copied.has(ModDataComponents.CRAFTER_BUFFER.get()), "the results are not copied");
            other.copyableSettings().paste().accept(copied);
            helper.assertValueEqual(other.logic().lockMode(), LockMode.WITH_SIGNAL, "the mode is copied");
            helper.assertTrue(other.logic().slots().isEmpty(), "no pattern appeared");
            helper.assertTrue(crafter.copyableSettings().kind().equals(other.copyableSettings().kind()),
                    "crafters share a kind");
            helper.assertTrue(!crafter.copyableSettings().kind().equals(ProviderContent.SETTINGS_KIND),
                    "a crafter's kind is not a provider's");
            helper.setBlock(SIGNAL, Blocks.AIR);
        }).thenWaitUntil(() -> finished(helper, crafterNetwork(helper, crafter), id[0], 8)).thenSucceed();
    }

    private static NetworkState crafterNetwork(GameTestHelper helper, CrafterBlockEntity crafter) {
        NetworkState network = crafter.network();
        if (network == null || !network.patterns().isCraftable(PLANKS)) {
            throw helper.assertionException(Component.literal("the crafter's pattern is not on the network"));
        }
        return network;
    }

    private static Object2LongOpenHashMap<StackKey> inputs(StackKey key, long amount) {
        Object2LongOpenHashMap<StackKey> map = new Object2LongOpenHashMap<>();
        map.put(key, amount);
        return map;
    }
}
