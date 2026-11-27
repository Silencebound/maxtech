package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.TextFit;
import dev.silencebound.maxtech.storage.logic.CardEdits;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.logic.NodeCategory;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.SettingKind;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The small popups of the controller screen's card view (M7L2 chunk 11): the node type picker of the "+" buttons and
 * the blanks (by category, Common or All as in the editor's palette, which shares the choice), and the input for one
 * setting a click on a card word opens: a number or text box, a label box with the network's labels, or an item search
 * with the held item. A pick goes to {@link Host}, which sends it as a card edit. Drawn over the card, inside the
 * detail's glass; while open it takes every click, key and wheel turn on the card. Client only.
 */
final class CardPopup {
    private static final int PAD = 3;
    private static final int ICON = 18;
    private static final int RESULTS = 12;
    private static final int FILL = 0xF025292D;
    private static final int HOVER = 0x24FFFFFF;

    /** What the popup's picks do; the screen implements it. */
    interface Host {
        /** Puts a text box among the screen's widgets. */
        EditBox addBox(EditBox box);

        void removeBox(EditBox box);

        /** Adds a node of {@code type} with the "+" button {@code add}. */
        void pickType(CardEdits.Add add, NodeType type);

        /** Sets setting {@code setting} of node {@code node}. */
        void setValue(int node, String setting, SettingValue<LogicKey> value);

        /** The item in the player's main hand; empty for none. */
        ItemStack held();
    }

    private enum Kind {
        TYPES, INPUT, LABEL, ITEM
    }

    /** A clickable row or chip drawn this frame. */
    private record Hit(int x, int y, int width, int height, Runnable action, String tip) {
    }

    private final Font font;
    private final Host host;
    private @Nullable Kind kind;
    private int x;
    private int y;
    private int width;
    private int maxHeight;
    private int height;
    private int scroll;
    private String title = "";
    // TYPES
    private CardEdits.Add add = CardEdits.Add.TRIGGER;
    // INPUT, LABEL, ITEM
    private int node;
    private @Nullable SettingSpec spec;
    private @Nullable EditBox box;
    private List<EditorPayloads.LabelInfo> labels = List.of();
    private List<LogicKey> results = List.of();
    private final List<Hit> hits = new ArrayList<>();

    CardPopup(Font font, Host host) {
        this.font = font;
        this.host = host;
    }

    boolean open() {
        return kind != null;
    }

    /** Opens the type picker of {@code add} in the box at ({@code x}, {@code y}), {@code width} wide. */
    void openTypes(CardEdits.Add add, int x, int y, int width, int maxHeight) {
        close();
        this.kind = Kind.TYPES;
        this.add = add;
        this.title = I18n.get(switch (add) {
            case TRIGGER -> ControllerTexts.CARD_TITLE_TRIGGER;
            case CONDITION -> ControllerTexts.CARD_TITLE_CONDITION;
            case ACTION -> ControllerTexts.CARD_TITLE_ACTION;
        });
        place(x, y, width, maxHeight);
    }

    /** Opens the input for setting {@code spec} of {@code node}; for a choice or yes/no nothing opens. */
    void openSetting(LogicNode<LogicKey> node, SettingSpec spec, List<EditorPayloads.LabelInfo> labels, int x, int y,
            int width, int maxHeight) {
        close();
        Kind next = switch (spec.kind()) {
            case NUMBER, TEXT -> Kind.INPUT;
            case LABEL -> Kind.LABEL;
            case ITEM, FLUID, TAG -> Kind.ITEM;
            case CHOICE, FLAG -> null;
        };
        if (next == null) {
            return;
        }
        this.kind = next;
        this.node = node.id();
        this.spec = spec;
        this.labels = labels;
        this.results = List.of();
        this.title = I18n.get(node.type().nameKey()) + ": " + I18n.get(spec.nameKey());
        place(x, y, width, maxHeight);
        EditBox made = new EditBox(font, x + PAD, y + PAD + font.lineHeight + 2, width - 2 * PAD, font.lineHeight + 3,
                Component.translatable(spec.nameKey()));
        made.setTextColor(Accent.TEXT);
        switch (spec.kind()) {
            case NUMBER -> {
                made.setMaxLength(20);
                made.setValue(Long.toString(node.settings().number(spec.name()).orElse(spec.defaultValue())));
                made.setFilter(text -> text.matches("-?[0-9]*"));
            }
            case TEXT, LABEL -> {
                made.setMaxLength(spec.kind() == SettingKind.LABEL ? NetworkRef.MAX_REF : SettingSpec.MAX_TEXT);
                made.setValue(node.settings().text(spec.name()).orElse(""));
                if (spec.kind() == SettingKind.LABEL) {
                    made.setHint(
                            Component.translatable(spec.required() ? EditorTexts.LABEL_HINT : EditorTexts.WHOLE_NETWORK)
                                    .withColor(Accent.DIM));
                }
            }
            default -> {
                SettingKind searched = spec.kind();
                made.setMaxLength(64);
                made.setHint(Component.translatable(switch (searched) {
                    case FLUID -> EditorTexts.SEARCH_FLUID_HINT;
                    case TAG -> EditorTexts.SEARCH_TAG_HINT;
                    default -> EditorTexts.SEARCH_HINT;
                }).withColor(Accent.DIM));
                made.setResponder(text -> results = switch (searched) {
                    case FLUID -> LogicEditorScreen.searchFluids(text);
                    case TAG -> LogicEditorScreen.searchTags(text);
                    default -> LogicEditorScreen.search(text);
                });
            }
        }
        made.setFocused(true);
        box = host.addBox(made);
    }

    private void place(int x, int y, int width, int maxHeight) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.maxHeight = maxHeight;
        this.height = maxHeight;
        this.scroll = 0;
    }

    void close() {
        EditBox shown = box;
        if (shown != null) {
            host.removeBox(shown);
        }
        box = null;
        kind = null;
        spec = null;
        hits.clear();
    }

    /** The box's text box, focused while the popup is open; null for the type picker. */
    @Nullable
    EditBox box() {
        return box;
    }

    // ---- drawing

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Kind shown = kind;
        if (shown == null) {
            return;
        }
        hits.clear();
        int step = font.lineHeight + 2;
        int contentHeight = switch (shown) {
            case TYPES -> step + rows().size() * step;
            case INPUT -> 2 * step + 4 + step;
            case LABEL -> 2 * step + 4 + Math.min(labels.size(), 6) * step + step;
            case ITEM -> 2 * step + 4 + step + (results.isEmpty() ? 0 : ICON * rowsOf(results.size())) + step;
        };
        height = Math.min(maxHeight, contentHeight + 2 * PAD);
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, Accent.ACCENT);
        graphics.fill(x, y, x + width, y + height, FILL);
        int inner = width - 2 * PAD;
        int left = x + PAD;
        int top = y + PAD;
        if (shown == Kind.TYPES) {
            // The title, and Common / All on the right.
            String toggle = I18n
                    .get(LogicEditorPrefs.paletteAll() ? ControllerTexts.CARD_ALL : ControllerTexts.CARD_COMMON);
            int toggleWidth = font.width(toggle);
            TextScale.text(graphics, font, TextFit.fit(title, inner - toggleWidth - PAD, font::width), left, top,
                    Accent.CYAN, false);
            boolean overToggle = Accent.contains(left + inner - toggleWidth, top - 1, toggleWidth, step, mouseX,
                    mouseY);
            TextScale.text(graphics, font, toggle, left + inner - toggleWidth, top,
                    overToggle ? Accent.BRIGHT : Accent.LABEL, false);
            hits.add(new Hit(left + inner - toggleWidth, top - 1, toggleWidth, step,
                    () -> LogicEditorPrefs.setPaletteAll(!LogicEditorPrefs.paletteAll()),
                    I18n.get(LogicEditorPrefs.paletteAll() ? EditorTexts.SHOW_COMMON : EditorTexts.SHOW_ALL)));
            List<@Nullable NodeType> rows = rows();
            List<NodeCategory> headings = headings();
            int visible = Math.max(1, (height - 2 * PAD - step) / step);
            scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - visible));
            for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
                int rowY = top + step + i * step;
                NodeType type = rows.get(scroll + i);
                if (type == null) {
                    NodeCategory category = headings.get(scroll + i);
                    TextScale.text(graphics, font, I18n.get(category.nameKey()).toUpperCase(java.util.Locale.ROOT),
                            left, rowY, Accent.DIM, false);
                    continue;
                }
                boolean over = Accent.contains(left, rowY - 1, inner, step, mouseX, mouseY);
                if (over) {
                    graphics.fill(left - 1, rowY - 1, left + inner + 1, rowY + step - 1, HOVER);
                }
                TextScale.text(graphics, font, TextFit.fit(I18n.get(type.nameKey()), inner - 6, font::width), left + 6,
                        rowY, over ? Accent.BRIGHT : Accent.TEXT, false);
                CardEdits.Add picked = add;
                hits.add(new Hit(left, rowY - 1, inner, step, () -> {
                    close();
                    host.pickType(picked, type);
                }, I18n.get(type.descriptionKey())));
            }
            return;
        }
        TextScale.text(graphics, font, TextFit.fit(title, inner, font::width), left, top, Accent.CYAN, false);
        EditBox shownBox = box;
        int boxY = top + step;
        if (shownBox != null) {
            graphics.fill(left - 1, boxY - 1, left + inner + 1, boxY + font.lineHeight + 4, Accent.DIM);
            graphics.fill(left, boxY, left + inner, boxY + font.lineHeight + 3, Accent.SLOT);
            shownBox.setX(left + 2);
            shownBox.setY(boxY + 2);
            shownBox.setWidth(inner - 4);
        }
        int rowY = boxY + step + 4;
        if (shown == Kind.LABEL) {
            String typed = shownBox == null ? "" : shownBox.getValue().strip().toLowerCase(java.util.Locale.ROOT);
            int listed = 0;
            for (EditorPayloads.LabelInfo info : labels) {
                if (listed == 6 || rowY + step > y + height - PAD - step) {
                    break;
                }
                String text = info.label().text();
                if (!typed.isEmpty() && !info.label().key().contains(typed)) {
                    continue;
                }
                boolean over = Accent.contains(left, rowY - 1, inner, step, mouseX, mouseY);
                if (over) {
                    graphics.fill(left - 1, rowY - 1, left + inner + 1, rowY + step - 1, HOVER);
                }
                DyeColor dye = info.label().colour();
                graphics.fill(left, rowY + 1, left + 5, rowY + 6,
                        dye == null ? Accent.CYAN : dye.getTextureDiffuseColor());
                TextScale.text(graphics, font, TextFit.fit(text, inner - 8, font::width), left + 8, rowY,
                        over ? Accent.BRIGHT : Accent.TEXT, false);
                hits.add(new Hit(left, rowY - 1, inner, step, () -> {
                    EditBox target = box;
                    if (target != null) {
                        target.setValue(text);
                    }
                    apply();
                }, ""));
                rowY += step;
                listed++;
            }
        }
        if (shown == Kind.ITEM) {
            SettingSpec setting = spec;
            if (setting != null && setting.kind() == SettingKind.ITEM) {
                String held = I18n.get(ControllerTexts.CARD_HELD);
                boolean over = Accent.contains(left, rowY - 1, font.width(held), step, mouseX, mouseY);
                TextScale.text(graphics, font, TextFit.fit(held, inner, font::width), left, rowY,
                        over ? Accent.BRIGHT : Accent.CYAN, false);
                hits.add(new Hit(left, rowY - 1, font.width(held), step, () -> {
                    ItemStack stack = host.held();
                    if (!stack.isEmpty()) {
                        pick(LogicKey.of(ItemKey.of(stack.getItem())));
                    }
                }, ""));
            }
            rowY += step;
            int perRow = Math.max(1, inner / ICON);
            for (int i = 0; i < results.size() && i < RESULTS; i++) {
                int ix = left + (i % perRow) * ICON;
                int iy = rowY + (i / perRow) * ICON;
                if (iy + ICON > y + height - PAD) {
                    break;
                }
                LogicKey key = results.get(i);
                boolean over = Accent.contains(ix, iy, ICON, ICON, mouseX, mouseY);
                if (over) {
                    graphics.fill(ix, iy, ix + ICON, iy + ICON, HOVER);
                }
                graphics.item(LogicEditorScreen.icon(key), ix + 1, iy + 1);
                hits.add(new Hit(ix, iy, ICON, ICON, () -> pick(key), LogicEditorScreen.keyName(key)));
            }
            rowY += results.isEmpty() ? 0 : ICON * rowsOf(Math.min(results.size(), RESULTS));
        }
        // OK and Cancel along the bottom.
        int buttonsY = Math.min(rowY, y + height - PAD - step + 1);
        String cancel = I18n.get(ControllerTexts.CARD_CANCEL);
        int cancelX = left + inner - font.width(cancel);
        drawLink(graphics, cancel, cancelX, buttonsY, mouseX, mouseY, this::close);
        if (shown != Kind.ITEM) {
            String ok = I18n.get(ControllerTexts.CARD_OK);
            drawLink(graphics, ok, cancelX - GAP_WORDS - font.width(ok), buttonsY, mouseX, mouseY, this::apply);
        }
    }

    private static final int GAP_WORDS = 8;

    private void drawLink(GuiGraphicsExtractor graphics, String text, int at, int rowY, int mouseX, int mouseY,
            Runnable action) {
        int w = font.width(text);
        boolean over = Accent.contains(at, rowY - 1, w, font.lineHeight + 2, mouseX, mouseY);
        TextScale.text(graphics, font, text, at, rowY, over ? Accent.BRIGHT : Accent.CYAN, false);
        hits.add(new Hit(at, rowY - 1, w, font.lineHeight + 2, action, ""));
    }

    private int rowsOf(int count) {
        int perRow = Math.max(1, (width - 2 * PAD) / ICON);
        return (count + perRow - 1) / perRow;
    }

    /** The picker's rows: null for a category heading, then its types; Common or All as the palette shows. */
    private List<@Nullable NodeType> rows() {
        List<@Nullable NodeType> rows = new ArrayList<>();
        NodeCategory last = null;
        boolean all = LogicEditorPrefs.paletteAll();
        for (NodeType type : CardEdits.choices(add)) {
            if (!all && !type.common()) {
                continue;
            }
            if (type.category() != last) {
                rows.add(null);
                last = type.category();
            }
            rows.add(type);
        }
        return rows;
    }

    /** The category of each row of {@link #rows()}. */
    private List<NodeCategory> headings() {
        List<NodeCategory> out = new ArrayList<>();
        NodeCategory last = null;
        boolean all = LogicEditorPrefs.paletteAll();
        for (NodeType type : CardEdits.choices(add)) {
            if (!all && !type.common()) {
                continue;
            }
            if (type.category() != last) {
                out.add(type.category());
                last = type.category();
            }
            out.add(type.category());
        }
        return out;
    }

    /** The tooltip of what is under the mouse; empty for none. */
    String tooltip(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (Accent.contains(hit.x(), hit.y(), hit.width(), hit.height(), mouseX, mouseY)) {
                return hit.tip();
            }
        }
        return "";
    }

    // ---- input

    /** A click while open: on a row or link it acts, outside the popup it closes it. Always taken. */
    boolean click(double mouseX, double mouseY) {
        if (kind == null) {
            return false;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (Accent.contains(hit.x(), hit.y(), hit.width(), hit.height(), mouseX, mouseY)) {
                hit.action().run();
                return true;
            }
        }
        if (!Accent.contains(x, y, width, height, mouseX, mouseY)) {
            close();
        }
        return true;
    }

    /** Enter applies the typed value, Escape closes; other keys go to the text box. Taken while open. */
    boolean key(KeyEvent event) {
        if (kind == null) {
            return false;
        }
        if (event.isEscape()) {
            close();
            return true;
        }
        if (event.isConfirmation()) {
            apply();
            return true;
        }
        EditBox shown = box;
        if (shown != null) {
            shown.keyPressed(event);
        }
        return true;
    }

    boolean scroll(double mouseX, double mouseY, int step) {
        if (kind == null) {
            return false;
        }
        scroll = Math.max(0, scroll + step);
        return true;
    }

    /** Sends the typed number, text or label. */
    private void apply() {
        SettingSpec setting = spec;
        EditBox shown = box;
        if (setting == null || shown == null) {
            close();
            return;
        }
        String text = shown.getValue();
        int target = node;
        String name = setting.name();
        if (setting.kind() == SettingKind.NUMBER) {
            long value;
            try {
                value = Long.parseLong(text.strip());
            } catch (NumberFormatException e) {
                // Empty or a lone minus: nothing to set.
                return;
            }
            close();
            host.setValue(target, name, new SettingValue.Num<>(setting.clamp(value)));
            return;
        }
        if (setting.kind() == SettingKind.TEXT || setting.kind() == SettingKind.LABEL) {
            close();
            host.setValue(target, name, new SettingValue.Text<>(text.strip()));
        }
    }

    private void pick(LogicKey key) {
        SettingSpec setting = spec;
        int target = node;
        close();
        if (setting != null) {
            host.setValue(target, setting.name(), new SettingValue.Item<>(key));
        }
    }
}
