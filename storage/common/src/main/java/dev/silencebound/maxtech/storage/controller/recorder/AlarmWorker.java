package dev.silencebound.maxtech.storage.controller.recorder;

import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.crafting.job.JobEvents;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.network.EnergyWatcher;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import org.jspecify.annotations.Nullable;

/**
 * One redstone alarm rule of a controller (PRD 7.2, Controller; the alarm module's worker before M7R item 3): it
 * watches one condition of its network and holds its output while the condition holds; the controller's blocks give a
 * redstone signal while any of its rules does. Nothing polls: the rule subscribes only to the events its condition
 * depends on (the network's stored FE from its tick, any committed change of the contents, charged operations, devices
 * joining and leaving, crafting jobs ending) and evaluates in a task on the network's scheduler, at most once per
 * {@code condition.minIntervalTicks}; further events before that cost nothing. A link's load falls with time and no
 * event says so: while it is over the threshold the rule looks again after each interval and stops once it is not. Low
 * power is only evaluated when the stored FE crosses the threshold. A rule whose host is not active (a member block, a
 * controller on standby, or {@code controller.recorders.alarms} off) gives no signal. Server thread.
 */
public final class AlarmWorker implements StorageTask, EnergyWatcher, ContentsWatcher {
    /** Ticks a finished crafting job keeps the job-finished condition met: a one-second pulse. */
    public static final int JOB_PULSE_TICKS = 20;

    /** What a rule needs from the block it runs on. Server thread. */
    public interface Host {
        /** Puts {@code task} on the network scheduler of the host's block entity. */
        void schedule(StorageTask task);

        /** Takes {@code task} off the scheduler again. */
        void cancel(StorageTask task);

        /** Runs {@code task} on the next pass. */
        void wake(StorageTask task);

        /** The rule's settings changed: the host saves them. */
        void settingsChanged();

        /** The rule's output or value changed: the host saves it and its neighbours read the new signal. */
        void outputChanged();

        /** Whether the rule may give its signal now. */
        boolean active();
    }

    private final Host host;
    private final Runnable wake = this::request;
    private final Runnable jobFinished = this::jobFinished;
    /** Game time the last crafting job of the network ended; {@code Long.MIN_VALUE} for none yet. */
    private long finishedAt = Long.MIN_VALUE;
    private AlarmSettings settings = AlarmSettings.DEFAULT;
    private boolean output;
    private boolean pending;
    private long nextEvaluation = Long.MIN_VALUE;
    private int evaluations;
    /** The last value read, for the screen. */
    private int value;
    /** Low power: which side of the threshold the stored FE was last seen on; 0 unseen, 1 met, 2 not met. */
    private byte side;
    private @Nullable NetworkState bound;

    public AlarmWorker(Host host) {
        this.host = host;
    }

    public AlarmSettings settings() {
        return settings;
    }

    /** Whether the rule's condition holds now, so the controller gives its signal. */
    public boolean output() {
        return output;
    }

    /** The last value of the watched condition, in percent. */
    public int value() {
        return value;
    }

    /** Evaluations since the rule was made; tests use it to see that nothing polls. */
    public int evaluations() {
        return evaluations;
    }

    /** Schedules the evaluation task (tasks belong to the block entity instance) and evaluates once. */
    public void start() {
        pending = false;
        host.schedule(this);
        request();
    }

    /** Stops the rule for good: its watchers go and its task leaves the scheduler. */
    public void stop() {
        unbind();
        pending = false;
        host.cancel(this);
    }

    /** The network the rule watches now, or null. */
    @Nullable
    NetworkState bound() {
        return bound;
    }

    /** The host joined {@code network} or left its network (null): the rule's watchers move along. */
    public void networkChanged(@Nullable StorageNetwork network) {
        unbind();
        NetworkState state = network instanceof NetworkState joined ? joined : null;
        bind(state);
        if (state == null) {
            evaluate();
        } else {
            pending = false;
            request();
        }
    }

    /** Replaces the settings; the rule evaluates again at its next chance. */
    public void setSettings(AlarmSettings next) {
        if (next.equals(settings)) {
            return;
        }
        NetworkState network = bound;
        unbind();
        settings = next;
        bind(network);
        host.settingsChanged();
        request();
    }

    /** The host's state changed (it went active or not): look again. */
    public void clusterChanged() {
        request();
    }

    private void bind(@Nullable NetworkState network) {
        bound = network;
        side = 0;
        if (network == null) {
            return;
        }
        switch (settings.type()) {
            case LOW_POWER -> network.watchEnergy(this);
            case STORAGE_FULL -> network.watch(this);
            case LINK_SATURATED -> network.watchLoad(wake);
            case DEVICE_LIMIT -> network.watchDevices(wake);
            case JOB_FINISHED -> JobEvents.watchFinished(network, jobFinished);
        }
    }

    private void unbind() {
        NetworkState network = bound;
        if (network != null) {
            network.unwatchEnergy(this);
            network.unwatch(this);
            network.unwatchLoad(wake);
            network.unwatchDevices(wake);
            JobEvents.unwatchFinished(network, jobFinished);
        }
        bound = null;
    }

    /** Asks for an evaluation on the network's next pass, once; events before it runs cost nothing. */
    private void request() {
        if (!pending) {
            pending = true;
            host.wake(this);
        }
    }

    private void jobFinished() {
        NetworkState network = bound;
        if (network != null) {
            finishedAt = network.level().getGameTime();
            request();
        }
    }

    @Override
    public void energyChanged(long stored, long capacity) {
        byte now = settings.met(percent(stored, capacity)) ? (byte) 1 : (byte) 2;
        if (now != side) {
            side = now;
            request();
        }
    }

    @Override
    public void changed(int id, long total) {
        request();
    }

    @Override
    public void released(int id) {
        // A released key's total was 0 already: nothing to look at.
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        if (gameTime < nextEvaluation) {
            return (int) Math.max(2, nextEvaluation - gameTime);
        }
        int interval = StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt();
        nextEvaluation = gameTime + interval;
        pending = false;
        boolean met = evaluate();
        if (settings.type() == AlarmSettings.Type.JOB_FINISHED && met) {
            // The pulse ends with time, which no event reports.
            pending = true;
            return Math.max(2, (int) (finishedAt + JOB_PULSE_TICKS - gameTime));
        }
        if (settings.type() == AlarmSettings.Type.LINK_SATURATED && met) {
            // Load falls with time, which no event reports: look again while the link is over the threshold.
            pending = true;
            return Math.max(2, interval);
        }
        return WAIT;
    }

    /** Reads the condition now and sets the output; returns whether it holds. */
    private boolean evaluate() {
        evaluations++;
        NetworkState network = bound;
        value = network == null ? 0 : (int) Math.min(100, read(network));
        boolean met = network != null && host.active() && settings.met(value);
        if (met != output) {
            output = met;
            host.outputChanged();
        }
        return met;
    }

    private long read(NetworkState network) {
        return switch (settings.type()) {
            case LOW_POWER -> percent(network.storedEnergy(), network.energyCapacity());
            case STORAGE_FULL -> percent(network.usedBytes(), network.totalBytes());
            case LINK_SATURATED -> {
                long busiest = network.busiestRootLink();
                yield busiest == Long.MIN_VALUE ? 0 : network.segmentLoad(busiest);
            }
            case DEVICE_LIMIT -> percent(network.limitedDevices(), network.deviceLimit());
            case JOB_FINISHED ->
                finishedAt != Long.MIN_VALUE && network.level().getGameTime() - finishedAt < JOB_PULSE_TICKS ? 100 : 0;
        };
    }

    private static long percent(long part, long whole) {
        // Doubles, since a creative network's stored FE times 100 would overflow a long.
        return whole <= 0 ? 0 : (long) (100.0 * part / whole);
    }

    /** Takes the settings and output from a save, a handover or an item, before the rule starts. */
    public void load(AlarmSettings saved, boolean savedOutput) {
        settings = saved;
        output = savedOutput;
    }
}
