package dev.silencebound.maxtech.storage.devicetab;

/**
 * Where a device screen's flyout content starts, relative to the panel's top left corner, when the flyout opens on the
 * right (B09 item 21b). Menus need it on both sides to place the slots an upgrades flyout shows, since a slot's
 * position is fixed when the menu is made; the numbers are those of Lib's client-only {@code SideTabs} (gap, tab, gap
 * and the flyout's padding across; padding and title strip down), and a unit test keeps them equal.
 */
public final class DeviceTabLayout {
    /** {@code SideTabs.GAP + SideTabs.TAB + SideTabs.GAP + SideTabs.PAD}. */
    public static final int CONTENT_RIGHT_OF_PANEL = 2 + 18 + 2 + 6;
    /** {@code SideTabs.PAD + SideTabs.TITLE}. */
    public static final int CONTENT_TOP = 6 + 14;
    /** One slot cell in an upgrades flyout. */
    public static final int SLOT = 18;
    /** Slots per row in an upgrades flyout. */
    public static final int SLOTS_PER_ROW = 2;
    /**
     * The narrowest upgrades flyout content: one row of slots. Narrow enough that it opens on the right of a 176 wide
     * panel even on the smallest GUI (320 wide), so its slots always line up.
     */
    public static final int UPGRADES_MIN_WIDTH = SLOTS_PER_ROW * SLOT;
    /** The widest upgrades flyout content, when the window has the room: room for its note. */
    public static final int UPGRADES_MAX_WIDTH = 96;

    private DeviceTabLayout() {
    }

    /** The x of upgrade slot {@code index} on a panel {@code panelWidth} wide. */
    public static int slotX(int panelWidth, int index) {
        return panelWidth + CONTENT_RIGHT_OF_PANEL + 1 + index % SLOTS_PER_ROW * SLOT;
    }

    /** The y of upgrade slot {@code index}. */
    public static int slotY(int index) {
        return CONTENT_TOP + 1 + index / SLOTS_PER_ROW * SLOT;
    }

    /**
     * The upgrades flyout's content width for {@code room} pixels of content room right of the tabs: as wide as fits,
     * between {@link #UPGRADES_MIN_WIDTH} and {@link #UPGRADES_MAX_WIDTH}.
     */
    public static int upgradesWidth(int room) {
        return Math.clamp(room, UPGRADES_MIN_WIDTH, UPGRADES_MAX_WIDTH);
    }

    /** Rows of slots for {@code count} upgrade slots. */
    public static int rows(int count) {
        return (count + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW;
    }
}
