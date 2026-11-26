package dev.silencebound.maxtech.storage.logic.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The live line inside watching nodes: which nodes have one, where it reads from, and its numbers. */
class LiveLineTest {
    @Test
    void watchingNodesHaveALiveLine() {
        List<NodeType> watching = List.of(NodeTypes.COUNT_CROSSES, NodeTypes.SENSOR_CROSSES, NodeTypes.ITEM_COUNT,
                NodeTypes.SENSOR_VALUE, NodeTypes.DEVICE_STATE, NodeTypes.POWER_CHANGES, NodeTypes.REDSTONE_CHANGES,
                NodeTypes.COUNTER, NodeTypes.LATCH, NodeTypes.HOLD, NodeTypes.DELAY);
        for (NodeType type : watching) {
            assertTrue(LiveLine.has(type), type.id());
            String entry = LiveLine.of(type).orElseThrow().entry();
            boolean output = type.outputs(NodeSettings.empty()).stream().anyMatch(port -> port.name().equals(entry));
            assertTrue(output || entry.equals(LiveLine.WATCHED) || entry.equals(LiveLine.DUE),
                    type.id() + " reads an output it has, the watch or the due tick: " + entry);
        }
        assertFalse(LiveLine.has(NodeTypes.NOTIFY));
        assertFalse(LiveLine.has(NodeTypes.COMPARE));
        assertEquals(LiveLine.WATCHED, LiveLine.of(NodeTypes.COUNT_CROSSES).orElseThrow().entry(),
                "a crossing reads the watched count, which its outputs miss while no limit is crossed");
        assertEquals(LogicLang.LIVE_COUNT, LiveLine.of(NodeTypes.COUNTER).orElseThrow().key());
    }

    @Test
    void crossingShowsTheValueAgainstItsLimit() {
        assertEquals("1,240 / >100", LiveLine.crossing(false, 1240, 100));
        assertEquals("3 / <10", LiveLine.crossing(true, 3, 10));
    }

    @Test
    void positionFollowsTheSwitchBackBand() {
        // Above 100, back at 64: over 100 is past, 65..100 holds, 64 and under is short.
        assertEquals(LiveLine.Position.PAST, LiveLine.position(false, 101, 100, 64));
        assertEquals(LiveLine.Position.BETWEEN, LiveLine.position(false, 100, 100, 64));
        assertEquals(LiveLine.Position.BETWEEN, LiveLine.position(false, 65, 100, 64));
        assertEquals(LiveLine.Position.SHORT, LiveLine.position(false, 64, 100, 64));
        // Without a switch-back value there is no band.
        assertEquals(LiveLine.Position.SHORT, LiveLine.position(false, 100, 100, 100));
        // Below 10, back at 20.
        assertEquals(LiveLine.Position.PAST, LiveLine.position(true, 9, 10, 20));
        assertEquals(LiveLine.Position.BETWEEN, LiveLine.position(true, 15, 10, 20));
        assertEquals(LiveLine.Position.SHORT, LiveLine.position(true, 20, 10, 20));
    }

    @Test
    void settingsResolveLikeTheCompiler() {
        NodeSettings<String> above = NodeSettings.<String>empty().withNumber("threshold", 100).withNumber("switch_back",
                64);
        assertFalse(LiveLine.below(NodeTypes.COUNT_CROSSES, above));
        assertEquals(100, LiveLine.threshold(NodeTypes.COUNT_CROSSES, above));
        assertEquals(64, LiveLine.switchBack(NodeTypes.COUNT_CROSSES, above));
        assertEquals(100, LiveLine.switchBack(NodeTypes.COUNT_CROSSES, above.withNumber("switch_back", 150)),
                "a switch-back past the limit is the limit");
        assertEquals(100,
                LiveLine.switchBack(NodeTypes.COUNT_CROSSES, NodeSettings.<String>empty().withNumber("threshold", 100)),
                "unset: the limit");
        NodeSettings<String> below = NodeSettings.<String>empty().withText("mode", "below").withNumber("threshold", 10)
                .withNumber("switch_back", 5);
        assertTrue(LiveLine.below(NodeTypes.COUNT_CROSSES, below));
        assertEquals(10, LiveLine.switchBack(NodeTypes.COUNT_CROSSES, below), "below: never under the limit");
    }

    @Test
    void delaySecondsRoundUp() {
        assertEquals(70, LiveLine.ticksLeft(1070, 1000));
        assertEquals(0, LiveLine.ticksLeft(1000, 1070), "a due tick in the past is 0, not negative");
        assertEquals("3.5", LiveLine.secondsLeft(70));
        assertEquals("1", LiveLine.secondsLeft(20));
        assertEquals("0.1", LiveLine.secondsLeft(1), "one tick left still shows time");
        assertEquals("0", LiveLine.secondsLeft(0));
        assertEquals("3,600", LiveLine.secondsLeft(NodeTypes.MAX_DELAY_TICKS));
    }
}
