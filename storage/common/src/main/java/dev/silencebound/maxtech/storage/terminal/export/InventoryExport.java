package dev.silencebound.maxtech.storage.terminal.export;

import dev.silencebound.maxtech.storage.crafting.order.ListCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text forms of an exported inventory list (M7C item 10): CSV, plain text and the {@code mtlist1:} list code
 * (through {@link ListCode}, the one place that writes it), and the name of a saved CSV file. Pure; any thread.
 */
public final class InventoryExport {
    /** The CSV header; fluids give their count in millibuckets. */
    public static final String CSV_HEADER = "name,id,count,craftable";
    /** The folder in the player's game directory that saved lists go to. */
    public static final String FOLDER = "maxtech_exports";
    private static final String CSV_LINE = "\r\n";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    private InventoryExport() {
    }

    /**
     * One listed entry.
     *
     * @param name
     *            the name as the player reads it
     * @param id
     *            the registry id, {@code namespace:path}
     * @param fluid
     *            whether it is a fluid; {@code count} is then in millibuckets
     * @param count
     *            how much the network holds; 0 for an entry that is only craftable
     * @param craftable
     *            whether the network has a pattern for it
     */
    public record Row(String name, String id, boolean fluid, long count, boolean craftable) {
    }

    /** {@code rows} as CSV (RFC 4180, CRLF line ends) under {@link #CSV_HEADER}. */
    public static String csv(List<Row> rows) {
        StringBuilder out = new StringBuilder(CSV_HEADER.length() + rows.size() * 48);
        out.append(CSV_HEADER).append(CSV_LINE);
        for (Row row : rows) {
            out.append(field(safe(row.name()))).append(',').append(field(row.id())).append(',').append(row.count())
                    .append(',').append(row.craftable()).append(CSV_LINE);
        }
        return out.toString();
    }

    /**
     * {@code value} as one CSV field: quoted when it holds a comma, a quote or a line break, with quotes doubled.
     */
    static String field(String value) {
        boolean quote = false;
        for (int i = 0; i < value.length() && !quote; i++) {
            char c = value.charAt(i);
            quote = c == ',' || c == '"' || c == '\n' || c == '\r';
        }
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /**
     * A name that a spreadsheet would read as a formula (a renamed item called {@code =HYPERLINK(...)}) gets a leading
     * apostrophe, so it stays text.
     */
    static String safe(String name) {
        if (name.isEmpty()) {
            return name;
        }
        char c = name.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' ? "'" + name : name;
    }

    /**
     * {@code rows} as plain text, one line each: {@code 64 × Stick}, {@code 1,000 mB Water}, with {@code craftableMark}
     * after a craftable entry when it is not empty.
     */
    public static String plain(List<Row> rows, String craftableMark) {
        StringBuilder out = new StringBuilder(rows.size() * 32);
        for (Row row : rows) {
            out.append(String.format(Locale.ROOT, "%,d", row.count())).append(row.fluid() ? " mB " : " × ")
                    .append(row.name());
            if (row.craftable() && !craftableMark.isEmpty()) {
                out.append(' ').append(craftableMark);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * The list code of {@code rows}: every entry the network holds, with its count (only craftable ones are left out,
     * since a code amount is at least 1), as far as {@link ListCode}'s bounds allow.
     */
    public static String listCode(List<Row> rows) {
        List<ListCode.Entry> entries = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (row.count() > 0) {
                entries.add(new ListCode.Entry(row.fluid(), row.id(), Math.min(row.count(), ListCode.MAX_AMOUNT)));
            }
        }
        return ListCode.encode(entries);
    }

    /** The file name of a list saved at {@code time}: {@code inventory-2026-10-10_14-03-59.csv}. */
    public static String fileName(LocalDateTime time) {
        return "inventory-" + FILE_TIME.format(time) + ".csv";
    }
}
