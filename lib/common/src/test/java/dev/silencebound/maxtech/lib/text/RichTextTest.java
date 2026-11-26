package dev.silencebound.maxtech.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RichTextTest {
    private static final Set<String> VALUES = Set.of("count", "sensor");
    private static final int RED = RichText.colour("red");
    private static final int GOLD = RichText.colour("gold");

    private static List<RichText.Run> runs(String source) {
        RichText text = RichText.parse(source, VALUES);
        assertEquals(1, text.lines().size(), "one line from " + source);
        return text.lines().getFirst().runs();
    }

    private static RichText.Text text(RichText.Run run) {
        return assertInstance(RichText.Text.class, run);
    }

    private static <T> T assertInstance(Class<T> type, Object value) {
        assertTrue(type.isInstance(value), () -> "expected " + type.getSimpleName() + ", was " + value);
        return type.cast(value);
    }

    @Test
    void plainTextIsOneRun() {
        List<RichText.Run> runs = runs("Hello world");
        assertEquals(1, runs.size());
        assertEquals(new RichText.Text("Hello world", RichText.Style.PLAIN), runs.getFirst());
        assertEquals(RichText.EMPTY, RichText.parse(""));
    }

    @Test
    void coloursBoldAndItalicNest() {
        List<RichText.Run> runs = runs("a<red>b<b>c</b>d</red><i><gold>e</>f");
        assertEquals(List.of(new RichText.Text("a", RichText.Style.PLAIN),
                new RichText.Text("b", new RichText.Style(RED, false, false)),
                new RichText.Text("c", new RichText.Style(RED, true, false)),
                new RichText.Text("d", new RichText.Style(RED, false, false)),
                new RichText.Text("e", new RichText.Style(GOLD, false, true)),
                new RichText.Text("f", new RichText.Style(RichText.NO_COLOUR, false, true))), runs);
        assertEquals(0xFF5555, RED, "vanilla's red");
        assertEquals(16, RichText.COLOUR_NAMES.size());
    }

    @Test
    void innerColourWinsAndClosingReturnsToTheOuter() {
        List<RichText.Run> runs = runs("<red>a<gold>b</gold>c");
        assertEquals(GOLD, runs.get(1).style().colour());
        assertEquals(RED, runs.get(2).style().colour());
        assertEquals(new RichText.Style(RED, true, false), runs("<RED><Bold>x").getFirst().style(), "case-insensitive");
    }

    @Test
    void unclosedTagsEndWithTheLine() {
        RichText text = RichText.parse("<red><b>first\nsecond");
        assertEquals(new RichText.Style(RED, true, false), text.lines().get(0).runs().getFirst().style());
        assertEquals(RichText.Style.PLAIN, text.lines().get(1).runs().getFirst().style());
    }

    @Test
    void unknownTagsAndStrayBracketsAreText() {
        assertEquals(List.of(new RichText.Text("<blink>a < b > c <>", RichText.Style.PLAIN)),
                runs("<blink>a < b > c <>"));
        // A closing tag with nothing open is dropped, not shown.
        assertEquals(List.of(new RichText.Text("xy", RichText.Style.PLAIN)), runs("x</></red>y"));
        assertEquals("[note] [item:] [item:Bad Id] [x",
                text(runs("[note] [item:] [item:Bad Id] [x").getFirst()).text());
        assertEquals("{unknown:1} {count", text(runs("{unknown:1} {count").getFirst()).text());
        assertEquals("{count:x}", text(RichText.parse("{count:x}").lines().getFirst().runs().getFirst()).text(),
                "no placeholders allowed");
    }

    @Test
    void lineTagsSetTheWholeLine() {
        RichText text = RichText.parse("<center><big>Title</big>\n<right>a<small>b\nplain");
        RichText.Line title = text.lines().get(0);
        assertEquals(RichText.Align.CENTER, title.align());
        assertEquals(RichText.Size.BIG, title.size(), "the closing tag is ignored");
        assertEquals(List.of(new RichText.Text("Title", RichText.Style.PLAIN)), title.runs());
        assertEquals(RichText.Align.RIGHT, text.lines().get(1).align());
        assertEquals(RichText.Size.SMALL, text.lines().get(1).size(), "anywhere in the line");
        assertEquals(RichText.Align.LEFT, text.lines().get(2).align(), "line tags do not carry over");
        assertEquals(RichText.Size.NORMAL, text.lines().get(2).size());
    }

    @Test
    void iconsTakeItemAndBlockIds() {
        RichText text = RichText.parse("[item:minecraft:diamond] x [block:stone] [ITEM:mod:a/b_c.d]");
        List<RichText.Run> runs = text.lines().getFirst().runs();
        assertEquals(new RichText.Icon("minecraft:diamond", RichText.Style.PLAIN), runs.get(0));
        assertEquals(" x ", text(runs.get(1)).text());
        assertEquals("minecraft:stone", assertInstance(RichText.Icon.class, runs.get(2)).id(), "default namespace");
        assertEquals("mod:a/b_c.d", assertInstance(RichText.Icon.class, runs.get(4)).id());
        assertEquals(3, text.icons().size());
        assertEquals(RED, runs("<red>[item:diamond]").getFirst().style().colour(), "icons carry the style");
    }

    @Test
    void placeholdersAreNumberedInReadingOrder() {
        RichText text = RichText.parse("Iron: {count:#c:ingots/iron}\n{SENSOR: door : redstone} {count:minecraft:dirt}",
                VALUES);
        assertEquals(List.of(new RichText.Value(0, "count", "#c:ingots/iron", RichText.Style.PLAIN),
                new RichText.Value(1, "sensor", "door : redstone", RichText.Style.PLAIN),
                new RichText.Value(2, "count", "minecraft:dirt", RichText.Style.PLAIN)), text.values());
        assertEquals(text.values().getFirst(), text.lines().getFirst().runs().get(1));
        assertEquals("", RichText.parse("{count}", VALUES).values().getFirst().argument());
    }

    @Test
    void escapesShowMarkupAsText() {
        assertEquals("<red> [item:diamond] {count:x} \\",
                text(runs("\\<red> \\[item:diamond] \\{count:x} \\\\").getFirst()).text());
        String typed = "a<b>[c]{count:d}\\";
        assertEquals(typed, text(runs(RichText.escape(typed)).getFirst()).text(), "escape round trip");
        assertEquals("a\\b", text(runs("a\\b").getFirst()).text(), "a lone backslash is text");
    }

    @Test
    void controlCharactersAndSectionSignsAreDropped() {
        assertEquals("acb", text(runs("a§c\u0007b").getFirst()).text());
        RichText text = RichText.parse("one\r\ntwo\rthree");
        assertEquals(3, text.lines().size(), "CR LF and CR break lines");
        assertEquals("one\ntwo", RichText.clean("one\r\ntwo"));
    }

    @Test
    void capsCutLengthLinesIconsAndValues() {
        String longText = "x".repeat(RichText.MAX_LENGTH + 50);
        RichText text = RichText.parse(longText);
        assertEquals(RichText.MAX_LENGTH, text(text.lines().getFirst().runs().getFirst()).text().length());
        assertTrue(text.truncated());
        assertFalse(RichText.parse("short").truncated());

        RichText lines = RichText.parse("a\n".repeat(RichText.MAX_LINES + 5));
        assertEquals(RichText.MAX_LINES, lines.lines().size());
        assertTrue(lines.truncated());

        RichText icons = RichText.parse("[item:dirt]".repeat(RichText.MAX_ICONS + 1));
        assertEquals(RichText.MAX_ICONS, icons.icons().size());
        assertEquals("[item:minecraft:dirt]".length() - "minecraft:".length(),
                text(icons.lines().getFirst().runs().getLast()).text().length(), "the extra icon is text");
        assertTrue(icons.truncated());

        RichText values = RichText.parse("{count:a}".repeat(RichText.MAX_VALUES + 2), VALUES);
        assertEquals(RichText.MAX_VALUES, values.values().size());
        assertTrue(values.truncated());

        String surrogates = "a" + "😀".repeat(RichText.MAX_LENGTH);
        String cleaned = RichText.clean(surrogates);
        assertFalse(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1)), "no split surrogate pair");
    }
}
