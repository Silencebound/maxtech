package dev.silencebound.maxtech.storage.crafting.order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The item list code {@code mtlist1:} (M7C items 6 and 10): a list of items and fluids with amounts as one line of text
 * that players copy, paste and type. This class is the only place that reads or writes it.
 *
 * <p>
 * Format: {@code mtlist1:} followed by entries separated by commas, each {@code <id>*<amount>}. {@code <id>} is a
 * registry id such as {@code minecraft:stick}; a fluid is written {@code f/minecraft:water} with its amount in
 * millibuckets. Example: {@code mtlist1:minecraft:stick*64,minecraft:torch*16,f/minecraft:water*1000}. Items are named
 * by id only, without components (enchantments, names). Written codes list each id once, in order.
 *
 * <p>
 * Reading is lenient, so a typed list works too: the {@code mtlist1:} prefix is optional, entries may also be separated
 * by semicolons or new lines, spaces are ignored, {@code *<amount>} may be left out (1), an id without a namespace
 * means {@code minecraft}, and an id named twice adds up. Reading is bounded: at most {@link #MAX_LENGTH} characters,
 * at most {@link #MAX_ENTRIES} different entries, amounts from 1 to {@link #MAX_AMOUNT}; what breaks a rule is reported
 * in {@link Parsed#errors()} and left out. Whether an id exists is the caller's check. Pure; any thread.
 */
public final class ListCode {
    /** The prefix of a list code, with its format version. */
    public static final String PREFIX = "mtlist1:";
    /** The fluid marker in front of a fluid's id. */
    public static final String FLUID = "f/";
    /** Different entries a code holds at most. */
    public static final int MAX_ENTRIES = 256;
    /** Characters read at most. */
    public static final int MAX_LENGTH = 32_768;
    /** The largest amount of one entry. */
    public static final long MAX_AMOUNT = Integer.MAX_VALUE;

    private ListCode() {
    }

    /**
     * One entry.
     *
     * @param fluid
     *            whether {@code id} names a fluid (amount in millibuckets)
     * @param id
     *            the registry id, {@code namespace:path}
     */
    public record Entry(boolean fluid, String id, long amount) {
    }

    /**
     * What a code or typed list held.
     *
     * @param entries
     *            the valid entries, each id once, in the order first named
     * @param errors
     *            the parts left out, as written (cut to 40 characters), at most 16
     */
    public record Parsed(List<Entry> entries, List<String> errors) {
    }

    private static final int MAX_ERRORS = 16;

    /** Writes {@code entries} as a code: each id once (amounts added up, capped), in order, at most the bounds. */
    public static String encode(List<Entry> entries) {
        Map<String, Entry> merged = merge(entries, null);
        StringBuilder out = new StringBuilder(PREFIX);
        boolean first = true;
        for (Entry entry : merged.values()) {
            String part = (entry.fluid() ? FLUID : "") + entry.id() + "*" + entry.amount();
            if (out.length() + part.length() + 1 > MAX_LENGTH) {
                break;
            }
            if (!first) {
                out.append(',');
            }
            out.append(part);
            first = false;
        }
        return out.toString();
    }

    /** Reads a code or a typed list; see the class comment. */
    public static Parsed decode(String text) {
        String body = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        body = body.strip();
        if (body.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            body = body.substring(PREFIX.length());
        }
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= body.length(); i++) {
            if (i < body.length() && !separator(body.charAt(i))) {
                continue;
            }
            String part = body.substring(start, i).replace(" ", "").replace("\t", "");
            start = i + 1;
            if (part.isEmpty()) {
                continue;
            }
            Entry entry = entry(part);
            if (entry == null) {
                error(errors, part);
            } else {
                entries.add(entry);
            }
        }
        Map<String, Entry> merged = merge(entries, errors);
        return new Parsed(List.copyOf(merged.values()), List.copyOf(errors));
    }

    private static boolean separator(char c) {
        return c == ',' || c == ';' || c == '\n' || c == '\r';
    }

    private static void error(List<String> errors, String part) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(part.length() > 40 ? part.substring(0, 40) : part);
        }
    }

    /** One {@code [f/]id[*amount]}, or null when it breaks a rule. */
    private static @Nullable Entry entry(String part) {
        String text = part;
        boolean fluid = text.regionMatches(true, 0, FLUID, 0, FLUID.length());
        if (fluid) {
            text = text.substring(FLUID.length());
        }
        long amount = 1;
        int star = text.lastIndexOf('*');
        if (star >= 0) {
            amount = amount(text.substring(star + 1));
            text = text.substring(0, star);
        }
        String id = id(text.toLowerCase(Locale.ROOT));
        if (amount < 1 || id == null) {
            return null;
        }
        return new Entry(fluid, id, amount);
    }

    /** A decimal amount from 1 to {@link #MAX_AMOUNT}, else -1. */
    private static long amount(String text) {
        if (text.isEmpty() || text.length() > 10) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value >= 1 && value <= MAX_AMOUNT ? value : -1;
    }

    /** {@code namespace:path} with valid characters, {@code minecraft} when no namespace is given, else null. */
    private static @Nullable String id(String text) {
        int colon = text.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : text.substring(0, colon);
        String path = colon < 0 ? text : text.substring(colon + 1);
        if (namespace.isEmpty() || path.isEmpty() || path.indexOf(':') >= 0) {
            return null;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (!idChar(c, false)) {
                return null;
            }
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!idChar(c, true)) {
                return null;
            }
        }
        return namespace + ":" + path;
    }

    /** A character an id may hold: as {@code Identifier} allows, {@code /} in the path only. */
    private static boolean idChar(char c, boolean path) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'
                || (path && c == '/');
    }

    /** Each id once, amounts added up and capped, at most {@link #MAX_ENTRIES}; the rest goes to {@code errors}. */
    private static Map<String, Entry> merge(List<Entry> entries, @Nullable List<String> errors) {
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String key = (entry.fluid() ? FLUID : "") + entry.id();
            Entry before = merged.get(key);
            if (before == null && merged.size() >= MAX_ENTRIES) {
                if (errors != null) {
                    error(errors, key);
                }
                continue;
            }
            long amount = Math.clamp(before == null ? entry.amount() : before.amount() + entry.amount(), 1, MAX_AMOUNT);
            merged.put(key, new Entry(entry.fluid(), entry.id(), amount));
        }
        return merged;
    }
}
