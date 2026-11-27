package dev.silencebound.maxtech.storage.display.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmTexts;
import dev.silencebound.maxtech.storage.display.material.MaterialTexts;
import dev.silencebound.maxtech.storage.display.production.ProductionTexts;
import dev.silencebound.maxtech.storage.display.task.TaskTexts;
import net.minecraft.client.gui.screens.Screen;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.display.DisplayChartSave;
import dev.silencebound.maxtech.storage.history.ChartValue;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A screen's settings GUI in the Accent Frame style: mode and colour, source, the counted item slot with its tag, and
 * the graph window. Everything it shows comes from the menu's synced data; every button is a menu button click. Client
 * only.
 */
public final class DisplayScreen extends DeviceScreen<DisplayMenu> {
    private static final int ROW_MODE = 18;
    private static final int ROW_SOURCE = 36;
    private static final int ROW_WINDOW = 80;
    private static final int HEIGHT = 16;

    private @Nullable AccentButton nextTag;
    private @Nullable AccentButton editText;
    private @Nullable AccentButton editTasks;
    private @Nullable AccentButton editList;
    private @Nullable AccentButton interval;
    private @Nullable EditBox chartValue;
    /** The chart value last sent or loaded, so closing the screen sends only a change. */
    private String sentChartValue = "";
    private boolean chartValueBad;

    public DisplayScreen(DisplayMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, DisplayMenu.WIDTH, DisplayMenu.HEIGHT);
        this.inventoryLabelY = DisplayMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
    }

    @Override
    protected void init() {
        super.init();
        add(8, ROW_MODE, 100, DisplayMenu.CYCLE_MODE, () -> Component.translatable(menu.mode().translationKey()));
        add(112, ROW_MODE, 56, DisplayMenu.CYCLE_ACCENT, () -> Component.translatable(DisplayContent.ACCENT_KEY));
        add(8, ROW_SOURCE, 160, DisplayMenu.CYCLE_SOURCE, () -> Component.translatable(menu.source().translationKey()));
        nextTag = add(128, DisplayMenu.SLOT_Y, 40, DisplayMenu.NEXT_TAG, () -> Component.literal(">"));
        add(8, ROW_WINDOW, 100, DisplayMenu.CYCLE_WINDOW,
                () -> menu.source() == DisplaySourceKind.HISTORY
                        ? Component.translatable(DisplayContent.CHART_WINDOW_KEY, menu.chartHours())
                        : Component.translatable(DisplayContent.WINDOW_KEY, menu.windowSeconds()));
        editText = edit(Component.translatable(DisplayContent.EDIT_TEXT_KEY), () -> new DisplayTextScreen(menu, this));
        editTasks = edit(Component.translatable(TaskTexts.EDIT_TASKS), () -> new TaskBoardScreen(menu, this));
        editList = edit(Component.translatable(MaterialTexts.EDIT), () -> new MaterialListScreen(menu, this));
        interval = add(112, ROW_WINDOW, 56, DisplayMenu.CYCLE_INTERVAL,
                () -> Component.translatable(DisplayContent.CHART_INTERVAL_KEY, intervalText(menu.chartInterval())));
        EditBox box = new EditBox(font, leftPos + 9, topPos + DisplayMenu.SLOT_Y + 1, 158, 14, Component.empty());
        box.setMaxLength(ChartValue.MAX_LENGTH);
        box.setHint(Component.translatable(DisplayContent.CHART_VALUE_HINT_KEY));
        box.setTooltip(Tooltip.create(Component.translatable(DisplayContent.CHART_VALUE_TIP_KEY)));
        if (minecraft != null && minecraft.level != null) {
            sentChartValue = menu.clientSettings(minecraft.level).chart().value();
        }
        box.setValue(sentChartValue);
        box.setResponder(text -> chartValueBad = !text.isBlank() && ChartValue.parse(text).isEmpty());
        chartValue = addRenderableWidget(box);
    }

    /** {@code ticks} as seconds or minutes. */
    static Component intervalText(int ticks) {
        return ticks < 1200
                ? Component.translatable(DisplayContent.CHART_SECONDS_KEY, ticks / 20)
                : Component.translatable(DisplayContent.CHART_MINUTES_KEY, ticks / 1200);
    }

    /** Sends the typed chart value if it changed and parses; the server checks it again. */
    private void sendChartValue() {
        EditBox box = chartValue;
        if (box == null || menu.source() != DisplaySourceKind.HISTORY) {
            return;
        }
        String text = box.getValue().strip();
        if (text.equals(sentChartValue) || (!text.isEmpty() && ChartValue.parse(text).isEmpty())) {
            return;
        }
        sentChartValue = text;
        Platform.get().network().sendToServer(new DisplayChartSave(menu.containerId, text));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox box = chartValue;
        if (box != null && box.isFocused() && box.visible) {
            if (event.isConfirmation()) {
                sendChartValue();
                box.setFocused(false);
                setFocused(null);
                return true;
            }
            if (!event.isEscape()) {
                // Typing goes to the box, not to the inventory key binding.
                box.keyPressed(event);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        sendChartValue();
        super.removed();
    }

    private AccentButton add(int x, int y, int width, int id, Supplier<Component> label) {
        return addRenderableWidget(MenuClick.button(leftPos + x, topPos + y, width, HEIGHT, label, menu, id));
    }

    /** A button that opens an editor over the still open menu: the text editor, the task board or the list. */
    private AccentButton edit(Component label, Supplier<Screen> editor) {
        return addRenderableWidget(new AccentButton(leftPos + 112, topPos + ROW_WINDOW, 56, HEIGHT, label, () -> {
            if (minecraft != null) {
                minecraft.setScreen(editor.get());
            }
        }));
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentButton button = nextTag;
        if (button != null) {
            button.visible = menu.source() == DisplaySourceKind.TAG_COUNT;
        }
        AccentButton edit = editText;
        if (edit != null) {
            edit.visible = menu.source() == DisplaySourceKind.TEXT;
        }
        AccentButton tasks = editTasks;
        if (tasks != null) {
            tasks.visible = menu.source() == DisplaySourceKind.TASK_BOARD;
        }
        AccentButton list = editList;
        if (list != null) {
            list.visible = menu.source() == DisplaySourceKind.MATERIAL_LIST;
        }
        boolean chart = menu.source() == DisplaySourceKind.HISTORY;
        AccentButton cycle = interval;
        if (cycle != null) {
            cycle.visible = chart;
        }
        EditBox box = chartValue;
        if (box != null) {
            box.visible = chart;
            box.setTextColor(chartValueBad ? 0xFFFF6060 : AccentFrame.TEXT);
        }
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        // The accent swatch inside the colour button.
        int colour = 0xFF000000
                | DisplaySettings.ACCENTS.getInt(Math.clamp(menu.accent(), 0, DisplaySettings.ACCENTS.size() - 1));
        graphics.fill(leftPos + 158, topPos + ROW_MODE + 4, leftPos + 166, topPos + ROW_MODE + 12, colour);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        if (menu.source() == DisplaySourceKind.HISTORY) {
            EditBox box = chartValue;
            if (chartValueBad && box != null) {
                // Below the interval row: the chart needs the graph research, so the locked line is not shown then.
                AccentFrame.text(graphics, font,
                        Component.translatable(DisplayContent.CHART_BAD_VALUE_KEY, box.getValue()), 8, ROW_WINDOW + 20,
                        imageWidth - 16, 0xFFFF6060, xm, ym);
            }
        } else {
            AccentButton button = nextTag;
            int subjectRight = button != null && button.visible ? button.getX() - leftPos - 4 : imageWidth - 8;
            AccentFrame.text(graphics, font, subject(), 30, DisplayMenu.SLOT_Y + 4, subjectRight - 30,
                    AccentFrame.LABEL, xm, ym);
        }
        if (menu.graphsLocked()) {
            AccentFrame.text(graphics, font, Component.translatable(DisplayContent.GRAPHS_LOCKED_KEY), 8,
                    ROW_WINDOW + 20, imageWidth - 16, AccentFrame.LABEL, xm, ym);
        }
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }

    /** What the screen counts: the item, or the tag picked from the item's tags; nothing for the other sources. */
    private Component subject() {
        ItemStack item = menu.countedItem();
        return switch (menu.source()) {
            case ITEM_COUNT ->
                item.isEmpty() ? Component.translatable(DisplayContent.NOTHING_KEY) : item.getHoverName();
            case TAG_COUNT -> tagName(item);
            case LOGIC_NOTICES -> Component.translatable(DisplayContent.NOTICES_HINT_KEY);
            case TEXT -> Component.translatable(DisplayContent.TEXT_HINT_KEY);
            case TASK_BOARD -> Component.translatable(TaskTexts.HINT);
            case ALARMS -> Component.translatable(AlarmTexts.HINT);
            case MATERIAL_LIST -> Component.translatable(MaterialTexts.HINT);
            case PRODUCTION -> Component.translatable(ProductionTexts.HINT);
            default -> Component.empty();
        };
    }

    private Component tagName(ItemStack item) {
        List<TagKey<Item>> tags = ConditionMenu.tags(item.getItem());
        int index = menu.tagIndex();
        return item.isEmpty() || index < 0 || index >= tags.size()
                ? Component.translatable(DisplayContent.NOTHING_KEY)
                : Component.literal("#" + tags.get(index).location());
    }
}
