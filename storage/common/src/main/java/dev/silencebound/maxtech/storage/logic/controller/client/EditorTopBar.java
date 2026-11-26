package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.lib.text.TextFit;

/**
 * The node editor's top bar layout, kept apart from drawing so it can be tested (owner screenshot 2026-10-09: the bar
 * ran off the left edge). The name box and the status line sit on the left; the primary buttons (Undo, Redo, Save,
 * Back) always sit on the right. The secondary buttons (History, Simulate, Revert and later ones) sit between them when
 * all of them fit, and otherwise move into a "More" menu that opens below its button. Every width comes from measured
 * text, so the bar fits at any window size and GUI scale; nothing is placed left of x = 0. Plain arithmetic.
 */
public final class EditorTopBar {
    /** The name box never shrinks below this while there is room for it. */
    public static final int NAME_FLOOR = 40;
    /** The status line keeps at least this much room before the name box shrinks. */
    public static final int STATUS_FLOOR = 24;
    /** The gap between the name box and the status text. */
    public static final int STATUS_INSET = 4;

    /**
     * Where everything goes. Buttons are listed left to right as given. {@code secondaryX} holds an x on the bar for an
     * inline secondary button; with {@code menu} set they all sit in the menu instead, a column {@code menuWidth} wide
     * starting at {@code menuX}, under the More button at {@code moreX}. {@code statusWidth} is the status line's room
     * with the edit-now button hidden; {@code editNowX} is where that button goes when shown.
     */
    public record Layout(int nameX, int nameWidth, int statusX, int statusWidth, Positions primaryX, boolean menu,
            Positions secondaryX, int moreX, int menuX, int menuWidth, int editNowX) {
        /** The status line's room while the edit-now button shows at its end, {@code gap} before it. */
        public int statusRoomBesideEditNow(int gap) {
            return Math.max(0, Math.min(statusWidth, editNowX - gap - statusX));
        }
    }

    /** The x of each button in a group, left to right. */
    public static final class Positions {
        private final int[] xs;

        Positions(int[] xs) {
            this.xs = xs.clone();
        }

        /** The x of button {@code i}. */
        public int get(int i) {
            return xs[i];
        }

        /** How many buttons the group has. */
        public int size() {
            return xs.length;
        }
    }

    private EditorTopBar() {
    }

    /**
     * Lays out a bar {@code width} wide with {@code gap} between elements: a name box between {@code nameMin} and
     * {@code nameMax} wide (it shrinks to {@link #NAME_FLOOR} only when the bar is too narrow for {@code nameMin}), a
     * status line that wants {@code statusMin}, the {@code primary} and {@code secondary} buttons with these widths, a
     * More button {@code moreWidth} wide and an edit-now button {@code editNowWidth} wide that shares the status line's
     * room.
     */
    public static Layout layout(int width, int gap, int nameMin, int nameMax, int statusMin, int[] primary,
            int[] secondary, int moreWidth, int editNowWidth) {
        int primaries = run(primary, gap);
        int inline = run(secondary, gap);
        int statusWant = Math.max(statusMin, editNowWidth + gap + STATUS_FLOOR);
        int left = gap + nameMin + STATUS_INSET + statusWant + gap;
        boolean menu = secondary.length > 0 && left + inline + primaries > width;
        int right = primaries + (menu ? moreWidth + gap : inline);
        // The right-hand group ends one gap from the edge; on a bar too narrow for it, it starts at the left edge and
        // runs off the right instead of the left.
        int x = Math.max(gap, width - right);
        int start = x;
        int[] secondaryX = new int[secondary.length];
        int moreX = -1;
        if (menu) {
            moreX = x;
            x += moreWidth + gap;
        } else {
            for (int i = 0; i < secondary.length; i++) {
                secondaryX[i] = x;
                x += secondary[i] + gap;
            }
        }
        int[] primaryX = new int[primary.length];
        for (int i = 0; i < primary.length; i++) {
            primaryX[i] = x;
            x += primary[i] + gap;
        }
        // The name and the status share what is left of the right-hand group.
        int room = Math.max(0, start - gap - gap);
        int nameWidth = TextFit.grow(room - STATUS_INSET - statusWant, nameMin, nameMax);
        nameWidth = Math.min(nameWidth,
                Math.max(Math.min(NAME_FLOOR, room), room - STATUS_INSET - STATUS_FLOOR - editNowWidth - gap));
        nameWidth = Math.max(0, nameWidth);
        int statusX = gap + nameWidth + STATUS_INSET;
        int statusWidth = Math.max(0, start - gap - statusX);
        int editNowX = Math.max(statusX, start - gap - editNowWidth);
        int menuWidth = 0;
        for (int w : secondary) {
            menuWidth = Math.max(menuWidth, w);
        }
        menuWidth = Math.max(menuWidth, moreWidth);
        int menuX = menu ? Math.max(0, Math.min(moreX + moreWidth - menuWidth, width - menuWidth)) : 0;
        return new Layout(gap, nameWidth, statusX, statusWidth, new Positions(primaryX), menu,
                new Positions(secondaryX), moreX, menuX, menuWidth, editNowX);
    }

    /** The width of buttons {@code widths} wide in a row, each followed by {@code gap}. */
    private static int run(int[] widths, int gap) {
        int total = 0;
        for (int w : widths) {
            total += w + gap;
        }
        return total;
    }
}
