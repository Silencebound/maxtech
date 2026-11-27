package dev.silencebound.maxtech.storage.devicetab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.storage.ioport.IoPortMenu;
import dev.silencebound.maxtech.storage.port.PortMenu;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The device side tabs' rules (B09 item 21b): the owner's fixed order, only the tabs a device has with no gaps, which
 * tab carries a problem badge, and the upgrade slots' place, which must match where Lib's {@link SideTabs} opens a
 * flyout on the right.
 */
class DeviceTabTest {
    @Test
    void tabsFollowTheOwnersOrder() {
        List<DeviceTab> all = DeviceTab.shown(DeviceTab.mask(DeviceTab.values()));
        assertEquals(List.of(DeviceTab.MODE, DeviceTab.PRIORITY, DeviceTab.UPGRADES, DeviceTab.LABEL, DeviceTab.ACCESS),
                all);
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).order() < all.get(i).order(), all.get(i - 1) + " before " + all.get(i));
        }
    }

    @Test
    void onlyTheTabsADeviceHasShowWithoutGaps() {
        // A port in import mode: mode, upgrades, label.
        assertEquals(List.of(DeviceTab.MODE, DeviceTab.UPGRADES, DeviceTab.LABEL),
                DeviceTab.shown(DeviceTab.mask(DeviceTab.LABEL, DeviceTab.UPGRADES, DeviceTab.MODE)));
        // A drive: priority and label.
        assertEquals(List.of(DeviceTab.PRIORITY, DeviceTab.LABEL),
                DeviceTab.shown(DeviceTab.mask(DeviceTab.LABEL, DeviceTab.PRIORITY)));
        assertEquals(List.of(), DeviceTab.shown(0));
        // The tab column has no gap for a missing tab: tab i sits right under tab i - 1.
        assertEquals(SideTabs.tabY(10, 1) - SideTabs.tabY(10, 0), SideTabs.TAB + SideTabs.SPACING);
    }

    @Test
    void problemsGoOnTheModeTabElseTheFirstTab() {
        assertEquals(DeviceTab.MODE, DeviceTab.badgeTab(DeviceTab.mask(DeviceTab.LABEL, DeviceTab.MODE)));
        assertEquals(DeviceTab.PRIORITY, DeviceTab.badgeTab(DeviceTab.mask(DeviceTab.LABEL, DeviceTab.PRIORITY)));
        assertEquals(DeviceTab.LABEL, DeviceTab.badgeTab(DeviceTab.mask(DeviceTab.LABEL)));
        assertNull(DeviceTab.badgeTab(0));
    }

    @Test
    void upgradeSlotsSitWhereARightFlyoutsContentStarts() {
        int width = PortMenu.WIDTH;
        SideTabs.Rect panel = new SideTabs.Rect(0, 0, width, PortMenu.HEIGHT);
        SideTabs.Rect box = SideTabs.flyout(panel, SideTabs.outerWidth(DeviceTabLayout.UPGRADES_MAX_WIDTH),
                SideTabs.outerHeight(40), 10_000, 10_000);
        assertEquals(panel.right() + SideTabs.GAP + SideTabs.TAB + SideTabs.GAP, box.x(), "flyout on the right");
        assertEquals(box.x() + SideTabs.PAD, width + DeviceTabLayout.CONTENT_RIGHT_OF_PANEL, "content x");
        assertEquals(box.y() + SideTabs.PAD + SideTabs.TITLE, DeviceTabLayout.CONTENT_TOP, "content y");
        // Slot items sit one pixel inside their 18 px frame, two to a row.
        assertEquals(width + DeviceTabLayout.CONTENT_RIGHT_OF_PANEL + 1, DeviceTabLayout.slotX(width, 0));
        assertEquals(DeviceTabLayout.slotX(width, 0) + 18, DeviceTabLayout.slotX(width, 1));
        assertEquals(DeviceTabLayout.slotX(width, 0), DeviceTabLayout.slotX(width, 2));
        assertEquals(DeviceTabLayout.slotY(0) + 18, DeviceTabLayout.slotY(2));
        assertEquals(2, DeviceTabLayout.rows(3));
        assertEquals(DeviceTabLayout.UPGRADES_MIN_WIDTH, DeviceTabLayout.upgradesWidth(10));
        assertEquals(DeviceTabLayout.UPGRADES_MAX_WIDTH, DeviceTabLayout.upgradesWidth(500));
        assertEquals(60, DeviceTabLayout.upgradesWidth(60));
    }

    @Test
    void upgradesFlyoutFitsRightOfTheDevicePanelsOnTheSmallestGui() {
        for (int width : new int[]{PortMenu.WIDTH, IoPortMenu.WIDTH}) {
            int left = (LayoutCheck.MIN_GUI_WIDTH - width) / 2;
            SideTabs.Rect panel = new SideTabs.Rect(left, 20, width, 166);
            int room = LayoutCheck.MIN_GUI_WIDTH - (left + width + DeviceTabLayout.CONTENT_RIGHT_OF_PANEL)
                    - SideTabs.PAD;
            int outer = SideTabs.outerWidth(DeviceTabLayout.upgradesWidth(room));
            SideTabs.Rect box = SideTabs.flyout(panel, outer, SideTabs.outerHeight(4 * 18), LayoutCheck.MIN_GUI_WIDTH,
                    LayoutCheck.MIN_GUI_HEIGHT);
            assertEquals(panel.right() + SideTabs.GAP + SideTabs.TAB + SideTabs.GAP, box.x(),
                    "a " + width + " wide panel's upgrades open on the right");
        }
    }
}
