package dev.silencebound.maxtech.lib.sync;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The client's search, filter and sort over a {@link ClientSyncedList}; it never asks the server. The view is rebuilt
 * only when the list's version, the search, the filter or the sort changed, and each entry is described (name, mod,
 * tags) once and cached until it leaves the list.
 *
 * <p>
 * Search: words separated by spaces must all match. {@code @text} matches the mod id, {@code #text} any tag id
 * ({@code namespace:path}), {@code $text} the tooltip, anything else the name; all case-insensitive substrings.
 *
 * <p>
 * Tooltips are expensive, so they are asked for only while a {@code $} word is in the search, only for entries that
 * pass the filter and every other word, and once per entry (cached like the name). A rebuild asks for at most
 * {@link #setTooltipBudget the budget} of tooltips the describer has not cached; entries still waiting are left out and
 * the next {@link #view()} continues, so a large list fills in over a few frames. Client thread.
 */
public final class SyncedListIndex<E> {
    private final ClientSyncedList<E> list;
    private final EntryDescriber<E> describer;
    private final Int2ObjectOpenHashMap<Description> descriptions = new Int2ObjectOpenHashMap<>();
    private IntList view = IntLists.emptyList();
    private int builtVersion = -1;
    private boolean dirty = true;
    private String search = "";
    private Term[] terms = new Term[0];
    private ListSort sort = ListSort.COUNT;
    private boolean reversed;
    private @Nullable Predicate<? super E> filter;
    private int tooltipBudget = DEFAULT_TOOLTIP_BUDGET;
    /** Tooltips the running rebuild may still compute. */
    private int budgetLeft;
    /** Whether the last rebuild left entries out because the tooltip budget ran out. */
    private boolean pending;

    /** Tooltips one rebuild computes at most, unless the describer says they are cached. */
    public static final int DEFAULT_TOOLTIP_BUDGET = 200;

    /** What the index needs to know about an entry. Called once per entry while it stays in the list. */
    public interface EntryDescriber<E> {
        /** The name shown to the player, used for search and name sorting. */
        String name(E entry);

        /** The id of the mod the entry comes from. */
        String modId(E entry);

        /** The entry's tag ids as {@code namespace:path}; empty if it has none. */
        List<String> tags(E entry);

        /**
         * The entry's tooltip as plain text, lines joined by newlines, for {@code $} words. Called at most once per
         * entry while it stays in the list, and only while a {@code $} word is searched. Defaults to the name.
         */
        default String tooltip(E entry) {
            return name(entry);
        }

        /**
         * Whether {@link #tooltip} is cheap for {@code entry} now because the describer keeps its own cache; such calls
         * do not count against the index's per-rebuild budget. Defaults to false.
         */
        default boolean tooltipCached(E entry) {
            return false;
        }
    }

    public SyncedListIndex(ClientSyncedList<E> list, EntryDescriber<E> describer) {
        this.list = list;
        this.describer = describer;
    }

    /** Sets the search text; see the class comment for its syntax. */
    public void setSearch(String text) {
        if (!text.equals(search)) {
            search = text;
            terms = parse(text);
            dirty = true;
        }
    }

    public String search() {
        return search;
    }

    /** Sets the order; {@code reversed} flips the main key (smallest count first, Z to A). */
    public void setSort(ListSort newSort, boolean newReversed) {
        if (newSort != sort || newReversed != reversed) {
            sort = newSort;
            reversed = newReversed;
            dirty = true;
        }
    }

    public ListSort sort() {
        return sort;
    }

    public boolean reversed() {
        return reversed;
    }

    /**
     * Shows only entries {@code filter} accepts, or all with {@code null}; checked before the search. The view is
     * rebuilt when a different filter object is set, so pass the same object for the same filter.
     */
    @SuppressWarnings("ReferenceEquality") // filters are compared by identity on purpose
    public void setFilter(@Nullable Predicate<? super E> newFilter) {
        if (newFilter != filter) {
            filter = newFilter;
            dirty = true;
        }
    }

    /** How many uncached tooltips one rebuild computes at most; at least 1. */
    public void setTooltipBudget(int budget) {
        tooltipBudget = Math.max(1, budget);
    }

    /** Whether the view still leaves out entries whose tooltips were not computed yet; the next view continues. */
    public boolean pending() {
        return pending;
    }

    /** Drops the cached tooltips, for example after a resource or language reload; the next view asks again. */
    public void invalidateTooltips() {
        for (Description description : descriptions.values()) {
            description.tooltip = null;
        }
        dirty = true;
    }

    /**
     * The ids that match the filter and the search, in sort order. Rebuilt here when anything changed; do not keep it.
     */
    public IntList view() {
        if (dirty || pending || builtVersion != list.version()) {
            rebuild();
        }
        return view;
    }

    private void rebuild() {
        // Only the tooltip budget asked for this rebuild: the list, search, filter and sort are unchanged, so the
        // matches can only grow, and an equal count means the same entries.
        boolean onlyPending = pending && !dirty && builtVersion == list.version();
        builtVersion = list.version();
        dirty = false;
        pending = false;
        budgetLeft = tooltipBudget;
        if (descriptions.size() > list.size()) {
            descriptions.keySet().removeIf(id -> list.entry(id) == null);
        }
        Predicate<? super E> currentFilter = filter;
        IntArrayList matches = new IntArrayList(list.size());
        for (IntIterator it = list.ids().iterator(); it.hasNext();) {
            int id = it.nextInt();
            E entry = list.entry(id);
            if (entry == null || (currentFilter != null && !currentFilter.test(entry))) {
                continue;
            }
            Description description = describe(id);
            if (description != null && matches(description, entry)) {
                matches.add(id);
            }
        }
        if (onlyPending && matches.size() == view.size()) {
            return;
        }
        int[] ids = matches.elements();
        IntArrays.quickSort(ids, 0, matches.size(), this::compare);
        view = IntLists.unmodifiable(matches);
    }

    private int compare(int a, int b) {
        Description da = descriptions.get(a);
        Description db = descriptions.get(b);
        int result = switch (sort) {
            case COUNT -> Long.compare(list.count(b), list.count(a));
            case NAME -> da.sortName.compareTo(db.sortName);
            case MOD -> da.mod.compareTo(db.mod);
        };
        if (reversed) {
            result = -result;
        }
        if (result == 0 && sort != ListSort.NAME) {
            result = da.sortName.compareTo(db.sortName);
        }
        return result != 0 ? result : Integer.compare(a, b);
    }

    @SuppressWarnings("ReferenceEquality") // a new definition object for an id means the id was reused
    private @Nullable Description describe(int id) {
        E entry = list.entry(id);
        if (entry == null) {
            return null;
        }
        Description description = descriptions.get(id);
        if (description == null || description.entry != entry) {
            List<String> tags = describer.tags(entry);
            String[] lowerTags = new String[tags.size()];
            for (int i = 0; i < lowerTags.length; i++) {
                lowerTags[i] = tags.get(i).toLowerCase(Locale.ROOT);
            }
            description = new Description(entry, describer.name(entry).toLowerCase(Locale.ROOT),
                    describer.modId(entry).toLowerCase(Locale.ROOT), lowerTags);
            descriptions.put(id, description);
        }
        return description;
    }

    /**
     * Whether {@code description} matches every word. Cheap words come first ({@link #parse} puts {@code $} words
     * last), so a tooltip is computed only for an entry that passed all of them; when the budget is spent the entry is
     * left out and the view is marked pending.
     */
    private boolean matches(Description description, E entry) {
        for (Term term : terms) {
            if (term.field() != Field.TOOLTIP) {
                if (!description.matches(term)) {
                    return false;
                }
                continue;
            }
            String tooltip = description.tooltip;
            if (tooltip == null) {
                if (!describer.tooltipCached(entry)) {
                    if (budgetLeft <= 0) {
                        pending = true;
                        return false;
                    }
                    budgetLeft--;
                }
                tooltip = describer.tooltip(entry).toLowerCase(Locale.ROOT);
                description.tooltip = tooltip;
            }
            if (!tooltip.contains(term.text())) {
                return false;
            }
        }
        return true;
    }

    /** Splits the search into words; {@code $} (tooltip) words go last because they are the expensive ones. */
    static Term[] parse(String text) {
        List<Term> parsed = new ArrayList<>();
        List<Term> tooltips = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).trim().split("\\s+", -1)) {
            if (word.isEmpty()) {
                continue;
            }
            char first = word.charAt(0);
            if ((first == '@' || first == '#' || first == '$') && word.length() == 1) {
                continue;
            }
            switch (first) {
                case '@' -> parsed.add(new Term(Field.MOD, word.substring(1)));
                case '#' -> parsed.add(new Term(Field.TAG, word.substring(1)));
                case '$' -> tooltips.add(new Term(Field.TOOLTIP, word.substring(1)));
                default -> parsed.add(new Term(Field.NAME, word));
            }
        }
        parsed.addAll(tooltips);
        return parsed.toArray(new Term[0]);
    }

    enum Field {
        NAME, MOD, TAG, TOOLTIP
    }

    record Term(Field field, String text) {
    }

    /** Lower-case strings searched and sorted on. */
    private static final class Description {
        final Object entry;
        final String sortName;
        final String mod;
        final String[] tags;
        /** The lower-case tooltip, computed on the first {@code $} search that needs it. */
        @Nullable
        String tooltip;

        Description(Object entry, String name, String mod, String[] tags) {
            this.entry = entry;
            this.sortName = name;
            this.mod = mod;
            this.tags = tags;
        }

        /** Whether a cheap (not tooltip) word matches. */
        boolean matches(Term term) {
            return switch (term.field()) {
                case NAME -> sortName.contains(term.text());
                case MOD -> mod.contains(term.text());
                case TAG -> {
                    for (String tag : tags) {
                        if (tag.contains(term.text())) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case TOOLTIP -> true;
            };
        }
    }
}
