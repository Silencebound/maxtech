package dev.silencebound.maxtech.storage.logic;

import java.util.List;

/**
 * One setting a node type offers. A setting with the same name as an input port is what that input reads while no wire
 * goes into it.
 *
 * @param name
 *            the setting's name, stable across versions, since saved graphs name it
 * @param kind
 *            what it holds
 * @param min
 *            for numbers, the smallest value; a saved value outside the bounds is clamped
 * @param max
 *            for numbers, the largest value
 * @param defaultValue
 *            for numbers and flags (1 is yes), the value of an unset setting; for choices, the index of the default
 * @param choices
 *            for choices, the choice ids in order; empty otherwise
 * @param required
 *            whether the graph is incomplete while the setting is unset (or empty text)
 * @param nameKey
 *            the setting's player-facing name
 */
public record SettingSpec(String name, SettingKind kind, long min, long max, long defaultValue, List<String> choices,
        boolean required, String nameKey) {
    /** Most characters of a text setting. */
    public static final int MAX_TEXT = 128;
    /** Most characters of a label. */
    public static final int MAX_LABEL = 32;
    /** Counts and thresholds stay well inside a long so sums and steps never overflow. */
    public static final long MAX_NUMBER = Long.MAX_VALUE / 16;

    public SettingSpec {
        choices = List.copyOf(choices);
    }

    static SettingSpec number(String name, long min, long max, long defaultValue, String english) {
        return new SettingSpec(name, SettingKind.NUMBER, min, max, defaultValue, List.of(), false, key(name, english));
    }

    static SettingSpec flag(String name, boolean defaultValue, String english) {
        return new SettingSpec(name, SettingKind.FLAG, 0, 1, defaultValue ? 1 : 0, List.of(), false,
                key(name, english));
    }

    /** A choice; {@code choices} pairs each id with its English text. */
    static SettingSpec choice(String name, String english, String... choices) {
        String[] ids = new String[choices.length / 2];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = choices[2 * i];
            LogicLang.shared("choice." + ids[i], choices[2 * i + 1]);
        }
        return new SettingSpec(name, SettingKind.CHOICE, 0, ids.length - 1, 0, List.of(ids), false, key(name, english));
    }

    static SettingSpec text(String name, boolean required, String english) {
        return new SettingSpec(name, SettingKind.TEXT, 0, 0, 0, List.of(), required, key(name, english));
    }

    static SettingSpec label(String name, boolean required, String english) {
        return new SettingSpec(name, SettingKind.LABEL, 0, 0, 0, List.of(), required, key(name, english));
    }

    static SettingSpec item(String name, boolean required, String english) {
        return new SettingSpec(name, SettingKind.ITEM, 0, 0, 0, List.of(), required, key(name, english));
    }

    /** A fluid or a fluid tag; the editor searches fluids. */
    static SettingSpec fluid(String name, boolean required, String english) {
        return new SettingSpec(name, SettingKind.FLUID, 0, 0, 0, List.of(), required, key(name, english));
    }

    /** An item tag; the editor searches tags. */
    static SettingSpec tag(String name, boolean required, String english) {
        return new SettingSpec(name, SettingKind.TAG, 0, 0, 0, List.of(), required, key(name, english));
    }

    private static String key(String name, String english) {
        return LogicLang.shared("setting." + name, english);
    }

    /** The index of choice {@code id}, or the default for an unknown one. */
    public int choiceIndex(String id) {
        int index = choices.indexOf(id);
        return index < 0 ? (int) defaultValue : index;
    }

    /** The lang key of choice {@code id}. */
    public static String choiceKey(String id) {
        return LogicLang.PREFIX + "choice." + id;
    }

    /** {@code value} clamped to this number setting's bounds. */
    public long clamp(long value) {
        return Math.clamp(value, min, max);
    }
}
