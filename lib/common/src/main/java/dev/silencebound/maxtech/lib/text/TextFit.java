package dev.silencebound.maxtech.lib.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Fits text into a width for MaxTech screens (owner request 2026-10-07: nothing cut mid-word without a sign). Layouts
 * first grow to the measured text; what still does not fit ends with an ellipsis, and the screen shows the whole text
 * as a tooltip. Pure: widths come from a measure function (the font on the client, a fixed width per character in
 * tests), so it is safe on both sides. Moved here from Storage's logic editor so Lib's GUI pieces share it.
 */
public final class TextFit {
    /** What ends a shortened text. */
    public static final String ELLIPSIS = "…";
    /** A cut at a space is used when it keeps at least this share (in percent) of the longest cut that fits. */
    private static final int WORD_SHARE = 60;

    private TextFit() {
    }

    /**
     * {@code text} when it is at most {@code width} wide; otherwise its longest start that fits with {@link #ELLIPSIS}
     * after it, ending at a word when that keeps most of the text, and the ellipsis alone (or nothing) when not even
     * one character fits.
     */
    public static String fit(String text, int width, ToIntFunction<String> measure) {
        if (measure.applyAsInt(text) <= width) {
            return text;
        }
        int room = width - measure.applyAsInt(ELLIPSIS);
        if (room < 0) {
            return "";
        }
        int end = 0;
        while (end < text.length() && measure.applyAsInt(text.substring(0, end + 1)) <= room) {
            end++;
        }
        int space = text.lastIndexOf(' ', end);
        if (space > 0) {
            String word = text.substring(0, space).stripTrailing();
            if (!word.isEmpty()
                    && measure.applyAsInt(word) * 100 >= measure.applyAsInt(text.substring(0, end)) * WORD_SHARE) {
                return word + ELLIPSIS;
            }
        }
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    /** Whether {@link #fit} shortens {@code text}, so the screen should offer the whole text as a tooltip. */
    public static boolean cut(String text, int width, ToIntFunction<String> measure) {
        return measure.applyAsInt(text) > width;
    }

    /**
     * {@code text} wrapped at spaces into lines at most {@code width} wide, at most {@code maxLines} of them; when it
     * needs more, the last line holds the rest of the text {@link #fit fitted} with an ellipsis. A word wider than a
     * line is cut with an ellipsis too. Empty for an empty text or no lines.
     */
    public static List<String> lines(String text, int width, int maxLines, ToIntFunction<String> measure) {
        List<String> all = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        wrap(text, width, measure, all, starts);
        if (all.size() <= maxLines) {
            return all;
        }
        List<String> out = new ArrayList<>(Math.max(0, maxLines));
        if (maxLines <= 0) {
            return out;
        }
        out.addAll(all.subList(0, maxLines - 1));
        out.add(fit(text.substring(starts.get(maxLines - 1)).strip(), width, measure));
        return out;
    }

    /** Whether {@link #lines} shortens {@code text}, so the screen should offer the whole text as a tooltip. */
    public static boolean linesCut(String text, int width, int maxLines, ToIntFunction<String> measure) {
        List<String> all = new ArrayList<>();
        wrap(text, width, measure, all, new ArrayList<>());
        return all.size() > maxLines;
    }

    /**
     * Greedy wrapping at spaces into {@code lines}, with where each line starts in {@code text} in {@code starts};
     * every line fits, and a word wider than a line is cut with an ellipsis.
     */
    private static void wrap(String text, int width, ToIntFunction<String> measure, List<String> lines,
            List<Integer> starts) {
        StringBuilder line = new StringBuilder();
        int i = 0;
        int length = text.length();
        while (i < length) {
            if (text.charAt(i) == ' ') {
                i++;
                continue;
            }
            int end = text.indexOf(' ', i);
            if (end < 0) {
                end = length;
            }
            String word = text.substring(i, end);
            if (!line.isEmpty() && measure.applyAsInt(line + " " + word) <= width) {
                line.append(' ').append(word);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                starts.add(i);
                line.append(measure.applyAsInt(word) <= width ? word : fit(word, width, measure));
            }
            i = end;
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
    }

    /** The widest of {@code texts}; 0 for none. */
    public static int widest(Iterable<String> texts, ToIntFunction<String> measure) {
        int widest = 0;
        for (String text : texts) {
            widest = Math.max(widest, measure.applyAsInt(text));
        }
        return widest;
    }

    /**
     * {@code needed} kept between {@code min} and {@code max}: a column or box that grows with its text up to a cap.
     */
    public static int grow(int needed, int min, int max) {
        return Math.max(min, Math.min(max, needed));
    }
}
