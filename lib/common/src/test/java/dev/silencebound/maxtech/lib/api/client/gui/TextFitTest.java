package dev.silencebound.maxtech.lib.api.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/** Text fitting and the widths that grow with text: never a silent cut. */
class TextFitTest {
    /** Six units per character, like most of the Minecraft font. */
    private static final ToIntFunction<String> MEASURE = TextFitTest::measure;

    private static int measure(String text) {
        return text.length() * 6;
    }

    @Test
    void textThatFitsIsUnchanged() {
        assertEquals("Item count", TextFit.fit("Item count", 60, MEASURE));
        assertFalse(TextFit.cut("Item count", 60, MEASURE));
    }

    @Test
    void longTextEndsWithAnEllipsisAtAWord() {
        // 13 characters of room: "Item count" plus the ellipsis rather than "Item count cr...".
        String fitted = TextFit.fit("Item count crosses", 84, MEASURE);
        assertEquals("Item count" + TextFit.ELLIPSIS, fitted);
        assertTrue(MEASURE.applyAsInt(fitted) <= 84);
        assertTrue(TextFit.cut("Item count crosses", 84, MEASURE));
    }

    @Test
    void aLongWordIsCutWithAnEllipsis() {
        // The only space keeps too little of the text: cut inside the word, but marked.
        String fitted = TextFit.fit("A labelleddevice", 60, MEASURE);
        assertEquals("A labelle" + TextFit.ELLIPSIS, fitted);
        assertTrue(MEASURE.applyAsInt(fitted) <= 60);
        assertEquals("Remember" + TextFit.ELLIPSIS, TextFit.fit("Rememberingvalue", 54, MEASURE));
    }

    @Test
    void noRoomGivesNothing() {
        assertEquals("", TextFit.fit("Anything", 3, MEASURE));
        assertEquals(TextFit.ELLIPSIS, TextFit.fit("Anything", 8, MEASURE));
    }

    @Test
    void textWrapsAtSpacesIntoLinesThatFit() {
        // 10 characters a line.
        List<String> lines = TextFit.lines("Keep a few stacks of glass", 60, 5, MEASURE);
        assertEquals(List.of("Keep a few", "stacks of", "glass"), lines);
        assertFalse(TextFit.linesCut("Keep a few stacks of glass", 60, 5, MEASURE));
        assertEquals(List.of(), TextFit.lines("   ", 60, 3, MEASURE));
    }

    @Test
    void textNeedingMoreLinesEndsTheLastWithAnEllipsis() {
        String text = "Logic finds devices on this network by label";
        List<String> lines = TextFit.lines(text, 60, 2, MEASURE);
        assertEquals(2, lines.size());
        assertEquals("Logic", lines.get(0));
        assertTrue(lines.get(1).startsWith("finds"), lines.get(1));
        assertTrue(lines.get(1).endsWith(TextFit.ELLIPSIS), lines.get(1));
        for (String line : lines) {
            assertTrue(MEASURE.applyAsInt(line) <= 60, line);
        }
        assertTrue(TextFit.linesCut(text, 60, 2, MEASURE));
        assertEquals(List.of(), TextFit.lines(text, 60, 0, MEASURE));
    }

    @Test
    void aWordWiderThanALineIsCutOnItsOwnLine() {
        List<String> lines = TextFit.lines("a #minecraft:logs_that_burn tag", 60, 3, MEASURE);
        assertEquals(List.of("a", "#minecraf" + TextFit.ELLIPSIS, "tag"), lines.subList(0, 3));
    }

    @Test
    void widthsGrowWithTheirTextUpToACap() {
        assertEquals(30, TextFit.widest(List.of("Graph", "Lamp", "Run"), MEASURE));
        assertEquals(0, TextFit.widest(List.of(), MEASURE));
        assertEquals(100, TextFit.grow(40, 100, 150));
        assertEquals(120, TextFit.grow(120, 100, 150));
        assertEquals(150, TextFit.grow(400, 100, 150));
    }
}
