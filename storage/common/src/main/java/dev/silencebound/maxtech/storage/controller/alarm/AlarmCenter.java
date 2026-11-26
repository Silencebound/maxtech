package dev.silencebound.maxtech.storage.controller.alarm;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobDriver;
import dev.silencebound.maxtech.storage.crafting.job.JobEvents;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.network.EnergyWatcher;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * A network's alarm panel (M7C item 16), kept by its controller core and saved with it: what needs attention (low
 * power, storage nearly full, stuck crafts, rules paused by errors, drives offline), who acknowledged each alarm and
 * when. Nothing ticks: the center listens to the events its conditions depend on (the stored FE from the network's
 * tick, committed content changes, cells turning full, devices joining and leaving, crafting jobs starting and ending,
 * Logic Controllers pausing or resuming rules) and looks again in a task on the network's scheduler, at most once per
 * {@code condition.minIntervalTicks} and only at the conditions an event touched; content changes, which a busy network
 * has every tick, look at the bytes in use at most once per {@code alarms.recheckTicks}. No event says a machine filled
 * up, so while a crafting job runs it also looks for stuck crafts every {@code alarms.recheckTicks}, and stops once
 * none runs. A held low power or storage alarm clears only {@code alarms.clearMarginPercent} past its threshold, and an
 * alarm raised again within {@code alarms.reannounceTicks} of its last raise is listed as new but not announced again,
 * so a value swinging round a threshold does not flood the log. A cleared alarm stays listed, dimmed, for
 * {@code alarms.clearedKeepTicks}; an acknowledged one comes back, unacknowledged, when it clears and happens again. A
 * raised alarm goes to the network's log (and so to its logic remotes) and, with {@code alarms.chat}, to the chat of
 * the players who may use the network. Server thread.
 */
public final class AlarmCenter implements StorageTask, EnergyWatcher, ContentsWatcher {
    /**
     * Most alarms kept per network; past it, the oldest cleared one goes, and with none cleared nothing new is kept.
     */
    public static final int MAX_ALARMS = 64;

    private static final int POWER = 1;
    private static final int STORAGE = 1 << 1;
    private static final int STUCK = 1 << 2;
    private static final int RULES = 1 << 3;
    private static final int OFFLINE = 1 << 4;
    private static final int ALL = POWER | STORAGE | STUCK | RULES | OFFLINE;
    private static final String LOW_POWER_KEY = "low_power";
    private static final String STORAGE_KEY = "storage_full";
    private static final String OFFLINE_KEY = "offline";
    private static final String STUCK_PREFIX = "stuck/";
    private static final String RULE_PREFIX = "rule/";
    /** Screens list fresh alarms first, then acknowledged ones that still hold, then cleared ones; newest first. */
    private static final Comparator<Alarm> ORDER = Comparator.comparingInt(AlarmCenter::rank)
            .thenComparing(Comparator.comparingLong(AlarmCenter::shownTime).reversed());

    private final ControllerBlockEntity core;
    private final Runnable devicesChanged = this::devicesChanged;
    private final Runnable fillChanged = this::fillChanged;
    private final Runnable jobFinished = this::jobFinished;
    private final ObjectArrayList<Alarm> alarms = new ObjectArrayList<>();
    private final ObjectArrayList<Runnable> listeners = new ObjectArrayList<>();
    private @Nullable NetworkState bound;
    private int dirty = ALL;
    private boolean pending;
    private long nextEvaluation = Long.MIN_VALUE;
    /** While crafting jobs run: the game time of the next look for stuck crafts; {@code Long.MAX_VALUE} otherwise. */
    private long nextStuckLook = Long.MAX_VALUE;
    /** Content changes look at the bytes in use no earlier than this game time ({@link #changed(int, long)}). */
    private long nextStorageLook = Long.MIN_VALUE;
    /** A content change came after the last look at the bytes in use. */
    private boolean storageOwed;
    /** Low power: which side of the threshold the stored FE was last seen on; 0 unseen, 1 low, 2 not low. */
    private byte powerSide;
    private int version;
    private int evaluations;
    private @Nullable List<Alarm> shown;

    public AlarmCenter(ControllerBlockEntity core) {
        this.core = core;
    }

    /**
     * Has crafting jobs that start or change wake the alarm panel of their network; once, during mod construction. A
     * job's end is heard per network through {@link JobEvents#watchFinished}.
     */
    public static void init() {
        AlarmTexts.register();
        JobQueue.listen(AlarmCenter::jobChanged);
    }

    private static void jobChanged(ServerLevel level, CraftingJob job) {
        JobRun run = JobQueue.progress(level, job.id());
        if (run == null) {
            return;
        }
        AlarmCenter center = of(JobDriver.network(level, run.origin()));
        if (center != null) {
            center.request(STUCK);
        }
    }

    /** The alarm panel of {@code network}: its only controller's; null without exactly one controller. */
    public static @Nullable AlarmCenter of(@Nullable NetworkState network) {
        return network != null && network.controllerDevice() instanceof ControllerBlockEntity controller
                ? controller.alarms()
                : null;
    }

    /** A Logic Controller of {@code network} paused or resumed rules: the panel looks at the rules again. */
    public static void rulesChanged(@Nullable NetworkState network) {
        AlarmCenter center = of(network);
        if (center != null) {
            center.request(RULES);
        }
    }

    // ---- reading

    /** Every alarm kept, in no order; the live list, do not change it. */
    public List<Alarm> alarms() {
        return alarms;
    }

    /**
     * The alarms in the order screens show them: fresh ones first, then acknowledged ones that still hold, then cleared
     * ones, each newest first. Built once per change.
     */
    public List<Alarm> shown() {
        List<Alarm> current = shown;
        if (current == null) {
            ArrayList<Alarm> sorted = new ArrayList<>(alarms);
            sorted.sort(ORDER);
            current = List.copyOf(sorted);
            shown = current;
        }
        return current;
    }

    private static int rank(Alarm alarm) {
        return alarm.fresh() ? 0 : alarm.active() ? 1 : 2;
    }

    private static long shownTime(Alarm alarm) {
        return alarm.active() ? alarm.raised() : alarm.cleared();
    }

    /** Alarms that hold and nobody acknowledged. */
    public int freshCount() {
        int count = 0;
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).fresh()) {
                count++;
            }
        }
        return count;
    }

    /** Alarms that hold. */
    public int activeCount() {
        int count = 0;
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).active()) {
                count++;
            }
        }
        return count;
    }

    /** Goes up with every change of the alarms; screens send them again when it moved. */
    public int version() {
        return version;
    }

    /** Looks since the center was made; tests use it to see that nothing polls. */
    public int evaluations() {
        return evaluations;
    }

    /**
     * Runs {@code listener} after every change of the alarms; it must only mark work. Adding one twice adds it once.
     */
    @SuppressWarnings("ReferenceEquality") // a listener is one object
    public void listen(Runnable listener) {
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i) == listener) {
                return;
            }
        }
        listeners.add(listener);
    }

    /** Stops running {@code listener}; nothing happens if it was not listening. */
    public void unlisten(Runnable listener) {
        listeners.remove(listener);
    }

    // ---- acknowledging

    /**
     * {@code by} acknowledged the alarm with {@code key} at {@code now}; returns false when there is no such alarm, it
     * no longer holds, or someone acknowledged it already. The caller checks the player's access.
     */
    public boolean acknowledge(String key, String by, long now) {
        for (int i = 0; i < alarms.size(); i++) {
            Alarm alarm = alarms.get(i);
            if (alarm.key().equals(key)) {
                if (!alarm.fresh()) {
                    return false;
                }
                alarms.set(i, alarm.acknowledge(by, now));
                changed();
                return true;
            }
        }
        return false;
    }

    // ---- lifecycle

    /** Looks at every condition on the network's next pass; for tests and after loading alarms by hand. */
    public void recheck() {
        request(ALL);
    }

    /** Schedules the task (tasks belong to the block entity instance) and looks at everything once. */
    public void start() {
        core.schedule(this);
        pending = false;
        request(ALL);
    }

    /** The core joined {@code network} or left its network (null): the watchers move along. */
    public void networkChanged(@Nullable NetworkState network) {
        unbind();
        bound = network;
        powerSide = 0;
        if (network != null) {
            network.watchEnergy(this);
            network.watch(this);
            network.watchFill(fillChanged);
            network.watchDevices(devicesChanged);
            JobEvents.watchFinished(network, jobFinished);
            pending = false;
            request(ALL);
        }
    }

    private void unbind() {
        NetworkState network = bound;
        if (network != null) {
            network.unwatchEnergy(this);
            network.unwatch(this);
            network.unwatchFill(fillChanged);
            network.unwatchDevices(devicesChanged);
            JobEvents.unwatchFinished(network, jobFinished);
        }
        bound = null;
    }

    /** Marks {@code bits} to look at on the network's next pass; events before it runs cost nothing. */
    private void request(int bits) {
        dirty |= bits;
        if (!pending) {
            pending = true;
            core.wake(this);
        }
    }

    private void devicesChanged() {
        request(RULES | OFFLINE | STORAGE | POWER);
    }

    private void fillChanged() {
        request(STORAGE);
    }

    private void jobFinished() {
        request(STUCK);
    }

    /**
     * The stored FE as a band: 1 at or below the threshold, 2 inside the clear margin above it, 3 above that. A wake
     * per band change is enough to raise low power at the threshold and clear it only past the margin.
     */
    @Override
    public void energyChanged(long stored, long capacity) {
        long percent = percent(stored, capacity);
        int threshold = StorageServerConfig.ALARM_LOW_POWER_PERCENT.getAsInt();
        byte now = capacity <= 0
                ? (byte) 3
                : percent <= threshold
                        ? (byte) 1
                        : percent <= threshold + StorageServerConfig.ALARM_CLEAR_MARGIN.getAsInt()
                                ? (byte) 2
                                : (byte) 3;
        if (now != powerSide) {
            powerSide = now;
            request(POWER);
        }
    }

    /**
     * A committed change of the contents. A busy network changes them every tick, so the bytes in use are looked at
     * once per {@code alarms.recheckTicks} at most: a change inside that time is owed a look, taken by the next change
     * or event after it, or by the controller's load refresh ({@link #loadRefresh}) if none comes. Costs a flag check,
     * or a game time read while a look is owed.
     */
    @Override
    public void changed(int id, long total) {
        if (storageOwed && pending) {
            return;
        }
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        storageOwed = true;
        if (network.level().getGameTime() >= nextStorageLook) {
            request(STORAGE);
        }
    }

    @Override
    public void released(int id) {
        // A released key's total was 0 already: the bytes in use did not change.
    }

    /**
     * The controller's load refresh, every few seconds while the network ticks (work the core does anyway): takes a
     * look at the bytes in use that a content change is owed, once it is due. A flag check otherwise.
     */
    public void loadRefresh(long gameTime) {
        if (storageOwed && !pending && gameTime >= nextStorageLook) {
            request(STORAGE);
        }
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        if (gameTime < nextEvaluation) {
            return (int) Math.max(2, nextEvaluation - gameTime);
        }
        pending = false;
        NetworkState network = bound;
        if (network == null || core.isIdle() || core.isStandby()) {
            // Kept as they are until the core runs a network again, so a rebuild does not raise them anew.
            return WAIT;
        }
        if (gameTime >= nextStuckLook) {
            dirty |= STUCK;
        }
        if (storageOwed && gameTime >= nextStorageLook) {
            dirty |= STORAGE;
        }
        int bits = dirty;
        dirty = 0;
        nextEvaluation = gameTime + StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt();
        if (bits != 0 && evaluate(network, bits, gameTime)) {
            changed();
        }
        if (prune(gameTime)) {
            changed();
        }
        long next = nextStuckLook;
        long expiry = nextExpiry();
        if (expiry < next) {
            next = expiry;
        }
        return next == Long.MAX_VALUE ? WAIT : (int) Math.clamp(next - gameTime, 2L, Integer.MAX_VALUE);
    }

    // ---- looking

    /** Looks at the conditions in {@code bits}; returns whether an alarm changed. */
    private boolean evaluate(NetworkState network, int bits, long now) {
        evaluations++;
        boolean changed = false;
        if ((bits & POWER) != 0) {
            // Hysteresis: a held alarm clears only past the margin, so a value swinging round the threshold does
            // not raise it again and again.
            int margin = active(LOW_POWER_KEY) ? StorageServerConfig.ALARM_CLEAR_MARGIN.getAsInt() : 0;
            long capacity = network.energyCapacity();
            boolean low = capacity > 0 && percent(network.storedEnergy(),
                    capacity) <= StorageServerConfig.ALARM_LOW_POWER_PERCENT.getAsInt() + margin;
            changed |= single(network, AlarmKind.LOW_POWER, LOW_POWER_KEY,
                    low
                            ? Component.translatable(AlarmTexts.LOW_POWER,
                                    StorageServerConfig.ALARM_LOW_POWER_PERCENT.getAsInt())
                            : null,
                    now);
        }
        if ((bits & STORAGE) != 0) {
            storageOwed = false;
            nextStorageLook = now + StorageServerConfig.ALARM_RECHECK_TICKS.getAsInt();
            long total = network.totalBytes();
            int threshold = StorageServerConfig.ALARM_STORAGE_FULL_PERCENT.getAsInt();
            int margin = active(STORAGE_KEY) ? StorageServerConfig.ALARM_CLEAR_MARGIN.getAsInt() : 0;
            boolean full = total > 0 && percent(network.usedBytes(), total) >= threshold - margin;
            changed |= single(network, AlarmKind.STORAGE_FULL, STORAGE_KEY,
                    full ? Component.translatable(AlarmTexts.STORAGE_FULL, threshold) : null, now);
        }
        if ((bits & OFFLINE) != 0) {
            int cells = network.offlineCells();
            changed |= single(network, AlarmKind.OFFLINE, OFFLINE_KEY,
                    cells > 0 ? Component.translatable(AlarmTexts.OFFLINE, cells) : null, now);
        }
        if ((bits & STUCK) != 0) {
            changed |= stuck(network, now);
        }
        if ((bits & RULES) != 0) {
            changed |= rules(network, now);
        }
        return changed;
    }

    /** Whether the alarm with {@code key} is kept and holds. */
    private boolean active(String key) {
        int index = indexOf(key);
        return index >= 0 && alarms.get(index).active();
    }

    private static long percent(long part, long whole) {
        // Doubles, since a creative network's stored FE times 100 would overflow a long.
        return whole <= 0 ? 0 : (long) (100.0 * part / whole);
    }

    /** The stuck crafts of the running jobs, each provider and reason once; schedules the next look while jobs run. */
    private boolean stuck(NetworkState network, long now) {
        List<CraftingJob> jobs = JobQueue.jobsOn(network);
        Set<String> found = new ObjectOpenHashSet<>();
        boolean changed = false;
        boolean running = false;
        for (int j = 0; j < jobs.size(); j++) {
            CraftingJob job = jobs.get(j);
            // A job stopped partial still waits on its stuck provider until resumed; only running ones are rechecked
            // on a timer, a partial one is looked at again when a job event comes.
            if (job.state() != JobState.RUNNING && job.state() != JobState.PARTIAL) {
                continue;
            }
            running |= job.state() == JobState.RUNNING;
            List<RequestPayloads.Stuck> stuck = CraftingSession.stuck(network, job,
                    JobQueue.progress(network.level(), job.id()));
            for (int s = 0; s < stuck.size(); s++) {
                RequestPayloads.Stuck one = stuck.get(s);
                if (one.reason().lock()) {
                    // A lock mode the player chose (B09 item 26) is not a fault; the job view still names it.
                    continue;
                }
                String key = STUCK_PREFIX + one.pos().asLong() + "/" + one.reason().name();
                if (found.add(key)) {
                    changed |= holds(network, AlarmKind.CRAFT_STUCK, key,
                            Component.translatable(AlarmTexts.CRAFT_STUCK, PatternItem.name(one.key()),
                                    position(one.pos()), Component.translatable(one.reason().translationKey())),
                            now);
                }
            }
        }
        nextStuckLook = running ? now + StorageServerConfig.ALARM_RECHECK_TICKS.getAsInt() : Long.MAX_VALUE;
        boolean cleared = clearOthers(AlarmKind.CRAFT_STUCK, found, now);
        return cleared || changed;
    }

    /** One alarm per Logic Controller of the network with rules paused by errors. */
    private boolean rules(NetworkState network, long now) {
        Set<String> found = new ObjectOpenHashSet<>();
        boolean changed = false;
        for (NetworkDevice device : network.devices()) {
            if (!(device instanceof LogicControllerBlockEntity controller)) {
                continue;
            }
            int paused = controller.worker().pausedRules();
            if (paused <= 0) {
                continue;
            }
            BlockPos pos = controller.getBlockPos();
            String key = RULE_PREFIX + pos.asLong();
            found.add(key);
            Component name = controller.label().isEmpty()
                    ? Component.translatable(AlarmTexts.RULE_ERROR_POS, position(pos))
                    : controller.label().component();
            changed |= holds(network, AlarmKind.RULE_ERROR, key,
                    Component.translatable(AlarmTexts.RULE_ERROR, name, paused), now);
        }
        boolean cleared = clearOthers(AlarmKind.RULE_ERROR, found, now);
        return cleared || changed;
    }

    private static String position(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    // ---- keeping

    /** An alarm with one key: raised (or kept) with {@code text}, or cleared when {@code text} is null. */
    private boolean single(NetworkState network, AlarmKind kind, String key, @Nullable Component text, long now) {
        if (text != null) {
            return holds(network, kind, key, text, now);
        }
        int index = indexOf(key);
        if (index >= 0 && alarms.get(index).active()) {
            alarms.set(index, alarms.get(index).clear(now));
            return true;
        }
        return false;
    }

    /**
     * The condition {@code key} holds now: a new alarm, or one that had cleared raised again (and no longer
     * acknowledged), is announced; one that still held only takes the new text.
     */
    private boolean holds(NetworkState network, AlarmKind kind, String key, Component text, long now) {
        int index = indexOf(key);
        if (index >= 0) {
            Alarm alarm = alarms.get(index);
            if (alarm.active()) {
                if (alarm.text().equals(text)) {
                    return false;
                }
                alarms.set(index, alarm.withText(text));
                return true;
            }
            alarms.set(index, Alarm.raise(key, kind, text, now));
            if (now - alarm.raised() < StorageServerConfig.ALARM_REANNOUNCE_TICKS.getAsInt()) {
                // Raised again soon after the last time: listed as new, but not announced again.
                return true;
            }
        } else {
            if (alarms.size() >= MAX_ALARMS && !dropOldestCleared()) {
                return false;
            }
            alarms.add(Alarm.raise(key, kind, text, now));
        }
        announce(network, kind, text, now);
        return true;
    }

    /** Clears every alarm of {@code kind} that still holds and whose key is not in {@code keep}. */
    private boolean clearOthers(AlarmKind kind, Set<String> keep, long now) {
        boolean changed = false;
        for (int i = 0; i < alarms.size(); i++) {
            Alarm alarm = alarms.get(i);
            if (alarm.kind() == kind.id() && alarm.active() && !keep.contains(alarm.key())) {
                alarms.set(i, alarm.clear(now));
                changed = true;
            }
        }
        return changed;
    }

    private int indexOf(String key) {
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private boolean dropOldestCleared() {
        int oldest = -1;
        for (int i = 0; i < alarms.size(); i++) {
            Alarm alarm = alarms.get(i);
            if (!alarm.active() && (oldest < 0 || alarm.cleared() < alarms.get(oldest).cleared())) {
                oldest = i;
            }
        }
        if (oldest < 0) {
            return false;
        }
        alarms.remove(oldest);
        return true;
    }

    /** Drops cleared alarms listed for {@code alarms.clearedKeepTicks}; returns whether one went. */
    private boolean prune(long now) {
        int keep = StorageServerConfig.ALARM_CLEARED_KEEP_TICKS.getAsInt();
        boolean removed = false;
        for (int i = alarms.size() - 1; i >= 0; i--) {
            Alarm alarm = alarms.get(i);
            if (!alarm.active() && now - alarm.cleared() >= keep) {
                alarms.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    /** The game time the next cleared alarm goes, or {@code Long.MAX_VALUE} with none. */
    private long nextExpiry() {
        long keep = StorageServerConfig.ALARM_CLEARED_KEEP_TICKS.getAsInt();
        long next = Long.MAX_VALUE;
        for (int i = 0; i < alarms.size(); i++) {
            Alarm alarm = alarms.get(i);
            if (!alarm.active()) {
                next = Math.min(next, alarm.cleared() + keep);
            }
        }
        return next;
    }

    /** A raised alarm goes to the network's log (logic remotes hear it) and, with the config, to chat. */
    private static void announce(NetworkState network, AlarmKind kind, Component text, long now) {
        network.notices().post(new NetworkNotices.Notice(now, AlarmTexts.NOTICE, "", kind.translationKey()));
        if (!StorageServerConfig.ALARM_CHAT.getAsBoolean()) {
            return;
        }
        Component line = Component.translatable(AlarmTexts.CHAT, network.name(), text);
        List<ServerPlayer> players = network.level().getServer().getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            if (network.canAccess(player)) {
                player.sendSystemMessage(line);
            }
        }
    }

    private void changed() {
        version++;
        shown = null;
        core.setChanged();
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).run();
        }
    }

    // ---- saving

    /** Takes the alarms from a save, before the core starts; they are looked at again once it runs a network. */
    public void load(List<Alarm> saved) {
        alarms.clear();
        for (int i = 0; i < saved.size() && alarms.size() < MAX_ALARMS; i++) {
            if (indexOf(saved.get(i).key()) < 0) {
                alarms.add(saved.get(i));
            }
        }
        shown = null;
        version++;
    }
}
