package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.cluster.ClusterNeeds;
import dev.silencebound.maxtech.storage.cluster.ClusterPreview;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPart;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuStats;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuTier;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.network.NetworkState;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The cluster ghost preview (M7C item 22): the cells where a held block would join the nearest cluster, without cells
 * that are taken or that touch another core's cluster, the cluster's needs, and the Network tab's cluster lines.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ClusterPreviewGameTests {
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final String CONTROLLER = "cluster_preview_controller";
    private static final String CPU = "cluster_preview_cpu";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(CONTROLLER, () -> StorageTestEnvironment.clearing(ClusterPreviewGameTests::controller));
        FUNCTIONS.register(CPU, () -> StorageTestEnvironment.clearing(ClusterPreviewGameTests::cpu));
    }

    private ClusterPreviewGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("cluster_preview"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, CONTROLLER, MaxtechStorage.id("empty_16x12x16"), 200);
        StorageTests.test(event, plain, CPU, MaxtechStorage.id("empty_16x12x16"), 200);
    }

    /**
     * A two-block controller, stone on top of its first block, and a second two-block controller two cells east of the
     * first: the preview for a held controller block (M7R) shows both blocks, grows above the second block, not into
     * the stone and not next to the other controller (the two would merge); one more block helps while under the size
     * cap and not at it; the free room line; the overview carries it through its codec.
     */
    private static void controller(GameTestHelper helper) {
        BlockPos core = new BlockPos(3, 2, 5);
        BlockPos bank = core.east();
        BlockPos gap = bank.east();
        BlockPos otherBank = gap.east();
        BlockPos otherCore = otherBank.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.setBlock(core.above(), Blocks.STONE);
        helper.setBlock(bank, ControllerContent.CONTROLLER.get());
        helper.setBlock(otherCore, ControllerContent.CONTROLLER.get());
        helper.setBlock(otherBank, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).blockCount(), 2,
                    "first multiblock");
            helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, otherCore).blockCount(), 2,
                    "second multiblock");
        }).thenExecute(() -> {
            ClusterPreview.Preview preview = ClusterPreview.controller(helper.getLevel(),
                    Vec3.atCenterOf(helper.absolutePos(core)));
            helper.assertValueEqual(preview.mode(), ClusterPreview.Mode.CONTROLLER, "mode");
            helper.assertValueEqual(preview.core(),
                    NetworkGameTests.controllerEntity(helper, core).getBlockPos().asLong(), "nearest controller");
            helper.assertTrue(preview.members().contains(helper.absolutePos(bank).asLong()), "second block shown");
            helper.assertTrue(preview.members().contains(helper.absolutePos(core).asLong()), "core shown");
            helper.assertTrue(preview.growth().contains(helper.absolutePos(bank.above()).asLong()), "grows up");
            helper.assertFalse(preview.growth().contains(helper.absolutePos(core.above()).asLong()), "not into stone");
            helper.assertFalse(preview.growth().contains(helper.absolutePos(gap).asLong()),
                    "not next to another core's bank");
            helper.assertTrue(preview.helps(), "a third block is under the size cap");
            helper.assertFalse(preview.conflict(), "no conflict");
            helper.assertValueEqual(preview.needs().get(0).kind(), ClusterNeeds.Kind.CONTROLLER_FREE,
                    "room for more blocks line");

            ControllerBlockEntity entity = NetworkGameTests.controllerEntity(helper, core);
            NetworkState network = NetworkGameTests.state(helper, core);
            List<ClusterNeeds.Need> needs = ClusterPreview.networkNeeds(entity, network);
            NetworkOverview overview = NetworkOverview.of(network,
                    new NetworkOverview.Cluster(entity.blockCount(), entity.cap(), entity.overCapacity(), needs));
            helper.assertValueEqual(overview.clusters(), needs, "overview carries the lines");
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            NetworkOverview.STREAM_CODEC.encode(buf, overview);
            helper.assertValueEqual(NetworkOverview.STREAM_CODEC.decode(buf).clusters(), needs, "through the codec");
        }).thenSucceed();
    }

    /**
     * A tier 1 core with one storage block, and a second core one cell beyond the cell east of that block: the preview
     * for a storage block grows above the block but not next to the other core, says how many more storage blocks reach
     * the step cap, and a held storage block helps.
     */
    private static void cpu(GameTestHelper helper) {
        BlockPos core = new BlockPos(3, 2, 5);
        BlockPos storage = core.east();
        BlockPos gap = storage.east();
        BlockPos otherCore = gap.east();
        helper.setBlock(core, CpuContent.CORE_1.get());
        helper.setBlock(storage, CpuContent.STORAGE.get());
        helper.setBlock(otherCore, CpuContent.CORE_1.get());
        helper.startSequence().thenWaitUntil(() -> {
            CpuCoreBlockEntity entity = helper.getBlockEntity(core, CpuCoreBlockEntity.class);
            helper.assertValueEqual(entity.count(CpuPart.STORAGE), 1, "one storage block");
            // A placed core's onLoad, which registers it, runs on a later tick than the placement.
            helper.assertTrue(JobQueue.cpusIn(helper.getLevel()).contains(entity), "core registered");
        }).thenExecute(() -> {
            ClusterPreview.Preview preview = ClusterPreview.cpu(helper.getLevel(),
                    Vec3.atCenterOf(helper.absolutePos(core)), CpuPart.STORAGE);
            helper.assertValueEqual(preview.mode(), ClusterPreview.Mode.CPU, "mode");
            helper.assertValueEqual(preview.core(), helper.absolutePos(core).asLong(), "nearest core");
            helper.assertTrue(preview.members().contains(helper.absolutePos(storage).asLong()), "storage is a member");
            helper.assertTrue(preview.growth().contains(helper.absolutePos(storage.above()).asLong()), "grows up");
            helper.assertFalse(preview.growth().contains(helper.absolutePos(gap).asLong()), "not next to another core");
            helper.assertFalse(preview.conflict(), "no conflict");
            int toCap = CpuStats.partsToCap(CpuStats.Caps.of(CpuTier.TIER_1), CpuStats.Parts.fromConfig(),
                    CpuPart.STORAGE);
            helper.assertValueEqual(preview.helps(), toCap > 1, "a storage block helps below the cap");
            List<ClusterNeeds.Kind> kinds = new ArrayList<>();
            for (ClusterNeeds.Need need : preview.needs()) {
                kinds.add(need.kind());
            }
            helper.assertValueEqual(kinds.get(0), ClusterNeeds.Kind.CPU, "heading");
            if (toCap > 1) {
                helper.assertTrue(
                        preview.needs()
                                .contains(new ClusterNeeds.Need(ClusterNeeds.Kind.CPU_STORAGE_MORE, preview.core(),
                                        toCap - 1, CpuStats.Caps.of(CpuTier.TIER_1).maxSteps(), 0)),
                        "storage blocks still to add");
            }
        }).thenSucceed();
    }
}
