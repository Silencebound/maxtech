package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.Locale;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Which kinds the terminal grid lists: everything, only items or only fluids. The player cycles it with a button; it is
 * kept in {@link TerminalPrefs} and so shared by every terminal that opens the terminal screen (block, part, wireless,
 * portable cell, chest).
 */
public enum TerminalView implements Predicate<StackKey> {
    ALL, ITEMS, FLUIDS;

    /** Whether {@code key} is listed. */
    @Override
    public boolean test(StackKey key) {
        return shows(key instanceof FluidKey);
    }

    /** Whether an entry of this kind is listed. */
    public boolean shows(boolean fluid) {
        return switch (this) {
            case ALL -> true;
            case ITEMS -> !fluid;
            case FLUIDS -> fluid;
        };
    }

    /** The index filter for this view: the view itself, so the same object every time; null for {@link #ALL}. */
    public @Nullable Predicate<StackKey> filter() {
        return this == ALL ? null : this;
    }

    /** The view the button switches to next: all, items, fluids, all. */
    public TerminalView next() {
        return switch (this) {
            case ALL -> ITEMS;
            case ITEMS -> FLUIDS;
            case FLUIDS -> ALL;
        };
    }

    /** The name saved in the prefs file. */
    public String serialName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The view saved as {@code name}; {@link #ALL} for a missing or unknown one. */
    public static TerminalView bySerialName(@Nullable String name) {
        for (TerminalView view : values()) {
            if (view.serialName().equals(name)) {
                return view;
            }
        }
        return ALL;
    }
}
