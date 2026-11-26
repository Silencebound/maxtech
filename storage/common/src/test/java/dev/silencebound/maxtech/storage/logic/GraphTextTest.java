package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

class GraphTextTest {
    private static final Codec<LogicGraph<String>> CODEC = LogicGraph.codec(Codec.STRING);
    private static final int MAX_LENGTH = 16_384;

    private static String write(String name, LogicGraph<String> graph) {
        return GraphText.write(CODEC, NbtOps.INSTANCE, name, graph).orElseThrow();
    }

    private static GraphText.Read<String> read(String text) {
        return read(text, 64);
    }

    private static GraphText.Read<String> read(String text, int maxNodes) {
        return GraphText.read(text, MAX_LENGTH, maxNodes, CODEC, NbtOps.INSTANCE);
    }

    /** One node of every type, every setting filled in for its kind, placed apart, with a few wires. */
    private static LogicGraph<String> everyType() {
        List<LogicNode<String>> nodes = new ArrayList<>();
        int id = 1;
        for (NodeType type : NodeTypes.all()) {
            NodeSettings<String> settings = NodeSettings.empty();
            for (SettingSpec spec : type.settings()) {
                settings = switch (spec.kind()) {
                    case NUMBER -> settings.withNumber(spec.name(), spec.max());
                    case FLAG -> settings.withFlag(spec.name(), true);
                    case CHOICE -> settings.withText(spec.name(), spec.choices().getLast());
                    case TEXT -> settings.withText(spec.name(), "Low on <red>cobble</red> é");
                    case LABEL -> settings.withText(spec.name(), "Not On This Network");
                    case ITEM -> settings.withItem(spec.name(), "minecraft:cobblestone");
                    case FLUID -> settings.withItem(spec.name(), "minecraft:water");
                    case TAG -> settings.withItem(spec.name(), "#minecraft:logs");
                };
            }
            nodes.add(new LogicNode<>(id, type, id * 37 - 400, -id * 11, settings));
            id++;
        }
        List<Wire> wires = List.of(new Wire(1, "crossed", 2, "do"), new Wire(3, "value", 4, "value"));
        return new LogicGraph<>(nodes, wires);
    }

    @Test
    void everyNodeTypeSurvivesARoundTrip() {
        LogicGraph<String> graph = everyType();
        String text = write("Cobble watch", graph);
        assertTrue(text.startsWith("mtlogic1:"), text);
        for (char c : text.substring("mtlogic1:".length()).toCharArray()) {
            assertTrue(Character.isLetterOrDigit(c) || c == '-' || c == '_', "base64url only: " + c);
        }
        GraphText.Read<String> read = read(text, NodeTypes.all().size());
        assertTrue(read.ok(), read.error());
        assertEquals(graph, read.graph().orElseThrow());
        assertEquals("Cobble watch", read.name());
        assertEquals(NodeTypes.all().size(), read.graph().orElseThrow().nodes().size());
    }

    @Test
    void wrappedTextStillReads() {
        String text = write("x", everyType());
        StringBuilder wrapped = new StringBuilder("  ");
        for (int i = 0; i < text.length(); i += 40) {
            wrapped.append(text, i, Math.min(text.length(), i + 40)).append("\r\n");
        }
        assertTrue(read(wrapped.toString(), 1024).ok());
    }

    @Test
    void aBigGraphStaysShort() {
        // 64 nodes, the default limit: the default maxImportLength must take it with room to spare.
        List<LogicNode<String>> nodes = new ArrayList<>();
        List<Wire> wires = new ArrayList<>();
        for (int i = 1; i <= 64; i++) {
            nodes.add(new LogicNode<>(i, i % 2 == 0 ? NodeTypes.COMPARE : NodeTypes.COUNT_CROSSES, i * 40, i * 16,
                    NodeSettings.<String>empty().withItem("item", "minecraft:item_number_" + i)
                            .withText("label", "chest " + i).withNumber("threshold", i * 1000L)));
            if (i % 2 == 0) {
                wires.add(new Wire(i - 1, "count", i, "value"));
            }
        }
        String text = write("A long graph name here", new LogicGraph<>(nodes, wires));
        assertTrue(text.length() < MAX_LENGTH / 4, "length " + text.length());
        assertTrue(read(text).ok());
    }

    @Test
    void tooLongTextIsRefusedBeforeReading() {
        String text = write("x", everyType());
        GraphText.Read<String> read = GraphText.read(text, text.length() - 1, 1024, CODEC, NbtOps.INSTANCE);
        assertEquals(GraphText.TOO_LONG, read.error());
        assertEquals(List.of(String.valueOf(text.length()), String.valueOf(text.length() - 1)), read.args());
        assertTrue(GraphText.read(text, text.length(), 1024, CODEC, NbtOps.INSTANCE).ok());
        String huge = "mtlogic1:" + "A".repeat(GraphText.HARD_MAX_LENGTH);
        assertEquals(GraphText.TOO_LONG, GraphText.read(huge, Integer.MAX_VALUE, 1024, CODEC, NbtOps.INSTANCE).error());
    }

    @Test
    void emptyAndForeignTextIsRefused() {
        assertEquals(GraphText.EMPTY, read(" \n ").error());
        assertEquals(GraphText.BAD_PREFIX, read("hello world").error());
        assertEquals(GraphText.BAD_PREFIX, read("mtlogic:abc").error());
        assertEquals(GraphText.BAD_PREFIX, read("mtlogicx:abc").error());
        assertEquals(GraphText.BAD_PREFIX, read("mtlogic0:abc").error());
        assertEquals(GraphText.BAD_PREFIX, read("mtlogic12345:abc").error());
        assertEquals(GraphText.BAD_PREFIX, read("mtlogic1").error());
    }

    @Test
    void newerFormatIsRefused() {
        String text = write("x", everyType());
        GraphText.Read<String> read = read("mtlogic2" + text.substring("mtlogic1".length()));
        assertEquals(GraphText.NEWER, read.error());
        assertEquals(List.of("2"), read.args());

        CompoundTag graph = new CompoundTag();
        graph.putInt("version", LogicGraph.FORMAT_VERSION + 1);
        GraphText.Read<String> inner = read(encode(root("x", graph)));
        assertEquals(GraphText.NEWER, inner.error());
    }

    @Test
    void damagedTextIsRefused() {
        String text = write("x", everyType());
        assertEquals(GraphText.CORRUPT, read(text.substring(0, text.length() - 7)).error(), "cut off");
        assertEquals(GraphText.CORRUPT, read("mtlogic1:not*base64").error(), "not base64url");
        assertEquals(GraphText.CORRUPT, read("mtlogic1:").error(), "nothing after the prefix");
        // Change one character in the middle: zlib's checksum or the data itself catches it.
        int middle = text.length() / 2;
        char swapped = text.charAt(middle) == 'A' ? 'B' : 'A';
        GraphText.Read<String> flipped = read(text.substring(0, middle) + swapped + text.substring(middle + 1));
        assertFalse(flipped.ok());
        assertEquals(GraphText.CORRUPT, flipped.error());
        // Valid zlib that is not NBT, and NBT without a graph.
        assertEquals(GraphText.CORRUPT, read(prefixed(deflate(new byte[]{1, 2, 3}))).error());
        assertEquals(GraphText.CORRUPT, read(encode(new CompoundTag())).error());
    }

    @Test
    void aCompressionBombIsRefused() {
        // A good graph with a megabyte of zeros beside it: small as text, refused once it inflates past the cap.
        CompoundTag graph = new CompoundTag();
        graph.putInt("version", 1);
        CompoundTag root = root("x", graph);
        root.putByteArray("padding", new byte[GraphText.MAX_DATA]);
        String text = encode(root);
        assertTrue(text.length() < MAX_LENGTH, "the bomb is small: " + text.length());
        assertEquals(GraphText.CORRUPT, read(text).error());
    }

    @Test
    void unknownNodeTypeNamesTheType() {
        CompoundTag node = new CompoundTag();
        node.putInt("id", 1);
        node.putString("type", "othermod_teleport");
        ListTag nodes = new ListTag();
        nodes.add(node);
        CompoundTag graph = new CompoundTag();
        graph.putInt("version", 1);
        graph.put("nodes", nodes);
        GraphText.Read<String> read = read(encode(root("x", graph)));
        assertEquals(GraphText.UNKNOWN_NODE, read.error());
        assertEquals(List.of("othermod_teleport"), read.args());
    }

    @Test
    void tooManyNodesIsRefusedWithTheCounts() {
        String text = write("x", everyType());
        int count = NodeTypes.all().size();
        GraphText.Read<String> read = read(text, count - 1);
        assertEquals(GraphText.TOO_MANY_NODES, read.error());
        assertEquals(List.of(String.valueOf(count), String.valueOf(count - 1)), read.args());
        assertTrue(read(text, count).ok());
    }

    @Test
    void aGraphTheCodecRefusesIsUnreadable() {
        CompoundTag node = new CompoundTag();
        node.putString("type", "constant");
        // No id: the node codec needs one.
        ListTag nodes = new ListTag();
        nodes.add(node);
        CompoundTag graph = new CompoundTag();
        graph.putInt("version", 1);
        graph.put("nodes", nodes);
        assertEquals(GraphText.UNREADABLE, read(encode(root("x", graph))).error());
    }

    @Test
    void longNamesAreCut() {
        GraphText.Read<String> read = read(write("n".repeat(100), LogicGraph.empty()));
        assertTrue(read.ok());
        assertEquals(GraphText.MAX_NAME, read.name().length());
    }

    @Test
    void untrustedNamesAreCleanedForShowing() {
        GraphText.Read<String> read = read(write("§cRed\nline", LogicGraph.empty()));
        assertEquals("cRedline", read.name());
        // A surrogate pair cut at the limit loses both halves.
        String emoji = "😀";
        assertEquals("a".repeat(GraphText.MAX_NAME - 1), GraphText.shown("a".repeat(GraphText.MAX_NAME - 1) + emoji));
    }

    // ---- hand-made texts

    private static CompoundTag root(String name, CompoundTag graph) {
        CompoundTag root = new CompoundTag();
        root.putString("name", name);
        root.put("graph", graph);
        return root;
    }

    private static String encode(CompoundTag root) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            NbtIo.write(root, out);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return prefixed(deflate(raw.toByteArray()));
    }

    private static String prefixed(byte[] compressed) {
        return "mtlogic1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed);
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
