package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.sensor.MachineReader;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Machine pools (M7L2 row 17) with real furnaces: each furnace has an export port labelled "furnaces" on top (empty
 * filter, so it moves nothing itself), which reads its furnace directly; a furnace is idle while it reads
 * {@code state.lit} 0. A sensor with the label beside a furnace overrides the port's own read. Sends go to idle
 * furnaces only, by strategy; denied members are skipped; simulate moves nothing and keeps the turn; raw iron in the
 * network and the furnaces always adds up to what was put in.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicPoolGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey IRON = ItemKey.of(Items.RAW_IRON);
    private static final String POOL = "furnaces";
    private static final long STOCK = 100;
    /** Furnace k stands at x = 5 + 3k, south of the cable line; its sensor is east of it, its port on top. */
    private static final int SPACING = 3;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_pool_first_idle_skips_busy", LogicPoolGameTests::firstIdle);
        register("logic_pool_spread_evenly_splits", LogicPoolGameTests::spreadEvenly);
        register("logic_pool_least_full_levels", LogicPoolGameTests::leastFull);
        register("logic_pool_round_robin_turn_survives_load", LogicPoolGameTests::roundRobin);
        register("logic_pool_denied_member_skipped", LogicPoolGameTests::denied);
        register("logic_pool_simulate_moves_nothing", LogicPoolGameTests::simulate);
        register("logic_pool_status_and_member_idle", LogicPoolGameTests::statusAndEvent);
        register("logic_pool_sensor_overrides_direct_read", LogicPoolGameTests::sensorOverrides);
        register("logic_pool_simulate_keeps_the_turn", LogicPoolGameTests::simulateKeepsTurn);
        register("logic_pool_direct_read_follows_faced_block", LogicPoolGameTests::directReadFollows);
        register("logic_pool_direct_comparator_reads", LogicPoolGameTests::directComparator);
    }

    private LogicPoolGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_pool"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 900);
        }
    }

    // ---- set-up

    /** The logic set-up, a cable line along the row, and {@code count} furnaces with their ports. */
    private record Setup(LogicControllerBlockEntity controller, List<FurnaceBlockEntity> furnaces,
            List<PortBlockEntity> ports) {
    }

    private static Setup setUp(GameTestHelper helper, String owner, int count) {
        return setUp(helper, owner, count, 0);
    }

    /** As {@link #setUp(GameTestHelper, String, int)}, with a labelled sensor beside furnace k for each bit k set. */
    private static Setup setUp(GameTestHelper helper, String owner, int count, int sensorMask) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, owner);
        // The network belongs to the controller's owner, so reads charge that team's budget, not a shared one.
        NetworkGameTests.controllerEntity(helper, NetworkGameTests.at(1)).setOwner(entity.ownerRecord());
        ConduitType cable = CableContent.STORAGE_CABLE_TIER_1.type().get();
        NetworkGameTests.cables(helper, 4, 5 + SPACING * count, cable);
        List<FurnaceBlockEntity> furnaces = new ArrayList<>();
        List<PortBlockEntity> ports = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int x = 5 + SPACING * k;
            BlockPos furnace = NetworkGameTests.at(x).south();
            helper.setBlock(furnace, Blocks.FURNACE);
            furnaces.add(helper.getBlockEntity(furnace, FurnaceBlockEntity.class));
            // The port sits on top, facing down into the furnace's input; it links only through its back (up), so
            // cables run up from the line and over it.
            BlockPos line = NetworkGameTests.at(x);
            for (BlockPos cablePos : List.of(line.above(), line.above(2), furnace.above(2))) {
                ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(cablePos), cable, null);
            }
            helper.setBlock(furnace.above(), PortContent.PORT.get().defaultBlockState()
                    .setValue(PortBlock.FACING, Direction.DOWN).setValue(PortBlock.MODE, PortMode.EXPORT));
            PortBlockEntity port = helper.getBlockEntity(furnace.above(), PortBlockEntity.class);
            port.setLabel(DeviceLabel.of(POOL, null));
            ports.add(port);
            if ((sensorMask & 1 << k) != 0) {
                BlockPos sensor = furnace.east();
                helper.setBlock(sensor,
                        SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.WEST));
                helper.getBlockEntity(sensor, SensorBlockEntity.class).setLabel(DeviceLabel.of(POOL, null));
            }
        }
        return new Setup(entity, furnaces, ports);
    }

    /** Lights furnace {@code furnace}: coal and cobblestone, so it burns for the whole test. */
    private static void burn(FurnaceBlockEntity furnace) {
        furnace.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        furnace.setItem(1, new ItemStack(Items.COAL, 8));
        furnace.setChanged();
    }

    /**
     * Lights furnace {@code furnace} with one cobblestone and one coal: once the cobblestone is smelted (200 ticks) the
     * furnace still burns for 1400 ticks with its input empty, so it would take raw iron if a send ignored that it is
     * busy.
     */
    private static void burnEmpty(FurnaceBlockEntity furnace) {
        furnace.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        furnace.setItem(1, new ItemStack(Items.COAL, 1));
        furnace.setChanged();
    }

    /** Whether {@code furnace} burns with nothing left to smelt. */
    private static void burningEmpty(GameTestHelper helper, FurnaceBlockEntity furnace) {
        helper.assertTrue(lit(furnace) && furnace.getItem(0).isEmpty(), "the busy furnace is not burning empty yet");
    }

    private static boolean lit(FurnaceBlockEntity furnace) {
        return furnace.getBlockState().getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
    }

    /** "Redstone into the controller changes" sends {@code amount} raw iron to the pool by {@code strategy}. */
    private static LogicGraph<LogicKey> send(String strategy, long amount) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.MOVE_TO_POOL, pool(s -> s.withItem("item", LogicKey.of(IRON))
                        .withNumber("amount", amount).withText("strategy", strategy)))
                .wire(1, "changed", 2, "do").build();
    }

    private static UnaryOperator<dev.silencebound.maxtech.storage.logic.NodeSettings<LogicKey>> pool(
            UnaryOperator<dev.silencebound.maxtech.storage.logic.NodeSettings<LogicKey>> more) {
        return s -> more.apply(s.withText("label", POOL).withText("idle_value", "state.lit"));
    }

    private static void pulse(GameTestHelper helper) {
        BlockPos top = NetworkGameTests.at(3).above();
        helper.setBlock(top, helper.getBlockState(top).is(Blocks.REDSTONE_BLOCK) ? Blocks.AIR : Blocks.REDSTONE_BLOCK);
    }

    private static NetworkState network(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return LogicControllerGameTests.network(helper, entity);
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        if (entity.worker().size() == 0) {
            throw helper.assertionException(Component.literal("no graph yet"));
        }
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
    }

    private static void ran(GameTestHelper helper, LogicControllerBlockEntity entity) {
        helper.assertTrue(evaluator(helper, entity).runs() > 0, "graph 0 has not run yet");
    }

    private static List<String> whys(GameTestHelper helper, LogicControllerBlockEntity entity, int node) {
        RunTrace trace = evaluator(helper, entity).trace();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < trace.size(); i++) {
            if (trace.node(i) == node) {
                out.add(trace.reason(i));
            }
        }
        return out;
    }

    /** Raw iron in furnace {@code furnace}'s input. */
    private static int iron(FurnaceBlockEntity furnace) {
        ItemStack input = furnace.getItem(0);
        return input.is(Items.RAW_IRON) ? input.getCount() : 0;
    }

    private static long[] irons(Setup setup) {
        long[] out = new long[setup.furnaces().size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = iron(setup.furnaces().get(k));
        }
        return out;
    }

    /** Raw iron in the network and in every furnace adds up to what was put in: nothing made, nothing lost. */
    private static void conserved(GameTestHelper helper, Setup setup) {
        long total = network(helper, setup.controller()).contents().amount(IRON);
        for (FurnaceBlockEntity furnace : setup.furnaces()) {
            total += iron(furnace);
        }
        helper.assertValueEqual(total, STOCK, "raw iron in the network and the furnaces");
    }

    // ---- tests

    /**
     * A burning furnace with an empty input and two idle ones: first idle sends all 8 to one idle furnace, none to the
     * burning one, and a second send goes to the same furnace (first idle keeps no turn).
     */
    private static void firstIdle(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_first", 3);
        LogicControllerBlockEntity entity = setup.controller();
        burnEmpty(setup.furnaces().get(0));
        long[] first = new long[3];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), IRON, STOCK);
            entity.worker().setGraph(0, "First idle", send("first_idle", 8));
        }).thenWaitUntil(() -> {
            ran(helper, entity);
            burningEmpty(helper, setup.furnaces().get(0));
        }).thenIdle(5).thenExecute(() -> pulse(helper)).thenWaitUntil(() -> {
            long[] now = irons(setup);
            helper.assertValueEqual(now[1] + now[2], 8L, "sent to the idle furnaces");
        }).thenExecute(() -> {
            long[] now = irons(setup);
            helper.assertValueEqual(now[0], 0L, "nothing to the burning furnace");
            helper.assertTrue(now[1] == 8 || now[2] == 8, "all 8 to one furnace: " + now[1] + ", " + now[2]);
            System.arraycopy(now, 0, first, 0, 3);
            conserved(helper, setup);
        }).thenIdle(25).thenExecute(() -> pulse(helper)).thenWaitUntil(() -> {
            long[] now = irons(setup);
            helper.assertValueEqual(now[1] + now[2], 16L, "the second send");
        }).thenExecute(() -> {
            long[] now = irons(setup);
            helper.assertValueEqual(now[0], 0L, "still nothing to the burning furnace");
            helper.assertValueEqual(now[1], first[1] * 2, "the same furnace again");
            helper.assertValueEqual(now[2], first[2] * 2, "the same furnace again");
            conserved(helper, setup);
        }).thenSucceed();
    }

    /** Four furnaces, one burning: 12 spread evenly gives each idle furnace 4 and the burning one none. */
    private static void spreadEvenly(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_spread", 4);
        LogicControllerBlockEntity entity = setup.controller();
        burnEmpty(setup.furnaces().get(2));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), IRON, STOCK);
            entity.worker().setGraph(0, "Spread", send("spread_evenly", 12));
        }).thenWaitUntil(() -> {
            ran(helper, entity);
            burningEmpty(helper, setup.furnaces().get(2));
        }).thenIdle(5).thenExecute(() -> pulse(helper)).thenWaitUntil(() -> {
            long[] now = irons(setup);
            helper.assertValueEqual(now[0] + now[1] + now[3], 12L, "sent");
        }).thenExecute(() -> {
            long[] now = irons(setup);
            helper.assertValueEqual(now[0], 4L, "furnace 0");
            helper.assertValueEqual(now[1], 4L, "furnace 1");
            helper.assertValueEqual(now[2], 0L, "the burning furnace");
            helper.assertValueEqual(now[3], 4L, "furnace 3");
            helper.assertValueEqual(entity.worker().lastMoved(), 12L, "moved");
            conserved(helper, setup);
        }).thenSucceed();
    }

    /** Three idle furnaces holding 10, 0 and 5 raw iron: least full sends 9 as 0, 7 and 2, so all hold about 7. */
    private static void leastFull(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_least", 3);
        LogicControllerBlockEntity entity = setup.controller();
        setup.furnaces().get(0).setItem(0, new ItemStack(Items.RAW_IRON, 10));
        setup.furnaces().get(2).setItem(0, new ItemStack(Items.RAW_IRON, 5));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), IRON, STOCK - 15);
            entity.worker().setGraph(0, "Least full", send("least_full", 9));
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(25).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(entity.worker().lastMoved(), 9L, "moved"))
                .thenExecute(() -> {
                    long[] now = irons(setup);
                    helper.assertValueEqual(now[0], 10L, "the fullest gets none");
                    helper.assertValueEqual(now[1], 7L, "the emptiest");
                    helper.assertValueEqual(now[2], 7L, "the middle one");
                    conserved(helper, setup);
                }).thenSucceed();
    }

    /**
     * Three idle furnaces, 4 at a time by round robin: the first send goes to one furnace; the controller is saved and
     * loaded again; the next sends go to the other two in turn, not back to the first.
     */
    private static void roundRobin(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_robin", 3);
        BlockPos pos = NetworkGameTests.at(3);
        long[] first = new long[3];
        helper.startSequence().thenWaitUntil(() -> network(helper, setup.controller())).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, setup.controller()), IRON, STOCK);
            setup.controller().worker().setGraph(0, "Turns", send("round_robin", 4));
        }).thenWaitUntil(() -> ran(helper, setup.controller())).thenIdle(25).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> {
                    long[] now = irons(setup);
                    helper.assertValueEqual(now[0] + now[1] + now[2], 4L, "the first send");
                }).thenExecute(() -> {
                    System.arraycopy(irons(setup), 0, first, 0, 3);
                    CompoundTag saved = setup.controller().saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(pos, Blocks.AIR);
                    helper.setBlock(pos, LogicContent.CONTROLLER.get());
                    LogicControllerGameTests.entity(helper, pos).loadCustomOnly(TagValueInput
                            .create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
                }).thenWaitUntil(() -> ran(helper, LogicControllerGameTests.entity(helper, pos))).thenIdle(25)
                .thenExecute(() -> pulse(helper)).thenWaitUntil(() -> {
                    long[] now = irons(setup);
                    helper.assertValueEqual(now[0] + now[1] + now[2], 8L, "the second send");
                }).thenExecute(() -> {
                    long[] now = irons(setup);
                    for (int k = 0; k < 3; k++) {
                        helper.assertTrue(now[k] <= 4, "furnace " + k + " got a second turn after the load: " + now[k]
                                + " (first send: " + first[0] + ", " + first[1] + ", " + first[2] + ")");
                    }
                }).thenIdle(25).thenExecute(() -> pulse(helper)).thenWaitUntil(() -> {
                    long[] now = irons(setup);
                    helper.assertValueEqual(now[0] + now[1] + now[2], 12L, "the third send");
                }).thenExecute(() -> {
                    long[] now = irons(setup);
                    for (int k = 0; k < 3; k++) {
                        helper.assertValueEqual(now[k], 4L, "each furnace had one turn");
                    }
                    long total = LogicControllerGameTests.network(helper, LogicControllerGameTests.entity(helper, pos))
                            .contents().amount(IRON) + now[0] + now[1] + now[2];
                    helper.assertValueEqual(total, STOCK, "raw iron in the network and the furnaces");
                }).thenSucceed();
    }

    /**
     * Two idle furnaces, one behind a port of another player: spread evenly sends all 8 to the other one, the why line
     * says one member was left alone, and the network's log says so once, though the rule runs twice.
     */
    private static void denied(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_denied", 2);
        LogicControllerBlockEntity entity = setup.controller();
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "lp_denied_stranger");
        setup.ports().get(0).setOwner(OwnerRecord.of(stranger));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), IRON, STOCK);
            entity.worker().setGraph(0, "Denied", send("spread_evenly", 8));
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(25).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(iron(setup.furnaces().get(1)), 8, "the allowed furnace"))
                .thenExecute(() -> {
                    helper.assertValueEqual(iron(setup.furnaces().get(0)), 0, "the stranger's furnace");
                    helper.assertTrue(whys(helper, entity, 2).contains(LogicLang.WHY_DENIED),
                            "why: " + whys(helper, entity, 2));
                    conserved(helper, setup);
                }).thenIdle(25).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(iron(setup.furnaces().get(1)), 16, "the second send"))
                .thenExecute(() -> {
                    helper.assertValueEqual(iron(setup.furnaces().get(0)), 0, "the stranger's furnace");
                    long denials = 0;
                    for (NetworkNotices.Notice notice : network(helper, entity).notices().lines()) {
                        denials += notice.key().equals(LogicLang.LOG_DENIED) ? 1 : 0;
                    }
                    helper.assertValueEqual(denials, 1L, "logged once");
                    conserved(helper, setup);
                }).thenSucceed();
    }

    /**
     * Simulating, a spread-evenly send of 6 over three idle furnaces moves nothing: the furnaces stay empty, the
     * network keeps its 100, and the controller records "would send 2" for each member.
     */
    private static void simulate(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_sim", 3);
        LogicControllerBlockEntity entity = setup.controller();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), IRON, STOCK);
            entity.worker().setGraph(0, "Simulated", send("spread_evenly", 6));
            entity.worker().setSimulate(0, true);
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(25).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertTrue(
                        entity.worker().actions().stream().anyMatch(a -> a.kind() == LogicAction.Kind.POOL_SEND),
                        "no send recorded yet"))
                .thenIdle(5).thenExecute(() -> {
                    List<String> sends = new ArrayList<>();
                    for (LogicAction action : entity.worker().actions()) {
                        if (action.kind() == LogicAction.Kind.POOL_SEND) {
                            helper.assertTrue(action.simulated(), "only simulated: " + action.describe());
                            sends.add(action.to() + " " + action.value());
                        }
                    }
                    helper.assertValueEqual(sends.size(), 3, "one per member: " + sends);
                    for (String send : sends) {
                        helper.assertTrue(send.endsWith(" 2"), "2 each: " + sends);
                    }
                    helper.assertTrue(sends.contains("#1 2") && sends.contains("#2 2") && sends.contains("#3 2"),
                            "numbered from 1: " + sends);
                    long[] now = irons(setup);
                    helper.assertValueEqual(now[0] + now[1] + now[2], 0L, "nothing in the furnaces");
                    helper.assertValueEqual(network(helper, entity).contents().amount(IRON), STOCK, "stored");
                    helper.assertTrue(whys(helper, entity, 2).contains(LogicLang.WHY_SIMULATED),
                            "why: " + whys(helper, entity, 2));
                }).thenSucceed();
    }

    /**
     * Without sensors, pool status counts three members with one burning, each port reading its furnace once and not
     * again while nothing changes (and the graph does not run). The burning furnace is replaced by a cold one: the
     * port's kept reading is dropped, it reads again, the status shows three idle and the turned-idle event fires once,
     * counting one.
     */
    private static void statusAndEvent(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_status", 3);
        LogicControllerBlockEntity entity = setup.controller();
        burn(setup.furnaces().get(0));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.POOL_STATUS, pool(s -> s))
                .node(2, NodeTypes.POOL_MEMBER_IDLE, pool(s -> s))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "a furnace is free")).wire(2, "turned_idle", 3, "do")
                .build();
        long[] runs = new long[1];
        int[] reads = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenWaitUntil(() -> helper.assertTrue(lit(setup.furnaces().get(0)), "furnace 0 burns"))
                .thenExecute(() -> entity.worker().setGraph(0, "Status", graph)).thenWaitUntil(() -> {
                    helper.assertValueEqual(out(helper, entity, 1, "members"), 3L, "members");
                    helper.assertValueEqual(out(helper, entity, 1, "idle"), 2L, "idle");
                    helper.assertValueEqual(out(helper, entity, 1, "busy"), 1L, "busy");
                    helper.assertValueEqual(out(helper, entity, 1, "any_idle"), 1L, "any idle");
                }).thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(notices(entity), 0L, "nothing turned idle yet");
                    runs[0] = entity.worker().runs();
                    reads[0] = entity.worker().poolDirectReads(setup.ports().get(0));
                    helper.assertTrue(reads[0] > 0, "the port read its furnace");
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0], "runs while idle");
                    helper.assertValueEqual(entity.worker().poolDirectReads(setup.ports().get(0)), reads[0],
                            "no reads while the furnace stays as it is");
                }).thenExecute(() -> {
                    BlockPos furnace = NetworkGameTests.at(5).south();
                    helper.setBlock(furnace, Blocks.AIR);
                    helper.setBlock(furnace, Blocks.FURNACE);
                }).thenWaitUntil(() -> {
                    helper.assertTrue(entity.worker().poolDirectReads(setup.ports().get(0)) > reads[0],
                            "read again after the furnace changed");
                    helper.assertValueEqual(out(helper, entity, 1, "idle"), 3L, "idle after the swap");
                    helper.assertValueEqual(notices(entity), 1L, "turned idle once");
                    helper.assertValueEqual(out(helper, entity, 2, "count"), 1L, "one furnace turned idle");
                }).thenIdle(20).thenExecute(() -> helper.assertValueEqual(notices(entity), 1L, "still once"))
                .thenSucceed();
    }

    /**
     * Idle by "items.total is equal to 0": the port on top sees only a furnace's input slot, a sensor beside it only
     * the fuel slot. Furnace 0 has coal and a sensor, furnace 1 is empty with no sensor: the sensor's read (8 coal,
     * busy) overrides the port's (empty input, idle), so all 8 raw iron go to furnace 1.
     */
    private static void sensorOverrides(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_override", 2, 1);
        LogicControllerBlockEntity entity = setup.controller();
        setup.furnaces().get(0).setItem(1, new ItemStack(Items.COAL, 8));
        setup.furnaces().get(0).setChanged();
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.MOVE_TO_POOL,
                        s -> s.withText("label", POOL).withText("idle_value", "items.total")
                                .withItem("item", LogicKey.of(IRON)).withNumber("amount", 8)
                                .withText("strategy", "spread_evenly"))
                .wire(1, "changed", 2, "do").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), IRON, STOCK);
            entity.worker().setGraph(0, "Override", graph);
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(25).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(entity.worker().lastMoved(), 8L, "moved"))
                .thenExecute(() -> {
                    helper.assertValueEqual(iron(setup.furnaces().get(0)), 0, "the sensor says furnace 0 is busy");
                    helper.assertValueEqual(iron(setup.furnaces().get(1)), 8, "furnace 1");
                    conserved(helper, setup);
                }).thenSucceed();
    }

    /**
     * Round robin in simulate mode: two simulated sends both go to member 1, since a simulated send moves nothing and
     * so does not pass the turn on.
     */
    private static void simulateKeepsTurn(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_simturn", 3);
        LogicControllerBlockEntity entity = setup.controller();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), IRON, STOCK);
            entity.worker().setGraph(0, "Turns", send("round_robin", 4));
            entity.worker().setSimulate(0, true);
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(5).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(simulatedSends(entity).size(), 1, "first simulated send"))
                .thenIdle(5).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(simulatedSends(entity).size(), 2, "second simulated send"))
                .thenExecute(() -> {
                    helper.assertValueEqual(simulatedSends(entity), List.of("#1 4", "#1 4"), "both to member 1");
                    long[] now = irons(setup);
                    helper.assertValueEqual(now[0] + now[1] + now[2], 0L, "nothing in the furnaces");
                }).thenSucceed();
    }

    /**
     * A port facing a chest (no pack reader, so only an update makes it read again), idle while it holds no items: is
     * 0: busy while the chest holds cobblestone, and the port reads it once and not again while nothing changes. The
     * chest is replaced by an empty one: the kept reading is dropped, the port reads again and the member is idle.
     */
    private static void directReadFollows(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_direct", 1);
        LogicControllerBlockEntity entity = setup.controller();
        BlockPos chest = NetworkGameTests.at(5).south();
        helper.setBlock(chest, Blocks.CHEST);
        helper.getBlockEntity(chest, ChestBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.POOL_STATUS, s -> s.withText("label", POOL).withText("idle_value", "items.total"))
                .build();
        int[] reads = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Chest", graph)).thenWaitUntil(() -> {
                    helper.assertValueEqual(out(helper, entity, 1, "members"), 1L, "members");
                    helper.assertValueEqual(out(helper, entity, 1, "idle"), 0L, "busy while it holds cobblestone");
                }).thenIdle(10).thenExecute(() -> reads[0] = entity.worker().poolDirectReads(setup.ports().get(0)))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertTrue(reads[0] > 0, "read once");
                    helper.assertValueEqual(entity.worker().poolDirectReads(setup.ports().get(0)), reads[0],
                            "no reads while the chest stays as it is");
                    helper.setBlock(chest, Blocks.AIR);
                    helper.setBlock(chest, Blocks.CHEST);
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(out(helper, entity, 1, "idle"), 1L, "idle once the chest is empty");
                    helper.assertTrue(entity.worker().poolDirectReads(setup.ports().get(0)) > reads[0], "read again");
                }).thenSucceed();
    }

    /**
     * The direct read's comparator value: 10 cobblestone go into a barrel and a chest through their item handlers from
     * the side, as a hopper puts them in, each under a pool port. A fresh reader on the barrel's port reads a
     * comparator output of at least 1. The chest's port reads 0: vanilla's chest gives no comparator output while a
     * solid block (the port) sits on top of it, whatever it holds; its items still read.
     */
    private static void directComparator(GameTestHelper helper) {
        Setup setup = setUp(helper, "lp_comparator", 2);
        BlockPos barrel = NetworkGameTests.at(5).south();
        BlockPos chest = NetworkGameTests.at(5 + SPACING).south();
        helper.setBlock(barrel, Blocks.BARREL);
        helper.setBlock(chest, Blocks.CHEST);
        helper.startSequence().thenWaitUntil(() -> network(helper, setup.controller())).thenExecute(() -> {
            helper.assertValueEqual(hopperInsert(helper, barrel), 10, "into the barrel");
            helper.assertValueEqual(hopperInsert(helper, chest), 10, "into the chest");
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(read(helper, setup.ports().get(0), "comparator") >= 1,
                    "the barrel's comparator output: " + read(helper, setup.ports().get(0), "comparator"));
            helper.assertValueEqual(read(helper, setup.ports().get(1), "comparator"), 0L,
                    "a chest under a solid block gives no comparator output");
            helper.assertValueEqual(read(helper, setup.ports().get(1), "items.total"), 10L, "the chest's items");
        }).thenSucceed();
    }

    /** Puts 10 cobblestone into the block at {@code pos} through its item handler from the east, committed. */
    private static int hopperInsert(GameTestHelper helper, BlockPos pos) {
        ResourceHandler<ItemResource> handler = helper.getLevel().getCapability(Capabilities.Item.BLOCK,
                helper.absolutePos(pos), Direction.EAST);
        if (handler == null) {
            throw helper.assertionException(Component.literal("no item handler at " + pos));
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(Items.COBBLESTONE), 10,
                    transaction);
            transaction.commit();
            return moved;
        }
    }

    /** {@code id} as a fresh direct reader on {@code port}'s machine reads it now. */
    private static long read(GameTestHelper helper, PortBlockEntity port, String id) {
        MachineReader reader = new MachineReader(() -> {
        });
        try {
            helper.assertTrue(
                    reader.refresh(helper.getLevel(), port.blockPos(), Direction.DOWN, port.ownerRecord(),
                            TeamBudget.team(helper.getLevel(), port), helper.getLevel().getGameTime(), 0),
                    "nothing read");
            return reader.value(id);
        } finally {
            reader.close();
        }
    }

    private static List<String> simulatedSends(LogicControllerBlockEntity entity) {
        List<String> sends = new ArrayList<>();
        for (LogicAction action : entity.worker().actions()) {
            if (action.kind() == LogicAction.Kind.POOL_SEND && action.simulated()) {
                sends.add(action.to() + " " + action.value());
            }
        }
        return sends;
    }

    private static long out(GameTestHelper helper, LogicControllerBlockEntity entity, int node, String output) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
        return evaluator.value(evaluator.graph().slot(node, output));
    }

    private static long notices(LogicControllerBlockEntity entity) {
        return entity.worker().actions().stream().filter(action -> action.kind() == LogicAction.Kind.NOTIFY).count();
    }
}
