package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.AnchorChunks;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.network.NetworkService;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.silencebound.maxtech.storage.research.Research;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The network anchor (M6c2 area G; a network device since M7R item 4, here placed against the controller): it keeps the
 * chunks of the controller, the drives and the cables between them loaded through NeoForge block tickets owned by the
 * core, lets them go when the network shrinks or the anchor breaks, shares one chunk cap per team, and does nothing
 * with the cap at 0. Each test has its own batch, so no other anchor holds tickets in its chunks.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ControllerAnchorGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String KEEPS = "controller_anchor_keeps_and_releases_chunks";
    private static final String TEAM_CAP = "controller_anchor_team_cap";
    private static final String OFF = "controller_anchor_off_at_zero";
    private static final String TEAM_MOVE = "controller_anchor_owner_changes_team";
    private static final String SWEEP = "controller_anchor_releases_unclaimed_tickets";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(KEEPS, ControllerAnchorGameTests::keepsAndReleasesChunks);
        register(TEAM_CAP, ControllerAnchorGameTests::teamCap);
        register(OFF, ControllerAnchorGameTests::offAtZero);
        register(TEAM_MOVE, ControllerAnchorGameTests::ownerChangesTeam);
        register(SWEEP, ControllerAnchorGameTests::releasesUnclaimed);
    }

    private ControllerAnchorGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("controller_anchor"),
                StorageTestEnvironment.plain());
        Holder<TestEnvironmentDefinition<?>> cap3 = event.registerEnvironment(
                MaxtechStorage.id("controller_anchor_cap_3"),
                new StorageTestEnvironment("devices.networkAnchor.chunks", "3", false));
        Holder<TestEnvironmentDefinition<?>> cap0 = event.registerEnvironment(
                MaxtechStorage.id("controller_anchor_cap_0"),
                new StorageTestEnvironment("devices.networkAnchor.chunks", "0", false));
        StorageTests.test(event, plain, KEEPS, STRUCTURE, 600);
        StorageTests.test(event, cap3, TEAM_CAP, STRUCTURE, 600);
        StorageTests.test(event, cap3, TEAM_MOVE, STRUCTURE, 600);
        StorageTests.test(event, plain, SWEEP, STRUCTURE, 600);
        StorageTests.test(event, cap0, OFF, STRUCTURE, 600);
    }

    /**
     * A core with an anchor and a drive 39 cables away: the anchor holds every chunk from the core to the drive and
     * NeoForge has a block ticket on the drive's chunk. Breaking the drive releases the chunks only it needed, breaking
     * the anchor releases the rest.
     */
    private static void keepsAndReleasesChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos core = new BlockPos(1, 1, 13);
        BlockPos anchor = core.above();
        BlockPos drive = new BlockPos(40, 1, 13);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        cables(helper, 2, 39, 13);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, drive);
        ControllerClusterGameTests.expansion(helper, anchor, ExpansionContent.NETWORK_ANCHOR);
        BlockPos absoluteCore = helper.absolutePos(core);
        LongSet route = chunks(helper, 1, 40, 13);
        long driveChunk = ChunkPos.pack(helper.absolutePos(drive));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(device.network() == NetworkGameTests.state(helper, core),
                        "network formed"))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(new LongOpenHashSet(AnchorChunks.held(level, absoluteCore)),
                                new LongOpenHashSet(route), "chunks held"))
                .thenExecute(() -> {
                    helper.assertTrue(route.size() >= 3, "the route crosses chunks: " + route.size());
                    helper.assertTrue(blockTicket(level, driveChunk), "a block ticket on the drive's chunk");
                    helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).anchoredChunks(),
                            route.size(), "core's count");
                    helper.destroyBlock(drive);
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(new LongOpenHashSet(AnchorChunks.held(level, absoluteCore)),
                                LongOpenHashSet.of(ChunkPos.pack(absoluteCore)), "only the cluster's chunk"))
                .thenExecute(() -> {
                    // Another anchor (a repeated run next to this one) may hold the chunk too.
                    helper.assertTrue(!blockTicket(level, driveChunk) || AnchorChunks.isHeld(level, driveChunk),
                            "the drive's chunk is let go");
                    helper.destroyBlock(anchor);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(AnchorChunks.held(level, absoluteCore).size(), 0,
                        "nothing held without the anchor"))
                .thenExecute(() -> helper.assertTrue(
                        !blockTicket(level, ChunkPos.pack(absoluteCore))
                                || AnchorChunks.isHeld(level, ChunkPos.pack(absoluteCore)),
                        "the core's chunk is let go"))
                .thenSucceed();
    }

    /**
     * Two anchored networks of one owner share a cap of 3 chunks: the first takes all three, the second gets none and
     * says so. Breaking the first anchor gives the second the room.
     */
    private static void teamCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "anchor_owner");
        BlockPos first = new BlockPos(1, 1, 5);
        BlockPos second = new BlockPos(1, 1, 20);
        helper.setBlock(first, ControllerContent.CONTROLLER.get());
        helper.setBlock(second, ControllerContent.CONTROLLER.get());
        NetworkGameTests.controllerEntity(helper, first).setOwner(OwnerRecord.of(owner));
        NetworkGameTests.controllerEntity(helper, second).setOwner(OwnerRecord.of(owner));
        cables(helper, 2, 39, 5);
        cables(helper, 2, 39, 20);
        NetworkGameTests.device(helper, new BlockPos(40, 1, 5));
        NetworkGameTests.device(helper, new BlockPos(40, 1, 20));
        ControllerClusterGameTests.expansion(helper, first.above(), ExpansionContent.NETWORK_ANCHOR);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(held(helper, first), 3, "first takes the cap"))
                .thenExecute(() -> ControllerClusterGameTests.expansion(helper, second.above(),
                        ExpansionContent.NETWORK_ANCHOR))
                .thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(held(helper, second), 0, "no room left for the team");
                    helper.assertValueEqual(held(helper, first), 3, "first keeps its chunks");
                    helper.assertTrue(ControllerClusterGameTests.keys(helper, second, BlockInfoRequest.Detail.FULL)
                            .contains(ExpansionContent.ANCHOR_CAPPED_KEY), "capped line");
                    helper.destroyBlock(first.above());
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(held(helper, first), 0, "first let go");
                    helper.assertValueEqual(held(helper, second), 3, "second took the room");
                }).thenExecute(() -> helper.destroyBlock(second.above()))
                .thenWaitUntil(() -> helper.assertValueEqual(held(helper, second), 0, "second let go")).thenSucceed();
    }

    /**
     * Two owners in their own teams each anchor 3 chunks (cap 3). When the second owner joins the first one's team, its
     * core counts against that team's cap at once (the team change re-evaluates it) and lets its chunks go.
     */
    private static void ownerChangesTeam(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Names (and so scoreboard entries and research teams) unique per run, so repeated runs don't share a team.
        String suffix = Integer.toHexString(level.getRandom().nextInt());
        ServerPlayer first = NetworkGameTests.player(level, "anc1_" + suffix);
        ServerPlayer second = NetworkGameTests.player(level, "anc2_" + suffix);
        Research.team(first, true);
        BlockPos a = new BlockPos(1, 1, 5);
        BlockPos b = new BlockPos(1, 1, 20);
        helper.setBlock(a, ControllerContent.CONTROLLER.get());
        helper.setBlock(b, ControllerContent.CONTROLLER.get());
        NetworkGameTests.controllerEntity(helper, a).setOwner(OwnerRecord.of(first));
        NetworkGameTests.controllerEntity(helper, b).setOwner(OwnerRecord.of(second));
        cables(helper, 2, 39, 5);
        cables(helper, 2, 39, 20);
        NetworkGameTests.device(helper, new BlockPos(40, 1, 5));
        NetworkGameTests.device(helper, new BlockPos(40, 1, 20));
        ControllerClusterGameTests.expansion(helper, a.above(), ExpansionContent.NETWORK_ANCHOR);
        ControllerClusterGameTests.expansion(helper, b.above(), ExpansionContent.NETWORK_ANCHOR);
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam("anc_" + suffix);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(held(helper, a), 3, "first owner's anchor");
            helper.assertValueEqual(held(helper, b), 3, "second owner's anchor, own team");
        }).thenIdle(120).thenExecute(() -> {
            // Past the load window: nothing re-checks the anchors now but the team change itself.
            helper.assertValueEqual(held(helper, b), 3, "second owner's anchor after the load window");
            scoreboard.addPlayerToTeam(first.getScoreboardName(), team);
            scoreboard.addPlayerToTeam(second.getScoreboardName(), team);
            Research.team(second, true);
        }).thenExecuteAfter(10, () -> {
            helper.assertValueEqual(held(helper, a), 3, "first keeps its chunks");
            helper.assertValueEqual(held(helper, b), 0, "no room left in the shared team");
        }).thenExecute(() -> {
            helper.destroyBlock(a);
            helper.destroyBlock(b);
            scoreboard.removePlayerTeam(team);
        }).thenSucceed();
    }

    /**
     * Tickets the level brought back for a core that never claims them (its core is gone) are released once the sweep
     * window after their seeding has passed.
     */
    private static void releasesUnclaimed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos owner = helper.absolutePos(new BlockPos(3, 1, 3));
        AnchorChunks.seed(level, owner, LongOpenHashSet.of(ChunkPos.pack(owner)));
        helper.assertValueEqual(AnchorChunks.held(level, owner).size(), 1, "seeded");
        helper.startSequence().thenIdle(150).thenExecute(
                () -> helper.assertValueEqual(AnchorChunks.held(level, owner).size(), 1, "kept during the window"))
                .thenWaitUntil(() -> helper.assertValueEqual(AnchorChunks.held(level, owner).size(), 0,
                        "released after the window"))
                .thenSucceed();
    }

    /** With the cap at 0 an anchor holds nothing. */
    private static void offAtZero(GameTestHelper helper) {
        BlockPos core = new BlockPos(1, 1, 13);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        cables(helper, 2, 20, 13);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(21, 1, 13));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.NETWORK_ANCHOR);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(device.network() == NetworkGameTests.state(helper, core),
                        "network formed"))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).services(NetworkService.ANCHOR), 1,
                        "anchor on the network"))
                .thenIdle(10).thenExecute(() -> helper.assertValueEqual(held(helper, core), 0, "nothing held at cap 0"))
                .thenSucceed();
    }

    private static int held(GameTestHelper helper, BlockPos core) {
        return AnchorChunks.held(helper.getLevel(), helper.absolutePos(core)).size();
    }

    private static void cables(GameTestHelper helper, int fromX, int toX, int z) {
        for (int x = fromX; x <= toX; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, z)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
    }

    /** The chunks of the blocks from x {@code fromX} to {@code toX} on row {@code z}. */
    private static LongSet chunks(GameTestHelper helper, int fromX, int toX, int z) {
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (int x = fromX; x <= toX; x++) {
            chunks.add(ChunkPos.pack(helper.absolutePos(new BlockPos(x, 1, z))));
        }
        return chunks;
    }

    /** Whether NeoForge holds a block-owned forced ticket (any mod's) on {@code chunk}. */
    private static boolean blockTicket(ServerLevel level, long chunk) {
        TicketStorage tickets = level.getDataStorage().get(TicketStorage.TYPE);
        if (tickets == null) {
            return false;
        }
        for (Ticket ticket : tickets.getTickets(chunk)) {
            if (ticket.getType() == NeoForgeMod.BLOCK_TICKET.value()) {
                return true;
            }
        }
        return false;
    }
}
