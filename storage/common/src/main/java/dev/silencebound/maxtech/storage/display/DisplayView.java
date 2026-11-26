package dev.silencebound.maxtech.storage.display;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a client knows of one screen: the last value the server sent, with its status and maximum, and the graph's
 * rolling window. The window is sampled on the client while the screen is drawn ({@link #sample}), one point every
 * {@code window / SAMPLES}, so a screen nobody looks at records nothing and costs nothing; the server's history
 * ({@link DisplayHistory}) seeds it when a player starts seeing the screen. Lives in the client's panel block entity or
 * part and dies with it. Client main thread.
 */
public final class DisplayView {
    /** Points a graph's window holds; the client samples the value this often per window while the screen is seen. */
    public static final int SAMPLES = 60;
    /** Logic notices a screen holds and shows at most, newest at the bottom. */
    public static final int NOTICE_LINES = 4;
    /** How long a logic notice stays on a screen, in ticks (two minutes); older ones are no longer drawn. */
    public static final int NOTICE_TICKS = 2400;

    private int status = DisplayUpdate.NO_DATA;
    private long value;
    private long max;
    private int updates;
    private final LongArrayList history = new LongArrayList();
    private int historyTicks;
    private final long[] ring = new long[SAMPLES];
    private int ringStart;
    private int ringSize;
    private int ringTicks;
    private long nextSample = Long.MIN_VALUE;
    private @Nullable Object text;
    private long textValue;
    private int textSource = -1;
    private int textGeneration = -1;
    private List<NetworkNotices.Notice> notices = List.of();
    private @Nullable Object noticeText;
    private int noticeKey;
    private LongList values = LongLists.EMPTY_LIST;
    private int valuesVersion;
    private int historyVersion;
    private @Nullable Object chartLabels;
    private int chartLabelsKey;
    private @Nullable Object layout;
    private @Nullable String layoutText;
    private int layoutKey;
    private ScreenRows rows = ScreenRows.EMPTY;
    private int rowsVersion;
    private @Nullable Object rowsLayout;
    private int rowsLayoutKey;

    /** Takes the placeholder values of the screen's text the server sent, in placeholder order. */
    public void values(LongList values) {
        this.values = values;
        valuesVersion++;
    }

    /** The placeholder values of the screen's text, in placeholder order; empty before the first. Do not change. */
    public LongList values() {
        return values;
    }

    /** Counts the value lists received; a changed count means the text's layout is out of date. */
    public int valuesVersion() {
        return valuesVersion;
    }

    /**
     * The renderer's layout of the screen's text {@code text} for {@code key} (its language, size and values), or null
     * when it made one for another text or key.
     */
    // The text is the settings' own string, replaced and never changed, so identity says whether it is the same.
    @SuppressWarnings("ReferenceEquality")
    public @Nullable Object cachedLayout(String text, int key) {
        return text == layoutText && key == layoutKey ? layout : null;
    }

    /** Keeps the renderer's layout for {@link #cachedLayout}. */
    public void cacheLayout(Object layout, String text, int key) {
        this.layout = layout;
        this.layoutText = text;
        this.layoutKey = key;
    }

    /** Takes a list screen's rows the server sent. */
    public void rows(ScreenRows rows) {
        this.rows = rows;
        rowsVersion++;
    }

    /** A list screen's rows; empty before the first. */
    public ScreenRows rows() {
        return rows;
    }

    /** Counts the rows received; a changed count means the laid-out rows are out of date. */
    public int rowsVersion() {
        return rowsVersion;
    }

    /** The renderer's laid-out rows for {@code key}, or null when it made them for another key. */
    public @Nullable Object cachedRows(int key) {
        return key == rowsLayoutKey ? rowsLayout : null;
    }

    /** Keeps the renderer's laid-out rows for {@link #cachedRows}. */
    public void cacheRows(Object layout, int key) {
        rowsLayout = layout;
        rowsLayoutKey = key;
    }

    /** Takes the newest logic notices the server sent, oldest first. */
    public void notices(List<NetworkNotices.Notice> notices) {
        this.notices = notices.size() > NOTICE_LINES
                ? List.copyOf(notices.subList(notices.size() - NOTICE_LINES, notices.size()))
                : notices;
        noticeText = null;
    }

    /** The newest logic notices, oldest first, at most {@link #NOTICE_LINES}; empty before the first. */
    public List<NetworkNotices.Notice> notices() {
        return notices;
    }

    /**
     * The renderer's laid-out notice lines for {@code key} (its language and the screen's width), or null when it made
     * them for another key or the notices changed since.
     */
    public @Nullable Object cachedNotices(int key) {
        return key == noticeKey ? noticeText : null;
    }

    /** Keeps the renderer's notice lines for {@link #cachedNotices}. */
    public void cacheNotices(Object text, int key) {
        noticeText = text;
        noticeKey = key;
    }

    /** Takes a value the server sent. */
    public void update(int status, long value, long max) {
        this.status = status;
        this.value = value;
        this.max = max;
        updates++;
    }

    /** Takes the server's past samples ({@link DisplayHistory}), oldest first, {@code ticksPerSample} apart. */
    public void history(LongList samples, int ticksPerSample) {
        history.clear();
        int from = Math.max(0, samples.size() - SAMPLES);
        history.addElements(0, samples.toLongArray(), from, samples.size() - from);
        historyTicks = ticksPerSample;
        historyVersion++;
        ringStart = 0;
        ringSize = 0;
        ringTicks = ticksPerSample;
        nextSample = Long.MIN_VALUE;
        for (int i = 0; i < history.size(); i++) {
            push(history.getLong(i));
        }
    }

    /**
     * Records the current value in the window if a sample is due at {@code gameTime}, one every {@code ticksPerSample}
     * ticks. A changed spacing (another window) starts the window over; after a gap longer than one spacing (the screen
     * was out of view) the next sample is due one spacing later, so a gap is never filled with made-up points. Nothing
     * before the first update.
     */
    public void sample(long gameTime, int ticksPerSample) {
        if (status == DisplayUpdate.NO_DATA) {
            return;
        }
        if (ticksPerSample != ringTicks) {
            ringStart = 0;
            ringSize = 0;
            ringTicks = ticksPerSample;
            nextSample = Long.MIN_VALUE;
        }
        if (gameTime < nextSample) {
            return;
        }
        push(value);
        boolean late = nextSample == Long.MIN_VALUE || gameTime - nextSample >= ticksPerSample;
        nextSample = late ? gameTime + ticksPerSample : nextSample + ticksPerSample;
    }

    private void push(long sample) {
        if (ringSize < SAMPLES) {
            ring[(ringStart + ringSize) % SAMPLES] = sample;
            ringSize++;
        } else {
            ring[ringStart] = sample;
            ringStart = (ringStart + 1) % SAMPLES;
        }
    }

    /**
     * The renderer's text for {@code value} of source {@code source} made in language generation {@code generation}, or
     * null when it was made for something else; saves building the same text every frame.
     */
    public @Nullable Object cachedText(long value, int source, int generation) {
        return value == textValue && source == textSource && generation == textGeneration ? text : null;
    }

    /** Keeps the renderer's text for {@link #cachedText}. */
    public void cacheText(Object text, long value, int source, int generation) {
        this.text = text;
        this.textValue = value;
        this.textSource = source;
        this.textGeneration = generation;
    }

    /** Counts the past samples received; a history chart's labels are out of date when it changed. */
    public int historyVersion() {
        return historyVersion;
    }

    /** The renderer's labels of a history chart for {@code key}, or null when it made them for another key. */
    public @Nullable Object cachedChartLabels(int key) {
        return key == chartLabelsKey ? chartLabels : null;
    }

    /** Keeps the renderer's chart labels for {@link #cachedChartLabels}. */
    public void cacheChartLabels(Object labels, int key) {
        chartLabels = labels;
        chartLabelsKey = key;
    }

    /** Points in the window, at most {@link #SAMPLES}. */
    public int samples() {
        return ringSize;
    }

    /** The window's point {@code index}, 0 the oldest. */
    public long sampleAt(int index) {
        return ring[(ringStart + index) % SAMPLES];
    }

    /** The largest point in the window, or the current value with none; 0 at least. */
    public long peak() {
        long peak = Math.max(0, value);
        for (int i = 0; i < ringSize; i++) {
            peak = Math.max(peak, ring[i]);
        }
        return peak;
    }

    /** {@link DisplayUpdate#NO_DATA} until the first update, then the status the server sent. */
    public int status() {
        return status;
    }

    public long value() {
        return value;
    }

    /** The value's maximum (stored FE's capacity, the cells' bytes); 0 for sources without one. */
    public long max() {
        return max;
    }

    /** Updates received so far; tests read it. */
    public int updates() {
        return updates;
    }

    /** The server's past samples, oldest first; empty without a {@link DisplayHistory}. Do not change. */
    public LongList serverHistory() {
        return history;
    }

    /** Ticks between the server's past samples; 0 without any. */
    public int serverHistoryTicks() {
        return historyTicks;
    }
}
