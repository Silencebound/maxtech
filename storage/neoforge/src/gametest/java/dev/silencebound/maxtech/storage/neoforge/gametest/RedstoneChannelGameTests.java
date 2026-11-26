package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlock;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionTexts;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import dev.silencebound.maxtech.storage.subnet.SubnetMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Bundled redstone channels (M7L2 row 22): a condition switch in "redstone to channel" mode writes the redstone coming
 * into it to one of the network's 16 channels, one in "channel to redstone" mode gives the channel's value; logic
 * graphs read and write channels; a controller's share survives saving; subnets keep their own channels; nothing
 * evaluates again when a value does not change. The row is the logic controller layout
 * ({@link LogicControllerGameTests#setUp}): core at x 1, test device at 2, logic controller at 3; the writer at 4 with
 * a lever on top, a test device at 5 (so the reader's output cannot reach the writer) and the reader at 6.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class RedstoneChannelGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final int RED = 14;
    private static final int BLUE = 11;
    private static final BlockPos WRITER = NetworkGameTests.at(4);
    private static final BlockPos LEVER = WRITER.above();
    private static final BlockPos READER = NetworkGameTests.at(6);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("channel_lever_to_reader", RedstoneChannelGameTests::leverToReader);
        register("channel_survives_reload", RedstoneChannelGameTests::survivesReload);
        register("channel_subnet_keeps_its_own", RedstoneChannelGameTests::subnetKeepsItsOwn);
        register("channel_logic_reads_and_writes", RedstoneChannelGameTests::logicReadsAndWrites);
        register("channel_no_updates_when_unchanged", RedstoneChannelGameTests::noUpdatesWhenUnchanged);
        register("channel_guest_needs_access", RedstoneChannelGameTests::guestNeedsAccess);
        register("channel_broken_writer_drops_reader", RedstoneChannelGameTests::brokenWriterDropsReader);
        register("channel_reader_ignores_other_research", RedstoneChannelGameTests::readerIgnoresOtherResearch);
    }

    private RedstoneChannelGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("redstone_channel"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    // ---- helpers

    private record Setup(LogicControllerBlockEntity controller, ServerPlayer owner) {
    }

    /** The logic controller row; its owner has the condition switch researched. */
    private static Setup setUp(GameTestHelper helper, String name) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, name);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), name + "_owner");
        entity.setOwner(OwnerRecord.of(owner));
        research(helper, owner);
        NetworkGameTests.device(helper, NetworkGameTests.at(5));
        return new Setup(entity, owner);
    }

    private static void research(GameTestHelper helper, ServerPlayer owner) {
        ResearchTeam team = Research.team(owner, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
    }

    /** A condition switch block at {@code pos} in {@code type} mode on {@code channel}, owned by {@code owner}. */
    private static ConditionSwitchBlockEntity channelSwitch(GameTestHelper helper, BlockPos pos, ServerPlayer owner,
            ConditionType type, int channel) {
        helper.setBlock(pos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
        entity.setOwner(OwnerRecord.of(owner));
        entity.worker().setSettings(ConditionSettings.DEFAULT.withType(type).withChannel(channel));
        return entity;
    }

    /** A lever lying on top of the block at {@code below}, off. */
    private static void lever(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
    }

    private static void pull(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockState lever = helper.getLevel().getBlockState(absolute);
        ((LeverBlock) lever.getBlock()).pull(lever, helper.getLevel(), absolute, null);
    }

    /** The switch's strength, the signal its block gives and its powered state all say {@code strength}. */
    private static void gives(GameTestHelper helper, BlockPos pos, int strength) {
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
        helper.assertValueEqual(entity.worker().strength(), strength, "switch strength at " + pos.toShortString());
        BlockPos absolute = helper.absolutePos(pos);
        helper.assertValueEqual(
                helper.getLevel().getBlockState(absolute).getSignal(helper.getLevel(), absolute, Direction.NORTH),
                strength, "signal the block gives");
        helper.assertValueEqual(helper.getBlockState(pos).getValue(ConditionSwitchBlock.POWERED), strength > 0,
                "powered");
    }

    private static NetworkState network(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return LogicControllerGameTests.network(helper, entity);
    }

    // ---- tests

    /**
     * A lever on the red writer gives 15 to the red reader elsewhere on the network, and the network's red channel
     * holds 15; the lever off gives 0 again.
     */
    private static void leverToReader(GameTestHelper helper) {
        Setup setup = setUp(helper, "ch_lever");
        ConditionSwitchBlockEntity writer = channelSwitch(helper, WRITER, setup.owner(), ConditionType.CHANNEL_IN, RED);
        channelSwitch(helper, READER, setup.owner(), ConditionType.CHANNEL_OUT, RED);
        lever(helper, LEVER);
        helper.startSequence().thenWaitUntil(() -> network(helper, setup.controller()))
                .thenWaitUntil(() -> helper.assertTrue(writer.network() != null, "writer on the network"))
                .thenExecute(() -> pull(helper, LEVER))
                .thenWaitUntil(() -> helper.assertValueEqual(writer.worker().input(), 15, "writer input"))
                .thenWaitUntil(() -> helper.assertValueEqual(network(helper, setup.controller()).channels().value(RED),
                        15, "red channel"))
                .thenWaitUntil(() -> gives(helper, READER, 15)).thenExecute(() -> {
                    helper.assertValueEqual(network(helper, setup.controller()).channels().value(BLUE), 0,
                            "blue channel");
                    gives(helper, WRITER, 0);
                    pull(helper, LEVER);
                }).thenWaitUntil(() -> gives(helper, READER, 0)).thenWaitUntil(() -> helper
                        .assertValueEqual(network(helper, setup.controller()).channels().value(RED), 0, "red channel"))
                .thenSucceed();
    }

    /** A constant 7 into "redstone out" on the red channel. */
    private static LogicGraph<LogicKey> writeRed(long value) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", value))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("channel", "red")).wire(1, "value", 2, "strength")
                .build();
    }

    /**
     * A graph writes 7 to red; the controller's share stays once the graph is removed. Removed from the world, its
     * share goes and red reads 0; placed again with its saved data, red is 7 at once (no graph left to write it) and
     * the reader gives 7.
     */
    private static void survivesReload(GameTestHelper helper) {
        Setup setup = setUp(helper, "ch_reload");
        LogicControllerBlockEntity entity = setup.controller();
        BlockPos at = NetworkGameTests.at(3);
        NetworkGameTests.device(helper, WRITER);
        channelSwitch(helper, READER, setup.owner(), ConditionType.CHANNEL_OUT, RED);
        CompoundTag[] saved = new CompoundTag[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Red", writeRed(7)))
                .thenWaitUntil(() -> gives(helper, READER, 7)).thenExecute(() -> {
                    entity.worker().removeGraph(0);
                    helper.assertValueEqual(network(helper, entity).channels().value(RED), 7,
                            "the share stays without the graph");
                    saved[0] = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(at, Blocks.AIR);
                }).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
                    helper.assertValueEqual(network.channels().value(RED), 0, "red without the controller");
                }).thenWaitUntil(() -> gives(helper, READER, 0)).thenExecute(() -> {
                    helper.setBlock(at, LogicContent.CONTROLLER.get());
                    LogicControllerGameTests.entity(helper, at).loadCustomOnly(TagValueInput
                            .create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved[0]));
                }).thenWaitUntil(() -> {
                    LogicControllerBlockEntity again = LogicControllerGameTests.entity(helper, at);
                    helper.assertValueEqual(again.worker().size(), 0, "no graph");
                    helper.assertValueEqual(network(helper, again).channels().value(RED), 7, "red after the load");
                }).thenWaitUntil(() -> gives(helper, READER, 7)).thenSucceed();
    }

    /**
     * A merged subnet still has channels of its own: the parent's red writer lights the parent's red reader, while the
     * subnet's red reader stays dark and the subnet's red channel stays 0.
     */
    private static void subnetKeepsItsOwn(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "ch_subnet_owner");
        research(helper, owner);
        BlockPos writerPos = new BlockPos(2, 1, 2);
        BlockPos parentReader = new BlockPos(4, 1, 2);
        BlockPos subnetReader = NetworkGameTests.at(8);
        channelSwitch(helper, writerPos, owner, ConditionType.CHANNEL_IN, RED);
        channelSwitch(helper, parentReader, owner, ConditionType.CHANNEL_OUT, RED);
        ConditionSwitchBlockEntity child = channelSwitch(helper, subnetReader, owner, ConditionType.CHANNEL_OUT, RED);
        lever(helper, writerPos.above());
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            helper.assertTrue(subnet.parent() == parent, "uplink in the parent");
            helper.assertTrue(child.network() != null && child.network() != parent, "the subnet is its own network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
            subnet.buffer().receive(10_000);
            subnet.setMode(SubnetMode.MERGED);
            pull(helper, writerPos.above());
        }).thenWaitUntil(() -> gives(helper, parentReader, 15)).thenIdle(20).thenExecute(() -> {
            gives(helper, subnetReader, 0);
            NetworkState inner = child.network();
            if (inner == null) {
                throw helper.assertionException(Component.literal("subnet reader left its network"));
            }
            helper.assertValueEqual(inner.channels().value(RED), 0, "the subnet's red channel");
        }).thenSucceed();
    }

    /**
     * A graph copies red to blue: "redstone strength" on red into "redstone out" on blue. The lever on the red writer
     * makes the blue reader give 15, and the read node shows 15; off again, both 0.
     */
    private static void logicReadsAndWrites(GameTestHelper helper) {
        Setup setup = setUp(helper, "ch_logic");
        LogicControllerBlockEntity entity = setup.controller();
        channelSwitch(helper, WRITER, setup.owner(), ConditionType.CHANNEL_IN, RED);
        channelSwitch(helper, READER, setup.owner(), ConditionType.CHANNEL_OUT, BLUE);
        LogicGraph<LogicKey> copy = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.REDSTONE_STRENGTH, s -> s.withText("channel", "red"))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("channel", "blue")).wire(1, "strength", 2, "strength")
                .build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Copy", copy)).thenIdle(2)
                .thenExecute(() -> helper.setBlock(LEVER, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> gives(helper, READER, 15)).thenExecute(() -> {
                    // The controller itself gets no redstone (the block only weakly powers the writer).
                    helper.assertValueEqual(
                            helper.getLevel().getBestNeighborSignal(helper.absolutePos(NetworkGameTests.at(3))), 0,
                            "redstone into the controller");
                    LogicEvaluator<LogicKey> evaluator = entity.worker().evaluator(0)
                            .orElseThrow(() -> helper.assertionException(Component.literal("graph paused")));
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(1, "strength")), 15L, "red read");
                    helper.assertValueEqual(entity.worker().channelShares()[BLUE], 15, "the controller's blue share");
                    helper.setBlock(LEVER, Blocks.AIR);
                }).thenWaitUntil(() -> gives(helper, READER, 0)).thenSucceed();
    }

    /**
     * A guest's logic controller writes 7 to red, but the red reader belongs to another player who has not trusted the
     * guest: the reader stays dark (the network-wide red is still 7, so logic reads see it) and the network's log says
     * so once. Once the reader's owner trusts the guest, the reader gives 7.
     */
    private static void guestNeedsAccess(GameTestHelper helper) {
        Setup setup = setUp(helper, "ch_guest");
        LogicControllerBlockEntity entity = setup.controller();
        NetworkGameTests.device(helper, WRITER);
        ServerPlayer other = NetworkGameTests.player(helper.getLevel(), "ch_guest_other");
        research(helper, other);
        ConditionSwitchBlockEntity reader = channelSwitch(helper, READER, other, ConditionType.CHANNEL_OUT, RED);
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Red", writeRed(7))).thenWaitUntil(() -> helper
                        .assertValueEqual(network(helper, entity).channels().value(RED), 7, "network-wide red"))
                .thenIdle(30).thenExecute(() -> {
                    gives(helper, READER, 0);
                    long denied = 0;
                    for (NetworkNotices.Notice notice : network(helper, entity).notices().lines()) {
                        if (notice.key().equals(ConditionTexts.LOG_CHANNEL_DENIED)) {
                            denied++;
                        }
                    }
                    helper.assertValueEqual(denied, 1L, "denials logged");
                    reader.setOwner(reader.ownerRecord().withTrusted(setup.owner().getUUID()));
                }).thenWaitUntil(() -> gives(helper, READER, 7)).thenSucceed();
    }

    /** Breaking the red writer while its lever is on takes its share away: the reader drops to 0. */
    private static void brokenWriterDropsReader(GameTestHelper helper) {
        Setup setup = setUp(helper, "ch_broken");
        // The writer sits beside the row, so breaking it leaves the reader on the network.
        NetworkGameTests.device(helper, WRITER);
        BlockPos writer = WRITER.south();
        channelSwitch(helper, writer, setup.owner(), ConditionType.CHANNEL_IN, RED);
        channelSwitch(helper, READER, setup.owner(), ConditionType.CHANNEL_OUT, RED);
        lever(helper, writer.above());
        helper.startSequence().thenWaitUntil(() -> network(helper, setup.controller()))
                .thenExecute(() -> pull(helper, writer.above())).thenWaitUntil(() -> gives(helper, READER, 15))
                .thenExecute(() -> helper.destroyBlock(writer)).thenWaitUntil(() -> gives(helper, READER, 0))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getBlockEntity(READER, ConditionSwitchBlockEntity.class)
                            .network() == network(helper, setup.controller()), "the reader is still on the network");
                    helper.assertValueEqual(network(helper, setup.controller()).channels().value(RED), 0, "red");
                }).thenSucceed();
    }

    /**
     * Once red is 15, a second red writer also at 15, a write on blue and a block update next to the writer change
     * nothing on red: the red reader evaluates no more and red's value changes no more.
     */
    private static void noUpdatesWhenUnchanged(GameTestHelper helper) {
        Setup setup = setUp(helper, "ch_quiet");
        LogicControllerBlockEntity entity = setup.controller();
        channelSwitch(helper, WRITER, setup.owner(), ConditionType.CHANNEL_IN, RED);
        ConditionSwitchBlockEntity reader = channelSwitch(helper, READER, setup.owner(), ConditionType.CHANNEL_OUT,
                RED);
        lever(helper, LEVER);
        int[] before = new int[1];
        long[] changes = new long[1];
        BlockPos second = NetworkGameTests.at(8);
        BlockPos blue = NetworkGameTests.at(7);
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> pull(helper, LEVER))
                .thenWaitUntil(() -> gives(helper, READER, 15)).thenIdle(20).thenExecute(() -> {
                    before[0] = reader.worker().evaluations();
                    changes[0] = network(helper, entity).channels().changes();
                    // A second red writer with no input yet: it writes no share.
                    channelSwitch(helper, second, setup.owner(), ConditionType.CHANNEL_IN, RED);
                    // A blue writer next to it with a full signal: blue changes, red does not.
                    channelSwitch(helper, blue, setup.owner(), ConditionType.CHANNEL_IN, BLUE);
                    helper.setBlock(blue.above(), Blocks.REDSTONE_BLOCK);
                    // A block update next to the first writer: its input is still 15.
                    helper.setBlock(WRITER.south(), Blocks.STONE);
                }).thenWaitUntil(() -> helper.assertValueEqual(network(helper, entity).channels().value(BLUE), 15,
                        "blue channel"))
                .thenIdle(30).thenExecute(() -> {
                    NetworkState network = network(helper, entity);
                    helper.assertValueEqual(network.channels().value(RED), 15, "red channel");
                    helper.assertValueEqual(network.channels().changes(), changes[0] + 1, "only blue changed");
                    helper.assertValueEqual(reader.worker().evaluations(), before[0], "the red reader stayed asleep");
                    // Now the second red writer gets 15 too: red's value stays, the reader's output with it.
                    helper.setBlock(second.above(), Blocks.REDSTONE_BLOCK);
                }).thenIdle(30).thenExecute(() -> {
                    NetworkState network = network(helper, entity);
                    helper.assertValueEqual(network.channels().changes(), changes[0] + 1, "red's value did not move");
                    gives(helper, READER, 15);
                }).thenSucceed();
    }

    /**
     * Research that cannot change the red reader's output does not wake it: another team completing the condition
     * switch node, and the reader owner's own team completing a node the switch does not depend on (regression: every
     * completion anywhere on the server woke every switch, which made {@code channel_no_updates_when_unchanged} flaky
     * when a test elsewhere completed research during its quiet window).
     */
    private static void readerIgnoresOtherResearch(GameTestHelper helper) {
        Setup setup = setUp(helper, "ch_research");
        channelSwitch(helper, WRITER, setup.owner(), ConditionType.CHANNEL_IN, RED);
        ConditionSwitchBlockEntity reader = channelSwitch(helper, READER, setup.owner(), ConditionType.CHANNEL_OUT,
                RED);
        lever(helper, LEVER);
        ServerPlayer other = NetworkGameTests.player(helper.getLevel(), "ch_research_other");
        int[] before = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup.controller()))
                .thenExecute(() -> pull(helper, LEVER)).thenWaitUntil(() -> gives(helper, READER, 15)).thenIdle(20)
                .thenExecute(() -> {
                    before[0] = reader.worker().evaluations();
                    research(helper, other);
                    ResearchTeam team = Research.team(setup.owner(), false);
                    helper.assertValueEqual(
                            Research.spendPoints(helper.getLevel().getServer(), team,
                                    MaxtechStorage.id("family_masks")),
                            Research.Result.DONE, "own team's unrelated node");
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(reader.worker().evaluations(), before[0], "the red reader stayed asleep");
                    gives(helper, READER, 15);
                }).thenSucceed();
    }
}
