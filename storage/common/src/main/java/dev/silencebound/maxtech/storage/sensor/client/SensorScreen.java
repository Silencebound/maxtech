package dev.silencebound.maxtech.storage.sensor.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.api.text.TextFit;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.sensor.SensorMenu;
import dev.silencebound.maxtech.storage.sensor.SensorTexts;
import dev.silencebound.maxtech.storage.sensor.SensorValue;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * A sensor's screen in the Accent Frame style, like the condition switch's: the polling button, then the readable
 * values with their live values (one row each, name on the left, value on the right): the values every block gets
 * first, then each pack reader's values under a row naming the reader. A name or value too long for its row ends with
 * an ellipsis and shows whole, with the value's logic name, in a tooltip. The values come from
 * {@link dev.silencebound.maxtech.storage.sensor.SensorView}; the button is a menu button. The mouse wheel scrolls.
 * Client only.
 */
public final class SensorScreen extends DeviceScreen<SensorMenu> {
    private static final int ROW_POLL = 18;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 40;
    private static final int ROW_HEIGHT = 12;
    private static final int VISIBLE = 12;
    private static final int BUTTON_HEIGHT = 14;
    private static final int POLL_WIDTH = 130;
    /** Gap between a row's name and its value. */
    private static final int GAP = 8;

    /** One list row: a reader's header, or a value. */
    private record Row(@Nullable String reader, @Nullable SensorValue value) {
    }

    private int scroll;
    private List<SensorValue> rowsFor = List.of();
    private List<Row> rows = List.of();

    public SensorScreen(SensorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SensorMenu.WIDTH, SensorMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(MenuClick.button(leftPos + pollX(), topPos + ROW_POLL, POLL_WIDTH, BUTTON_HEIGHT,
                () -> SensorTexts.poll(menu.poll()), menu, SensorMenu.CYCLE_POLL));
    }

    private int pollX() {
        return imageWidth - LIST_X - POLL_WIDTH;
    }

    /** The rows for the menu's values, made anew only when a new list arrived. */
    // The list is replaced, never changed, when a view arrives.
    @SuppressWarnings("ReferenceEquality")
    private List<Row> rows() {
        List<SensorValue> values = menu.values();
        if (values != rowsFor) {
            List<Row> next = new ArrayList<>(values.size() + 4);
            String group = "";
            for (SensorValue value : values) {
                if (!value.group().equals(group)) {
                    group = value.group();
                    if (!group.isEmpty()) {
                        next.add(new Row(group, null));
                    }
                }
                next.add(new Row(null, value));
            }
            rows = next;
            rowsFor = values;
            scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - VISIBLE));
        }
        return rows;
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        AccentFrame.box(graphics, leftPos + LIST_X - 2, topPos + LIST_Y - 2, imageWidth - 2 * LIST_X + 4,
                VISIBLE * ROW_HEIGHT + 4);
    }

    /** The width a value's text may take: at most half the row, so the name always keeps room. */
    private int valueRoom() {
        return (imageWidth - 2 * LIST_X) / 2;
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        if (menu.claimBlocked()) {
            TextScale.text(graphics, font, Component.translatable(NetworkInfo.CLAIM_BLOCKED_KEY), LIST_X, LIST_Y + 2,
                    AccentFrame.LABEL, false);
            return;
        }
        if (!menu.loaded()) {
            TextScale.text(graphics, font, Component.translatable(SensorTexts.NOT_LOADED), LIST_X, LIST_Y + 2,
                    AccentFrame.LABEL, false);
            return;
        }
        List<Row> list = rows();
        if (list.isEmpty()) {
            TextScale.text(graphics, font, Component.translatable(SensorTexts.NOTHING), LIST_X, LIST_Y + 2,
                    AccentFrame.LABEL, false);
            return;
        }
        int right = imageWidth - LIST_X;
        int width = right - LIST_X;
        for (int i = 0; i < VISIBLE && scroll + i < list.size(); i++) {
            Row row = list.get(scroll + i);
            int y = LIST_Y + i * ROW_HEIGHT + 2;
            String reader = row.reader();
            if (reader != null) {
                String header = Component.translatable(SensorTexts.READER, reader).getString();
                TextScale.text(graphics, font, TextFit.fit(header, width, font::width), LIST_X, y, AccentFrame.LABEL,
                        false);
                continue;
            }
            SensorValue value = row.value();
            if (value == null) {
                continue;
            }
            String shown = TextFit.fit(SensorTexts.formatValue(value).getString(), valueRoom(), font::width);
            int shownWidth = font.width(shown);
            int indent = value.group().isEmpty() ? 0 : 6;
            String name = TextFit.fit(value.name().getString(), width - indent - shownWidth - GAP, font::width);
            TextScale.text(graphics, font, name, LIST_X + indent, y, AccentFrame.TEXT, false);
            TextScale.text(graphics, font, shown, right - shownWidth, y,
                    value.found() ? AccentFrame.LABEL : AccentFrame.TEXT, false);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (menu.claimBlocked() || !menu.loaded()) {
            return;
        }
        int x = mouseX - leftPos;
        int y = mouseY - topPos - LIST_Y;
        if (x < LIST_X || x >= imageWidth - LIST_X || y < 0 || y >= VISIBLE * ROW_HEIGHT) {
            return;
        }
        List<Row> list = rows();
        int index = scroll + y / ROW_HEIGHT;
        if (index >= list.size()) {
            return;
        }
        Row row = list.get(index);
        SensorValue value = row.value();
        List<Component> lines = new ArrayList<>(3);
        if (value == null) {
            String reader = row.reader();
            if (reader != null) {
                lines.add(Component.translatable(SensorTexts.READER, reader));
            }
        } else {
            lines.add(value.name());
            lines.add(SensorTexts.formatValue(value));
            lines.add(Component.translatable(SensorTexts.VALUE_ID, value.id()));
        }
        if (!lines.isEmpty()) {
            ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int max = Math.max(0, rows().size() - VISIBLE);
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, max);
        return true;
    }
}
