package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDispatcher;
import dev.silencebound.maxtech.storage.crafting.provider.DispatchStrategy;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Why a craft is stuck (M7C item 9): a provider names a full machine, an output storage will not take, a machine
 * without FE and a network without power, and the job view's status lists the provider holding a running job up until
 * it is free again. A controller at x = 1, a test storage device at x = 2 (left out where storage must be missing) with
 * a CPU above it, the provider at x = 3 facing east and its machine at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StuckCraftGameTests {
    private static final String[] NAMES = {"stuck_machine_full_in_job_view", "stuck_output_blocked",
            "stuck_machine_without_energy", "stuck_network_without_power"};
    private static final StackKey RAW_IRON = ItemKey.of(Items.RAW_IRON);
    private static final StackKey COAL = ItemKey.of(Items.COAL);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], StuckCraftGameTests::machineFullInJobView);
        register(NAMES[1], StuckCraftGameTests::outputBlocked);
        register(NAMES[2], StuckCraftGameTests::machineWithoutEnergy);
        register(NAMES[3], StuckCraftGameTests::networkWithoutPower);
    }

    private StuckCraftGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("stuck"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 300);
        }
    }

    private static PatternInput in(StackKey key) {
        return new PatternInput(new PatternIngredient.Exact(key), 1, Optional.empty(), false);
    }

    private static CraftingPattern processing(List<PatternInput> inputs, StackKey output) {
        return new CraftingPattern(CraftingPattern.Kind.PROCESSING, inputs, List.of(new PatternOutput(output, 1, 1f)),
                Optional.empty(), Optional.empty());
    }

    /** Controller (powered when {@code fe} > 0), storage unless {@code storage} is false, provider and its pattern. */
    private static PatternProviderBlockEntity setUp(GameTestHelper helper, long fe, boolean storage,
            CraftingPattern pattern) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        if (fe > 0) {
            NetworkGameTests.power(helper, core, fe);
        }
        if (storage) {
            NetworkGameTests.device(helper, NetworkGameTests.at(2));
        } else {
            NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        }
        helper.setBlock(NetworkGameTests.at(3), ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(NetworkGameTests.at(3),
                PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(pattern));
        return provider;
    }

    private static NetworkState network(GameTestHelper helper, PatternProviderBlockEntity provider) {
        NetworkState network = provider.network();
        if (network == null || network.patterns().providerCount() == 0) {
            throw helper.assertionException(Component.literal("the provider is in no network"));
        }
        return network;
    }

    private static Object2LongOpenHashMap<StackKey> inputs(StackKey key, long amount) {
        Object2LongOpenHashMap<StackKey> map = new Object2LongOpenHashMap<>();
        map.put(key, amount);
        return map;
    }

    /**
     * A chest full of stone takes no raw iron: the provider says the machine is full, and the job, which stopped
     * partial since nothing could be sent, names the provider and the reason in its status; once the chest has room a
     * resume sends the batch and the line is gone.
     */
    private static void machineFullInJobView(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(4), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(NetworkGameTests.at(4), ChestBlockEntity.class);
        for (int i = 0; i < chest.getContainerSize(); i++) {
            chest.setItem(i, new ItemStack(Items.STONE, 64));
        }
        PatternProviderBlockEntity provider = setUp(helper, 1_000_000, true, processing(List.of(in(RAW_IRON)), IRON));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            NetworkGameTests.insert(network, RAW_IRON, 1);
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, IRON, 1);
            id[0] = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                    JobQueue.Request.player(UUID.randomUUID())).job().orElseThrow().id();
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, provider);
            CraftingJob job = Objects.requireNonNull(CraftingJobs.get(helper.getLevel()).get(id[0]));
            JobRun run = JobQueue.progress(helper.getLevel(), id[0]);
            List<RequestPayloads.Stuck> stuck = CraftingSession.stuck(network, job, run);
            helper.assertValueEqual(stuck.size(), 1, "one stuck provider");
            helper.assertValueEqual(stuck.getFirst().reason(), StuckReason.MACHINE_FULL, "the machine is full");
            helper.assertValueEqual(stuck.getFirst().pos(), helper.absolutePos(NetworkGameTests.at(3)), "where");
            helper.assertValueEqual(stuck.getFirst().key(), IRON, "the held-up step");
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            RequestPayloads.JobStatus status = new RequestPayloads.JobStatus(1, id[0], List.of(), stuck);
            RequestPayloads.JobStatus.CODEC.encode(buffer, status);
            helper.assertValueEqual(RequestPayloads.JobStatus.CODEC.decode(buffer), status, "status codec");
        }).thenExecute(() -> {
            // Nothing could be sent, so the job stopped partial; with room in the chest a resume sends the batch.
            helper.assertValueEqual(Objects.requireNonNull(CraftingJobs.get(helper.getLevel()).get(id[0])).state(),
                    JobState.PARTIAL, "stopped partial");
            chest.setItem(0, ItemStack.EMPTY);
            helper.assertTrue(JobQueue.resume(helper.getLevel(), id[0]), "resumed");
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, provider);
            helper.assertTrue(chest.getItem(0).is(Items.RAW_IRON), "the batch went in");
            CraftingJob job = Objects.requireNonNull(CraftingJobs.get(helper.getLevel()).get(id[0]));
            helper.assertTrue(
                    CraftingSession.stuck(network, job, JobQueue.progress(helper.getLevel(), id[0])).isEmpty(),
                    "nothing stuck once it has room");
        }).thenExecute(() -> JobQueue.cancel(helper.getLevel(), id[0])).thenSucceed();
    }

    /** Smelted iron in the furnace with no storage on the network: the output is blocked. */
    private static void outputBlocked(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        PatternProviderBlockEntity provider = setUp(helper, 1_000_000, false,
                processing(List.of(in(RAW_IRON), in(COAL)), IRON));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            Object2LongOpenHashMap<StackKey> given = inputs(RAW_IRON, 1);
            given.put(COAL, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider),
                    provider.logic().patterns().getFirst(), 1, given, DispatchStrategy.FASTEST), 1, "one craft in");
            FurnaceBlockEntity furnace = helper.getBlockEntity(NetworkGameTests.at(4), FurnaceBlockEntity.class);
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT));
        }).thenWaitUntil(() -> helper.assertTrue(provider.logic().stuck() == StuckReason.OUTPUT_BLOCKED,
                "the output is blocked" + ": " + provider.logic().stuck())).thenSucceed();
    }

    /** Silicon in a lithograph without FE: once the provider looks for the output, it says there is no power. */
    private static void machineWithoutEnergy(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(4), MachineContent.block(MachineKind.LITHOGRAPH));
        StackKey silicon = ItemKey.of(MaterialContent.SILICON.get());
        PatternProviderBlockEntity provider = setUp(helper, 1_000_000, true,
                processing(List.of(in(silicon)), ItemKey.of(Items.STONE)));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            helper.assertTrue(provider.logic().stuck() == null,
                    "nothing stuck before" + ": " + provider.logic().stuck());
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider),
                    provider.logic().patterns().getFirst(), 1, inputs(silicon, 1), DispatchStrategy.FASTEST), 1,
                    "one craft in");
        }).thenWaitUntil(() -> helper.assertTrue(provider.logic().stuck() == StuckReason.NO_POWER,
                "the machine has no power" + ": " + provider.logic().stuck())).thenSucceed();
    }

    /** A network whose controller has no FE: its providers say there is no power. */
    private static void networkWithoutPower(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper, 0, true, processing(List.of(in(RAW_IRON)), IRON));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(
                () -> helper.assertTrue(provider.logic().stuck() == StuckReason.NO_POWER, "no power on the network"))
                .thenSucceed();
    }
}
