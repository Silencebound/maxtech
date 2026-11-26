package dev.silencebound.maxtech.storage.logic.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.storage.logic.controller.RecentRate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class AdminListTest {
    private static AdminRow row(String name, String owner, float rate, int cost, int errors) {
        return row(name, owner, "minecraft:overworld", rate, cost, errors);
    }

    private static AdminRow row(String name, String owner, String dimension, float rate, int cost, int errors) {
        return row(name, owner, dimension, rate, cost, 0, 0, errors);
    }

    private static AdminRow row(String name, String owner, String dimension, float rate, int cost, float points,
            int lastPoints, int errors) {
        return new AdminRow(dimension, new BlockPos(name.length(), 64, 0), 0, name, owner, 0, rate, cost, points,
                lastPoints, -1, errors, errors > 0 ? "some.error" : "", "");
    }

    private static List<String> names(List<AdminRow> rows) {
        List<String> out = new ArrayList<>();
        for (AdminRow row : rows) {
            out.add(row.name());
        }
        return out;
    }

    private static final List<AdminRow> ROWS = List.of(row("cheap", "Bob", 1.0f, 2, 0),
            row("broken", "alice", 0.0f, 0, 2), row("busy", "carol", 40.0f, 8, 0), row("tied", "Bob", 1.0f, 5, 0),
            row("odd", "alice", 3.0f, 1, 1));

    @Test
    void costPutsTheMostChecksPerSecondFirstThenTheLastRunsCost() {
        assertEquals(List.of("busy", "odd", "tied", "cheap", "broken"),
                names(AdminList.sorted(ROWS, AdminList.Sort.COST)));
    }

    @Test
    void actionsPutTheMostActionPointsPerSecondFirstThenTheLastRunsPointsThenChecks() {
        String world = "minecraft:overworld";
        List<AdminRow> rows = List.of(row("checky", "a", world, 90.0f, 50, 0.0f, 0, 0),
                row("mover", "a", world, 2.0f, 4, 55.0f, 11, 0), row("switcher", "a", world, 2.0f, 4, 6.0f, 2, 0),
                row("flicker", "a", world, 1.0f, 3, 6.0f, 4, 0), row("idle", "a", world, 0.0f, 0, 0.0f, 0, 0));
        assertEquals(List.of("mover", "flicker", "switcher", "checky", "idle"),
                names(AdminList.sorted(rows, AdminList.Sort.ACTIONS)));
    }

    @Test
    void errorsPutTheMostErrorsFirstThenCost() {
        assertEquals(List.of("broken", "odd", "busy", "tied", "cheap"),
                names(AdminList.sorted(ROWS, AdminList.Sort.ERRORS)));
    }

    @Test
    void ownerSortsByNameIgnoringCaseThenCost() {
        assertEquals(List.of("odd", "broken", "tied", "cheap", "busy"),
                names(AdminList.sorted(ROWS, AdminList.Sort.OWNER)));
    }

    @Test
    void pagesSplitAndClamp() {
        List<AdminRow> rows = AdminList.sorted(ROWS, AdminList.Sort.COST);
        assertEquals(1, AdminList.pages(0, 2));
        assertEquals(3, AdminList.pages(rows.size(), 2));
        assertEquals(List.of("busy", "odd"), names(AdminList.page(rows, 1, 2)));
        assertEquals(List.of("broken"), names(AdminList.page(rows, 3, 2)));
        assertEquals(List.of("broken"), names(AdminList.page(rows, 9, 2)), "past the end shows the last page");
        assertEquals(List.of("busy", "odd"), names(AdminList.page(rows, 0, 2)), "before the start shows the first");
        assertEquals(List.of(), names(AdminList.page(List.of(), 1, 2)));
    }

    @Test
    void filterKeepsOrderByDimensionAndOwnerPart() {
        List<AdminRow> rows = List.of(row("a", "Alice", 1, 1, 0), row("b", "bob", "minecraft:the_nether", 1, 1, 0),
                row("c", "Malice", "minecraft:the_nether", 1, 1, 0));
        assertEquals(List.of("a", "b", "c"), names(AdminList.filter(rows, "", "")));
        assertEquals(List.of("b", "c"), names(AdminList.filter(rows, "minecraft:the_nether", "")));
        assertEquals(List.of("a", "c"), names(AdminList.filter(rows, "", "ALIC")));
        assertEquals(List.of("c"), names(AdminList.filter(rows, "minecraft:the_nether", "lic")));
    }

    @Test
    void recentRateCountsTheLastWindowAndThisOne() {
        RecentRate rate = new RecentRate();
        assertEquals(0.0, rate.perSecond(0));
        rate.add(100, 10);
        // 100 checks over the 10 s window before this one plus 0.5 s of this one.
        assertEquals(100 * 20.0 / 210, rate.perSecond(10), 1e-9);
        rate.add(50, 250);
        // The window moved on: the 100 count as the last window, the 50 as this one, 10 s + 2.5 s.
        assertEquals(150 * 20.0 / 250, rate.perSecond(250), 1e-9);
        // In the next window only the 50 of the last one count.
        assertEquals(50 * 20.0 / 200, rate.perSecond(400), 1e-9);
        // Two windows later nothing was paid recently.
        assertEquals(0.0, rate.perSecond(600));
        rate.add(10, 1000);
        assertEquals(10 * 20.0 / 200, rate.perSecond(1000), 1e-9, "an idle gap does not count as the last window");
    }
}
