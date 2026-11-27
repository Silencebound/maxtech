package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.logic.ForEach;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeCategory;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.PortType;
import dev.silencebound.maxtech.storage.logic.Sequences;
import dev.silencebound.maxtech.storage.logic.SettingKind;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import dev.silencebound.maxtech.storage.logic.editor.NodeLayout;
import dev.silencebound.maxtech.storage.logic.editor.SettingRules;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * How a logic node and its wires look, shared by the node editor ({@link LogicEditorScreen}) and the controller's
 * read-only NODES diagram ({@link NodeDiagram}): the node's box with its category-coloured title bar, summary and live
 * lines, the typed ports and the wire colours. Positions are in editor units relative to the node ({@link NodeLayout});
 * a caller that draws smaller pushes a scale on {@code pose()} first. Client only.
 */
final class NodeArt {
    static final int NODE_BODY = AccentFrame.GRAPHITE;
    static final int TITLE_TEXT = 0xFFFFFFFF;
    /** A number a live line is sized for; larger values end with an ellipsis and show whole in the tooltip. */
    static final long LIVE_SAMPLE = 99_999;

    /**
     * A node's live line in words: {@code text} is not yet fitted to the node; {@code tips} explain a crossing's
     * position; {@code countdown} is set while a delay counts down, so the line is made again each tick.
     */
    record Live(String text, int colour, List<Component> tips, boolean countdown) {
    }

    private NodeArt() {
    }

    /**
     * Draws a node's box at ({@code x}, {@code y}): outline, body, title bar in its category's colour, the fitted
     * title, the summary line and the live line when they are not null. Ports and badges are drawn by the caller.
     */
    static void body(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            NodeCategory category, String title, @Nullable String summary, @Nullable String live, int liveColour) {
        graphics.fill(x, y, x + width, y + height, AccentFrame.OUTLINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, NODE_BODY);
        graphics.fill(x + 1, y + 1, x + width - 1, y + NodeLayout.HEADER, categoryColour(category));
        TextScale.text(graphics, font, title, x + 3, y + 2, TITLE_TEXT, false);
        if (summary != null) {
            TextScale.text(graphics, font, summary, x + 4, y + NodeLayout.SUMMARY_TOP, AccentFrame.LABEL, false);
        }
        if (live != null) {
            TextScale.text(graphics, font, live, x + 4, y + NodeLayout.LIVE_TOP, liveColour, false);
        }
    }

    /** A port centred on ({@code cx}, {@code cy}) in its type's colour and shape (the concept page's legend). */
    static void port(GuiGraphicsExtractor graphics, PortType type, int cx, int cy, boolean bad) {
        int colour = bad ? StatusPalette.bad() : portColour(type);
        switch (type) {
            case EVENT -> {
                // A triangle pointing right, six tall.
                for (int row = 0; row < 6; row++) {
                    int width = Math.min(row, 5 - row) + 1;
                    graphics.fill(cx - 2, cy - 3 + row, cx - 2 + width * 2, cy - 2 + row, colour);
                }
            }
            case NUMBER -> {
                graphics.fill(cx - 1, cy - 2, cx + 2, cy + 3, colour);
                graphics.fill(cx - 2, cy - 1, cx + 3, cy + 2, colour);
            }
            case YES_NO -> {
                graphics.fill(cx, cy - 2, cx + 1, cy + 3, colour);
                graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, colour);
                graphics.fill(cx - 2, cy, cx + 3, cy + 1, colour);
            }
            case ITEM -> {
                graphics.fill(cx - 3, cy - 3, cx + 4, cy + 4, AccentFrame.OUTLINE);
                graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, colour);
            }
            case LABEL -> {
                graphics.fill(cx, cy - 2, cx + 1, cy - 1, colour);
                graphics.fill(cx - 1, cy - 1, cx + 2, cy + 1, colour);
                graphics.fill(cx - 2, cy + 1, cx + 3, cy + 3, colour);
            }
        }
    }

    static int portColour(PortType type) {
        return switch (type) {
            case EVENT -> 0xFFE0892A;
            case NUMBER -> 0xFF4F86D8;
            case YES_NO -> 0xFF3BA33B;
            case ITEM -> 0xFF9A5AD0;
            case LABEL -> 0xFF2FA3A3;
        };
    }

    /** Event wires are thicker than value wires, as on the concept page. */
    static int thickness(PortType type) {
        return type == PortType.EVENT ? 2 : 1;
    }

    static int categoryColour(NodeCategory category) {
        return switch (category) {
            case EVENTS -> 0xFFB06A20;
            case READ -> 0xFF2F62A8;
            case CHECK -> 0xFF2E7D32;
            case REMEMBER -> 0xFF6A4A9A;
            case ACT -> 0xFFA83A30;
            case TIME -> 0xFF2A7F86;
        };
    }

    /** A node's height from its port rows and whether it has a live line. */
    static int height(LogicNode<?> node) {
        return NodeLayout.height(node.type().inputs(node.settings()).size(),
                node.type().outputs(node.settings()).size(), LiveLine.has(node.type()));
    }

    /** The highlight behind a step sequence's current step. */
    static final int STEP_NOW = 0xFF2E5A66;

    /** Step {@code k}'s name in a sequence with {@code settings}: the player's, else "Step k". */
    static String stepName(NodeSettings<?> settings, int k) {
        return Sequences.shownName(settings, k, number -> I18n.get(LogicLang.STEP_DEFAULT_NAME, number));
    }

    /** A step row's text: "2 wait". */
    private static String stepRow(NodeSettings<?> settings, int k) {
        return k + " " + stepName(settings, k);
    }

    /** How wide a step sequence's step rows are; 0 for other nodes. */
    static int stepRowsWidth(Font font, LogicNode<?> node) {
        if (node.type() != NodeTypes.SEQUENCE) {
            return 0;
        }
        int widest = 0;
        for (int k = 1; k <= Sequences.steps(node.settings()); k++) {
            widest = Math.max(widest, font.width(stepRow(node.settings(), k)));
        }
        return widest;
    }

    /**
     * A step sequence's steps as rows under its live line, the step it is at ({@code current}, 0 when unknown) on a
     * highlight; nothing for other nodes. The rows fit in the body, which is two port rows high per step.
     */
    static void stepRows(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, LogicNode<?> node,
            long current) {
        if (node.type() != NodeTypes.SEQUENCE) {
            return;
        }
        for (int k = 1; k <= Sequences.steps(node.settings()); k++) {
            int rowY = y + NodeLayout.LIVE_TOP + k * NodeLayout.PORT_PITCH;
            boolean now = k == current;
            if (now) {
                graphics.fill(x + 3, rowY - 1, x + width - 3, rowY + 9, STEP_NOW);
            }
            TextScale.text(graphics, font, NodeLayout.fitLine(stepRow(node.settings(), k), width, font::width), x + 4,
                    rowY, now ? TITLE_TEXT : AccentFrame.LABEL, false);
        }
    }

    /** A short line under a node's title: its item, label, mode and limit, or other main setting. */
    static String summary(LogicNode<LogicKey> node) {
        NodeType type = node.type();
        NodeSettings<LogicKey> settings = node.settings();
        if (type == NodeTypes.SEQUENCE) {
            return I18n.get(LogicLang.STEP_COUNT, Sequences.steps(settings));
        }
        if (type == NodeTypes.FOR_EACH) {
            // What it goes through: the tag, how many items the list holds, or the stock keeper's label.
            return switch (ForEach.source(settings)) {
                case ForEach.SOURCE_TAG -> settings.item("tag").map(LogicEditorScreen::keyName).orElse("");
                case ForEach.SOURCE_LIST -> {
                    int count = 0;
                    for (int k = 1; k <= ForEach.listSize(settings); k++) {
                        count += settings.item(ForEach.item(k)).isPresent() ? 1 : 0;
                    }
                    yield I18n.get(LogicLang.EACH_LIST_COUNT, count);
                }
                default -> settings.text("label").filter(text -> !text.isBlank()).map(text -> "@" + text).orElse("");
            };
        }
        StringBuilder out = new StringBuilder();
        Optional<LogicKey> item = settings.item("item");
        if (item.isPresent()
                && type.setting("item").filter(spec -> SettingRules.shows(type, settings, spec)).isPresent()) {
            out.append(LogicEditorScreen.keyName(item.get()));
        }
        Optional<SettingSpec> mode = type.setting("mode");
        if (mode.isPresent()) {
            String choice = SettingRules.choice(type, settings, "mode");
            append(out, I18n.get(SettingSpec.choiceKey(choice)).toLowerCase(Locale.ROOT));
            long limit = settings.number("threshold")
                    .orElse(type.setting("threshold").map(SettingSpec::defaultValue).orElse(0L));
            append(out, InfoFormat.number(limit));
        }
        for (SettingSpec spec : type.settings()) {
            if (out.length() > 0) {
                break;
            }
            if (!SettingRules.shows(type, settings, spec)) {
                continue;
            }
            switch (spec.kind()) {
                case LABEL, TEXT -> settings.text(spec.name()).filter(text -> !text.isBlank())
                        .ifPresent(text -> out.append(spec.kind() == SettingKind.LABEL ? "@" + text : text));
                case NUMBER -> settings.number(spec.name()).ifPresent(value -> out.append(InfoFormat.number(value)));
                case FLAG -> settings.flag(spec.name())
                        .ifPresent(value -> out.append(I18n.get(value ? EditorTexts.VALUE_YES : EditorTexts.VALUE_NO)));
                case CHOICE ->
                    out.append(I18n.get(SettingSpec.choiceKey(SettingRules.choice(type, settings, spec.name()))));
                case ITEM, FLUID, TAG ->
                    settings.item(spec.name()).ifPresent(key -> out.append(LogicEditorScreen.keyName(key)));
            }
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String text) {
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(text);
    }

    /** How wide {@code node}'s live line is with a typical value, so the node is sized for it before values arrive. */
    static int liveSampleWidth(Font font, LogicNode<LogicKey> node) {
        Optional<LiveLine.Spec> spec = LiveLine.of(node.type());
        if (spec.isEmpty()) {
            return 0;
        }
        if (node.type() == NodeTypes.SEQUENCE) {
            int widest = 0;
            for (int k = 1; k <= Sequences.steps(node.settings()); k++) {
                widest = Math.max(widest, font.width(I18n.get(spec.get().key(), stepName(node.settings(), k))));
            }
            return Math.max(widest, stepRowsWidth(font, node));
        }
        return switch (spec.get().format()) {
            case CROSSING -> font.width(LiveLine.crossing(LiveLine.below(node.type(), node.settings()), LIVE_SAMPLE,
                    LiveLine.threshold(node.type(), node.settings())));
            case DUE -> Math.max(font.width(I18n.get(LogicLang.LIVE_IDLE)),
                    font.width(I18n.get(spec.get().key(), LiveLine.secondsLeft(NodeTypes.MAX_DELAY_TICKS))));
            case NUMBER -> font.width(I18n.get(spec.get().key(), InfoFormat.number(LIVE_SAMPLE)));
            case YES_NO -> font.width(I18n.get(spec.get().key(), I18n.get(EditorTexts.VALUE_YES)));
        };
    }

    /**
     * {@code node}'s live line for {@code value} of its watched entry ({@code spec}) at game time {@code now}: the
     * watched count against its limit, a state, or the time a delay has left.
     */
    static Live live(LogicNode<LogicKey> node, LiveLine.Spec spec, long value, long now) {
        int colour = AccentFrame.TEXT;
        boolean countdown = false;
        List<Component> tips = new ArrayList<>(3);
        if (node.type() == NodeTypes.SEQUENCE) {
            // The step's name; before its first run the sequence is at no step.
            int steps = Sequences.steps(node.settings());
            String at = value >= 1 && value <= steps ? stepName(node.settings(), (int) value) : "-";
            return new Live(I18n.get(spec.key(), at), colour, tips, false);
        }
        String text = switch (spec.format()) {
            case CROSSING -> {
                boolean below = LiveLine.below(node.type(), node.settings());
                long threshold = LiveLine.threshold(node.type(), node.settings());
                long back = LiveLine.switchBack(node.type(), node.settings());
                LiveLine.Position position = LiveLine.position(below, value, threshold, back);
                colour = switch (position) {
                    case PAST -> StatusPalette.good();
                    case BETWEEN -> StatusPalette.warn();
                    case SHORT -> AccentFrame.LABEL;
                };
                tips.add(Component.translatable(LogicLang.LIVE_NOW, InfoFormat.number(value),
                        (below ? "<" : ">") + InfoFormat.number(threshold)));
                MutableComponent where = switch (position) {
                    case PAST -> Component.translatable(LogicLang.LIVE_PAST);
                    case BETWEEN -> Component.translatable(LogicLang.LIVE_BETWEEN, InfoFormat.number(back));
                    case SHORT -> Component.translatable(LogicLang.LIVE_SHORT);
                };
                tips.add(where.withColor(colour));
                yield LiveLine.crossing(below, value, threshold);
            }
            case DUE -> {
                countdown = value != 0;
                yield value == 0
                        ? I18n.get(LogicLang.LIVE_IDLE)
                        : I18n.get(spec.key(), LiveLine.secondsLeft(LiveLine.ticksLeft(value, now)));
            }
            case NUMBER -> I18n.get(spec.key(), InfoFormat.number(value));
            case YES_NO -> I18n.get(spec.key(), I18n.get(value != 0 ? EditorTexts.VALUE_YES : EditorTexts.VALUE_NO));
        };
        return new Live(text, colour, tips, countdown);
    }
}
