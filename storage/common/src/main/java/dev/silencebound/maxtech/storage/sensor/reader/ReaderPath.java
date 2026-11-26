package dev.silencebound.maxtech.storage.sensor.reader;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * A path into a block entity's saved data, as a pack reader names it: names separated by dots, list positions in
 * brackets, and names with dots or brackets in quotes inside brackets. {@code Energy.stored}, {@code Items[0].count},
 * {@code tanks[1].Amount} and {@code ["odd.name"].value} are paths. Positions count from 0. Immutable; parsed once when
 * the reader file loads, so finding a value allocates nothing.
 */
public final class ReaderPath {
    /** Longest path text a reader may give. */
    public static final int MAX_LENGTH = 256;
    /** Most steps in one path. */
    public static final int MAX_STEPS = 32;

    /** The path as written, re-encoded by {@link #CODEC}. */
    private final String text;
    /** Per step, the name to look up; null for a list position. */
    private final @Nullable String[] names;
    /** Per step, the list position; -1 for a name. */
    private final int[] positions;

    /** Reads and writes a path as its text; a bad path fails with what is wrong and where. */
    public static final Codec<ReaderPath> CODEC = Codec.STRING.comapFlatMap(ReaderPath::parse, ReaderPath::text);

    private ReaderPath(String text, @Nullable String[] names, int[] positions) {
        this.text = text;
        this.names = names;
        this.positions = positions;
    }

    /** The path as written. */
    public String text() {
        return text;
    }

    /** Steps in the path; at least one. */
    public int steps() {
        return positions.length;
    }

    /** The name of step {@code i}, or empty for a list position. */
    public String name(int i) {
        String name = names[i];
        return name == null ? "" : name;
    }

    /** The list position of step {@code i}, or -1 for a name. */
    public int position(int i) {
        return positions[i];
    }

    /**
     * Parses {@code text}: an error says what is wrong and at which character (counted from 1), so a pack maker finds
     * it in the log next to the file's name.
     */
    public static DataResult<ReaderPath> parse(String text) {
        if (text.isEmpty()) {
            return DataResult.error(() -> "empty path");
        }
        if (text.length() > MAX_LENGTH) {
            return DataResult.error(() -> "path longer than " + MAX_LENGTH + " characters");
        }
        List<@Nullable String> names = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        int i = 0;
        boolean needName = true;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '[') {
                int close = text.indexOf(']', i);
                if (close < 0) {
                    return error(text, i, "'[' without ']'");
                }
                String inside = text.substring(i + 1, close);
                if (inside.length() >= 2 && inside.charAt(0) == '"' && inside.charAt(inside.length() - 1) == '"') {
                    String name = inside.substring(1, inside.length() - 1);
                    if (name.isEmpty()) {
                        return error(text, i, "empty name in quotes");
                    }
                    names.add(name);
                    positions.add(-1);
                } else {
                    int position = position(inside);
                    if (position < 0) {
                        return error(text, i, "'" + inside + "' is not a list position (0, 1, 2, ...)");
                    }
                    names.add(null);
                    positions.add(position);
                }
                i = close + 1;
                needName = false;
            } else if (c == '.') {
                if (needName) {
                    return error(text, i, "a name is missing before '.'");
                }
                i++;
                if (i == text.length()) {
                    return error(text, i - 1, "the path ends with '.'");
                }
                if (text.charAt(i) == '.' || text.charAt(i) == '[') {
                    return error(text, i, "a name is missing after '.'");
                }
                needName = true;
            } else if (c == ']' || c == '"') {
                return error(text, i, "unexpected '" + c + "'");
            } else {
                if (!needName) {
                    return error(text, i, "'.' or '[' expected");
                }
                int end = i;
                while (end < text.length() && ".[]\"".indexOf(text.charAt(end)) < 0) {
                    end++;
                }
                names.add(text.substring(i, end));
                positions.add(-1);
                i = end;
                needName = false;
            }
            if (positions.size() > MAX_STEPS) {
                return DataResult.error(() -> "more than " + MAX_STEPS + " steps in '" + text + "'");
            }
        }
        int[] steps = new int[positions.size()];
        for (int s = 0; s < steps.length; s++) {
            steps[s] = positions.get(s);
        }
        return DataResult.success(new ReaderPath(text, names.toArray(new String[0]), steps));
    }

    private static DataResult<ReaderPath> error(String text, int index, String what) {
        return DataResult.error(() -> what + " at character " + (index + 1) + " of '" + text + "'");
    }

    /** {@code text} as a list position, or -1 when it is not one. */
    private static int position(String text) {
        if (text.isEmpty() || text.length() > 6) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /**
     * The tag the path leads to from {@code root}, or null when some step is missing: a name that is not there or is
     * asked of something that is not a compound, a position past the end or asked of something that is not a list or
     * array. Never throws.
     */
    public @Nullable Tag find(Tag root) {
        Tag at = root;
        for (int i = 0; i < positions.length; i++) {
            int position = positions[i];
            if (position < 0) {
                if (!(at instanceof CompoundTag compound)) {
                    return null;
                }
                String name = names[i];
                Tag next = name == null ? null : compound.get(name);
                if (next == null) {
                    return null;
                }
                at = next;
            } else {
                if (!(at instanceof CollectionTag list) || position >= list.size()) {
                    return null;
                }
                at = list.get(position);
            }
        }
        return at;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReaderPath path && path.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text + Arrays.toString(positions);
    }
}
