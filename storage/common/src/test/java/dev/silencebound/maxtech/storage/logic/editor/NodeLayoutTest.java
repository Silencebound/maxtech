package dev.silencebound.maxtech.storage.logic.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.text.TextFit;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/** Node sizes that grow with their text, and the geometry that follows the width. Text fitting is Lib's TextFitTest. */
class NodeLayoutTest {
    /** Six units per character, like most of the Minecraft font. */
    private static final ToIntFunction<String> MEASURE = NodeLayoutTest::measure;

    private static int measure(String text) {
        return text.length() * 6;
    }

    @Test
    void nodesAreWideEnoughForTheirTitleAndLines() {
        assertEquals(NodeLayout.WIDTH, NodeLayout.width(30, 30), "short text keeps the narrowest node");
        int title = MEASURE.applyAsInt("Redstone changes");
        int width = NodeLayout.width(title, 0);
        assertTrue(NodeLayout.titleRoom(width) >= title, "the whole title fits: " + width);
        int line = MEASURE.applyAsInt("Cobblestone above 100");
        width = NodeLayout.width(0, line);
        assertTrue(NodeLayout.lineRoom(width) >= line, "the whole line fits: " + width);
        assertEquals(NodeLayout.MAX_WIDTH, NodeLayout.width(1000, 0), "past the cap the text gets an ellipsis");
    }

    @Test
    void titlesAndLinesThatDoNotFitEndWithAnEllipsis() {
        // Owner screenshot 2026-10-09: a title read "Switch devic" with no sign that it was cut.
        String title = "Switch device on the far side of the base";
        int width = NodeLayout.width(MEASURE.applyAsInt(title), 0);
        String shown = NodeLayout.fitTitle(title, width, MEASURE);
        assertTrue(shown.endsWith(TextFit.ELLIPSIS), "a cut title ends with an ellipsis: " + shown);
        assertTrue(MEASURE.applyAsInt(shown) <= NodeLayout.titleRoom(width), "the cut title fits: " + shown);
        assertTrue(title.startsWith(shown.substring(0, shown.length() - 1)), "it is the title's start: " + shown);
        assertEquals("Switch device",
                NodeLayout.fitTitle("Switch device", NodeLayout.width(MEASURE.applyAsInt("Switch device"), 0), MEASURE),
                "a title that fits stays whole");
        String line = "minecraft:cobblestone above 1,000,000 and more";
        String fitted = NodeLayout.fitLine(line, NodeLayout.MAX_WIDTH, MEASURE);
        assertTrue(fitted.endsWith(TextFit.ELLIPSIS), "a cut line ends with an ellipsis: " + fitted);
        assertTrue(MEASURE.applyAsInt(fitted) <= NodeLayout.lineRoom(NodeLayout.MAX_WIDTH), "the line fits");
    }

    @Test
    void nodeGeometryFollowsItsWidth() {
        int width = 120;
        int hit = NodeLayout.portAt(1, 2, width, width + 1, NodeLayout.portY(1));
        assertTrue(NodeLayout.hitOutput(hit), "outputs sit on the wider right edge");
        assertEquals(1, NodeLayout.hitIndex(hit));
        assertEquals(NodeLayout.MISS, NodeLayout.portAt(1, 2, width, NodeLayout.WIDTH, NodeLayout.portY(1)),
                "the narrow node's edge is inside a wide one");
        assertTrue(NodeLayout.inside(1, 1, false, width, width - 1, NodeLayout.HEADER + 1));
        assertTrue(NodeLayout.onHeader(width, width - 1, 2));
        assertEquals(NodeLayout.height(1, 1) + NodeLayout.PORT_PITCH, NodeLayout.height(1, 1, true),
                "a live line adds one row to a small node");
        assertEquals(NodeLayout.height(3, 3), NodeLayout.height(3, 3, true), "a tall node already has the room");
    }
}
