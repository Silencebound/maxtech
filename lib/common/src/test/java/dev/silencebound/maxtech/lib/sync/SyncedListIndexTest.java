package dev.silencebound.maxtech.lib.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncedListIndexTest {
    private final ClientSyncedList<TestEntries> list = new ClientSyncedList<>();
    private final SyncedListIndex<TestEntries> index = new SyncedListIndex<>(list, TestEntries.DESCRIBER);

    @BeforeEach
    void fill() {
        put(1, new TestEntries("Iron Ingot", "minecraft", List.of("c:ingots/iron", "c:ingots")), 30);
        put(2, new TestEntries("Copper Ingot", "minecraft", List.of("c:ingots/copper", "c:ingots")), 500);
        put(3, new TestEntries("Bronze Ingot", "maxtech", List.of("c:ingots/bronze", "c:ingots")), 30);
        put(4, new TestEntries("Iron Plate", "maxtech", List.of("c:plates/iron")), 7);
    }

    @Test
    void sortsByCountLargestFirstWithNameTieBreak() {
        assertEquals(List.of(2, 3, 1, 4), index.view());
        index.setSort(ListSort.COUNT, true);
        assertEquals(List.of(4, 3, 1, 2), index.view(), "reversed: smallest first, ties still by name");
    }

    @Test
    void sortsByNameAndMod() {
        index.setSort(ListSort.NAME, false);
        assertEquals(List.of(3, 2, 1, 4), index.view());
        index.setSort(ListSort.NAME, true);
        assertEquals(List.of(4, 1, 2, 3), index.view());
        index.setSort(ListSort.MOD, false);
        assertEquals(List.of(3, 4, 2, 1), index.view(), "maxtech before minecraft, then by name");
    }

    @Test
    void searchesNameModAndTags() {
        index.setSearch("iron");
        assertEquals(List.of(1, 4), index.view());
        index.setSearch("@maxtech");
        assertEquals(List.of(3, 4), index.view());
        index.setSearch("#ingots ingot");
        assertEquals(List.of(2, 3, 1), index.view());
        index.setSearch("@maxtech #iron");
        assertEquals(List.of(4), index.view(), "all words must match");
        index.setSearch("IRON   plate ");
        assertEquals(List.of(4), index.view(), "case and extra spaces do not matter");
        index.setSearch("@ #");
        assertEquals(4, index.view().size(), "a bare prefix matches everything");
        index.setSearch("gold");
        assertEquals(List.of(), index.view());
    }

    @Test
    void followsListChanges() {
        index.setSearch("ingot");
        IntList before = index.view();
        assertEquals(3, before.size());
        list.apply(TestEntries.TYPE.update(0, false, new int[0], List.of(), new int[]{2, 1}, new long[]{0, 900}));
        assertEquals(List.of(1, 3), index.view(), "copper removed, iron now largest");
        put(2, new TestEntries("Tin Ingot", "maxtech", List.of()), 1);
        assertEquals(List.of(1, 3, 2), index.view(), "a reused id is described afresh");
    }

    @Test
    void idReusedBetweenTwoViewsIsDescribedAfresh() {
        index.setSearch("bronze");
        assertEquals(List.of(3), index.view());
        list.apply(TestEntries.TYPE.update(0, false, new int[0], List.of(), new int[]{3}, new long[]{0}));
        put(3, new TestEntries("Steel Ingot", "maxtech", List.of()), 30);
        assertEquals(List.of(), index.view(), "id 3 is steel now, not bronze");
        index.setSearch("steel");
        assertEquals(List.of(3), index.view());
    }

    /** A describer whose tooltips add a lore line per name and count how often they were built. */
    private static final class TooltipDescriber implements SyncedListIndex.EntryDescriber<TestEntries> {
        private static final Map<String, String> LORE = Map.of("Iron Ingot", "Magnetic", "Copper Ingot",
                "Oxidises over time", "Bronze Ingot", "Magnetic alloy", "Iron Plate", "Flat");
        final List<String> built = new ArrayList<>();
        final Set<String> cached = new HashSet<>();

        @Override
        public String name(TestEntries entry) {
            return entry.name();
        }

        @Override
        public String modId(TestEntries entry) {
            return entry.mod();
        }

        @Override
        public List<String> tags(TestEntries entry) {
            return entry.tags();
        }

        @Override
        public String tooltip(TestEntries entry) {
            built.add(entry.name());
            return entry.name() + "\n" + LORE.getOrDefault(entry.name(), "");
        }

        @Override
        public boolean tooltipCached(TestEntries entry) {
            return cached.contains(entry.name());
        }
    }

    @Test
    void parsesTooltipWordsLastAndSkipsBarePrefixes() {
        SyncedListIndex.Term[] terms = SyncedListIndex.parse("$Magnetic @mod $ #tag  name");
        assertEquals(List.of(new SyncedListIndex.Term(SyncedListIndex.Field.MOD, "mod"),
                new SyncedListIndex.Term(SyncedListIndex.Field.TAG, "tag"),
                new SyncedListIndex.Term(SyncedListIndex.Field.NAME, "name"),
                new SyncedListIndex.Term(SyncedListIndex.Field.TOOLTIP, "magnetic")), List.of(terms));
    }

    @Test
    void searchesTooltipsAloneAndWithModAndTag() {
        TooltipDescriber describer = new TooltipDescriber();
        SyncedListIndex<TestEntries> tooltips = new SyncedListIndex<>(list, describer);
        tooltips.setSearch("$magnetic");
        assertEquals(List.of(3, 1), tooltips.view(), "iron's and bronze's lore");
        tooltips.setSearch("$magnetic @minecraft");
        assertEquals(List.of(1), tooltips.view());
        tooltips.setSearch("#c:ingots $OXIDISES");
        assertEquals(List.of(2), tooltips.view());
        tooltips.setSearch("$");
        assertEquals(4, tooltips.view().size(), "a bare $ matches everything");
        assertEquals(4, describer.built.size(), "each tooltip built once, however many searches use it");
    }

    @Test
    void buildsTooltipsOnlyForEntriesPassingCheapWords() {
        TooltipDescriber describer = new TooltipDescriber();
        SyncedListIndex<TestEntries> tooltips = new SyncedListIndex<>(list, describer);
        tooltips.setSearch("iron");
        assertEquals(List.of(1, 4), tooltips.view());
        assertEquals(List.of(), describer.built, "no $ word, no tooltips");
        tooltips.setSearch("$flat @maxtech");
        assertEquals(List.of(4), tooltips.view());
        assertEquals(List.of("Bronze Ingot", "Iron Plate"), describer.built.stream().sorted().toList(),
                "only the two maxtech entries were asked, although $ came first");
    }

    @Test
    void tooltipBudgetFillsTheViewOverSeveralRebuilds() {
        TooltipDescriber describer = new TooltipDescriber();
        SyncedListIndex<TestEntries> tooltips = new SyncedListIndex<>(list, describer);
        tooltips.setTooltipBudget(1);
        tooltips.setSearch("$ingot");
        int rebuilds = 0;
        int shown = 0;
        IntList previous = IntList.of();
        do {
            IntList current = tooltips.view();
            int size = current.size();
            assertTrue(size >= shown, "the view only grows while it fills in");
            if (rebuilds > 0 && size == shown) {
                assertSame(previous, current, "a pending rebuild that found nothing new keeps the sorted view");
            }
            previous = current;
            shown = size;
            rebuilds++;
            assertEquals(rebuilds, describer.built.size(), "one tooltip per rebuild");
        } while (tooltips.pending() && rebuilds < 10);
        assertEquals(4, rebuilds, "four tooltips, one per rebuild");
        assertFalse(tooltips.pending());
        assertEquals(List.of(2, 3, 1), tooltips.view());
        assertEquals(4, describer.built.size());
        tooltips.setSearch("$magnetic");
        assertEquals(List.of(3, 1), tooltips.view(), "a new keystroke builds nothing");
        assertEquals(4, describer.built.size());
    }

    @Test
    void pendingRebuildWithoutNewMatchesKeepsTheView() {
        TooltipDescriber describer = new TooltipDescriber();
        SyncedListIndex<TestEntries> tooltips = new SyncedListIndex<>(list, describer);
        tooltips.setTooltipBudget(1);
        // Two of the four tooltips match, so at least one later rebuild builds a tooltip that adds nothing.
        tooltips.setSearch("$magnetic");
        IntList previous = tooltips.view();
        int kept = 0;
        while (tooltips.pending()) {
            IntList current = tooltips.view();
            if (current.size() == previous.size()) {
                assertSame(previous, current, "no new match, no new sort");
                kept++;
            }
            previous = current;
        }
        assertTrue(kept > 0);
        assertEquals(List.of(3, 1), tooltips.view());
    }

    @Test
    void cachedTooltipsDoNotCountAgainstTheBudget() {
        TooltipDescriber describer = new TooltipDescriber();
        describer.cached.addAll(List.of("Iron Ingot", "Copper Ingot", "Bronze Ingot", "Iron Plate"));
        SyncedListIndex<TestEntries> tooltips = new SyncedListIndex<>(list, describer);
        tooltips.setTooltipBudget(1);
        tooltips.setSearch("$ingot");
        assertEquals(List.of(2, 3, 1), tooltips.view());
        assertFalse(tooltips.pending());
    }

    @Test
    void invalidatedTooltipsAreBuiltAgain() {
        TooltipDescriber describer = new TooltipDescriber();
        SyncedListIndex<TestEntries> tooltips = new SyncedListIndex<>(list, describer);
        tooltips.setSearch("$flat");
        assertEquals(List.of(4), tooltips.view());
        tooltips.invalidateTooltips();
        assertEquals(List.of(4), tooltips.view());
        assertEquals(8, describer.built.size(), "every entry asked again after the reload");
    }

    @Test
    void filterCombinesWithSearchAndRunsFirst() {
        TooltipDescriber describer = new TooltipDescriber();
        SyncedListIndex<TestEntries> tooltips = new SyncedListIndex<>(list, describer);
        Predicate<TestEntries> minecraft = entry -> entry.mod().equals("minecraft");
        tooltips.setFilter(minecraft);
        assertEquals(List.of(2, 1), tooltips.view());
        tooltips.setSearch("ingot $magnetic");
        assertEquals(List.of(1), tooltips.view());
        assertEquals(List.of("Copper Ingot", "Iron Ingot"), describer.built.stream().sorted().toList(),
                "filtered-out entries never build a tooltip");
        tooltips.setFilter(null);
        assertEquals(List.of(3, 1), tooltips.view());
    }

    private void put(int id, TestEntries entry, long count) {
        list.apply(TestEntries.TYPE.update(0, false, new int[]{id}, List.of(entry), new int[]{id}, new long[]{count}));
    }
}
