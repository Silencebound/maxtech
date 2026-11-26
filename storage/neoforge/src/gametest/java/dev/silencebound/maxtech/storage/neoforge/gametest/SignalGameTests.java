package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.content.ContentToggle;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.AntennaBlockEntity;
import dev.silencebound.maxtech.storage.signal.AntennaLinking;
import dev.silencebound.maxtech.storage.signal.AntennaMountBlockEntity;
import dev.silencebound.maxtech.storage.signal.Antennas;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessCoverage;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalSource;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalStatus;
import dev.silencebound.maxtech.storage.worldgen.WorldgenContent;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * M6d signal: antennas paired with the linking tool join two parts of a network over one route segment with the lower
 * tier's channels and range; an antenna mount pulls the expansions at a remote antenna into the controller's cluster;
 * the wireless terminal reaches its network within the range its wireless access mounts give; and the {@code wireless}
 * and {@code dataGeodes} module switches turn their content off.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class SignalGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("signal_antennas_pair_and_link", SignalGameTests::pairAndLink);
        register("signal_antenna_channels_by_tier", SignalGameTests::channelsByTier);
        StorageTests.function("signal_antenna_range", StorageTestEnvironment.clearing(SignalGameTests::range));
        register("signal_antenna_mount_links_site", SignalGameTests::mountLinksSite);
        register("signal_wireless_range_per_mount", SignalGameTests::wirelessRange);
        register("signal_modules_off", SignalGameTests::modulesOff);
        register("signal_unpair_patches_routes", SignalGameTests::unpairPatchesRoutes);
        register("signal_pair_inside_network", SignalGameTests::pairInsideNetwork);
        register("signal_wireless_charge", SignalGameTests::wirelessCharge);
    }

    private SignalGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("signal"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("signal_short_range"),
                        new StorageTestEnvironment("signal.antenna.rangeTier1", "16", false)),
                "signal_antenna_range", STRUCTURE, 100);
    }

    /**
     * A core with a cable and a tier-1 antenna, and a far tier-1 antenna 26 blocks off with a device beside it: no
     * network reaches the device until the linking tool pairs the two (use on one, then the other), then the device is
     * in the core's network over a 16-channel segment. Breaking the near antenna unpairs the far one and cuts the
     * device off again.
     */
    private static void pairAndLink(GameTestHelper helper) {
        BlockPos core = new BlockPos(2, 1, 2);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        cable(helper, core.east());
        BlockPos near = new BlockPos(4, 1, 2);
        BlockPos far = new BlockPos(30, 1, 2);
        helper.setBlock(near, SignalContent.ANTENNA_TIER_1.get());
        helper.setBlock(far, SignalContent.ANTENNA_TIER_1.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, far.east());
        ServerPlayer player = researched(helper, "antenna_pairer", false);
        ItemStack tool = new ItemStack(P2PContent.LINKER.get());
        ServerLevel level = helper.getLevel();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        antenna(helper, near).network() == NetworkGameTests.state(helper, core), "near antenna joined"))
                .thenExecute(() -> {
                    helper.assertFalse(device.network() == NetworkGameTests.state(helper, core), "linked unpaired");
                    AntennaLinking.use(level, helper.absolutePos(near), player, tool);
                    helper.assertTrue(GlobalPos.of(level.dimension(), helper.absolutePos(near))
                            .equals(tool.get(ModDataComponents.ANTENNA_LINKER.get())), "picked");
                    AntennaLinking.use(level, helper.absolutePos(far), player, tool);
                    helper.assertValueEqual(antenna(helper, near).partner(), helper.absolutePos(far).asLong(),
                            "near paired");
                    helper.assertValueEqual(antenna(helper, far).partner(), helper.absolutePos(near).asLong(),
                            "far paired");
                    helper.assertFalse(tool.has(ModDataComponents.ANTENNA_LINKER.get()), "pick kept after pairing");
                })
                .thenWaitUntil(() -> helper.assertTrue(device.network() == NetworkGameTests.state(helper, core),
                        "device reached over the link"))
                // Routes are rebuilt a little after the merge.
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, core).segmentCapacity(helper.absolutePos(far).asLong()), 16,
                        "tier-1 link channels"))
                .thenExecute(() -> {
                    helper.destroyBlock(near);
                    helper.assertValueEqual(antenna(helper, far).partner(), Antennas.NONE, "far unpaired");
                }).thenWaitUntil(() -> helper.assertFalse(device.network() == NetworkGameTests.state(helper, core),
                        "device still linked"))
                .thenSucceed();
    }

    /** Two tier-3 antennas link with 64 channels; a tier-3 and a tier-2 with the lower tier's 32. */
    private static void channelsByTier(GameTestHelper helper) {
        BlockPos coreA = new BlockPos(2, 1, 2);
        BlockPos coreB = new BlockPos(2, 1, 10);
        helper.setBlock(coreA, ControllerContent.CONTROLLER.get());
        helper.setBlock(coreB, ControllerContent.CONTROLLER.get());
        helper.setBlock(coreA.east(), SignalContent.ANTENNA_TIER_3.get());
        helper.setBlock(coreB.east(), SignalContent.ANTENNA_TIER_3.get());
        BlockPos farA = new BlockPos(30, 1, 2);
        BlockPos farB = new BlockPos(30, 1, 10);
        helper.setBlock(farA, SignalContent.ANTENNA_TIER_3.get());
        helper.setBlock(farB, SignalContent.ANTENNA_TIER_2.get());
        ServerLevel level = helper.getLevel();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkGameTests.state(helper, coreA);
            NetworkGameTests.state(helper, coreB);
        }).thenExecute(() -> {
            helper.assertValueEqual(pair(level, helper.absolutePos(coreA.east()), helper.absolutePos(farA)), "",
                    "tier 3 pair refused");
            helper.assertValueEqual(pair(level, helper.absolutePos(coreB.east()), helper.absolutePos(farB)), "",
                    "tier 3 and 2 refused");
        }).thenWaitUntil(() -> {
            helper.assertTrue(antenna(helper, farA).network() == NetworkGameTests.state(helper, coreA), "A linked");
            helper.assertTrue(antenna(helper, farB).network() == NetworkGameTests.state(helper, coreB), "B linked");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(
                    NetworkGameTests.state(helper, coreA).segmentCapacity(helper.absolutePos(farA).asLong()), 64,
                    "two tier-3 antennas");
            helper.assertValueEqual(
                    NetworkGameTests.state(helper, coreB).segmentCapacity(helper.absolutePos(farB).asLong()), 32,
                    "the lower tier counts");
        }).thenSucceed();
    }

    /**
     * With {@code signal.antenna.rangeTier1} at 16: two tier-1 antennas 21 blocks apart do not pair, 14 apart they do,
     * and two tier-2 antennas 21 apart pair at tier 2's range. Two mounts never pair with each other.
     */
    private static void range(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = new BlockPos(4, 1, 2);
        BlockPos tooFar = new BlockPos(25, 1, 2);
        BlockPos close = new BlockPos(18, 1, 2);
        helper.setBlock(a, SignalContent.ANTENNA_TIER_1.get());
        helper.setBlock(tooFar, SignalContent.ANTENNA_TIER_1.get());
        helper.setBlock(close, SignalContent.ANTENNA_TIER_1.get());
        BlockPos b = new BlockPos(4, 1, 6);
        BlockPos b2 = new BlockPos(25, 1, 6);
        helper.setBlock(b, SignalContent.ANTENNA_TIER_2.get());
        helper.setBlock(b2, SignalContent.ANTENNA_TIER_2.get());
        BlockPos mount1 = new BlockPos(4, 1, 10);
        BlockPos mount2 = new BlockPos(8, 1, 10);
        helper.setBlock(mount1, ExpansionContent.ANTENNA_MOUNT.get());
        helper.setBlock(mount2, ExpansionContent.ANTENNA_MOUNT.get());
        helper.assertValueEqual(pair(level, helper.absolutePos(a), helper.absolutePos(tooFar)),
                SignalContent.OUT_OF_RANGE_KEY, "21 blocks at tier 1");
        helper.assertValueEqual(antenna(helper, a).partner(), Antennas.NONE, "refused pair kept");
        helper.assertValueEqual(pair(level, helper.absolutePos(a), helper.absolutePos(close)), "",
                "14 blocks at tier 1");
        helper.assertValueEqual(pair(level, helper.absolutePos(b), helper.absolutePos(b2)), "", "21 blocks at tier 2");
        helper.assertValueEqual(pair(level, helper.absolutePos(mount1), helper.absolutePos(mount2)),
                SignalContent.TWO_MOUNTS_KEY, "two mounts");
        helper.succeed();
    }

    /**
     * An antenna mount is a network device (M7R item 4): placed on a cable far from the core and paired with an antenna
     * 26 blocks off, it links that antenna's site into the network over a segment of the antenna's tier (16 channels
     * for tier 1), so a device and a wireless access mount beside the antenna join and count. Both ends save the
     * pairing. Breaking the antenna unpairs the mount and cuts the site off; the remote mount stops counting.
     */
    private static void mountLinksSite(GameTestHelper helper) {
        BlockPos core = new BlockPos(2, 1, 10);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (int x = 3; x <= 5; x++) {
            cable(helper, new BlockPos(x, 1, 10));
        }
        BlockPos mount = new BlockPos(5, 2, 10);
        helper.setBlock(mount, ExpansionContent.ANTENNA_MOUNT.get());
        BlockPos remote = new BlockPos(30, 1, 10);
        helper.setBlock(remote, SignalContent.ANTENNA_TIER_1.get());
        BlockPos access = remote.east();
        helper.setBlock(access, ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, remote.north());
        ServerLevel level = helper.getLevel();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        coreEntity(helper, core).services(NetworkService.ANTENNA_MOUNT), 1, "mount on the network"))
                .thenExecute(() -> {
                    helper.assertFalse(device.network() == NetworkGameTests.state(helper, core),
                            "site linked unpaired");
                    helper.assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 0,
                            "remote access mount counted unpaired");
                    helper.assertValueEqual(pair(level, helper.absolutePos(mount), helper.absolutePos(remote)), "",
                            "mount pairing refused");
                    helper.assertTrue(antenna(helper, remote).partnerIsMount(), "paired with the mount");
                    helper.assertValueEqual(mountEntity(helper, mount).antenna(), helper.absolutePos(remote).asLong(),
                            "the mount saves its antenna");
                }).thenWaitUntil(() -> {
                    helper.assertTrue(device.network() == NetworkGameTests.state(helper, core), "site not linked");
                    helper.assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 1,
                            "remote access mount counted");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, core).segmentCapacity(helper.absolutePos(remote).asLong()), 16,
                        "tier-1 link channels"))
                .thenExecute(() -> {
                    helper.destroyBlock(remote);
                    helper.assertValueEqual(mountEntity(helper, mount).antenna(), Antennas.NONE, "mount unpaired");
                }).thenWaitUntil(() -> {
                    helper.assertFalse(device.network() == NetworkGameTests.state(helper, core), "site still linked");
                    helper.assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 0,
                            "remote access mount still counted");
                }).thenSucceed();
    }

    private static AntennaMountBlockEntity mountEntity(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, AntennaMountBlockEntity.class);
    }

    /**
     * A wireless terminal bound to a core reaches it within 64 blocks of its wireless access mount with one mount (also
     * measured from an antenna of the network), 96 with two and the cap of 256 with seven or eight; distances are
     * measured west of the first mount, the westmost one (M7R item 4: from the mounts, not the core). Its terminal
     * closes out of range; without the research or a binding it opens nothing.
     */
    private static void wirelessRange(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 2);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.setBlock(core.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        for (int z = 3; z <= 5; z++) {
            cable(helper, new BlockPos(5, 1, z));
        }
        BlockPos antenna = new BlockPos(5, 1, 6);
        helper.setBlock(antenna, SignalContent.ANTENNA_TIER_1.get());
        ServerLevel level = helper.getLevel();
        ServerPlayer player = researched(helper, "wireless_user", true);
        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        terminal.set(ModDataComponents.WIRELESS_LINK.get(), GlobalPos.of(level.dimension(), helper.absolutePos(core)));
        terminal.set(ModDataComponents.WIRELESS_ENERGY.get(), 100_000);
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        Vec3 centre = helper.absolutePos(core.east()).getCenter();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 1, "one mount");
            helper.assertTrue(antenna(helper, antenna).network() == NetworkGameTests.state(helper, core),
                    "antenna joined");
        }).thenExecute(() -> {
            helper.assertValueEqual(WirelessCoverage.range(coreEntity(helper, core)), 64, "one mount's range");
            expectReach(helper, player, centre.add(63, 0, 0), Reach.OK, "63 blocks east");
            expectReach(helper, player, centre.add(65, 0, 0), Reach.OUT_OF_RANGE, "65 blocks east");
            // 66 blocks from the mount but about 62 from the antenna, which is 4 blocks south of the core.
            expectReach(helper, player, centre.add(0, 0, 66), Reach.OK, "near the antenna");
            player.setPos(centre.add(10, 0, 0));
            WirelessTerminalSource source = WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND);
            helper.assertTrue(source != null && source.stillValid(player), "terminal opens in range");
            player.setPos(centre.add(70, 0, 0));
            helper.assertFalse(source != null && source.stillValid(player), "terminal stays open out of range");
            helper.setBlock(core.east(2), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        }).thenWaitUntil(() -> helper
                .assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 2, "two mounts"))
                .thenExecute(() -> {
                    expectReach(helper, player, centre.add(-95, 0, 0), Reach.OK, "95 blocks with two");
                    expectReach(helper, player, centre.add(-97, 0, 0), Reach.OUT_OF_RANGE, "97 blocks with two");
                    for (int i = 3; i <= 7; i++) {
                        helper.setBlock(core.east(i), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
                    }
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 7, "seven mounts"))
                .thenExecute(() -> {
                    helper.assertValueEqual(WirelessCoverage.range(coreEntity(helper, core)), 256, "seven mounts");
                    helper.setBlock(core.east(8), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS),
                                8, "eight mounts"))
                .thenExecute(() -> {
                    helper.assertValueEqual(WirelessCoverage.range(coreEntity(helper, core)), 256, "capped");
                    expectReach(helper, player, centre.add(-255, 0, 0), Reach.OK, "255 blocks at the cap");
                    expectReach(helper, player, centre.add(-257, 0, 0), Reach.OUT_OF_RANGE, "257 blocks at the cap");
                    player.setPos(centre);
                    helper.assertValueEqual(
                            WirelessTerminalItem.reach(player, new ItemStack(SignalContent.WIRELESS_TERMINAL.get())),
                            Reach.UNBOUND, "unbound terminal");
                    ServerPlayer stranger = NetworkGameTests.player(level, "wireless_stranger");
                    stranger.setPos(centre);
                    helper.assertValueEqual(WirelessTerminalItem.reach(stranger, terminal), Reach.LOCKED,
                            "without research");
                }).thenSucceed();
    }

    /**
     * {@code modules.wireless} off: a bound terminal in range reaches nothing. {@code modules.dataGeodes} off: budding
     * data crystal is hidden and does not grow on random ticks, and the geode feature places nothing; on again, it
     * grows.
     */
    private static void modulesOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos core = new BlockPos(5, 1, 2);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.setBlock(core.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ServerPlayer player = researched(helper, "wireless_switch", true);
        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        terminal.set(ModDataComponents.WIRELESS_LINK.get(), GlobalPos.of(level.dimension(), helper.absolutePos(core)));
        terminal.set(ModDataComponents.WIRELESS_ENERGY.get(), 100_000);
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        player.setPos(helper.absolutePos(core).getCenter());
        BlockPos budding = new BlockPos(20, 2, 20);
        helper.startSequence().thenWaitUntil(() -> helper
                .assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 1, "mount active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(WirelessTerminalItem.reach(player, terminal), Reach.OK, "on: reaches");
                    StorageModules.WIRELESS.overrideEnabled(false);
                    try {
                        helper.assertValueEqual(WirelessTerminalItem.reach(player, terminal), Reach.DISABLED,
                                "off: disabled");
                        helper.assertTrue(WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND) == null,
                                "off: a terminal source");
                    } finally {
                        StorageModules.WIRELESS.overrideEnabled(null);
                    }
                    helper.setBlock(budding, DataCrystalContent.BUDDING_DATA_CRYSTAL.get());
                    StorageModules.DATA_GEODES.overrideEnabled(false);
                    try {
                        helper.assertTrue(ContentToggle.hiddenItems()
                                .contains(DataCrystalContent.BUDDING_DATA_CRYSTAL.get().asItem()), "budding hidden");
                        randomTicks(helper, budding, 200);
                        helper.assertValueEqual(buds(helper, budding), 0, "buds grown with geodes off");
                        RandomSource random = RandomSource.create(7);
                        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
                        for (int i = 0; i < 100; i++) {
                            helper.assertFalse(
                                    WorldgenContent.DEEP_DATA_GEODE.get()
                                            .place(new FeaturePlaceContext<>(Optional.empty(), level,
                                                    level.getChunkSource().getGenerator(), random, origin,
                                                    NoneFeatureConfiguration.INSTANCE)),
                                    "geode placed with geodes off");
                        }
                    } finally {
                        StorageModules.DATA_GEODES.overrideEnabled(null);
                    }
                    helper.assertFalse(ContentToggle.hiddenItems()
                            .contains(DataCrystalContent.BUDDING_DATA_CRYSTAL.get().asItem()), "budding shown again");
                    randomTicks(helper, budding, 200);
                    helper.assertTrue(buds(helper, budding) > 0, "no buds with geodes on");
                }).thenSucceed();
    }

    /**
     * Two tier-3 antennas pair, so the far antenna's route takes the 64-channel link, and then a tier-1 cable run joins
     * both sides into one loop. Unpairing them cuts that link inside the one network: the routes are patched without a
     * full rebuild (a small network, no root link cut), the far antenna is routed over the cable at the cable's width,
     * and the device behind it keeps its zone and can still store.
     */
    private static void unpairPatchesRoutes(GameTestHelper helper) {
        BlockPos core = new BlockPos(2, 1, 2);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        // The near antenna sits on the core so the link is the widest route; the cable run starts beside the core.
        BlockPos near = core.east();
        BlockPos far = new BlockPos(30, 1, 2);
        helper.setBlock(near, SignalContent.ANTENNA_TIER_3.get());
        helper.setBlock(far, SignalContent.ANTENNA_TIER_3.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, far.east());
        ServerLevel level = helper.getLevel();
        long nearPos = helper.absolutePos(near).asLong();
        long farPos = helper.absolutePos(far).asLong();
        int[] before = new int[2];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core))
                .thenExecute(() -> helper.assertValueEqual(
                        pair(level, helper.absolutePos(near), helper.absolutePos(far)), "", "pair refused"))
                .thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, core).routeParent(farPos),
                        nearPos, "far antenna routed over the link"))
                .thenExecute(() -> {
                    // The cable run comes after the pairing and attaches to the routes already there, so the cut
                    // below is the only route change.
                    for (int x = 2; x <= 30; x++) {
                        cable(helper, new BlockPos(x, 1, 3));
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, core).nodeCount(), 33,
                        "nodes with the cable run"))
                .thenIdle(5).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(network.segmentCapacity(farPos), 64, "link width");
                    NetworkGameTests.power(helper, core, 10_000);
                    before[0] = network.rebuilds();
                    before[1] = network.patches();
                    antenna(helper, near).unpair();
                    helper.assertValueEqual(antenna(helper, far).partner(), Antennas.NONE, "far end still paired");
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertTrue(network.patches() > before[1] && network.rebuilds() == before[0],
                            "routes not patched, or fully rebuilt");
                }).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertTrue(device.network() == network, "unpairing split the network");
                    helper.assertValueEqual(network.routeParent(farPos),
                            helper.absolutePos(new BlockPos(30, 1, 3)).asLong(), "far antenna's route after unpairing");
                    helper.assertValueEqual(network.segmentCapacity(farPos), 8, "cable width after unpairing");
                    helper.assertTrue(network.zone(device.getBlockPos().asLong()) >= 0, "device lost its zone");
                    helper.assertValueEqual(NetworkGameTests.insert(network, ItemKey.of(Items.COBBLESTONE), 5), 5L,
                            "stored after unpairing");
                }).thenSucceed();
    }

    /**
     * A tier-1 cable run already joins a core's side to a far antenna and a device; pairing two tier-3 antennas inside
     * that one network routes the far antenna over the 64-channel link at once (a full rebuild), not only after some
     * later rebuild.
     */
    private static void pairInsideNetwork(GameTestHelper helper) {
        BlockPos core = new BlockPos(2, 1, 2);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        BlockPos near = core.east();
        BlockPos far = new BlockPos(30, 1, 2);
        helper.setBlock(near, SignalContent.ANTENNA_TIER_3.get());
        helper.setBlock(far, SignalContent.ANTENNA_TIER_3.get());
        for (int x = 2; x <= 30; x++) {
            cable(helper, new BlockPos(x, 1, 3));
        }
        TestStorage.TestDevice device = NetworkGameTests.device(helper, far.east());
        ServerLevel level = helper.getLevel();
        long nearPos = helper.absolutePos(near).asLong();
        long farPos = helper.absolutePos(far).asLong();
        int[] rebuilds = new int[1];
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(device.network() == NetworkGameTests.state(helper, core), "device joined over the cable"))
                .thenIdle(5).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(network.segmentCapacity(farPos), 8, "cable width before pairing");
                    rebuilds[0] = network.rebuilds();
                    helper.assertValueEqual(pair(level, helper.absolutePos(near), helper.absolutePos(far)), "",
                            "pair refused");
                })
                // A rebuild is rate-limited, so allow a few ticks, far fewer than the test's timeout.
                .thenExecuteAfter(25, () -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertTrue(network.rebuilds() > rebuilds[0], "no rebuild after pairing");
                    helper.assertValueEqual(network.routeParent(farPos), nearPos, "far antenna's route after pairing");
                    helper.assertValueEqual(network.segmentCapacity(farPos), 64, "link width after pairing");
                }).thenSucceed();
    }

    /**
     * The wireless terminal runs on its own FE: uncharged it does not open; a charger fills it through the item energy
     * capability ({@code maxInput} per transfer, nothing drawn out); every insert or extract costs
     * {@code energyPerOperation} (50 in the standalone preset), a rolled-back one nothing; below that cost it moves
     * nothing and shows no power.
     */
    private static void wirelessCharge(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 2);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.setBlock(core.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        cable(helper, new BlockPos(5, 1, 3));
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, new BlockPos(5, 1, 4));
        ServerLevel level = helper.getLevel();
        ServerPlayer player = researched(helper, "wireless_charger", true);
        player.getAbilities().instabuild = false;
        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        terminal.set(ModDataComponents.WIRELESS_LINK.get(), GlobalPos.of(level.dimension(), helper.absolutePos(core)));
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        player.setPos(helper.absolutePos(core).getCenter());
        StackKey cobble = ItemKey.of(Items.COBBLESTONE);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(coreEntity(helper, core).services(NetworkService.WIRELESS_ACCESS), 1,
                    "mount active");
            helper.assertTrue(drive.network() == NetworkGameTests.state(helper, core), "drive joined");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            helper.assertValueEqual(WirelessTerminalItem.reach(player, player.getMainHandItem()), Reach.NO_POWER,
                    "uncharged");
            helper.assertTrue(WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND) == null,
                    "uncharged terminal opened");
            EnergyHandler battery = ItemAccess.forPlayerSlot(player, player.getInventory().getSelectedSlot())
                    .getCapability(Capabilities.Energy.ITEM);
            if (battery == null) {
                throw helper.assertionException(Component.literal("no item energy capability on the terminal"));
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(battery.insert(2_000, transaction), 1_000, "one transfer's max input");
                transaction.commit();
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(battery.insert(1_000, transaction), 1_000, "second transfer");
                helper.assertValueEqual(battery.extract(500, transaction), 0, "drawn out");
                transaction.commit();
            }
            helper.assertValueEqual(WirelessTerminalItem.energy(player.getMainHandItem()), 2_000, "charge");
            helper.assertValueEqual(WirelessTerminalItem.reach(player, player.getMainHandItem()), Reach.OK, "charged");
            WirelessTerminalSource source = WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND);
            if (source == null) {
                throw helper.assertionException(Component.literal("charged terminal did not open"));
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(source.insert(cobble, 10, transaction), 10L, "stored");
                transaction.commit();
            }
            helper.assertValueEqual(WirelessTerminalItem.energy(player.getMainHandItem()), 1_950, "after one insert");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(source.extract(cobble, 4, transaction), 4L, "simulated extract");
            }
            helper.assertValueEqual(WirelessTerminalItem.energy(player.getMainHandItem()), 1_950, "after a rollback");
            player.getMainHandItem().set(ModDataComponents.WIRELESS_ENERGY.get(), 40);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(source.extract(cobble, 4, transaction), 0L, "taken without the FE");
                transaction.commit();
            }
            helper.assertValueEqual(source.status(TerminalMenu.STATUS), TerminalStatus.NO_POWER.index(),
                    "status when drained");
            helper.assertValueEqual(WirelessTerminalItem.energy(player.getMainHandItem()), 40, "spent when refused");
            helper.assertValueEqual(NetworkGameTests.state(helper, core).contents().amount(cobble), 10L,
                    "network contents");
        }).thenSucceed();
    }

    private static void expectReach(GameTestHelper helper, ServerPlayer player, Vec3 at, Reach expected, String what) {
        player.setPos(at);
        helper.assertValueEqual(WirelessTerminalItem.reach(player, player.getMainHandItem()), expected, what);
    }

    private static void randomTicks(GameTestHelper helper, BlockPos pos, int ticks) {
        RandomSource random = RandomSource.create(3);
        BlockPos at = helper.absolutePos(pos);
        for (int i = 0; i < ticks; i++) {
            BlockState state = helper.getLevel().getBlockState(at);
            state.randomTick(helper.getLevel(), at, random);
        }
    }

    private static int buds(GameTestHelper helper, BlockPos pos) {
        int buds = 0;
        for (Direction side : Direction.values()) {
            if (!helper.getBlockState(pos.relative(side)).is(Blocks.AIR)) {
                buds++;
            }
        }
        return buds;
    }

    /** A player whose team completed Antennas and, with {@code wireless}, Wireless. */
    private static ServerPlayer researched(GameTestHelper helper, String name, boolean wireless) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, name);
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        List<String> nodes = wireless
                ? List.of("port_tier_2", "antennas", "wireless")
                : List.of("port_tier_2", "antennas");
        for (String node : nodes) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        return player;
    }

    /** {@link AntennaLinking#pair}, with an empty string for a pairing that worked. */
    private static String pair(ServerLevel level, BlockPos a, BlockPos b) {
        String refused = AntennaLinking.pair(level, a, b);
        return refused == null ? "" : refused;
    }

    private static AntennaBlockEntity antenna(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, AntennaBlockEntity.class);
    }

    private static ControllerBlockEntity coreEntity(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ControllerBlockEntity.class);
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
    }
}
