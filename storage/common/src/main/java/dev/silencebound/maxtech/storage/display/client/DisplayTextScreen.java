package dev.silencebound.maxtech.storage.display.client;

import dev.silencebound.maxtech.lib.client.gui.accent.Icon;
import dev.silencebound.maxtech.lib.client.gui.accent.IconButton;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplayText;
import dev.silencebound.maxtech.storage.display.DisplayTextSave;
import dev.silencebound.maxtech.storage.display.DisplayView;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import org.jspecify.annotations.Nullable;

/**
 * The text editor of a screen showing {@link dev.silencebound.maxtech.storage.display.DisplaySourceKind#TEXT}, opened
 * from the settings GUI over the still open display menu: a text box, buttons that insert markup at the cursor (the 16
 * colours, bold, italic, close, sizes, alignment, an icon of the held item or from a search, and live values), a
 * preview laid out exactly as the wall shows it, and the reason a save would be refused. Saving sends
 * {@link DisplayTextSave}; the server checks it again. Escape goes back to the settings. Client only.
 */
public final class DisplayTextScreen extends Screen {
    public static final int WIDTH = 248;
    /** The panel and its title tab fit a 240 high window. */
    public static final int HEIGHT = 228;
    private static final int PAD = 8;
    private static final int INNER = WIDTH - 2 * PAD;
    private static final int ROW_COLOURS = 8;
    private static final int ROW_STYLE = 22;
    private static final int ROW_ICON = 38;
    private static final int ROW_RESULTS = 54;
    private static final int ROW_VALUES = 74;
    private static final int ROW_BOX = 92;
    private static final int BOX_HEIGHT = 62;
    private static final int ROW_PREVIEW = 160;
    private static final int PREVIEW_HEIGHT = 36;
    private static final int ROW_STATUS = 200;
    private static final int ROW_BUTTONS = 210;
    private static final int RESULTS = 13;
    private static final int PREVIEW_BACKGROUND = 0xFF0A1418;
    /** A colour swatch's edge, and the height of the labelled buttons: a line high. */
    private static final int SWATCH = 11;
    private static final int BUTTON_HEIGHT = 14;

    private final DisplayMenu menu;
    private final Screen parent;
    private String text;
    private int left;
    private int top;
    private @Nullable MultiLineEditBox box;
    /** What each search result button shows; empty hides the button. */
    private final ItemStack[] shown = new ItemStack[RESULTS];
    private final List<IconButton> results = new ArrayList<>();
    private ScreenPainter.@Nullable Fitted preview;
    private Optional<Component> problem = Optional.empty();
    /** The saved text's placeholders, to show their live values in the preview while they are unchanged. */
    private List<RichText.Value> savedValues = List.of();

    public DisplayTextScreen(DisplayMenu menu, Screen parent) {
        super(Component.translatable(DisplayContent.EDITOR_TITLE_KEY));
        this.menu = menu;
        this.parent = parent;
        this.text = "";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = AccentFrame.top(height, HEIGHT);
        results.clear();
        if (minecraft != null && minecraft.level != null && box == null) {
            DisplaySettings settings = menu.clientSettings(minecraft.level);
            text = settings.text();
            savedValues = DisplayText.parse(text).values();
        }
        // Colour swatches and the closing tag.
        for (int i = 0; i < RichText.COLOUR_NAMES.size(); i++) {
            String name = RichText.COLOUR_NAMES.get(i);
            addRenderableWidget(IconButton
                    .of(left + PAD + i * 12, top + ROW_COLOURS,
                            Icon.swatch(0xFF000000 | RichText.colour(name), SWATCH - 2, SWATCH - 2),
                            Component.translatable(DisplayContent.colourKey(name)), () -> insert("<" + name + ">"))
                    .size(SWATCH));
        }
        add(PAD + 16 * 12 + 2, ROW_COLOURS, INNER - 16 * 12 - 2, () -> Component.literal("</>"), () -> insert("</>"),
                DisplayContent.CLOSE_TAG_KEY);
        // Style, size and alignment.
        String[] tags = {"<b>", "<i>", "<small>", "<normal>", "<big>", "<left>", "<center>", "<right>"};
        String[] keys = {DisplayContent.BOLD_KEY, DisplayContent.ITALIC_KEY, DisplayContent.SMALL_KEY,
                DisplayContent.NORMAL_KEY, DisplayContent.BIG_KEY, DisplayContent.LEFT_KEY, DisplayContent.CENTRE_KEY,
                DisplayContent.RIGHT_KEY};
        int styleWidth = (INNER - (tags.length - 1) * 2) / tags.length;
        for (int i = 0; i < tags.length; i++) {
            String tag = tags[i];
            String key = keys[i];
            add(PAD + i * (styleWidth + 2), ROW_STYLE, styleWidth, () -> label(key, tag), () -> insert(tag), key);
        }
        // Icons: the held item, or one from the search.
        add(PAD, ROW_ICON, 70, () -> Component.translatable(DisplayContent.HELD_ICON_KEY), this::insertHeld,
                DisplayContent.HELD_ICON_TIP_KEY);
        EditBox searchBox = new EditBox(font, left + PAD + 74, top + ROW_ICON + 1, INNER - 74, 14, Component.empty());
        searchBox.setHint(Component.translatable(DisplayContent.SEARCH_HINT_KEY));
        searchBox.setMaxLength(48);
        searchBox.setTextColor(AccentFrame.TEXT);
        searchBox.setResponder(this::search);
        addRenderableWidget(searchBox);
        for (int i = 0; i < RESULTS; i++) {
            int index = i;
            shown[i] = ItemStack.EMPTY;
            Icon icon = new Icon() {
                @Override
                public int width() {
                    return 16;
                }

                @Override
                public int height() {
                    return 16;
                }

                @Override
                public void draw(GuiGraphicsExtractor graphics, int x, int y, int colour) {
                    graphics.item(shown[index], x, y);
                }
            };
            IconButton result = IconButton.of(left + PAD + i * 18, top + ROW_RESULTS, icon,
                    () -> shown[index].getHoverName(), () -> insertResult(index));
            result.visible = false;
            results.add(addRenderableWidget(result));
        }
        // Live values.
        int valueWidth = (INNER - 4) / 3;
        add(PAD, ROW_VALUES, valueWidth, () -> Component.translatable(DisplayContent.ITEM_COUNT_KEY),
                () -> insert("{count:" + countedItem() + "}"), DisplayContent.ITEM_COUNT_TIP_KEY);
        add(PAD + valueWidth + 2, ROW_VALUES, valueWidth, () -> Component.translatable(DisplayContent.TAG_COUNT_KEY),
                () -> insert("{count:#" + countedTag() + "}"), DisplayContent.TAG_COUNT_TIP_KEY);
        add(PAD + 2 * (valueWidth + 2), ROW_VALUES, valueWidth, () -> Component.translatable(DisplayContent.SENSOR_KEY),
                () -> insert("{sensor:label:redstone}"), DisplayContent.SENSOR_TIP_KEY);
        // The text itself.
        MultiLineEditBox editor = MultiLineEditBox.builder().setX(left + PAD).setY(top + ROW_BOX)
                .setTextColor(AccentFrame.TEXT).setCursorColor(AccentFrame.TEXT)
                .build(font, INNER, BOX_HEIGHT, Component.translatable(DisplayContent.EDITOR_TITLE_KEY));
        editor.setCharacterLimit(RichText.MAX_LENGTH);
        editor.setLineLimit(RichText.MAX_LINES);
        editor.setValue(text, true);
        editor.setValueListener(this::changed);
        editor.setTooltip(Tooltip.create(Component.translatable(DisplayContent.MARKUP_TIP_KEY)));
        box = addRenderableWidget(editor);
        setInitialFocus(editor);
        // Save and cancel.
        add(WIDTH - PAD - 124, ROW_BUTTONS, 60, () -> Component.translatable(DisplayContent.SAVE_KEY), this::save, null)
                .enabled(() -> problem.isEmpty());
        add(WIDTH - PAD - 60, ROW_BUTTONS, 60, () -> Component.translatable(DisplayContent.CANCEL_KEY), this::back,
                null);
        changed(text);
        search(searchBox.getValue());
    }

    private static Component label(String key, String tag) {
        // Bold and italic show as letters in their style; the rest by name.
        return switch (tag) {
            case "<b>" -> Component.literal("B").withStyle(style -> style.withBold(true));
            case "<i>" -> Component.literal("I").withStyle(style -> style.withItalic(true));
            default -> Component.translatable(key);
        };
    }

    private AccentButton add(int x, int y, int w, Supplier<Component> label, Runnable action,
            @Nullable String tooltip) {
        AccentButton button = new AccentButton(left + x, top + y, w, BUTTON_HEIGHT, label.get(), action).label(label);
        if (tooltip != null) {
            button.setTooltip(Tooltip.create(Component.translatable(tooltip)));
        }
        return addRenderableWidget(button);
    }

    /** Types {@code markup} at the cursor of the text box (through its own input path) and gives it the focus back. */
    private void insert(String markup) {
        MultiLineEditBox editor = box;
        if (editor == null) {
            return;
        }
        setFocused(editor);
        editor.setFocused(true);
        markup.codePoints().forEach(codepoint -> editor.charTyped(new CharacterEvent(codepoint)));
    }

    private void insertResult(int index) {
        ItemStack stack = shown[index];
        if (!stack.isEmpty()) {
            insert("[item:" + BuiltInRegistries.ITEM.getKey(stack.getItem()) + "]");
        }
    }

    /** Shows {@code stack} on result button {@code index}; an empty stack hides it. */
    private void show(int index, ItemStack stack) {
        shown[index] = stack;
        IconButton button = results.get(index);
        button.visible = !stack.isEmpty();
        button.refreshTooltip();
    }

    private void insertHeld() {
        if (minecraft != null && minecraft.player != null) {
            ItemStack held = minecraft.player.getMainHandItem();
            if (!held.isEmpty()) {
                insert("[item:" + BuiltInRegistries.ITEM.getKey(held.getItem()) + "]");
            }
        }
    }

    /** The item in the settings slot, or stone for the player to change. */
    private String countedItem() {
        ItemStack item = menu.countedItem();
        return BuiltInRegistries.ITEM.getKey(item.isEmpty() ? Items.STONE : item.getItem()).toString();
    }

    /** The tag picked in the settings, or the logs tag for the player to change. */
    private String countedTag() {
        ItemStack item = menu.countedItem();
        List<TagKey<Item>> tags = item.isEmpty() ? List.of() : ConditionMenu.tags(item.getItem());
        int index = menu.tagIndex();
        return index >= 0 && index < tags.size() ? tags.get(index).location().toString() : "minecraft:logs";
    }

    /** Fills the result row with the first items whose id or name contains {@code query}. */
    private void search(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int found = 0;
        if (!needle.isEmpty()) {
            for (Item item : BuiltInRegistries.ITEM) {
                if (found == RESULTS) {
                    break;
                }
                if (new ItemStack(item).isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                ItemStack stack = new ItemStack(item);
                if (id.contains(needle) || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)) {
                    show(found++, stack);
                }
            }
        }
        for (int i = found; i < results.size(); i++) {
            show(i, ItemStack.EMPTY);
        }
    }

    /** The text changed: check it as the server will and lay the preview out as the wall will. */
    private void changed(String value) {
        text = value;
        problem = DisplayText.problem(value);
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        Vec2 size = menu.clientSize(minecraft.level);
        DisplayView view = menu.clientView(minecraft.level);
        RichText parsed = DisplayText.parse(value);
        List<RichText.Value> values = parsed.values();
        preview = ScreenPainter.fit(parsed, n -> previewValue(values.get(n), view), font, size.x, size.y,
                menu.isPart() ? ScreenPainter.PART_BEZEL : ScreenPainter.PANEL_BEZEL);
    }

    /** A placeholder's live value while the saved text has the same one; "?" for a new one until it is saved. */
    private String previewValue(RichText.Value value, @Nullable DisplayView view) {
        LongList live = view == null ? LongLists.EMPTY_LIST : view.values();
        for (int i = 0; i < savedValues.size() && i < live.size(); i++) {
            RichText.Value saved = savedValues.get(i);
            if (saved.name().equals(value.name()) && saved.argument().equals(value.argument())) {
                return AmountFormat.compact(live.getLong(i));
            }
        }
        return "?";
    }

    private void save() {
        if (problem.isPresent()) {
            return;
        }
        Platform.get().network().sendToServer(new DisplayTextSave(menu.containerId, text));
        back();
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft != null && (minecraft.player == null || minecraft.player.containerMenu != menu)) {
            minecraft.setScreen(null);
        }
    }

    /** Escape goes back to the settings instead of closing the menu. */
    @Override
    public void onClose() {
        back();
    }

    private void back() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, WIDTH, HEIGHT, title, mouseX, mouseY);
        graphics.fill(left + PAD - 1, top + ROW_RESULTS - 1, left + PAD + RESULTS * 18 - 1, top + ROW_RESULTS + 17,
                AccentFrame.SLOT);
        graphics.fill(left + PAD, top + ROW_PREVIEW, left + PAD + INNER, top + ROW_PREVIEW + PREVIEW_HEIGHT,
                PREVIEW_BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        drawPreview(graphics);
        Component status = problem
                .orElseGet(() -> Component.translatable(DisplayContent.LENGTH_KEY, text.length(), RichText.MAX_LENGTH));
        AccentFrame.text(graphics, font, status, left + PAD, top + ROW_STATUS, INNER,
                problem.isPresent() ? 0xFFE0A040 : AccentFrame.LABEL, mouseX, mouseY);

    }

    /** The preview: the wall's layout scaled into the preview box, in the screen's accent colour. */
    private void drawPreview(GuiGraphicsExtractor graphics) {
        ScreenPainter.Fitted fitted = preview;
        if (fitted == null || minecraft == null || minecraft.level == null) {
            return;
        }
        int accent = menu.clientSettings(minecraft.level).accentColour();
        float scale = Math.min((INNER - 4) / Math.max(1, fitted.areaWidth()),
                (PREVIEW_HEIGHT - 4) / Math.max(1, fitted.areaHeight()));
        float originX = left + PAD + (INNER - fitted.areaWidth() * scale) / 2;
        float originY = top + ROW_PREVIEW + (PREVIEW_HEIGHT - fitted.areaHeight() * scale) / 2;
        RichLayout layout = fitted.layout();
        graphics.enableScissor(left + PAD, top + ROW_PREVIEW, left + PAD + INNER, top + ROW_PREVIEW + PREVIEW_HEIGHT);
        for (int r = 0; r < fitted.shownRows(); r++) {
            RichLayout.Row row = layout.rows().get(r);
            float rowScale = scale * row.scale();
            graphics.pose().pushMatrix();
            graphics.pose().translate(originX + layout.left(row) * scale, originY + row.top() * scale);
            graphics.pose().scale(rowScale, rowScale);
            for (RichLayout.Piece piece : row.pieces()) {
                FormattedCharSequence sequence = piece.text;
                if (sequence != null) {
                    TextScale.text(graphics, font, sequence, piece.x, 0, accent, false);
                } else if (!piece.icon.isEmpty()) {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(piece.x, 0);
                    graphics.pose().scale(RichLayout.ICON / 16F, RichLayout.ICON / 16F);
                    graphics.item(piece.icon, 0, 0);
                    graphics.pose().popMatrix();
                }
            }
            graphics.pose().popMatrix();
        }
        graphics.disableScissor();
    }
}
