package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.module.ExpansionBlock;
import dev.silencebound.maxtech.storage.controller.module.ExpansionBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The multiblock controller (M7R item 1): identical controller blocks form one controller in any shape, every face
 * touching a cable or device is a root link, the size cap follows research and the blocks over it stay dark, breaking
 * the primary hands its state on, two touching controllers merge, the device limit is gone, the multiblock is saved, an
 * old cluster save migrates, and FE fed into any block reaches the network's store.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ControllerClusterGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("controller_multiblock_any_shape", ControllerClusterGameTests::anyShape);
        register("controller_root_links_from_faces", ControllerClusterGameTests::rootLinksFromFaces);
        register("controller_size_cap_by_research", ControllerClusterGameTests::sizeCapByResearch);
        register("controller_break_primary_keeps_state", ControllerClusterGameTests::breakPrimaryKeepsState);
        register("controller_touching_controllers_merge", ControllerClusterGameTests::touchingMerge);
        register("controller_split_releases_blocks", ControllerClusterGameTests::splitReleases);
        register("controller_no_device_limit", ControllerClusterGameTests::noDeviceLimit);
        register("controller_break_middle_primary", ControllerClusterGameTests::breakMiddlePrimary);
        register("controller_break_primary_splits_groups", ControllerClusterGameTests::breakPrimarySplitsGroups);
        register("controller_member_keeps_unloaded_primary", ControllerClusterGameTests::memberKeepsUnloadedPrimary);
        register("controller_keeps_energy_in_item", ControllerClusterGameTests::keepsEnergyInItem);
        register("controller_multiblock_saves", ControllerClusterGameTests::multiblockSaves);
        register("controller_old_cluster_save_migrates", ControllerClusterGameTests::oldClusterSaveMigrates);
        register("controller_inputs_share_one_store", ControllerClusterGameTests::inputsShareOneStore);
    }

    private ControllerClusterGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("controller"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(plain, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * Six controller blocks in no straight line (one east, one on top of that, one south of that, one north, one west)
     * form one controller with six blocks' FE; a block touching none of them is a controller of its own. Members point
     * to the primary, show its status and say whose they are; they are no devices.
     */
    private static void anyShape(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        List<BlockPos> members = List.of(core.east(), core.east().above(), core.east().above().south(), core.north(),
                core.west());
        BlockPos stray = core.east(4);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.setBlock(stray, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.west(2));
        long corePos = helper.absolutePos(core).asLong();
        // The core first, so it is the oldest block and the primary.
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, members)).thenWaitUntil(() -> {
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            helper.assertValueEqual(controller.blockCount(), 6, "blocks");
            helper.assertTrue(device.network() == NetworkGameTests.state(helper, core), "device on the west block");
        }).thenExecute(() -> NetworkGameTests.power(helper, core, 1_000)).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.energyCapacity(), 60_000L, "six blocks' FE");
            helper.assertValueEqual(network.controllerCount(), 1, "one controller");
            helper.assertValueEqual(network.deviceCount(), 2, "the primary and the device; members are no devices");
            for (BlockPos member : members) {
                ControllerBlockEntity entity = block(helper, member);
                helper.assertFalse(entity.isPrimary(), "member " + member.toShortString());
                helper.assertValueEqual(entity.primaryPos(), corePos, "primary of " + member.toShortString());
                helper.assertValueEqual(NetworkGameTests.status(helper, member), ControllerStatus.ONLINE,
                        "status of " + member.toShortString());
            }
        }).thenExecute(() -> {
            ControllerBlockEntity lone = block(helper, stray);
            helper.assertTrue(lone.isPrimary() && lone.blockCount() == 1, "the stray block is its own controller");
            helper.assertTrue(keys(helper, core, BlockInfoRequest.Detail.FULL).contains(ControllerContent.CLUSTER_KEY),
                    "size line");
            helper.assertTrue(
                    keys(helper, core.north(), BlockInfoRequest.Detail.FULL).contains(ControllerContent.MEMBER_KEY),
                    "member line");
        }).thenSucceed();
    }

    /**
     * Three blocks in a row with cables on two north faces and one south face and devices right on a top face and a
     * west face: five root links, each carrying its own channels. Breaking the east block takes its link away.
     */
    private static void rootLinksFromFaces(GameTestHelper helper) {
        BlockPos west = new BlockPos(5, 1, 5);
        BlockPos middle = west.east();
        BlockPos east = middle.east();
        helper.setBlock(west, ControllerContent.CONTROLLER.get());
        helper.setBlock(middle, ControllerContent.CONTROLLER.get());
        helper.setBlock(east, ControllerContent.CONTROLLER.get());
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            cable(helper, west.north(i));
            cable(helper, east.north(i));
            cable(helper, middle.south(i));
        }
        devices.add(NetworkGameTests.device(helper, west.north(4)));
        devices.add(NetworkGameTests.device(helper, east.north(4)));
        devices.add(NetworkGameTests.device(helper, middle.south(4)));
        devices.add(NetworkGameTests.device(helper, middle.above()));
        devices.add(NetworkGameTests.device(helper, west.west()));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, west);
            for (TestStorage.TestDevice device : devices) {
                helper.assertTrue(device.network() == network, "a device is not in the network");
            }
            helper.assertValueEqual(network.rootLinks(), 5, "root links: faces touching cables or devices");
            helper.assertValueEqual(network.rootLinksUsed(), 5, "root links in use");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, west);
            long westLink = helper.absolutePos(west.north()).asLong();
            long eastLink = helper.absolutePos(east.north()).asLong();
            helper.assertValueEqual(network.segmentChannels(westLink), 1, "west link channels");
            helper.assertValueEqual(network.segmentChannels(eastLink), 1, "east link channels");
            helper.assertValueEqual(network.segmentCapacity(westLink), 8, "a tier-1 cable's channels");
            helper.assertTrue(network.isRouteRoot(helper.absolutePos(east).asLong()), "the east block ends routes");
            helper.destroyBlock(east);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, west);
            helper.assertValueEqual(network.rootLinks(), 4, "root links without the east block");
            helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, west).blockCount(), 2, "two blocks");
        }).thenSucceed();
    }

    /**
     * The owner's team starts at the base size (8): of ten blocks in a row the last two stay dark, add no FE, show off
     * and say why. Completing the first size node raises the cap to 16 and lights them up.
     */
    private static void sizeCapByResearch(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "size_owner");
        BlockPos core = new BlockPos(2, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        List<BlockPos> row = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            row.add(core.east(i));
        }
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, row)).thenWaitUntil(() -> {
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            helper.assertValueEqual(controller.cap(), 8, "base size");
            helper.assertValueEqual(controller.blockCount(), 8, "blocks under the cap");
            helper.assertValueEqual(controller.darkBlocks().size(), 2, "dark blocks");
            helper.assertTrue(controller.overCapacity(), "over the cap");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 1_000);
            helper.assertValueEqual(NetworkGameTests.state(helper, core).energyCapacity(), 80_000L, "eight blocks");
            helper.assertFalse(block(helper, core.east(7)).isDark(), "the eighth is lit");
            helper.assertTrue(block(helper, core.east(8)).isDark(), "the ninth is dark");
            helper.assertTrue(block(helper, core.east(9)).isDark(), "the tenth is dark");
            helper.assertTrue(keys(helper, core, BlockInfoRequest.Detail.FULL).contains(ControllerContent.OVER_CAP_KEY),
                    "over-cap line");
            helper.assertTrue(
                    keys(helper, core.east(9), BlockInfoRequest.Detail.BRIEF).contains(ControllerContent.DARK_KEY),
                    "dark line");
        }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.status(helper, core.east(9)),
                ControllerStatus.OFF, "a dark block shows off")).thenExecute(() -> {
                    // A dark block adds no capacity, but its FE is not frozen: the next fill draws it in, and FE fed
                    // into it reaches the network's store.
                    ControllerBlockEntity primary = NetworkGameTests.controllerEntity(helper, core);
                    long before = primary.buffer().stored();
                    block(helper, core.east(9)).buffer().load(500);
                    primary.detectNow();
                    helper.assertValueEqual(primary.buffer().stored(), before + 500, "the dark block's FE drawn in");
                    helper.assertValueEqual(block(helper, core.east(9)).buffer().stored(), 0L, "dark buffer empty");
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(block(helper, core.east(9)).energyInput().insert(200, transaction), 200,
                                "FE into a dark block");
                        transaction.commit();
                    }
                    helper.assertValueEqual(primary.buffer().stored(), before + 700, "in the controller's buffer");
                    ResearchTeam team = Research.team(owner, true);
                    Research.addToken(server, team);
                    helper.assertValueEqual(Research.useToken(server, team, MaxtechStorage.id("controller_size_1")),
                            Research.Result.DONE, "first size node");
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    helper.assertValueEqual(controller.cap(), 16, "first step");
                    helper.assertValueEqual(controller.blockCount(), 10, "all ten");
                    helper.assertFalse(controller.overCapacity(), "no longer over the cap");
                    helper.assertFalse(block(helper, core.east(9)).isDark(), "lit");
                    helper.assertValueEqual(NetworkGameTests.state(helper, core).energyCapacity(), 100_000L,
                            "ten blocks");
                }).thenSucceed();
    }

    /**
     * Breaking the primary of three blocks hands everything to the next block: the network keeps running, all its FE
     * (above the smaller capacity), its name and its recovery rows; the broken block's item is a plain one. Breaking a
     * member then keeps the FE in the primary too.
     */
    private static void breakPrimaryKeepsState(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos next = core.east();
        BlockPos last = next.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, next.north());
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, List.of(next, last)))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).blockCount(), 3, "three blocks"))
                .thenExecute(() -> {
                    ControllerBlockEntity primary = NetworkGameTests.controllerEntity(helper, core);
                    NetworkGameTests.power(helper, core, 25_000);
                    helper.assertValueEqual(primary.rename("Kept Vault"), NetworkNames.Renamed.DONE, "renamed");
                    primary.voided(COBBLE, 5);
                    helper.getLevel().destroyBlock(helper.absolutePos(core), true);
                    helper.assertFalse(dropsCarryState(helper, core), "the broken primary's item is plain");
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity successor = block(helper, next);
                    helper.assertTrue(successor.isPrimary(), "the next block took over");
                    // Handing over refreshes the network before the block is gone; its status must not put it back.
                    helper.assertBlockNotPresent(ControllerContent.CONTROLLER.get(), core);
                    helper.assertValueEqual(successor.blockCount(), 2, "two blocks left");
                    NetworkState network = NetworkGameTests.state(helper, next);
                    helper.assertTrue(device.network() == network, "device still in");
                    helper.assertValueEqual(network.controllerCount(), 1, "one controller");
                    helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "online");
                    helper.assertValueEqual(network.storedEnergy(), 25_000L, "no FE lost");
                    helper.assertValueEqual(successor.networkName(), "Kept Vault", "the name moved");
                    helper.assertFalse(successor.recovery().isEmpty(), "the recovery rows moved");
                    helper.assertValueEqual(NetworkGameTests.status(helper, last), ControllerStatus.ONLINE,
                            "the last block lit");
                }).thenExecute(() -> {
                    helper.getLevel().destroyBlock(helper.absolutePos(last), true);
                    helper.assertFalse(dropsCarryState(helper, last), "a member's item is plain");
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, next);
                    helper.assertValueEqual(network.energyCapacity(), 10_000L, "one block's capacity");
                    helper.assertValueEqual(network.storedEnergy(), 25_000L, "FE still kept");
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 3), 3L, "still stores");
                }).thenSucceed();
    }

    /** Whether a controller item dropped near {@code pos} carries FE or a name. */
    private static boolean dropsCarryState(GameTestHelper helper, BlockPos pos) {
        boolean found = false;
        boolean carries = false;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2))) {
            ItemStack stack = entity.getItem();
            if (stack.is(ControllerContent.CONTROLLER.item().get())) {
                found = true;
                carries |= stack.has(ModDataComponents.CONTROLLER_ENERGY.get())
                        || stack.has(ModDataComponents.NETWORK_NAME.get());
                entity.discard();
            }
        }
        if (!found) {
            throw helper.assertionException(Component.literal("no controller item at " + pos.toShortString()));
        }
        return carries;
    }

    /**
     * Two separate controllers, A placed before B, each with FE, a name and B with a recovery row: a block placed
     * between them merges both into A's multiblock, with both FE and B's recovery row; B's name is free again.
     */
    private static void touchingMerge(GameTestHelper helper) {
        BlockPos a = new BlockPos(3, 1, 5);
        BlockPos gap = a.east();
        BlockPos b = gap.east();
        helper.setBlock(a, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(3).thenExecute(() -> helper.setBlock(b, ControllerContent.CONTROLLER.get()))
                .thenIdle(3).thenExecute(() -> {
                    NetworkGameTests.power(helper, a, 4_000);
                    NetworkGameTests.power(helper, b, 3_000);
                    helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, a).rename("Alpha Net"),
                            NetworkNames.Renamed.DONE, "A named");
                    helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, b).rename("Beta Net"),
                            NetworkNames.Renamed.DONE, "B named");
                    NetworkGameTests.controllerEntity(helper, b).voided(COBBLE, 7);
                    helper.setBlock(gap, ControllerContent.CONTROLLER.get());
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity primary = block(helper, a);
                    helper.assertTrue(primary.isPrimary(), "A, placed first, keeps its state");
                    helper.assertValueEqual(primary.blockCount(), 3, "one controller of three blocks");
                    helper.assertFalse(block(helper, b).isPrimary(), "B joined");
                }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, a).storedEnergy(), 7_000L,
                        "both FE in A's buffer"))
                .thenExecute(() -> {
                    ControllerBlockEntity primary = block(helper, a);
                    helper.assertValueEqual(block(helper, b).buffer().stored(), 0L, "B's buffer emptied into A's");
                    helper.assertValueEqual(primary.networkName(), "Alpha Net", "A's name");
                    helper.assertTrue(NetworkNames.get(helper.getLevel().getServer()).find("Beta Net").isEmpty(),
                            "B's name free again");
                    helper.assertFalse(primary.recovery().isEmpty(), "B's recovery row moved");
                    helper.assertValueEqual(NetworkGameTests.state(helper, a).controllerCount(), 1, "one controller");
                }).thenSucceed();
    }

    /**
     * Breaking the middle of three blocks (a member, not the primary) leaves the far block as a controller of its own;
     * the FE stays.
     */
    private static void splitReleases(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos middle = core.east();
        BlockPos far = middle.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, List.of(middle, far)))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).blockCount(), 3, "three blocks"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, core, 6_000);
                    helper.destroyBlock(middle);
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(block(helper, core).blockCount(), 1, "core");
                    ControllerBlockEntity alone = block(helper, far);
                    helper.assertTrue(alone.isPrimary(), "the far block is a controller of its own");
                    helper.assertFalse(alone.networkName().isEmpty(), "with a name of its own");
                    helper.assertValueEqual(block(helper, core).buffer().stored(), 6_000L, "FE kept");
                }).thenSucceed();
    }

    /**
     * Breaking the primary in the middle of a line of three leaves two controllers of one block each: both are
     * primaries (no block still points at the broken one), each runs its own network with its device on its root link,
     * and the FE is kept exactly once (all of it with the successor, none in the plain item).
     */
    private static void breakMiddlePrimary(GameTestHelper helper) {
        BlockPos primary = new BlockPos(5, 1, 5);
        BlockPos west = primary.west();
        BlockPos east = primary.east();
        helper.setBlock(primary, ControllerContent.CONTROLLER.get());
        for (BlockPos end : List.of(west, east)) {
            cable(helper, end.north());
            cable(helper, end.north(2));
        }
        TestStorage.TestDevice westDevice = NetworkGameTests.device(helper, west.north(3));
        TestStorage.TestDevice eastDevice = NetworkGameTests.device(helper, east.north(3));
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, List.of(west, east)))
                .thenWaitUntil(() -> helper.assertValueEqual(block(helper, primary).blockCount(), 3, "three blocks"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, primary, 15_000);
                    helper.getLevel().destroyBlock(helper.absolutePos(primary), true);
                    helper.assertFalse(dropsCarryState(helper, primary), "the broken primary's item is plain");
                }).thenWaitUntil(() -> {
                    for (BlockPos end : List.of(west, east)) {
                        ControllerBlockEntity entity = block(helper, end);
                        helper.assertTrue(entity.isPrimary(), "a controller of its own: " + end.toShortString());
                        helper.assertValueEqual(entity.blockCount(), 1, "one block: " + end.toShortString());
                    }
                    NetworkState westNet = NetworkGameTests.state(helper, west);
                    NetworkState eastNet = NetworkGameTests.state(helper, east);
                    helper.assertTrue(westNet != eastNet, "two networks");
                    helper.assertTrue(westDevice.network() == westNet && eastDevice.network() == eastNet,
                            "each device in its controller's network");
                    for (NetworkState network : List.of(westNet, eastNet)) {
                        helper.assertValueEqual(network.controllerCount(), 1, "one controller");
                        helper.assertValueEqual(network.rootLinksUsed(), 1, "its root link");
                    }
                })
                .thenExecute(() -> helper.assertValueEqual(
                        block(helper, west).buffer().stored() + block(helper, east).buffer().stored(), 15_000L,
                        "FE kept exactly once"))
                .thenSucceed();
    }

    /**
     * Breaking a primary whose removal splits the controller into two groups of two blocks: each group forms a
     * controller with one primary that its other block points to, and the FE is kept exactly once.
     */
    private static void breakPrimarySplitsGroups(GameTestHelper helper) {
        BlockPos primary = new BlockPos(6, 1, 5);
        List<List<BlockPos>> groups = List.of(List.of(primary.west(), primary.west(2)),
                List.of(primary.east(), primary.east(2)));
        helper.setBlock(primary, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2)
                .thenExecute(() -> placeAll(helper,
                        List.of(groups.get(0).get(0), groups.get(0).get(1), groups.get(1).get(0),
                                groups.get(1).get(1))))
                .thenWaitUntil(() -> helper.assertValueEqual(block(helper, primary).blockCount(), 5, "five blocks"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, primary, 12_000);
                    helper.destroyBlock(primary);
                }).thenWaitUntil(() -> {
                    long total = 0;
                    for (List<BlockPos> group : groups) {
                        ControllerBlockEntity first = block(helper, group.get(0));
                        ControllerBlockEntity second = block(helper, group.get(1));
                        helper.assertTrue(first.isPrimary() != second.isPrimary(),
                                "one primary in the group at " + group.get(0).toShortString());
                        ControllerBlockEntity head = first.isPrimary() ? first : second;
                        ControllerBlockEntity member = first.isPrimary() ? second : first;
                        helper.assertValueEqual(head.blockCount(), 2, "two blocks in the group");
                        helper.assertTrue(member.primary() == head, "the member points to its group's primary");
                        total += head.buffer().stored() + member.buffer().stored();
                    }
                    helper.assertValueEqual(total, 12_000L, "FE kept exactly once");
                }).thenSucceed();
    }

    /**
     * A member whose primary sits in an unloaded chunk (simulated: the block is loaded again from data that names a
     * primary far away) stays a member when a nearby controller's fill passes it; it is not freed as if its primary
     * were gone. Once it loads with its real primary again, that primary takes it back, and the FE is never doubled.
     */
    private static void memberKeepsUnloadedPrimary(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos member = core.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        BlockPos far = helper.absolutePos(core).offset(100_000, 0, 0);
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, List.of(member)))
                .thenWaitUntil(() -> helper.assertValueEqual(block(helper, core).blockCount(), 2, "two blocks"))
                .thenExecute(() -> {
                    helper.assertFalse(helper.getLevel().isLoaded(far), "the far position is not loaded");
                    NetworkGameTests.power(helper, core, 5_000);
                    reloadMember(helper, member, far.asLong());
                    block(helper, core).detectNow();
                    ControllerBlockEntity kept = block(helper, member);
                    helper.assertFalse(kept.isPrimary(), "still a member");
                    helper.assertValueEqual(kept.primaryPos(), far.asLong(), "of its unloaded primary");
                    helper.assertValueEqual(block(helper, core).blockCount(), 1, "not taken by the other");
                    helper.assertValueEqual(block(helper, core).buffer().stored() + kept.buffer().stored(), 5_000L,
                            "no FE doubled");
                    reloadMember(helper, member, helper.absolutePos(core).asLong());
                    block(helper, core).detectNow();
                    helper.assertValueEqual(block(helper, core).blockCount(), 2, "taken back");
                    helper.assertValueEqual(
                            block(helper, core).buffer().stored() + block(helper, member).buffer().stored(), 5_000L,
                            "still no FE doubled");
                }).thenSucceed();
    }

    /** Replaces the block entity at {@code pos} with one loaded from its own data, pointing at {@code primary}. */
    private static void reloadMember(GameTestHelper helper, BlockPos pos, long primary) {
        CompoundTag tag = block(helper, pos).saveWithoutMetadata(helper.getLevel().registryAccess());
        tag.putLong("primary", primary);
        ControllerBlockEntity loaded = new ControllerBlockEntity(helper.absolutePos(pos), helper.getBlockState(pos));
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        helper.getLevel().setBlockEntity(loaded);
    }

    /** 40 devices on a tier-4 trunk all join: the controller sets no device limit any more (M7R item 1). */
    private static void noDeviceLimit(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (int x = 2; x <= 21; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(NetworkGameTests.at(x)),
                    CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
            devices.add(NetworkGameTests.device(helper, new BlockPos(x, 1, 0)));
            devices.add(NetworkGameTests.device(helper, new BlockPos(x, 1, 2)));
        }
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.limitedDevices(), 40, "all devices in");
            helper.assertValueEqual(network.deviceLimit(), Integer.MAX_VALUE, "no limit");
            for (TestStorage.TestDevice device : devices) {
                helper.assertTrue(device.network() == network && device.deviceLink().refusedBy() == null,
                        "a device was turned away");
            }
        }).thenSucceed();
    }

    /**
     * The broken lone controller's item keeps its FE and names it; placing the item puts it back. An empty block's item
     * is plain.
     */
    private static void keepsEnergyInItem(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        BlockPos empty = NetworkGameTests.controller(helper, 5);
        NetworkGameTests.power(helper, core, 5000);
        ItemStack drop = drop(helper, core);
        helper.assertValueEqual(drop.getOrDefault(ModDataComponents.CONTROLLER_ENERGY.get(), 0L), 5000L,
                "FE in the item");
        List<Component> lines = new ArrayList<>();
        drop.getItem().appendHoverText(drop, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        helper.assertTrue(lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents t
                && t.getKey().equals(ControllerContent.ENERGY_TOOLTIP_KEY)), "tooltip " + lines);
        helper.assertFalse(drop(helper, empty).has(ModDataComponents.CONTROLLER_ENERGY.get()), "empty block's item");
        BlockPos placed = NetworkGameTests.at(9);
        helper.setBlock(placed, ControllerContent.CONTROLLER.get());
        ControllerBlockEntity entity = NetworkGameTests.controllerEntity(helper, placed);
        entity.applyComponentsFromItemStack(drop);
        helper.assertValueEqual(entity.buffer().stored(), 5000L, "FE back in the placed block");
        helper.succeed();
    }

    private static ItemStack drop(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockState state = helper.getBlockState(pos);
        for (ItemStack stack : Block.getDrops(state, helper.getLevel(), absolute,
                helper.getLevel().getBlockEntity(absolute), null, ItemStack.EMPTY)) {
            if (stack.is(ControllerContent.CONTROLLER.item().get())) {
                return stack;
            }
        }
        throw helper.assertionException(Component.literal("No controller drop"));
    }

    /**
     * The primary saves its blocks, so its stats and root-link blocks come back on load without a search; a member
     * saves its primary.
     */
    private static void multiblockSaves(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2)
                .thenExecute(() -> placeAll(helper, List.of(core.east(), core.east().above())))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).blockCount(), 3,
                                "three blocks"))
                .thenExecute(() -> {
                    ControllerBlockEntity loaded = reload(helper, core);
                    helper.assertTrue(loaded.isPrimary(), "primary after loading");
                    helper.assertValueEqual(loaded.blockCount(), 3, "blocks after loading");
                    helper.assertValueEqual(loaded.stats().bufferMu(), 30_000L, "FE capacity after loading");
                    helper.assertValueEqual(loaded.exitNodes().size(), 3, "every block carries root links");
                    ControllerBlockEntity member = reload(helper, core.east());
                    helper.assertFalse(member.isPrimary(), "member after loading");
                    helper.assertValueEqual(member.primaryPos(), helper.absolutePos(core).asLong(), "its primary");
                }).thenSucceed();
    }

    private static ControllerBlockEntity reload(GameTestHelper helper, BlockPos pos) {
        CompoundTag tag = block(helper, pos).saveWithoutMetadata(helper.getLevel().registryAccess());
        ControllerBlockEntity loaded = new ControllerBlockEntity(helper.absolutePos(pos), helper.getBlockState(pos));
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        return loaded;
    }

    /**
     * A core saved before M7R (no data version, the cluster fields, a capacitor bank and a batch amplifier as members,
     * 30,000 FE, a name) loads with its FE and name and finds its multiblock again: the bank, a tier-1 energy cell in
     * the world now (M7R item 5, its id is an alias), takes the 20,000 FE above the one block's buffer, the amplifier
     * turns into a card in the controller's slots (M7R item 2) and its block goes. Data from a newer version is loaded
     * as it is, without the migration steps.
     */
    private static void oldClusterSaveMigrates(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos bank = core.east();
        BlockPos amplifier = core.west();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        // The old bank loads as a tier-1 energy cell in place (registry alias).
        helper.setBlock(bank, EnergyContent.TIER_1.get());
        long corePos = helper.absolutePos(core).asLong();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "first fill done"))
                .thenExecute(() -> {
                    // The amplifier as an old core left it: lit and pointing at the core. It is placed in this tick,
                    // so no fill has met it yet.
                    expansion(helper, amplifier, ExpansionContent.BATCH_AMPLIFIER);
                    expansionEntity(helper, amplifier).join(corePos);
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    CompoundTag old = oldCoreTag(helper, controller, bank, amplifier);
                    controller.loadWithComponents(
                            TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), old));
                    CompoundTag saved = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.assertTrue(saved.getBooleanOr(ControllerBlockEntity.REDETECT, false), "asks for a fill");
                    helper.assertFalse(saved.contains("idle"), "the idle flag is gone");
                    helper.assertValueEqual(saved.getIntOr(SaveFormat.VERSION_KEY, 0),
                            StorageSaveFormats.BLOCK_ENTITIES.currentVersion(), "saved in the new version");
                    helper.assertValueEqual(controller.buffer().stored(), 30_000L, "FE kept");
                    helper.assertValueEqual(controller.networkName(), "Old Cluster", "name kept");
                    helper.assertValueEqual(controller.expansions(ModuleKind.CAPACITOR_BANK), 1,
                            "the bank is listed until the fill");
                    helper.assertValueEqual(controller.expansions(ModuleKind.BATCH_AMPLIFIER), 1,
                            "the amplifier is listed until the fill");
                    helper.assertValueEqual(controller.cards().usable(), 0, "no card slots saved before M7R");
                    controller.requestDetect();
                }).thenWaitUntil(() -> helper.assertBlockNotPresent(ExpansionContent.BATCH_AMPLIFIER.get(), amplifier))
                .thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    helper.assertValueEqual(controller.expansions(ModuleKind.CAPACITOR_BANK), 0, "no bank");
                    helper.assertValueEqual(controller.expansions(ModuleKind.BATCH_AMPLIFIER), 0, "no amplifier block");
                    helper.assertValueEqual(controller.cards(CardKind.BATCH_AMPLIFIER), 1, "the amplifier is a card");
                    helper.assertValueEqual(controller.stats().batchPercent(), 125, "and works");
                    helper.assertValueEqual(ControllerCardGameTests.droppedCards(helper, amplifier), 0,
                            "nothing dropped");
                    EnergyCellBlockEntity cell = helper.getBlockEntity(bank, EnergyCellBlockEntity.class);
                    helper.assertValueEqual(controller.buffer().stored(), 10_000L, "the controller full");
                    helper.assertValueEqual(cell.cellStore().stored(), 20_000L, "the rest in the bank's cell");
                    helper.assertFalse(controller.saveWithoutMetadata(helper.getLevel().registryAccess())
                            .getBooleanOr(ControllerBlockEntity.REDETECT, false), "fill done");
                    // A newer version than this build knows: loaded unchanged, the step does not run.
                    CompoundTag newer = oldCoreTag(helper, controller, bank, amplifier);
                    newer.putInt(SaveFormat.VERSION_KEY, StorageSaveFormats.BLOCK_ENTITIES.currentVersion() + 1);
                    ControllerBlockEntity copy = new ControllerBlockEntity(helper.absolutePos(core),
                            helper.getBlockState(core));
                    copy.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), newer));
                    CompoundTag again = copy.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.assertFalse(again.getBooleanOr(ControllerBlockEntity.REDETECT, false),
                            "no migration of newer data");
                    helper.assertValueEqual(again.getIntOr(SaveFormat.VERSION_KEY, 0),
                            StorageSaveFormats.BLOCK_ENTITIES.currentVersion() + 1, "newer stamp kept");
                    helper.assertValueEqual(copy.buffer().stored(), 30_000L, "newer data's FE");
                }).thenSucceed();
    }

    /** {@code controller}'s data as a pre-M7R core saved it: no version, the cluster fields, two members. */
    private static CompoundTag oldCoreTag(GameTestHelper helper, ControllerBlockEntity controller, BlockPos bank,
            BlockPos amplifier) {
        CompoundTag old = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
        old.remove(SaveFormat.VERSION_KEY);
        old.remove("card_slots");
        old.remove("cards");
        old.remove("blocks");
        old.remove("dark");
        old.putLong("energy", 30_000L);
        old.putString("network_name", "Old Cluster");
        old.putLongArray("members",
                new long[]{helper.absolutePos(bank).asLong(), helper.absolutePos(amplifier).asLong()});
        old.putIntArray("kinds", new int[]{ModuleKind.CAPACITOR_BANK.index(), ModuleKind.BATCH_AMPLIFIER.index()});
        old.putBoolean("idle", false);
        old.putBoolean("over", false);
        old.putInt("cap", 8);
        return old;
    }

    /**
     * FE fed into the primary and FE fed into a member land in the same network store; a block in no network still
     * takes FE into its own buffer.
     */
    private static void inputsShareOneStore(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        BlockPos member = core.above();
        BlockPos cell = NetworkGameTests.at(2);
        helper.setBlock(cell, EnergyContent.TIER_1.get());
        BlockPos lone = NetworkGameTests.controller(helper, 10);
        helper.startSequence().thenIdle(2).thenExecute(() -> placeAll(helper, List.of(member))).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.energyCells().size(), 1, "the cell in the network");
            helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).buffer().capacity(), 20_000L,
                    "the primary's capacity with the member");
        }).thenExecute(() -> {
            // With the primary's buffer full, FE fed in at the primary and at the member both reach the cell.
            NetworkGameTests.power(helper, core, 20_000);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).energyInput().insert(400, transaction), 400,
                        "FE into the full primary");
                helper.assertValueEqual(block(helper, member).energyInput().insert(300, transaction), 300,
                        "FE into the member");
                transaction.commit();
            }
            helper.assertValueEqual(helper.getBlockEntity(cell, EnergyCellBlockEntity.class).cellStore().stored(), 700L,
                    "both in the cell");
            helper.assertValueEqual(NetworkGameTests.state(helper, core).storedEnergy(), 20_700L, "network total");
            NetworkGameTests.controllerEntity(helper, core).buffer().tryConsume(20_000);
        }).thenExecute(() -> {
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).energyInput().insert(400, transaction), 400,
                        "FE into the primary");
                helper.assertValueEqual(block(helper, member).energyInput().insert(300, transaction), 300,
                        "FE into the member");
                transaction.commit();
            }
            NetworkState network = NetworkGameTests.state(helper, core);
            // The emptied primary buffer takes this round first; the cell keeps its 700.
            helper.assertValueEqual(network.storedEnergy(), 1_400L, "both in the network's store");
            helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).buffer().stored(), 700L,
                    "primary buffer");
            helper.assertValueEqual(block(helper, member).buffer().stored(), 0L, "nothing in the member's own buffer");
            // A block that is in no network (not in the level) fills its own buffer.
            ControllerBlockEntity alone = new ControllerBlockEntity(helper.absolutePos(lone),
                    helper.getBlockState(lone));
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(alone.energyInput().insert(200, transaction), 200,
                        "FE into a block in no network");
                transaction.commit();
            }
            helper.assertValueEqual(alone.buffer().stored(), 200L, "its own buffer");
        }).thenSucceed();
    }

    /** The controller block at {@code pos} itself, member or primary. */
    private static ControllerBlockEntity block(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ControllerBlockEntity.class);
    }

    private static void placeAll(GameTestHelper helper, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            helper.setBlock(pos, ControllerContent.CONTROLLER.get());
        }
    }

    /** Places an expansion, status light or (since M7R item 4) network device at {@code pos}. */
    static void expansion(GameTestHelper helper, BlockPos pos, BlockEntry<? extends Block, ?> entry) {
        helper.setBlock(pos, entry.get());
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
    }

    static ExpansionBlockEntity expansionEntity(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ExpansionBlockEntity.class);
    }

    static int load(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockState(pos).getValue(ExpansionBlock.LOAD);
    }

    static List<String> keys(GameTestHelper helper, BlockPos pos, BlockInfoRequest.Detail detail) {
        BlockPos absolute = helper.absolutePos(pos);
        @Nullable
        BlockInfoProvider provider = InfoLookups.BLOCK.find(helper.getLevel(), absolute, null);
        if (provider == null) {
            throw helper.assertionException(Component.literal("No block info at " + pos.toShortString()));
        }
        List<String> keys = new ArrayList<>();
        provider.addInfo(new BlockInfoRequest(helper.getLevel(), absolute, null, null, detail),
                line -> keys.add(line.getContents() instanceof TranslatableContents translatable
                        ? translatable.getKey()
                        : line.getString()));
        return keys;
    }
}
