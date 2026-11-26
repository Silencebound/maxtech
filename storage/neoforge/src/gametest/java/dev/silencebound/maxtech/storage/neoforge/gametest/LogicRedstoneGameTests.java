package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlock;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Analog redstone (M7L2 chunk 6): a strength 0 to 15 out through condition switches in logic mode, to dust and
 * comparators; number inputs clamped; yes/no graphs as before; the strength into a labelled device read back. The row
 * is the logic controller layout ({@link LogicControllerGameTests#setUp}): core at x 1, test drive at 2, logic
 * controller at 3, the switch labelled "lamp" at 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicRedstoneGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final BlockPos SWITCH = NetworkGameTests.at(4);
    /** Dust north of the switch, a comparator south of it reading from it. */
    private static final BlockPos DUST = SWITCH.north();
    private static final BlockPos COMPARATOR = SWITCH.south();
    /** A test device labelled "door" east of the switch, so the switch powers it. */
    private static final BlockPos DOOR = NetworkGameTests.at(5);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_redstone_strength_to_dust_and_comparator", LogicRedstoneGameTests::toDustAndComparator);
        register("logic_redstone_number_input_clamped", LogicRedstoneGameTests::clamped);
        register("logic_redstone_yes_no_as_before", LogicRedstoneGameTests::yesNo);
        register("logic_redstone_strength_in_from_label", LogicRedstoneGameTests::strengthIn);
    }

    private LogicRedstoneGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("logic_redstone"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    // ---- helpers

    /**
     * The logic controller row with a logic-mode condition switch labelled "lamp" at x 4; the switch's owner owns the
     * controller and has the switch researched.
     */
    private static Setup setUp(GameTestHelper helper, String name) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, name);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), name + "_owner");
        entity.setOwner(OwnerRecord.of(owner));
        ResearchTeam team = Research.team(owner, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
        helper.setBlock(SWITCH, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity lamp = helper.getBlockEntity(SWITCH, ConditionSwitchBlockEntity.class);
        lamp.setOwner(OwnerRecord.of(owner));
        lamp.worker().setSettings(ConditionSettings.DEFAULT.withType(ConditionType.LOGIC));
        lamp.setLabel(DeviceLabel.of("lamp", null));
        return new Setup(entity, lamp);
    }

    private record Setup(LogicControllerBlockEntity controller, ConditionSwitchBlockEntity lamp) {
    }

    /** A number constant into the strength of "redstone out" to "lamp". */
    private static LogicGraph<LogicKey> strength(long value) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", value))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "value", 2, "strength")
                .build();
    }

    /** The signal the switch block gives towards {@code towards}. */
    private static int signal(GameTestHelper helper, Direction towards) {
        BlockPos pos = helper.absolutePos(SWITCH);
        return helper.getLevel().getBlockState(pos).getSignal(helper.getLevel(), pos, towards);
    }

    private static void strengthIs(GameTestHelper helper, Setup setup, int strength) {
        helper.assertValueEqual(setup.lamp().worker().strength(), strength, "switch strength");
        helper.assertValueEqual(signal(helper, Direction.NORTH), strength, "signal the block gives");
        helper.assertValueEqual(helper.getBlockState(SWITCH).getValue(ConditionSwitchBlock.POWERED), strength > 0,
                "powered");
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        if (entity.worker().size() == 0) {
            throw helper.assertionException(Component.literal("no graph yet"));
        }
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
    }

    private static long output(GameTestHelper helper, LogicControllerBlockEntity entity, int node, String output) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
        return evaluator.value(evaluator.graph().slot(node, output));
    }

    // ---- tests

    /**
     * A constant 7 into "redstone out" makes the switch give 7: dust next to it carries power 7 and a comparator
     * reading from it outputs 7. A constant 3 then gives 3 in both.
     */
    private static void toDustAndComparator(GameTestHelper helper) {
        Setup setup = setUp(helper, "lr_dust");
        LogicControllerBlockEntity entity = setup.controller();
        helper.setBlock(DUST.below(), Blocks.STONE);
        helper.setBlock(COMPARATOR.below(), Blocks.STONE);
        helper.setBlock(DUST, Blocks.REDSTONE_WIRE);
        // A comparator's input is the block its facing points at.
        helper.setBlock(COMPARATOR,
                Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Lamp", strength(7)))
                .thenWaitUntil(() -> strengthIs(helper, setup, 7))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        helper.getBlockState(DUST).getValue(RedStoneWireBlock.POWER), 7, "dust power"))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        helper.getBlockEntity(COMPARATOR, ComparatorBlockEntity.class).getOutputSignal(), 7,
                        "comparator output"))
                .thenExecute(() -> entity.worker().setGraph(0, "Lamp", strength(3)))
                .thenWaitUntil(() -> strengthIs(helper, setup, 3))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        helper.getBlockState(DUST).getValue(RedStoneWireBlock.POWER), 3, "dust power"))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        helper.getBlockEntity(COMPARATOR, ComparatorBlockEntity.class).getOutputSignal(), 3,
                        "comparator output"))
                .thenSucceed();
    }

    /** A number above 15 gives 15, below 0 gives 0 (and the switch stops being powered); the strength is saved. */
    private static void clamped(GameTestHelper helper) {
        Setup setup = setUp(helper, "lr_clamp");
        LogicControllerBlockEntity entity = setup.controller();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Lamp", strength(40)))
                .thenWaitUntil(() -> strengthIs(helper, setup, 15))
                .thenExecute(() -> entity.worker().setGraph(0, "Lamp", strength(5)))
                .thenWaitUntil(() -> strengthIs(helper, setup, 5)).thenExecute(() -> {
                    // The strength is saved with the switch, like its on/off state.
                    ConditionSwitchBlockEntity lamp = setup.lamp();
                    net.minecraft.nbt.CompoundTag saved = lamp.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.assertValueEqual(saved.getIntOr("logic_strength", -1), 5, "saved strength");
                    entity.worker().setGraph(0, "Lamp", strength(-3));
                }).thenWaitUntil(() -> strengthIs(helper, setup, 0)).thenSucceed();
    }

    /**
     * A yes/no into "on" with the strength left at its default gives 15 for yes and 0 for no, as graphs from before
     * analog redstone do; a strength setting of 4 gives 4 for yes.
     */
    private static void yesNo(GameTestHelper helper) {
        Setup setup = setUp(helper, "lr_yes_no");
        LogicControllerBlockEntity entity = setup.controller();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Lamp", yesNoGraph(true, s -> s)))
                .thenWaitUntil(() -> strengthIs(helper, setup, 15))
                .thenExecute(() -> entity.worker().setGraph(0, "Lamp", yesNoGraph(false, s -> s)))
                .thenWaitUntil(() -> strengthIs(helper, setup, 0))
                .thenExecute(
                        () -> entity.worker().setGraph(0, "Lamp", yesNoGraph(true, s -> s.withNumber("strength", 4))))
                .thenWaitUntil(() -> strengthIs(helper, setup, 4)).thenSucceed();
    }

    private static LogicGraph<LogicKey> yesNoGraph(boolean yes,
            java.util.function.UnaryOperator<dev.silencebound.maxtech.storage.logic.NodeSettings<LogicKey>> extra) {
        return new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no").withFlag("yes", yes))
                .node(2, NodeTypes.REDSTONE_OUT, s -> extra.apply(s.withText("label", "lamp")))
                .wire(1, "value", 2, "on").build();
    }

    /**
     * The switch at 7 powers the test device labelled "door" next to it: "redstone strength" and "redstone changes"
     * with that label read 7, and 3 once the switch gives 3.
     */
    private static void strengthIn(GameTestHelper helper) {
        Setup setup = setUp(helper, "lr_in");
        LogicControllerBlockEntity entity = setup.controller();
        NetworkGameTests.device(helper, DOOR).setLabel(DeviceLabel.of("door", null));
        LogicGraph<LogicKey> seven = withReads(7);
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Door", seven))
                .thenWaitUntil(() -> strengthIs(helper, setup, 7))
                .thenWaitUntil(() -> helper.assertValueEqual(output(helper, entity, 3, "strength"), 7L, "read"))
                .thenWaitUntil(() -> helper.assertValueEqual(output(helper, entity, 3, "powered"), 1L, "powered"))
                .thenWaitUntil(() -> helper.assertValueEqual(output(helper, entity, 4, "strength"), 7L, "event"))
                .thenExecute(() -> entity.worker().setGraph(0, "Door", withReads(3)))
                .thenWaitUntil(() -> helper.assertValueEqual(output(helper, entity, 3, "strength"), 3L, "read"))
                .thenWaitUntil(() -> helper.assertValueEqual(output(helper, entity, 4, "strength"), 3L, "event"))
                .thenSucceed();
    }

    /** {@link #strength} plus a "redstone strength" (3) and a "redstone changes" (4) node on the label "door". */
    private static LogicGraph<LogicKey> withReads(long value) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", value))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp"))
                .node(3, NodeTypes.REDSTONE_STRENGTH, s -> s.withText("label", "door"))
                .node(4, NodeTypes.REDSTONE_CHANGES, s -> s.withText("label", "door")).wire(1, "value", 2, "strength")
                .build();
    }
}
