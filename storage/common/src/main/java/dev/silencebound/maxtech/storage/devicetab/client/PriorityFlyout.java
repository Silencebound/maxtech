package dev.silencebound.maxtech.storage.devicetab.client;

import dev.silencebound.maxtech.lib.client.gui.accent.Accent;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import dev.silencebound.maxtech.storage.priority.PriorityPayloads;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

/**
 * The priority tab's flyout for a menu with the shared priority (drives, bulk drives, subnets, generators, storage
 * buses): the raising steps, a number field and the lowering steps, as compact buttons. Every change is a
 * {@link PriorityPayloads.Set} for the open menu, sent as soon as the field holds a valid number; the server checks
 * access and the range (the whole int range). Client only.
 */
public final class PriorityFlyout<M extends AbstractContainerMenu & PriorityMenu> implements SideTabs.Flyout {
    private static final int STEP_WIDTH = 32;
    private static final int GAP = 2;
    private static final int ROW = AccentButton.COMPACT_HEIGHT + 4;
    /** Sign and ten digits. */
    private static final int MAX_CHARS = 11;

    private final M menu;
    private String text;
    private int sent;
    private @Nullable EditBox field;

    public PriorityFlyout(M menu) {
        this.menu = menu;
        this.sent = menu.priority();
        this.text = Integer.toString(sent);
    }

    @Override
    public int width() {
        int half = PriorityControl.STEP_COUNT / 2;
        return half * STEP_WIDTH + (half - 1) * GAP;
    }

    @Override
    public int height() {
        return 2 * ROW + AccentButton.COMPACT_HEIGHT;
    }

    @Override
    public void init(SideTabs.Rect content, Consumer<AbstractWidget> add) {
        // The value may have changed since the flyout was last open.
        sent = menu.priority();
        text = Integer.toString(sent);
        int half = PriorityControl.STEP_COUNT / 2;
        for (int i = 0; i < half; i++) {
            int x = content.x() + i * (STEP_WIDTH + GAP);
            // Raising steps on top, smallest first; lowering steps below, smallest first.
            add.accept(step(x, content.y(), half + i));
            add.accept(step(x, content.y() + 2 * ROW, half - 1 - i));
        }
        EditBox box = new EditBox(Minecraft.getInstance().font, content.x(), content.y() + ROW, content.width(),
                AccentButton.COMPACT_HEIGHT, Component.translatable(PriorityControl.TITLE_KEY));
        box.setMaxLength(MAX_CHARS);
        box.setFilter(PriorityFlyout::allowed);
        box.setValue(text);
        box.setResponder(this::typed);
        box.setTextColor(Accent.TEXT);
        field = box;
        add.accept(box);
    }

    @Override
    public void closed() {
        field = null;
    }

    private AccentButton step(int x, int y, int step) {
        return new AccentButton(x, y, STEP_WIDTH, AccentButton.COMPACT_HEIGHT,
                Component.literal(PriorityControl.stepLabel(step)), () -> add(PriorityControl.step(step)));
    }

    /** Digits with an optional leading minus; the empty field and a lone minus are allowed while typing. */
    static boolean allowed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isDigit(c) || (c == '-' && i == 0))) {
                return false;
            }
        }
        return true;
    }

    private void typed(String value) {
        text = value;
        Integer parsed = parse(value);
        if (parsed != null) {
            send(parsed);
        }
    }

    private void add(int step) {
        Integer parsed = parse(text);
        int next = Math.clamp(PriorityControl.add(parsed == null ? sent : parsed, step), menu.minPriority(),
                menu.maxPriority());
        text = Integer.toString(next);
        EditBox box = field;
        if (box != null) {
            box.setValue(text);
        }
        send(next);
    }

    private @Nullable Integer parse(String value) {
        if (value.isEmpty() || value.equals("-")) {
            return null;
        }
        try {
            // Up to 11 characters fit a long; a number beyond the int range stops at its end.
            return (int) Math.clamp(Long.parseLong(value), menu.minPriority(), menu.maxPriority());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void send(int priority) {
        if (priority == sent) {
            return;
        }
        sent = priority;
        Platform.get().network().sendToServer(new PriorityPayloads.Set(menu.containerId, priority));
    }
}
