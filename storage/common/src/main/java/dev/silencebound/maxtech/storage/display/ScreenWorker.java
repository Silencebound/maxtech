package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.sensor.SensorDevice;
import dev.silencebound.maxtech.storage.sensor.SensorRef;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The server side of one screen (a wall's anchor panel, or a display part): reads its source from the network and sends
 * the value to the players who can see it ({@link DisplayViewers}), only when it changed and at most every
 * {@code display.updateTicks} ticks. Counts are pushed: the per-key listener (one key) or the contents watcher (a tag)
 * wakes the screen's task, and only while someone sees it; so do logic notices sent to the network's displays, which go
 * out as the newest few lines ({@link DisplayNoticesUpdate}). A rich text screen ({@link DisplaySourceKind#TEXT}) reads
 * the placeholders of its text: counts of items and tags pushed the same way, sensor values by label sampled on the
 * cadence, sent as one list ({@link DisplayValuesUpdate}) when one of them changed. Rates, power and bytes are sampled
 * from the network's cached stats on that cadence while someone sees it. With no viewers the task sleeps
 * ({@link StorageTask#WAIT}) and costs nothing per tick. Server thread.
 */
public final class ScreenWorker
        implements
            StorageTask,
            KeyListener,
            ContentsWatcher,
            DisplayViewers.Screen,
            RowFeed.Screen {
    /** Marks a key whose tag membership is known, so a member of no tag is not 0 (unknown). */
    private static final int KNOWN = 1 << 31;

    /** What the screen belongs to. */
    public interface Host {
        DeviceLink deviceLink();

        /** The settings the screen shows now. */
        DisplaySettings settings();

        /** The block (the anchor panel, or the part's cable). */
        BlockPos pos();

        /** The part's face, -1 for a panel block. */
        int side();

        boolean isRemoved();

        /** The screen's recorded chart changed and should be saved ({@link ChartRecorder}). */
        default void recordChanged() {
        }
    }

    /** What one placeholder of a text screen reads. */
    private record TextSlot(int kind, @Nullable StackKey key, int tag, String label, @Nullable SensorRef ref) {
        static final int NONE = 0;
        static final int ITEM = 1;
        static final int TAG = 2;
        static final int SENSOR = 3;
    }

    private final Host host;
    private @Nullable ServerLevel level;
    private long chunk;
    /** The players who see the chunk, kept up to date by {@link DisplayViewers} while started; empty otherwise. */
    private List<ServerPlayer> viewers = List.of();
    private final ObjectArrayList<StackKey> listened = new ObjectArrayList<>();
    private @Nullable NetworkState watched;
    /** The tags summed: the tag count's one tag, or a text's tag placeholders. */
    private final ObjectArrayList<TagKey<Item>> tags = new ObjectArrayList<>();
    /** Per key id, {@link #KNOWN} and a bit per tag in {@link #tags} it belongs to; 0 while unknown. */
    private final Int2IntOpenHashMap tagMembers = new Int2IntOpenHashMap();
    private final Int2LongOpenHashMap tagCounts = new Int2LongOpenHashMap();
    private long[] tagTotals = new long[0];
    /** A text screen's placeholders in their order, resolved with the settings. */
    private final ObjectArrayList<TextSlot> textSlots = new ObjectArrayList<>();
    private long[] textValues = new long[0];
    private long @Nullable [] sentTextValues;
    /** Whether some placeholder reads a sensor, so the screen samples on the cadence while seen. */
    private boolean textSampled;
    private int valueSends;
    private @Nullable NetworkState rateNetwork;
    private long rateCounter;
    private long rateTime = Long.MIN_VALUE;
    private long rate;
    private boolean sentAny;
    private int sentStatus;
    private long sentValue;
    private long sentMax;
    private long nextSend = Long.MIN_VALUE;
    private boolean historyDue;
    private int sends;
    private final LongArrayList historyBuffer = new LongArrayList();
    /** The network whose logic notices the screen shows, null for other sources. */
    private @Nullable NetworkState noticeNetwork;
    private final Runnable noticeWatcher = this::noticePosted;
    private boolean noticesDue;
    private long sentNotices = -1;
    private int noticeSends;
    private int chartSends;
    /** A history chart's recorder; it records only while the source is {@link DisplaySourceKind#HISTORY}. */
    private final ChartRecorder chart;
    private final long[] chartPoints = new long[DisplayView.SAMPLES];
    /** A list screen's feed ({@link DisplaySourceKind#rows()}), null for other sources. */
    private @Nullable RowFeed feed;
    private @Nullable ScreenRows sentRows;
    private boolean rowsDue;
    private boolean feedWorkDue;
    private int rowSends;

    public ScreenWorker(Host host) {
        this.host = host;
        this.chart = new ChartRecorder(host, this::chartRecorded);
    }

    @Override
    public Host host() {
        return host;
    }

    @Override
    public void rowsChanged() {
        rowsDue = true;
        if (level != null && hasViewers()) {
            host.deviceLink().wake(this);
        }
    }

    @Override
    public void workDue() {
        if (level != null) {
            feedWorkDue = true;
            host.deviceLink().wake(this);
        }
    }

    /** The list screen's feed, for tests; null for other sources or before the screen started. */
    public @Nullable RowFeed feed() {
        return feed;
    }

    /**
     * {@code player} clicked shown row {@code row} of this list screen: the feed handles it (it checks access). Returns
     * whether it did; false for other sources, so the screen's settings open as before. Server thread.
     */
    public boolean clickRow(ServerPlayer player, int row) {
        RowFeed current = feed;
        NetworkState network = host.deviceLink().network();
        return current != null && network != null && current.click(player, network, row);
    }

    /** Row lists sent so far (one per change, whatever the number of viewers); for tests. */
    public int rowSends() {
        return rowSends;
    }

    /** The rows last sent, for tests; null before the first. */
    public @Nullable ScreenRows sentRows() {
        return sentRows;
    }

    /** The history chart's recorder (saved by the host). */
    public ChartRecorder chart() {
        return chart;
    }

    /** The chart took a sample or changed state: viewers get the chart again. */
    private void chartRecorded() {
        if (level != null && hasViewers()) {
            historyDue = true;
            host.deviceLink().wake(this);
        }
    }

    /** Starts watching the screen's chunk and source and schedules its task; once per load, server side. */
    public void start(ServerLevel level) {
        if (this.level != null) {
            return;
        }
        this.level = level;
        this.chunk = ChunkPos.pack(host.pos());
        viewers = DisplayViewers.register(level, chunk, this);
        subscribe(host.deviceLink().network());
        host.deviceLink().schedule(this);
        host.deviceLink().schedule(chart);
        historyDue = true;
    }

    /** Stops everything {@link #start} began (the panel stopped being an anchor, or is gone). */
    public void stop() {
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        level = null;
        DisplayViewers.unregister(current, chunk, this);
        viewers = List.of();
        unsubscribe();
        host.deviceLink().cancel(this);
        host.deviceLink().cancel(chart);
        chart.release();
    }

    public boolean isStarted() {
        return level != null;
    }

    /** The settings changed: re-subscribe and send the new source's value. */
    public void settingsChanged() {
        if (level == null) {
            return;
        }
        unsubscribe();
        subscribe(host.deviceLink().network());
        host.deviceLink().wake(chart);
        resend();
    }

    /** The device joined {@code network} or left its network (null): follow the tag watcher and resend. */
    public void networkChanged(@Nullable StorageNetwork network) {
        if (level == null) {
            return;
        }
        NetworkState state = network instanceof NetworkState s ? s : null;
        if (watched != state) {
            unwatchTag();
            watchTag(state);
        }
        if (noticeNetwork != state) {
            unwatchNotices();
            watchNotices(state);
        }
        RowFeed current = feed;
        if (current != null) {
            current.unbind();
            current.bind(state);
        }
        rateNetwork = null;
        if (state == null) {
            // No task runs outside a network; say so now.
            send(DisplayUpdate.OFFLINE, 0, 0, false);
        } else {
            resend();
        }
    }

    private void resend() {
        sentAny = false;
        sentTextValues = null;
        sentRows = null;
        rowsDue = true;
        historyDue = true;
        noticesDue = true;
        nextSend = Long.MIN_VALUE;
        rateNetwork = null;
        host.deviceLink().wake(this);
    }

    private void subscribe(@Nullable NetworkState network) {
        DisplaySettings settings = host.settings();
        if (settings.source() == DisplaySourceKind.ITEM_COUNT && settings.item().isPresent()) {
            listen(settings.item().get());
        }
        if (settings.source() == DisplaySourceKind.TAG_COUNT && settings.tag().isPresent()) {
            tags.add(settings.tag().get());
        }
        if (settings.source() == DisplaySourceKind.TEXT) {
            resolveText(settings.text());
        }
        tagTotals = new long[tags.size()];
        if (!tags.isEmpty()) {
            watchTag(network);
        }
        watchNotices(network);
        if (settings.source().rows()) {
            RowFeed made = RowFeeds.create(settings.source(), this);
            feed = made;
            if (made != null) {
                made.bind(network);
            }
        }
    }

    private void listen(StackKey key) {
        if (!listened.contains(key)) {
            listened.add(key);
            host.deviceLink().listen(key, this);
        }
    }

    /** Reads the text's placeholders once: listens to item counts, adds tags to the tag sums, keeps sensor ids. */
    private void resolveText(String text) {
        textSlots.clear();
        textSampled = false;
        for (RichText.Value value : DisplayText.parse(text).values()) {
            TextSlot slot = new TextSlot(TextSlot.NONE, null, -1, "", null);
            if (value.name().equals(DisplayText.COUNT)) {
                Optional<DisplayText.CountTarget> target = DisplayText.count(value.argument());
                Item item = target.isPresent() ? target.get().item() : null;
                TagKey<Item> tag = target.isPresent() ? target.get().tag() : null;
                if (item != null) {
                    StackKey key = ItemKey.of(item);
                    listen(key);
                    slot = new TextSlot(TextSlot.ITEM, key, -1, "", null);
                } else if (tag != null) {
                    int index = tags.indexOf(tag);
                    if (index < 0) {
                        index = tags.size();
                        tags.add(tag);
                    }
                    slot = new TextSlot(TextSlot.TAG, null, /* tag= */ index, "", null);
                }
            } else if (value.name().equals(DisplayText.SENSOR)) {
                Optional<DisplayText.SensorTarget> target = DisplayText.sensor(value.argument());
                Optional<SensorRef> ref = target.isPresent()
                        ? SensorRef.parse(target.get().valueId())
                        : Optional.empty();
                if (target.isPresent() && ref.isPresent()) {
                    slot = new TextSlot(TextSlot.SENSOR, null, -1, target.get().labelKey(), ref.get());
                    textSampled = true;
                }
            }
            textSlots.add(slot);
        }
        textValues = new long[textSlots.size()];
    }

    private void unsubscribe() {
        for (int i = 0; i < listened.size(); i++) {
            host.deviceLink().unlisten(listened.get(i), this);
        }
        listened.clear();
        unwatchTag();
        tags.clear();
        textSlots.clear();
        textValues = new long[0];
        textSampled = false;
        unwatchNotices();
        RowFeed current = feed;
        if (current != null) {
            current.unbind();
            feed = null;
        }
        sentRows = null;
    }

    /** Hears of {@code network}'s display notices while the screen shows them. */
    private void watchNotices(@Nullable NetworkState network) {
        if (network != null && host.settings().source() == DisplaySourceKind.LOGIC_NOTICES) {
            noticeNetwork = network;
            network.watchDisplayNotices(noticeWatcher);
        }
    }

    private void unwatchNotices() {
        NetworkState current = noticeNetwork;
        if (current != null) {
            current.unwatchDisplayNotices(noticeWatcher);
            noticeNetwork = null;
        }
    }

    /** A notify node sent a notice to the network's displays: send it if someone sees the screen. */
    private void noticePosted() {
        if (hasViewers()) {
            host.deviceLink().wake(this);
        }
    }

    /** Starts summing the tags over {@code network}'s totals: one walk over the totals, then per change. */
    private void watchTag(@Nullable NetworkState network) {
        tagMembers.clear();
        tagCounts.clear();
        Arrays.fill(tagTotals, 0);
        if (network == null || tags.isEmpty()) {
            return;
        }
        watched = network;
        network.watch(this);
        StorageContents contents = network.contents();
        for (Int2LongMap.Entry entry : contents.snapshot().totals().int2LongEntrySet()) {
            count(contents, entry.getIntKey(), entry.getLongValue());
        }
    }

    private void unwatchTag() {
        NetworkState current = watched;
        if (current != null) {
            current.unwatch(this);
            watched = null;
        }
    }

    /** Adds the key with {@code id} at {@code total} to the sums of its tags; returns whether it is in any. */
    private boolean count(StorageContents contents, int id, long total) {
        int member = tagMembers.get(id);
        if (member == 0) {
            member = KNOWN;
            if (contents.key(id) instanceof ItemKey item) {
                for (int t = 0; t < tags.size(); t++) {
                    if (item.item().builtInRegistryHolder().is(tags.get(t))) {
                        member |= 1 << t;
                    }
                }
            }
            tagMembers.put(id, member);
        }
        if (member == KNOWN) {
            return false;
        }
        long delta = total - tagCounts.put(id, total);
        addToTags(member, delta);
        return true;
    }

    /** Adds {@code delta} to the sum of every tag whose bit {@code member} has. */
    private void addToTags(int member, long delta) {
        for (int t = 0; t < tagTotals.length; t++) {
            if ((member & (1 << t)) != 0) {
                tagTotals[t] += delta;
            }
        }
    }

    @Override
    public void changed(int id, long total) {
        NetworkState current = watched;
        if (current != null && count(current.contents(), id, total) && hasViewers()) {
            host.deviceLink().wake(this);
        }
    }

    @Override
    public void released(int id) {
        addToTags(tagMembers.remove(id), -tagCounts.remove(id));
    }

    @Override
    public void keyChanged(StackKey key, long total) {
        if (hasViewers()) {
            host.deviceLink().wake(this);
        }
    }

    @Override
    public void viewersChanged(boolean added) {
        if (added) {
            resend();
        }
    }

    @Override
    public boolean isGone() {
        return level == null || host.isRemoved();
    }

    private boolean hasViewers() {
        return level != null && !viewers.isEmpty();
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        RowFeed rowFeed = feed;
        if (feedWorkDue && rowFeed != null) {
            // Work that does not wait for viewers (a goal to look at).
            feedWorkDue = false;
            rowFeed.work(transfer.network(), gameTime);
        }
        if (!hasViewers()) {
            return WAIT;
        }
        if (gameTime < nextSend) {
            return (int) Math.min(Integer.MAX_VALUE, nextSend - gameTime);
        }
        int interval = StorageServerConfig.DISPLAY_UPDATE_TICKS.getAsInt();
        NetworkState network = transfer.network();
        DisplaySettings settings = host.settings();
        DisplaySourceKind source = settings.source();
        DisplayHistory history = DisplayHistory.current();
        // A watched count that never changes would never run again; the history may want a call to keep recording it.
        int keepAlive = history == null ? 0 : history.watched(network, settings);
        int status = network.status() == StorageNetwork.Status.ONLINE && network.isPowered()
                ? DisplayUpdate.ONLINE
                : DisplayUpdate.STOPPED;
        if (source.isRate() && !sampleRate(network, source, gameTime)) {
            // The first sample only sets the rate's baseline.
            return interval;
        }
        long value = value(network, settings);
        long max = max(network, source, chart);
        if (historyDue) {
            historyDue = false;
            sendHistory(network, settings);
        }
        if (!sentAny || status != sentStatus || value != sentValue || max != sentMax) {
            send(status, value, max, true);
            nextSend = gameTime + interval;
        }
        if (source == DisplaySourceKind.LOGIC_NOTICES && (noticesDue || value != sentNotices)) {
            sendNotices(network, value);
        }
        if (rowFeed != null) {
            if (rowsDue || sentRows == null) {
                rowsDue = false;
                ScreenRows rows = rowFeed.rows(network, gameTime);
                if (!rows.equals(sentRows)) {
                    sendRows(rows);
                    nextSend = gameTime + interval;
                }
            }
            int refresh = rowFeed.refreshTicks();
            if (refresh > 0) {
                rowsDue = true;
                return Math.max(refresh, interval);
            }
        }
        if (source == DisplaySourceKind.TEXT) {
            readText(network);
            long[] sent = sentTextValues;
            if (sent == null || !Arrays.equals(sent, textValues)) {
                sendValues();
                nextSend = gameTime + interval;
            }
            if (textSampled) {
                return interval;
            }
        }
        if (source.pushed()) {
            return keepAlive > 0 ? keepAlive : WAIT;
        }
        return interval;
    }

    /** Brings {@link #rate} up to date; false when this was the first sample on this network. */
    private boolean sampleRate(NetworkState network, DisplaySourceKind source, long gameTime) {
        long counter = switch (source) {
            case POWER_IN -> network.energyReceived();
            case POWER_OUT -> network.energySpent();
            default -> network.operations();
        };
        boolean ready = rateNetwork == network && rateTime < gameTime;
        if (ready) {
            long ticks = gameTime - rateTime;
            // Throughput is per second, power per tick.
            rate = source == DisplaySourceKind.THROUGHPUT
                    ? (counter - rateCounter) * 20 / ticks
                    : (counter - rateCounter) / ticks;
        }
        if (rateNetwork != network || rateTime < gameTime) {
            rateNetwork = network;
            rateCounter = counter;
            rateTime = gameTime;
        }
        return ready;
    }

    private long value(NetworkState network, DisplaySettings settings) {
        return switch (settings.source()) {
            case ITEM_COUNT -> {
                Optional<StackKey> item = settings.item();
                yield item.isPresent() ? network.contents().amount(item.get()) : 0;
            }
            case TAG_COUNT -> tagTotals.length > 0 ? tagTotals[0] : 0;
            case THROUGHPUT, POWER_IN, POWER_OUT -> rate;
            case POWER_STORED -> network.storedEnergy();
            case BYTES_USED -> network.usedBytes();
            case BYTES_FREE -> Math.max(0, network.totalBytes() - network.usedBytes());
            case LOGIC_NOTICES -> network.displayNotices().posted();
            case TEXT, TASK_BOARD, ALARMS, MATERIAL_LIST, PRODUCTION -> 0;
            case HISTORY -> {
                var ring = chart.ring();
                yield ring == null ? 0 : ring.latest();
            }
        };
    }

    /** Reads the text's placeholder values into {@link #textValues}. */
    private void readText(NetworkState network) {
        for (int i = 0; i < textSlots.size(); i++) {
            TextSlot slot = textSlots.get(i);
            StackKey key = slot.key();
            textValues[i] = switch (slot.kind()) {
                case TextSlot.ITEM -> key == null ? 0 : network.contents().amount(key);
                case TextSlot.TAG -> slot.tag() < tagTotals.length ? tagTotals[slot.tag()] : 0;
                case TextSlot.SENSOR -> sensorValue(network, slot);
                default -> 0;
            };
        }
    }

    /** The value of the first sensor with the slot's label on {@code network}; 0 without one. */
    private static long sensorValue(NetworkState network, TextSlot slot) {
        SensorRef ref = slot.ref();
        if (ref == null) {
            return 0;
        }
        List<NetworkDevice> devices = network.labels().devicesByKey(slot.label());
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i) instanceof SensorDevice sensor) {
                return sensor.sensor().value(ref);
            }
        }
        return 0;
    }

    /** Sends the text's values to the players who see the screen. */
    private void sendValues() {
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        long[] copy = textValues.clone();
        sentTextValues = copy;
        List<ServerPlayer> players = DisplayViewers.players(current, chunk);
        if (players.isEmpty()) {
            return;
        }
        DisplayValuesUpdate update = new DisplayValuesUpdate(host.pos(), host.side(), LongArrayList.wrap(copy));
        for (int i = 0; i < players.size(); i++) {
            DisplaySync.send(players.get(i), update);
        }
        valueSends++;
    }

    /** Sends a list screen's rows to the players who see the screen. */
    private void sendRows(ScreenRows rows) {
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        sentRows = rows;
        List<ServerPlayer> players = DisplayViewers.players(current, chunk);
        if (players.isEmpty()) {
            return;
        }
        DisplayRowsUpdate update = new DisplayRowsUpdate(host.pos(), host.side(), rows);
        for (int i = 0; i < players.size(); i++) {
            DisplaySync.send(players.get(i), update);
        }
        rowSends++;
    }

    /** Value lists of a text screen sent so far (one per change, whatever the number of viewers); for tests. */
    public int valueSends() {
        return valueSends;
    }

    /** Sends the network's newest display notices to the players who see the screen. */
    private void sendNotices(NetworkState network, long posted) {
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        noticesDue = false;
        sentNotices = posted;
        List<ServerPlayer> players = DisplayViewers.players(current, chunk);
        if (players.isEmpty()) {
            return;
        }
        DisplayNoticesUpdate update = new DisplayNoticesUpdate(host.pos(), host.side(),
                network.displayNotices().newest(DisplayView.NOTICE_LINES));
        for (int i = 0; i < players.size(); i++) {
            DisplaySync.send(players.get(i), update);
        }
        noticeSends++;
    }

    /** Notice lists sent so far (one per new notice, whatever the number of viewers); for tests. */
    public int noticeSends() {
        return noticeSends;
    }

    private static long max(NetworkState network, DisplaySourceKind source, ChartRecorder chart) {
        return switch (source) {
            case POWER_STORED -> network.energyCapacity();
            case BYTES_USED, BYTES_FREE -> network.totalBytes();
            // A chart has no maximum; the slot carries its state (recording, limit reached, nothing set).
            case HISTORY -> chart.state();
            default -> 0;
        };
    }

    private void send(int status, long value, long max, boolean force) {
        ServerLevel current = level;
        if (current == null || (!force && sentAny && status == sentStatus)) {
            return;
        }
        sentAny = true;
        sentStatus = status;
        sentValue = value;
        sentMax = max;
        List<ServerPlayer> players = DisplayViewers.players(current, chunk);
        if (players.isEmpty()) {
            return;
        }
        DisplayUpdate update = new DisplayUpdate(host.pos(), host.side(), status, value, max);
        for (int i = 0; i < players.size(); i++) {
            DisplaySync.send(players.get(i), update);
        }
        sends++;
    }

    private void sendHistory(NetworkState network, DisplaySettings settings) {
        if (settings.source() == DisplaySourceKind.HISTORY) {
            sendChart(settings.chart());
            return;
        }
        DisplayHistory history = DisplayHistory.current();
        ServerLevel current = level;
        if (history == null || current == null) {
            return;
        }
        int ticksPerSample = Math.max(1, settings.windowSeconds() * 20 / DisplayView.SAMPLES);
        historyBuffer.clear();
        history.fill(network, settings, DisplayView.SAMPLES, ticksPerSample, historyBuffer);
        if (historyBuffer.isEmpty()) {
            return;
        }
        int size = Math.min(historyBuffer.size(), DisplayView.SAMPLES);
        DisplayHistoryUpdate update = new DisplayHistoryUpdate(host.pos(), host.side(), ticksPerSample,
                new LongArrayList(historyBuffer.subList(historyBuffer.size() - size, historyBuffer.size())));
        List<ServerPlayer> players = DisplayViewers.players(current, chunk);
        for (int i = 0; i < players.size(); i++) {
            DisplaySync.send(players.get(i), update);
        }
    }

    /**
     * Sends the recorded chart to the players who see the screen; an empty one before the first sample, so a screen
     * switched to the chart does not keep showing its old graph.
     */
    private void sendChart(ChartSettings settings) {
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        int points = chart.chart(settings, chartPoints);
        int ticksPerPoint = points == 0
                ? 1
                : (int) Math.min(Integer.MAX_VALUE, (settings.windowTicks() + points - 1) / points);
        DisplayHistoryUpdate update = new DisplayHistoryUpdate(host.pos(), host.side(), ticksPerPoint,
                new LongArrayList(chartPoints, 0, points));
        List<ServerPlayer> players = DisplayViewers.players(current, chunk);
        for (int i = 0; i < players.size(); i++) {
            DisplaySync.send(players.get(i), update);
        }
        chartSends++;
    }

    /** Charts sent so far (one per sample or settings change seen, whatever the number of viewers); for tests. */
    public int chartSends() {
        return chartSends;
    }

    /** Value updates sent so far (one per change, whatever the number of viewers); for tests. */
    public int sends() {
        return sends;
    }
}
