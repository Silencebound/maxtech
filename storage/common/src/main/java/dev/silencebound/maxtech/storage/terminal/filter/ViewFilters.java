package dev.silencebound.maxtech.storage.terminal.filter;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.terminal.TerminalView;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What the terminal grid lists (B09 item 24): the {@link TerminalView}'s kinds, then the union of the view filters in
 * the terminal's filter slots. Each filter shows what its list matches, or in hide mode everything else; with several,
 * an entry shows if any of them shows it. Blank filters limit nothing. The filters are compiled into
 * {@link CellFilter.Matcher}s once, when the slots change, so {@link #test} allocates nothing. Client thread;
 * immutable.
 */
public final class ViewFilters implements Predicate<StackKey> {
    private final TerminalView view;
    private final CellFilter.Matcher[] matchers;

    private ViewFilters(TerminalView view, CellFilter.Matcher[] matchers) {
        this.view = view;
        this.matchers = matchers;
    }

    /**
     * The grid's filter for {@code view} and {@code filters} (blank ones skipped), or null when it lets everything
     * through, so the index needs no filter at all.
     */
    public static @Nullable Predicate<StackKey> of(TerminalView view, List<CellFilter> filters) {
        int used = 0;
        for (CellFilter filter : filters) {
            if (!filter.isBlank()) {
                used++;
            }
        }
        if (used == 0) {
            return view.filter();
        }
        CellFilter.Matcher[] matchers = new CellFilter.Matcher[used];
        int next = 0;
        for (CellFilter filter : filters) {
            if (!filter.isBlank()) {
                matchers[next++] = filter.matcher();
            }
        }
        return new ViewFilters(view, matchers);
    }

    @Override
    public boolean test(StackKey key) {
        if (!view.test(key)) {
            return false;
        }
        for (CellFilter.Matcher matcher : matchers) {
            if (matcher.accepts(key)) {
                return true;
            }
        }
        return false;
    }
}
