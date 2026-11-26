package dev.silencebound.maxtech.storage.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.OptionalDynamic;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * A logic graph as a short text players copy and paste (M7L2 sharing): {@code mtlogic1:} followed by the graph's saved
 * data, compressed and base64url-encoded. The data is the compound {@code {"name": ..., "graph": ...}}, where
 * {@code graph} is exactly what {@link LogicGraph#codec} saves (nodes with their settings and canvas positions, wires),
 * written as binary NBT and compressed with zlib (whose checksum catches a damaged text). Items stay item ids and
 * labels stay their text; device positions and the graph's memory never go in, so a text means the same on any network
 * and a label no device carries shows the usual "not on this network" note.
 *
 * <p>
 * {@link #read} takes untrusted text and is bounded at every step: the text length first, then at most
 * {@link #MAX_DATA} bytes after decompression, the NBT reader's own quota and depth, the node count against the
 * caller's limit before the graph codec runs, and the codec's list caps. Every problem comes back as a lang key from
 * {@link LogicLang} with its arguments, never as an exception. Pure; any thread.
 */
public final class GraphText {
    /** What every text starts with, before the format version and a colon. */
    public static final String PREFIX = "mtlogic";
    /** The text format this code writes and reads; a newer one is refused with {@link #NEWER}. */
    public static final int VERSION = 1;
    /** The longest text read at all, whatever the config allows; also the import packet's bound. */
    public static final int HARD_MAX_LENGTH = 65_536;
    /** Most bytes the decompressed data may hold. */
    static final int MAX_DATA = 1 << 20;
    /** Longest graph name kept from a text (the controller's name limit). */
    public static final int MAX_NAME = 32;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_VERSION_DIGITS = 4;
    private static final String NAME = "name";
    private static final String GRAPH = "graph";

    public static final String TOO_LONG = LogicLang.add("share.too_long",
            "The text is too long: %s characters, at most %s");
    public static final String EMPTY = LogicLang.add("share.empty", "The clipboard holds no text");
    public static final String BAD_PREFIX = LogicLang.add("share.bad_prefix",
            "This is not a logic graph: the text should start with mtlogic");
    public static final String NEWER = LogicLang.add("share.newer",
            "This graph comes from a newer version of MaxTech Storage (format %s)");
    public static final String CORRUPT = LogicLang.add("share.corrupt",
            "The text is damaged or cut off; copy it again");
    public static final String UNKNOWN_NODE = LogicLang.add("share.unknown_node",
            "The graph uses a node this game does not have: %s. A mod may be missing");
    public static final String UNREADABLE = LogicLang.add("share.unreadable",
            "The graph could not be read; it may name an item from a mod this game does not have");
    public static final String TOO_MANY_NODES = LogicLang.add("share.too_many_nodes",
            "The graph has %s nodes; this server allows at most %s");

    private GraphText() {
    }

    /**
     * The outcome of {@link #read}: the graph and its name, or the lang key of the problem with its arguments.
     *
     * @param graph
     *            the graph; empty when {@code error} says why not
     * @param name
     *            the graph's name as written, cleaned by {@link #shown}; empty on an error
     * @param error
     *            the problem's lang key; empty when the graph was read
     * @param args
     *            the problem's arguments
     */
    public record Read<K>(Optional<LogicGraph<K>> graph, String name, String error, List<String> args) {
        public Read {
            args = List.copyOf(args);
        }

        static <K> Read<K> failed(String error, Object... args) {
            String[] text = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                text[i] = String.valueOf(args[i]);
            }
            return new Read<>(Optional.empty(), "", error, List.of(text));
        }

        public boolean ok() {
            return graph.isPresent();
        }
    }

    /** Loads the class so its lang lines are declared; {@link LogicLang#english()} calls it. */
    static void init() {
    }

    /**
     * {@code graph} named {@code name} as a text; empty when the codec cannot encode it (an item its codec refuses).
     * {@code ops} must be an NBT ops ({@code NbtOps}, or registry ops over it for item keys).
     */
    public static <K> Optional<String> write(Codec<LogicGraph<K>> codec, DynamicOps<Tag> ops, String name,
            LogicGraph<K> graph) {
        Optional<CompoundTag> encoded = encode(codec, ops, graph);
        return encoded.isEmpty() ? Optional.empty() : writeData(name, encoded.get());
    }

    /**
     * The text of a graph already in its saved data ({@link #encode}); empty when the data cannot be written. A logic
     * card's size is measured with it, so a card holds no more than a text the server would import.
     */
    public static Optional<String> writeData(String name, CompoundTag graph) {
        CompoundTag root = new CompoundTag();
        root.putString(NAME, name);
        root.put(GRAPH, graph);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            NbtIo.write(root, out);
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.of(PREFIX + VERSION + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(raw.toByteArray())));
    }

    /**
     * {@code graph} as its codec's saved data: the {@code graph} part of a text, and what a logic card holds. Empty
     * when the codec cannot encode it. {@code ops} as for {@link #write}.
     */
    public static <K> Optional<CompoundTag> encode(Codec<LogicGraph<K>> codec, DynamicOps<Tag> ops,
            LogicGraph<K> graph) {
        Optional<Tag> encoded = codec.encodeStart(ops, graph).result();
        return encoded.isPresent() && encoded.get() instanceof CompoundTag compound
                ? Optional.of(compound)
                : Optional.empty();
    }

    /**
     * Reads a text written by {@link #write}. Whitespace anywhere is ignored (chat and editors wrap long lines).
     * Refused, in this order: longer than {@code maxLength} characters (or {@link #HARD_MAX_LENGTH}), empty, a wrong
     * prefix, a newer format, damaged data, more nodes than {@code maxNodes}, a node type this game does not have, and
     * a graph the codec refuses (such as an unknown item).
     */
    public static <K> Read<K> read(String text, int maxLength, int maxNodes, Codec<LogicGraph<K>> codec,
            DynamicOps<Tag> ops) {
        int limit = Math.min(maxLength, HARD_MAX_LENGTH);
        if (text.length() > limit) {
            return Read.failed(TOO_LONG, text.length(), limit);
        }
        String compact = withoutWhitespace(text);
        if (compact.isEmpty()) {
            return Read.failed(EMPTY);
        }
        if (!compact.startsWith(PREFIX)) {
            return Read.failed(BAD_PREFIX);
        }
        int colon = compact.indexOf(':', PREFIX.length());
        if (colon <= PREFIX.length() || colon > PREFIX.length() + MAX_VERSION_DIGITS) {
            return Read.failed(BAD_PREFIX);
        }
        int version = 0;
        for (int i = PREFIX.length(); i < colon; i++) {
            char c = compact.charAt(i);
            if (c < '0' || c > '9') {
                return Read.failed(BAD_PREFIX);
            }
            version = version * 10 + (c - '0');
        }
        if (version < 1) {
            return Read.failed(BAD_PREFIX);
        }
        if (version > VERSION) {
            return Read.failed(NEWER, version);
        }
        Optional<CompoundTag> root = data(compact.substring(colon + 1));
        if (root.isEmpty()) {
            return Read.failed(CORRUPT);
        }
        Dynamic<Tag> data = new Dynamic<>(ops, root.get());
        OptionalDynamic<Tag> graph = data.get(GRAPH);
        if (graph.result().isEmpty()) {
            return Read.failed(CORRUPT);
        }
        return fromData(graph.result().get(), data.get(NAME).asString(""), maxNodes, codec);
    }

    /**
     * Reads a graph from its codec's saved data ({@link #encode}) with every check {@link #read} makes once the text is
     * unpacked: a newer format, more nodes than {@code maxNodes}, a node type this game does not have, and a graph the
     * codec refuses. {@code rawName} is cleaned by {@link #shown}. A logic card's data goes through here too, since a
     * player in creative mode can put any data on an item.
     */
    public static <K> Read<K> fromData(Dynamic<Tag> graph, String rawName, int maxNodes, Codec<LogicGraph<K>> codec) {
        int format = graph.get("version").asInt(0);
        if (format > LogicGraph.FORMAT_VERSION) {
            return Read.failed(NEWER, format);
        }
        List<Dynamic<Tag>> nodes = graph.get("nodes").asList(Function.identity());
        if (nodes.size() > maxNodes) {
            return Read.failed(TOO_MANY_NODES, nodes.size(), maxNodes);
        }
        for (Dynamic<Tag> node : nodes) {
            String type = node.get("type").asString("");
            if (NodeTypes.byId(type).isEmpty()) {
                return Read.failed(UNKNOWN_NODE, shown(type));
            }
        }
        Optional<LogicGraph<K>> decoded;
        try {
            decoded = codec.parse(graph).result();
        } catch (RuntimeException e) {
            // A codec's constructor may throw on bad input instead of returning an error (storage-facts.md).
            decoded = Optional.empty();
        }
        if (decoded.isEmpty()) {
            return Read.failed(UNREADABLE);
        }
        return new Read<>(decoded, shown(rawName), "", List.of());
    }

    /**
     * Text from an untrusted source made safe to show: control characters and the section sign (legacy formatting)
     * dropped, at most {@link #MAX_NAME} characters, never ending in half a surrogate pair.
     */
    public static String shown(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NAME));
        for (int i = 0; i < raw.length() && out.length() < MAX_NAME; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c) && c != '§') {
                out.append(c);
            }
        }
        if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static String withoutWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The compound in the encoded part of a text; empty when it is not valid base64url, zlib or NBT, or too large. */
    private static Optional<CompoundTag> data(String encoded) {
        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Optional<byte[]> raw = inflate(compressed);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw.get()))) {
            return Optional.of(NbtIo.read(in, new NbtAccounter(4L * MAX_DATA, MAX_DEPTH)));
        } catch (IOException | RuntimeException e) {
            // NbtIo throws IOException for a bad root, NbtAccounterException or ReportedNbtException for the rest.
            return Optional.empty();
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 16);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Decompresses zlib data, refusing more than {@link #MAX_DATA} bytes, cut-off data and a bad checksum. */
    private static Optional<byte[]> inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return Optional.empty();
                }
                if (out.size() + n > MAX_DATA) {
                    return Optional.empty();
                }
                out.write(buffer, 0, n);
            }
            return Optional.of(out.toByteArray());
        } catch (DataFormatException e) {
            return Optional.empty();
        } finally {
            inflater.end();
        }
    }
}
