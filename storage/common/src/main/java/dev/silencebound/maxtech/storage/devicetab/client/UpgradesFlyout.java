package dev.silencebound.maxtech.storage.devicetab.client;

import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabLayout;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The upgrades tab's flyout (B09 item 21b): the device's upgrade card slots in rows of two, and an optional note under
 * them (the speed the cards give, or why they do nothing). The slots are the menu's {@code UpgradeSlot}s, placed where
 * this flyout's content starts when it opens on the right ({@link DeviceTabLayout}); they show only while it is open
 * there, and if the window has no room on the right the flyout says so instead. Client only.
 */
public final class UpgradesFlyout implements SideTabs.Flyout {
    private static final int NOTE_LINES = 3;
    private static final int GAP = 4;

    private final int slots;
    private final IntSupplier room;
    private final @Nullable Supplier<@Nullable Component> note;
    private final IntSupplier noteColour;
    private boolean placed;

    /**
     * @param slots
     *            how many upgrade slots the menu has
     * @param room
     *            the content width that fits right of the tabs now; the flyout is as wide as fits, at least one row of
     *            slots ({@link DeviceTabLayout#upgradesWidth})
     * @param note
     *            the line under the slots, or null for none
     */
    public UpgradesFlyout(int slots, IntSupplier room, @Nullable Supplier<@Nullable Component> note,
            IntSupplier noteColour) {
        this.slots = slots;
        this.room = room;
        this.note = note;
        this.noteColour = noteColour;
    }

    @Override
    public int width() {
        return DeviceTabLayout.upgradesWidth(room.getAsInt());
    }

    @Override
    public int height() {
        int height = DeviceTabLayout.rows(slots) * DeviceTabLayout.SLOT;
        if (note != null) {
            height += GAP + NOTE_LINES * lineStep();
        }
        return height;
    }

    private static int lineStep() {
        return TextScale.lineHeight(Minecraft.getInstance().font) + 1;
    }

    /** Whether the content sits where the menu's slots are, so they can show. */
    boolean placed() {
        return placed;
    }

    void place(boolean placed) {
        this.placed = placed;
    }

    @Override
    public void init(SideTabs.Rect content, Consumer<AbstractWidget> add) {
        // The slots are the menu's; nothing to add.
    }

    @Override
    public void closed() {
        placed = false;
    }

    @Override
    public void extract(GuiGraphicsExtractor graphics, Font font, SideTabs.Rect content, int mouseX, int mouseY) {
        int rowsHeight = DeviceTabLayout.rows(slots) * DeviceTabLayout.SLOT;
        if (!placed) {
            ScreenComponents.fittedLines(graphics, font, Component.translatable(DeviceTabTexts.NO_ROOM_KEY),
                    content.x(), content.y(), content.width(), NOTE_LINES, lineStep(), Accent.AMBER, mouseX, mouseY);
            return;
        }
        Supplier<@Nullable Component> line = note;
        Component text = line == null ? null : line.get();
        if (text != null) {
            ScreenComponents.fittedLines(graphics, font, text, content.x(), content.y() + rowsHeight + GAP,
                    content.width(), NOTE_LINES, lineStep(), noteColour.getAsInt(), mouseX, mouseY);
        }
    }
}
