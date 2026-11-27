package dev.silencebound.maxtech.storage.devicetab.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A side tab's flyout made of rows (B09 item 21b): a setting is a caption over a full-width {@link AccentButton} that
 * steps the setting (left click forward, right click back), a status is a line of text. Rows can hide themselves; the
 * flyout's height follows the rows shown when it opens, and {@link #signature()} tells the screen when that set changed
 * so it can lay the flyout out again. Client only.
 */
public final class SettingsFlyout implements SideTabs.Flyout {
    /** The content's width: buttons span it. */
    public static final int WIDTH = 100;
    private static final int GAP = 4;
    private static final int CAPTION_GAP = 1;

    private final List<Row> rows = new ArrayList<>();

    /** One row. */
    private record Row(@Nullable Component caption, @Nullable Supplier<Component> value, @Nullable Runnable forward,
            @Nullable Runnable back, BooleanSupplier enabled, @Nullable IntSupplier tooltipKey,
            @Nullable IntFunction<Component> tooltip, @Nullable Supplier<@Nullable Component> line,
            @Nullable IntSupplier colour, BooleanSupplier shown) {
        boolean button() {
            return value != null;
        }
    }

    /**
     * Adds a setting: {@code caption} over a button showing {@code value}; a left click runs {@code forward}, a right
     * click {@code back} (none: right clicks do nothing). The tooltip is {@code tooltip} of {@code tooltipKey}, built
     * again when the key changes.
     */
    public SettingsFlyout setting(Component caption, Supplier<Component> value, Runnable forward,
            @Nullable Runnable back, IntSupplier tooltipKey, IntFunction<Component> tooltip) {
        return setting(caption, value, forward, back, tooltipKey, tooltip, () -> true, () -> true);
    }

    /** {@link #setting} that greys out while {@code enabled} is false and hides while {@code shown} is false. */
    public SettingsFlyout setting(Component caption, Supplier<Component> value, Runnable forward,
            @Nullable Runnable back, IntSupplier tooltipKey, IntFunction<Component> tooltip, BooleanSupplier enabled,
            BooleanSupplier shown) {
        rows.add(new Row(caption, value, forward, back, enabled, tooltipKey, tooltip, null, null, shown));
        return this;
    }

    /** Adds a line of text in {@code colour}; hidden while {@code line} gives null or {@code shown} is false. */
    public SettingsFlyout line(Supplier<@Nullable Component> line, IntSupplier colour, BooleanSupplier shown) {
        rows.add(new Row(null, null, null, null, () -> true, null, null, line, colour, shown));
        return this;
    }

    /** Whether any row shows now; a flyout without rows has no tab. */
    public boolean anyShown() {
        return signature() != 0;
    }

    /** One bit per row that shows now (rows past the 31st share the last bit). */
    public int signature() {
        int signature = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (shows(rows.get(i))) {
                signature |= 1 << Math.min(i, 30);
            }
        }
        return signature;
    }

    private static boolean shows(Row row) {
        if (!row.shown.getAsBoolean()) {
            return false;
        }
        Supplier<@Nullable Component> line = row.line;
        return line == null || line.get() != null;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        Font font = Minecraft.getInstance().font;
        int height = 0;
        for (Row row : rows) {
            if (shows(row)) {
                height += rowHeight(font, row) + GAP;
            }
        }
        return Math.max(0, height - GAP);
    }

    private static int rowHeight(Font font, Row row) {
        int line = TextScale.lineHeight(font);
        return row.button() ? line + CAPTION_GAP + AccentButton.HEIGHT : line;
    }

    @Override
    public void init(SideTabs.Rect content, Consumer<AbstractWidget> add) {
        Font font = Minecraft.getInstance().font;
        int y = content.y();
        for (Row row : rows) {
            if (!shows(row)) {
                continue;
            }
            Supplier<Component> value = row.value;
            Runnable forward = row.forward;
            if (value != null && forward != null) {
                int buttonY = y + TextScale.lineHeight(font) + CAPTION_GAP;
                AccentButton button = new AccentButton(content.x(), buttonY, content.width(), AccentButton.HEIGHT,
                        value.get(), row.enabled, forward).label(value);
                Runnable back = row.back;
                if (back != null) {
                    button.onRightClick(back);
                }
                IntSupplier key = row.tooltipKey;
                IntFunction<Component> tooltip = row.tooltip;
                if (key != null && tooltip != null) {
                    button.tooltip(key, tooltip);
                }
                add.accept(button);
            }
            y += rowHeight(font, row) + GAP;
        }
    }

    @Override
    public void extract(GuiGraphicsExtractor graphics, Font font, SideTabs.Rect content, int mouseX, int mouseY) {
        int y = content.y();
        for (Row row : rows) {
            if (!shows(row)) {
                continue;
            }
            Component caption = row.caption;
            Supplier<@Nullable Component> line = row.line;
            if (caption != null) {
                Accent.text(graphics, font, caption, content.x(), y, content.width(), Accent.LABEL, mouseX, mouseY);
            } else if (line != null) {
                Component text = line.get();
                IntSupplier colour = row.colour;
                if (text != null) {
                    Accent.text(graphics, font, text, content.x(), y, content.width(),
                            colour == null ? Accent.TEXT : colour.getAsInt(), mouseX, mouseY);
                }
            }
            y += rowHeight(font, row) + GAP;
        }
    }
}
