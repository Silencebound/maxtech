package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.lib.client.gui.RightAngleWire;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.text.TextFit;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSentence;
import dev.silencebound.maxtech.storage.logic.Port;
import dev.silencebound.maxtech.storage.logic.Sequences;
import dev.silencebound.maxtech.storage.logic.PortType;
import dev.silencebound.maxtech.storage.logic.Wire;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.editor.DiagramFit;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import dev.silencebound.maxtech.storage.logic.editor.NodeLayout;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A rule's graph drawn read-only in the Logic Controller's NODES view (M7L2 chunk 11): the same nodes, ports and wire
 * colours as the node editor ({@link NodeArt}), scaled to fit the view and centred. A graph too large to read at the
 * smallest scale ({@link #MIN_SCALE}) shows at that scale and is panned by dragging. Nodes with a live line show the
 * values the card view received; hovering a node outlines it and shows its title, sentence and live value. The caches
 * are made again only when a new graph or new values arrive. Client only.
 */
final class NodeDiagram {
    /** The smallest scale, in GUI pixels per editor unit; below it node text is too small to read. */
    static final double MIN_SCALE = 0.5;
    /** The largest scale: a small graph shows at the editor's size, not magnified. */
    static final double MAX_SCALE = 1.0;
    /** Room around the nodes for ports and wire stubs. */
    private static final int MARGIN = 8;
    private static final int STUB = 6;
    private static final int CANVAS = 0xFF101316;
    private static final int HOVER_EDGE = 0xFFFFFF55;

    /** Whether the hover tooltip says a click opens the editor (the controller's view; not the guide's). */
    private final boolean editorTips;
    private @Nullable LogicGraph<LogicKey> graph;
    private @Nullable List<EditorPayloads.LiveValue> seenValues;
    /** The graph's nodes in its order, which is the drawing order (later nodes on top). */
    private List<LogicNode<LogicKey>> nodes = List.of();
    private final Int2ObjectOpenHashMap<LogicNode<LogicKey>> byId = new Int2ObjectOpenHashMap<>();
    private final Int2IntOpenHashMap widths = new Int2IntOpenHashMap();
    private final Int2ObjectOpenHashMap<String> titles = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String> summaries = new Int2ObjectOpenHashMap<>();
    /** Whole summaries of the nodes whose shown summary ends with an ellipsis, for the tooltip. */
    private final Int2ObjectOpenHashMap<String> cutSummaries = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String> sentences = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Object2LongOpenHashMap<String>> values = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String> liveLines = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String> liveFull = new Int2ObjectOpenHashMap<>();
    private final Int2IntOpenHashMap liveColours = new Int2IntOpenHashMap();
    private boolean countdown;
    private long liveTime = Long.MIN_VALUE;
    private Wire[] wires = new Wire[0];
    private int[] wireOut = new int[0];
    private int[] wireIn = new int[0];
    private PortType[] wireTypes = new PortType[0];
    private final int[] route = new int[RightAngleWire.MAX_POINTS * 2];
    private int minX;
    private int minY;
    private int contentWidth;
    private int contentHeight;

    // From the last draw, for input.
    private int viewX;
    private int viewY;
    private int viewWidth;
    private int viewHeight;
    private double scale = MAX_SCALE;
    private double originX;
    private double originY;
    private double panX;
    private double panY;

    /** A diagram for the Logic Controller's NODES view. */
    NodeDiagram() {
        this(true);
    }

    /** A diagram; {@code editorTips} adds "click to open the editor" to the hover tooltip. */
    NodeDiagram(boolean editorTips) {
        this.editorTips = editorTips;
    }

    /** The width of the shown graph's nodes and margins in editor units; 0 while it has no nodes. */
    int contentWidth() {
        return contentWidth;
    }

    /** The height of the shown graph's nodes and margins in editor units; 0 while it has no nodes. */
    int contentHeight() {
        return contentHeight;
    }

    /**
     * Shows {@code shown} with live values {@code live}; caches are made again only when the graph or the values are
     * new objects. A new graph starts unpanned.
     */
    @SuppressWarnings("ReferenceEquality") // each graph or value update the server sends is a new object
    void show(Font font, LogicGraph<LogicKey> shown, List<EditorPayloads.LiveValue> live) {
        if (shown != graph) {
            graph = shown;
            panX = 0;
            panY = 0;
            rebuild(font, shown);
            seenValues = null;
        }
        if (live != seenValues) {
            seenValues = live;
            setValues(live);
        }
    }

    private void setValues(List<EditorPayloads.LiveValue> live) {
        values.clear();
        for (EditorPayloads.LiveValue value : live) {
            Object2LongOpenHashMap<String> map = values.get(value.node());
            if (map == null) {
                map = new Object2LongOpenHashMap<>();
                values.put(value.node(), map);
            }
            map.put(value.output(), value.value());
        }
        liveTime = Long.MIN_VALUE;
    }

    private void rebuild(Font font, LogicGraph<LogicKey> shown) {
        byId.clear();
        widths.clear();
        titles.clear();
        summaries.clear();
        cutSummaries.clear();
        sentences.clear();
        nodes = shown.nodes();
        Language language = Language.getInstance();
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (LogicNode<LogicKey> node : shown.nodes()) {
            byId.put(node.id(), node);
            String title = I18n.get(node.type().nameKey());
            String summary = NodeArt.summary(node);
            int width = NodeLayout.width(font.width(title),
                    Math.max(font.width(summary), NodeArt.liveSampleWidth(font, node)));
            widths.put(node.id(), width);
            titles.put(node.id(), NodeLayout.fitTitle(title, width, font::width));
            String fitted = NodeLayout.fitLine(summary, width, font::width);
            summaries.put(node.id(), fitted);
            if (!fitted.equals(summary)) {
                cutSummaries.put(node.id(), summary);
            }
            if (language.has(node.type().sentenceKey())) {
                sentences.put(node.id(), NodeSentence.of(node.type(), node.settings(), language::getOrDefault,
                        LogicEditorScreen::keyName));
            }
            left = Math.min(left, node.x());
            top = Math.min(top, node.y());
            right = Math.max(right, node.x() + width);
            bottom = Math.max(bottom, node.y() + NodeArt.height(node));
        }
        if (shown.nodes().isEmpty()) {
            minX = 0;
            minY = 0;
            contentWidth = 0;
            contentHeight = 0;
        } else {
            minX = left - MARGIN;
            minY = top - MARGIN;
            contentWidth = right - left + 2 * MARGIN;
            contentHeight = bottom - top + 2 * MARGIN;
        }
        List<Wire> list = shown.wires();
        int n = list.size();
        wires = list.toArray(new Wire[0]);
        wireOut = new int[n];
        wireIn = new int[n];
        wireTypes = new PortType[n];
        for (int i = 0; i < n; i++) {
            Wire wire = wires[i];
            LogicNode<LogicKey> from = byId.get(wire.from());
            LogicNode<LogicKey> to = byId.get(wire.to());
            List<Port> outputs = from == null ? List.of() : from.type().outputs(from.settings());
            wireOut[i] = portIndex(outputs, wire.output());
            wireIn[i] = to == null ? -1 : portIndex(to.type().inputs(to.settings()), wire.input());
            wireTypes[i] = wireOut[i] < 0 ? PortType.EVENT : outputs.get(wireOut[i]).type();
        }
    }

    private static int portIndex(List<Port> ports, String name) {
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Makes the live lines from the values; again each second while a delay counts down. */
    private void refreshLive(Font font, long now) {
        if (liveTime != Long.MIN_VALUE && !(countdown && now / 20 != liveTime / 20)) {
            return;
        }
        liveTime = now;
        liveLines.clear();
        liveFull.clear();
        liveColours.clear();
        countdown = false;
        for (LogicNode<LogicKey> node : nodes) {
            Optional<LiveLine.Spec> found = LiveLine.of(node.type());
            Object2LongOpenHashMap<String> map = values.get(node.id());
            if (found.isEmpty() || map == null || !map.containsKey(found.get().entry())) {
                continue;
            }
            NodeArt.Live line = NodeArt.live(node, found.get(), map.getLong(found.get().entry()), now);
            countdown |= line.countdown();
            int room = NodeLayout.lineRoom(widths.getOrDefault(node.id(), NodeLayout.WIDTH));
            liveLines.put(node.id(), TextFit.fit(line.text(), room, font::width));
            liveFull.put(node.id(), line.text());
            liveColours.put(node.id(), line.colour());
        }
    }

    /** Whether the graph has no nodes. */
    boolean empty() {
        return byId.isEmpty();
    }

    /** Whether the graph is larger than the view at the smallest scale, so it is panned by dragging. */
    boolean pannable() {
        return DiagramFit.pans(contentWidth, viewWidth, scale) || DiagramFit.pans(contentHeight, viewHeight, scale);
    }

    /**
     * Draws the diagram into the GUI rectangle ({@code x}, {@code y}, {@code width}, {@code height}) at game time
     * {@code now}; a hovered node gets an outline and a tooltip. {@code guiScale} snaps the scale to whole window
     * pixels.
     */
    void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height, int guiScale, long now,
            int mouseX, int mouseY) {
        viewX = x;
        viewY = y;
        viewWidth = width;
        viewHeight = height;
        graphics.fill(x, y, x + width, y + height, CANVAS);
        if (width <= 0 || height <= 0) {
            return;
        }
        scale = DiagramFit.scale(contentWidth, contentHeight, width, height, MIN_SCALE, MAX_SCALE, guiScale);
        panX = DiagramFit.clampPan(panX, contentWidth, width, scale);
        panY = DiagramFit.clampPan(panY, contentHeight, height, scale);
        originX = DiagramFit.origin(x, width, minX, contentWidth, scale, panX);
        originY = DiagramFit.origin(y, height, minY, contentHeight, scale, panY);
        refreshLive(font, now);
        int hovered = nodeAt(mouseX, mouseY);

        graphics.enableScissor(x, y, x + width, y + height);
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) originX, (float) originY);
        graphics.pose().scale((float) scale);
        for (int i = 0; i < wires.length; i++) {
            drawWire(graphics, i);
        }
        for (LogicNode<LogicKey> node : nodes) {
            drawNode(graphics, font, node, node.id() == hovered);
        }
        graphics.pose().popMatrix();
        CanvasFade.draw(graphics, x, y, x + width, y + height, CANVAS);
        graphics.disableScissor();

        LogicNode<LogicKey> node = hovered < 0 ? null : byId.get(hovered);
        if (node != null) {
            List<Component> tip = new ArrayList<>(5);
            tip.add(Component.translatable(node.type().nameKey()).withColor(Accent.TEXT));
            String sentence = sentences.get(node.id());
            String cut = cutSummaries.get(node.id());
            if (sentence != null) {
                tip.add(Component.literal(sentence));
            } else if (cut != null) {
                // Without a sentence the summary is the only place for the settings; shown whole when shortened.
                tip.add(Component.literal(cut));
            }
            String live = liveFull.get(node.id());
            if (live != null) {
                tip.add(Component.literal(live).withColor(Accent.CYAN));
            }
            if (editorTips) {
                tip.add(Component.translatable(ControllerTexts.NODES_OPEN_TIP).withColor(Accent.LABEL));
            }
            if (pannable()) {
                tip.add(Component.translatable(ControllerTexts.NODES_DRAG_TIP).withColor(Accent.LABEL));
            }
            graphics.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        } else if (pannable() && Accent.contains(x, y, width, height, mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, Component.translatable(ControllerTexts.NODES_DRAG_TIP), mouseX,
                    mouseY);
        }
    }

    private void drawWire(GuiGraphicsExtractor graphics, int i) {
        Wire wire = wires[i];
        LogicNode<LogicKey> from = byId.get(wire.from());
        LogicNode<LogicKey> to = byId.get(wire.to());
        if (from == null || to == null || wireOut[i] < 0 || wireIn[i] < 0) {
            return;
        }
        int count = RightAngleWire.route(from.x() + widths.getOrDefault(from.id(), NodeLayout.WIDTH) + 3,
                from.y() + NodeLayout.portY(wireOut[i]), to.x() - 3, to.y() + NodeLayout.portY(wireIn[i]), STUB, route);
        PortType type = wireTypes[i];
        RightAngleWire.draw(graphics, route, count, NodeArt.thickness(type), NodeArt.portColour(type));
    }

    private void drawNode(GuiGraphicsExtractor graphics, Font font, LogicNode<LogicKey> node, boolean hovered) {
        int x = node.x();
        int y = node.y();
        int width = widths.getOrDefault(node.id(), NodeLayout.WIDTH);
        int height = NodeArt.height(node);
        if (hovered) {
            graphics.fill(x - 2, y - 2, x + width + 2, y + height + 2, HOVER_EDGE);
        }
        NodeArt.body(graphics, font, x, y, width, height, node.type().category(), titles.getOrDefault(node.id(), ""),
                summaries.get(node.id()), liveLines.get(node.id()),
                liveColours.getOrDefault(node.id(), AccentFrame.TEXT));
        Object2LongOpenHashMap<String> nodeValues = values.get(node.id());
        NodeArt.stepRows(graphics, font, x, y, width, node,
                nodeValues == null ? 0 : nodeValues.getOrDefault(Sequences.STEP, 0L));
        List<Port> inputs = node.type().inputs(node.settings());
        for (int i = 0; i < inputs.size(); i++) {
            NodeArt.port(graphics, inputs.get(i).type(), x, y + NodeLayout.portY(i), false);
        }
        List<Port> outputs = node.type().outputs(node.settings());
        for (int i = 0; i < outputs.size(); i++) {
            NodeArt.port(graphics, outputs.get(i).type(), x + width, y + NodeLayout.portY(i), false);
        }
    }

    /** Whether the GUI point is inside the diagram's view (as last drawn). */
    boolean contains(double x, double y) {
        return Accent.contains(viewX, viewY, viewWidth, viewHeight, x, y);
    }

    /** The node under the GUI point, or -1. */
    int nodeAt(double x, double y) {
        if (!contains(x, y)) {
            return -1;
        }
        double cx = DiagramFit.toCanvas(x, originX, scale);
        double cy = DiagramFit.toCanvas(y, originY, scale);
        int found = -1;
        // The last drawn node is on top.
        for (LogicNode<LogicKey> node : nodes) {
            int width = widths.getOrDefault(node.id(), NodeLayout.WIDTH);
            if (cx >= node.x() && cx < node.x() + width && cy >= node.y() && cy < node.y() + NodeArt.height(node)) {
                found = node.id();
            }
        }
        return found;
    }

    /** Pans by a mouse drag of ({@code dx}, {@code dy}) GUI pixels; the next draw clamps it. */
    void drag(double dx, double dy) {
        panX -= dx;
        panY -= dy;
    }
}
