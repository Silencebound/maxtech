package dev.silencebound.maxtech.lib.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A small, forgiving text markup for screens and messages, parsed into lines of styled runs. Pure Java: it knows no
 * items, fonts or values, so a server can validate text and a client lay it out from the same parse. Immutable and
 * thread-safe once built.
 *
 * <p>
 * Syntax, one line of output per line of input:
 * <ul>
 * <li>Colours: {@code <red>text</red>} with the 16 Minecraft text colour names ({@link #COLOUR_NAMES}, such as
 * {@code dark_blue} or {@code gold}); {@code <b>} or {@code <bold>} and {@code <i>} or {@code <italic>} for bold and
 * italic. Tags nest; {@code </red>} closes the newest open {@code <red>}, {@code </>} the newest open tag of any kind.
 * Tags still open at the end of a line close there, so a line starting {@code <red>} is red to its end. Tag names
 * ignore case but take no spaces, so {@code a < b > c} stays text.</li>
 * <li>Line tags, anywhere in a line, set the whole line; the last one of a kind wins and their closing tags are
 * ignored: {@code <left>}, {@code <center>} (or {@code <centre>}), {@code <right>} for the alignment and
 * {@code <small>}, {@code <normal>}, {@code <big>} for the size ({@link Size}).</li>
 * <li>Icons: {@code [item:minecraft:diamond]} or {@code [block:minecraft:stone]}; without a namespace {@code minecraft}
 * is meant ({@code [item:diamond]}). Whether the item exists is for the caller to check.</li>
 * <li>Placeholders: {@code {name}} or {@code {name:argument}} for a name the caller allows ({@code parse}'s
 * {@code placeholders}, matched case-insensitively); the argument is everything after the first colon. The caller
 * resolves them into values ({@link Value#index} numbers them in reading order).</li>
 * <li>A backslash before {@code <}, {@code [}, <code>{</code> or another backslash shows that character as text
 * ({@link #escape}).</li>
 * </ul>
 * Anything else is text: an unknown tag, a malformed icon, a placeholder of an unknown name and a stray bracket are
 * shown as typed. Control characters and the section sign are dropped, so text cannot carry vanilla's legacy formatting
 * codes. Input past {@link #MAX_LENGTH} characters or {@link #MAX_LINES} lines, and icons or placeholders past
 * {@link #MAX_ICONS} or {@link #MAX_VALUES}, are cut off or shown as text; {@link #truncated()} says whether that
 * happened.
 */
public final class RichText {
    /** Longest input, in characters; longer input is cut there. */
    public static final int MAX_LENGTH = 1024;
    /** Most lines; later lines are dropped. */
    public static final int MAX_LINES = 24;
    /** Most icons; later ones are shown as text. */
    public static final int MAX_ICONS = 32;
    /** Most placeholders; later ones are shown as text. */
    public static final int MAX_VALUES = 16;
    /** No colour: the caller's default. */
    public static final int NO_COLOUR = -1;

    /** The 16 Minecraft text colour names, in vanilla's order (as {@code ChatFormatting}). */
    public static final List<String> COLOUR_NAMES = List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow",
            "white");
    /** The RGB of each of {@link #COLOUR_NAMES}, as vanilla's {@code ChatFormatting} colours. */
    private static final int[] COLOUR_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
            0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** Longest tag between angle brackets, in characters. */
    private static final int MAX_TAG = 16;
    /** Longest icon between square brackets. */
    private static final int MAX_ICON = 128;
    /** Longest placeholder between braces. */
    private static final int MAX_PLACEHOLDER = 160;

    /** A line's alignment. */
    public enum Align {
        LEFT, CENTER, RIGHT
    }

    /** A line's text size, as a scale of the normal size. */
    public enum Size {
        SMALL(0.75F), NORMAL(1.0F), BIG(2.0F);

        private final float scale;

        Size(float scale) {
            this.scale = scale;
        }

        public float scale() {
            return scale;
        }
    }

    /**
     * How a run looks.
     *
     * @param colour
     *            RGB, or {@link #NO_COLOUR} for the caller's default
     */
    public record Style(int colour, boolean bold, boolean italic) {
        public static final Style PLAIN = new Style(NO_COLOUR, false, false);
    }

    /** One piece of a line. */
    public sealed interface Run permits Text, Icon, Value {
        Style style();
    }

    /** Plain text in one style; never empty. */
    public record Text(String text, Style style) implements Run {
    }

    /**
     * An item's icon.
     *
     * @param id
     *            {@code namespace:path}, lower case; whether the item exists is not checked
     */
    public record Icon(String id, Style style) implements Run {
    }

    /**
     * A placeholder the caller fills in.
     *
     * @param index
     *            its position among the text's placeholders, from 0 in reading order
     * @param name
     *            the placeholder's name, lower case
     * @param argument
     *            everything after the first colon, as typed; empty without one
     */
    public record Value(int index, String name, String argument, Style style) implements Run {
    }

    /** One line: its runs in reading order (none for an empty line), alignment and size. */
    public record Line(List<Run> runs, Align align, Size size) {
        public Line {
            runs = List.copyOf(runs);
        }
    }

    public static final RichText EMPTY = new RichText(List.of(), List.of(), List.of(), false);

    private final List<Line> lines;
    private final List<Icon> icons;
    private final List<Value> values;
    private final boolean truncated;

    private RichText(List<Line> lines, List<Icon> icons, List<Value> values, boolean truncated) {
        this.lines = lines;
        this.icons = icons;
        this.values = values;
        this.truncated = truncated;
    }

    /** The lines, at most {@link #MAX_LINES}. */
    public List<Line> lines() {
        return lines;
    }

    /** Every icon in reading order, at most {@link #MAX_ICONS}. */
    public List<Icon> icons() {
        return icons;
    }

    /** Every placeholder in reading order (the {@link Value#index} order), at most {@link #MAX_VALUES}. */
    public List<Value> values() {
        return values;
    }

    /** Whether input was cut off or icons or placeholders over the caps were shown as text. */
    public boolean truncated() {
        return truncated;
    }

    /** The RGB of colour name {@code name} (lower case), or {@link #NO_COLOUR} for an unknown name. */
    public static int colour(String name) {
        int index = COLOUR_NAMES.indexOf(name);
        return index < 0 ? NO_COLOUR : COLOUR_RGB[index];
    }

    /** {@code text} with every character the markup would read as markup escaped, so it shows as typed. */
    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '<' || c == '[' || c == '{') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * {@code source} with line breaks as {@code \n}, without control characters (but line breaks) and section signs,
     * and cut to {@link #MAX_LENGTH} characters without splitting a surrogate pair. What a server stores.
     */
    public static String clean(String source) {
        StringBuilder out = new StringBuilder(Math.min(source.length(), MAX_LENGTH));
        for (int i = 0; i < source.length() && out.length() < MAX_LENGTH; i++) {
            char c = source.charAt(i);
            if (c == '\r') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    continue;
                }
                c = '\n';
            }
            if ((c < 0x20 && c != '\n') || c == 0x7F || c == '§') {
                continue;
            }
            if (Character.isHighSurrogate(c) && out.length() + 1 >= MAX_LENGTH) {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** {@code source} parsed with no placeholders: every brace is text. */
    public static RichText parse(String source) {
        return parse(source, Set.of());
    }

    /**
     * Parses {@code source} (cleaned first, see {@link #clean}); {@code placeholders} are the lower-case names read as
     * placeholders. Never fails: whatever is not markup is text.
     */
    public static RichText parse(String source, Set<String> placeholders) {
        String text = clean(source);
        boolean truncated = text.length() < source.length() && text.length() >= MAX_LENGTH;
        if (text.isEmpty()) {
            return EMPTY;
        }
        Parser parser = new Parser(placeholders);
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            if (parser.lines.size() == MAX_LINES) {
                truncated = true;
                break;
            }
            parser.line(text.substring(start, end));
            start = end + 1;
        }
        return new RichText(Collections.unmodifiableList(parser.lines), Collections.unmodifiableList(parser.icons),
                Collections.unmodifiableList(parser.values), truncated || parser.overCap);
    }

    /** The parse of one text, line by line. */
    private static final class Parser {
        private final Set<String> placeholders;
        final List<Line> lines = new ArrayList<>();
        final List<Icon> icons = new ArrayList<>();
        final List<Value> values = new ArrayList<>();
        boolean overCap;
        /** Open style tags of the current line, oldest first: their names and colours. */
        private final List<String> open = new ArrayList<>();
        private final List<Integer> openColours = new ArrayList<>();
        private final List<Run> runs = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();
        private Style style = Style.PLAIN;
        private Align align = Align.LEFT;
        private Size size = Size.NORMAL;

        Parser(Set<String> placeholders) {
            this.placeholders = placeholders;
        }

        void line(String line) {
            open.clear();
            openColours.clear();
            runs.clear();
            pending.setLength(0);
            style = Style.PLAIN;
            align = Align.LEFT;
            size = Size.NORMAL;
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                int next = switch (c) {
                    case '\\' -> escaped(line, i);
                    case '<' -> tag(line, i);
                    case '[' -> icon(line, i);
                    case '{' -> placeholder(line, i);
                    default -> -1;
                };
                if (next < 0) {
                    pending.append(c);
                    i++;
                } else {
                    i = next;
                }
            }
            flush();
            lines.add(new Line(runs, align, size));
        }

        private int escaped(String line, int at) {
            if (at + 1 < line.length() && "\\<[{".indexOf(line.charAt(at + 1)) >= 0) {
                pending.append(line.charAt(at + 1));
                return at + 2;
            }
            return -1;
        }

        /** Reads a tag at {@code at}; returns the index after it, or -1 when it is no known tag. */
        private int tag(String line, int at) {
            int end = line.indexOf('>', at + 1);
            if (end < 0 || end - at - 1 > MAX_TAG) {
                return -1;
            }
            String body = line.substring(at + 1, end).toLowerCase(Locale.ROOT);
            boolean closing = body.startsWith("/");
            String name = closing ? body.substring(1) : body;
            switch (name) {
                case "left" -> align = closing ? align : Align.LEFT;
                case "center", "centre" -> align = closing ? align : Align.CENTER;
                case "right" -> align = closing ? align : Align.RIGHT;
                case "small" -> size = closing ? size : Size.SMALL;
                case "normal" -> size = closing ? size : Size.NORMAL;
                case "big" -> size = closing ? size : Size.BIG;
                case "" -> {
                    if (!closing) {
                        return -1;
                    }
                    close(null);
                }
                default -> {
                    String styleName = switch (name) {
                        case "b", "bold" -> "b";
                        case "i", "italic" -> "i";
                        default -> colour(name) != NO_COLOUR ? name : null;
                    };
                    if (styleName == null) {
                        return -1;
                    }
                    if (closing) {
                        close(styleName);
                    } else {
                        open.add(styleName);
                        openColours.add(colour(styleName));
                        restyle();
                    }
                }
            }
            return end + 1;
        }

        /** Closes the newest open tag named {@code name}, or the newest of any with null; nothing if none is open. */
        private void close(@Nullable String name) {
            for (int i = open.size() - 1; i >= 0; i--) {
                if (name == null || open.get(i).equals(name)) {
                    open.remove(i);
                    openColours.remove(/* index */ i);
                    restyle();
                    return;
                }
            }
        }

        private void restyle() {
            int colour = NO_COLOUR;
            boolean bold = false;
            boolean italic = false;
            for (int i = 0; i < open.size(); i++) {
                String name = open.get(i);
                if (name.equals("b")) {
                    bold = true;
                } else if (name.equals("i")) {
                    italic = true;
                } else {
                    colour = openColours.get(i);
                }
            }
            Style changed = new Style(colour, bold, italic);
            if (!changed.equals(style)) {
                flush();
                style = changed;
            }
        }

        private int icon(String line, int at) {
            int end = line.indexOf(']', at + 1);
            if (end < 0 || end - at - 1 > MAX_ICON) {
                return -1;
            }
            String body = line.substring(at + 1, end).trim().toLowerCase(Locale.ROOT);
            String id;
            if (body.startsWith("item:")) {
                id = body.substring(5);
            } else if (body.startsWith("block:")) {
                id = body.substring(6);
            } else {
                return -1;
            }
            id = id.trim();
            String normal = normalId(id);
            if (normal == null) {
                return -1;
            }
            if (icons.size() >= MAX_ICONS) {
                overCap = true;
                return -1;
            }
            flush();
            Icon icon = new Icon(normal, style);
            icons.add(icon);
            runs.add(icon);
            return end + 1;
        }

        private int placeholder(String line, int at) {
            int end = line.indexOf('}', at + 1);
            if (end < 0 || end - at - 1 > MAX_PLACEHOLDER) {
                return -1;
            }
            String body = line.substring(at + 1, end);
            int colon = body.indexOf(':');
            String name = (colon < 0 ? body : body.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            if (!placeholders.contains(name)) {
                return -1;
            }
            if (values.size() >= MAX_VALUES) {
                overCap = true;
                return -1;
            }
            flush();
            Value value = new Value(values.size(), name, colon < 0 ? "" : body.substring(colon + 1).trim(), style);
            values.add(value);
            runs.add(value);
            return end + 1;
        }

        private void flush() {
            if (!pending.isEmpty()) {
                runs.add(new Text(pending.toString(), style));
                pending.setLength(0);
            }
        }
    }

    /**
     * {@code id} as {@code namespace:path} when it is a valid resource id (lower-case letters, digits and {@code _-.},
     * plus {@code /} in the path), with {@code minecraft} for a missing namespace; null otherwise.
     */
    static @Nullable String normalId(String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        if (namespace.isEmpty() || path.isEmpty() || !valid(namespace, false) || !valid(path, true)) {
            return null;
        }
        return namespace + ":" + path;
    }

    private static boolean valid(String part, boolean path) {
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'
                    || (path && c == '/');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
