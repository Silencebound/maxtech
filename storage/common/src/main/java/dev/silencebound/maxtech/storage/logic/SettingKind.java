package dev.silencebound.maxtech.storage.logic;

/** What a node setting holds; the editor shows a field to match. */
public enum SettingKind {
    /** A whole number between the spec's bounds. */
    NUMBER,
    /** Yes or no. */
    FLAG,
    /** One of the spec's choices, saved by its id. */
    CHOICE,
    /** Free text such as a notice or a sensor value's name, at most {@link SettingSpec#MAX_TEXT} characters. */
    TEXT,
    /** A device label, at most {@link SettingSpec#MAX_LABEL} characters; empty means the whole network. */
    LABEL,
    /** An item (or fluid) to count, craft or move. */
    ITEM,
    /** A fluid or a fluid tag to measure; saved like an item. */
    FLUID,
    /** An item tag to count; saved like an item. */
    TAG;

    /** Whether the setting holds an item, a fluid or a tag ({@link SettingValue.Item}). */
    public boolean holdsKey() {
        return this == ITEM || this == FLUID || this == TAG;
    }

    /** Whether {@code value} is saved as this kind's value type. */
    public boolean accepts(SettingValue<?> value) {
        return switch (this) {
            case NUMBER -> value instanceof SettingValue.Num<?>;
            case FLAG -> value instanceof SettingValue.Flag<?>;
            case CHOICE, TEXT, LABEL -> value instanceof SettingValue.Text<?>;
            case ITEM, FLUID, TAG -> value instanceof SettingValue.Item<?>;
        };
    }
}
