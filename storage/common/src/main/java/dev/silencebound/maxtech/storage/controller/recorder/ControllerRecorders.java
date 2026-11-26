package dev.silencebound.maxtech.storage.controller.recorder;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.module.LedgerBook;
import dev.silencebound.maxtech.storage.controller.module.TelemetryRecorder;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The recorders built into a controller (PRD 7.2, Controller; M7R item 3): its telemetry history, its ledger and its
 * redstone alarm rules. Every controller block has one, but only a working primary's records (not a member, not a
 * controller on standby), each behind its {@code controller.recorders.*} switch; the views are the controller's History
 * tab and the terminal's history tab, each needing its research for the owner's team ({@link RecordsView}).
 *
 * <p>
 * Cost: the telemetry history is a task on the network's scheduler that samples once per {@code sampleTicks} into fixed
 * ring buffers; the ledger hears committed moves only while it records (a network with no recording ledger keeps no
 * move journal); each alarm rule sleeps until an event of its condition. With a switch off its recorder holds no
 * buffers, watches nothing and has no task. Nothing runs per tick.
 *
 * <p>
 * The state lives on the primary and moves with it: a handover gives the successor the history, the ledger and the
 * rules; a merge adds the taken-over controller's ledger rows and rules to the winner (rules past
 * {@code controller.recorders.alarmRules} go), and its history ends; a block that leaves a multiblock starts with
 * nothing. The ledger and the rules are saved with the primary; the history is not. Server thread.
 */
public final class ControllerRecorders {
    /** The most rules the config allows, and so the most a view reads. */
    public static final int MOST_RULES = 8;
    /** Rule edits a view sends: cycle the condition, then the four threshold steps, remove, add. */
    public static final int OP_CYCLE = 0;
    public static final int OP_FIRST_STEP = 1;
    public static final int OP_REMOVE = 5;
    public static final int OP_ADD = 6;
    private static final int[] STEPS = {-10, -1, 1, 10};
    /** Threshold steps. */
    public static final int STEP_COUNT = STEPS.length;
    private static final String LEDGER = "ledger";
    private static final String RULES = "alarm_rules";
    private static final String RULES_ON = "alarm_on";
    private static final Codec<LedgerBook<StackKey>> LEDGER_CODEC = LedgerBook.codec(StackKey.CODEC);

    private final ControllerBlockEntity owner;
    private final RuleHost ruleHost = new RuleHost();
    private final LedgerRecorder ledger;
    private final ObjectArrayList<AlarmWorker> rules = new ObjectArrayList<>();
    private final StorageTask telemetryTask = new TelemetryTask();
    private @Nullable TelemetryRecorder telemetry;
    /**
     * Whether the sampling task is on the owner's scheduler; scheduling it again would run it at once, so it is
     * scheduled only when it is not.
     */
    private boolean sampling;
    /** Whether any rule holds; the controller's blocks give 15 while it does. */
    private boolean output;
    /** Whether the rules' tasks are scheduled on the owner. */
    private boolean started;
    /** Whether the rules could give their signal at the last {@link #refresh}; a change makes them look again. */
    private boolean active;

    public ControllerRecorders(ControllerBlockEntity owner) {
        this.owner = owner;
        this.ledger = new LedgerRecorder(new LedgerHost());
    }

    /** The label of threshold step {@code i}: "-10", "+1" and so on. */
    public static String stepLabel(int i) {
        int step = STEPS[i];
        return step > 0 ? "+" + step : Integer.toString(step);
    }

    /** Whether the owner records now: a working primary in a server level. */
    private boolean working() {
        Level level = owner.getLevel();
        return level != null && !level.isClientSide() && owner.isPrimary() && !owner.isStandby() && !owner.isRemoved();
    }

    // Telemetry.

    /** The telemetry history, or null while the owner records none. */
    public @Nullable TelemetryRecorder telemetry() {
        return telemetry;
    }

    /**
     * Makes or drops the telemetry history: one while a working primary has {@code controller.recorders.telemetry} on
     * (a new one when the config's numbers changed, starting the history again), none otherwise; the sampling task
     * stops by itself. On loading, fills, standby changes and the load display's cadence.
     */
    public void refreshTelemetry() {
        if (!working() || !StorageServerConfig.RECORD_TELEMETRY.getAsBoolean()) {
            telemetry = null;
            return;
        }
        int sampleTicks = StorageServerConfig.TELEMETRY_SAMPLE_TICKS.getAsInt();
        int samples = StorageServerConfig.TELEMETRY_SAMPLES.getAsInt();
        int maxKeys = StorageServerConfig.TELEMETRY_MAX_KEYS.getAsInt();
        TelemetryRecorder current = telemetry;
        if (current == null || !current.matches(sampleTicks, samples, maxKeys)) {
            telemetry = new TelemetryRecorder(sampleTicks, samples, maxKeys);
        }
        if (!sampling) {
            sampling = true;
            owner.schedule(telemetryTask);
        }
    }

    /** Samples the history on its cadence; stops once there is none. */
    private final class TelemetryTask implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            TelemetryRecorder current = telemetry;
            if (current == null || !owner.isPrimary()) {
                sampling = false;
                return DONE;
            }
            current.sample(transfer.network(), gameTime);
            return current.sampleTicks();
        }

        @Override
        public boolean runsWhileShed() {
            // A history without gaps, like a display's chart.
            return true;
        }
    }

    // Ledger.

    /** The ledger; empty on a member block. */
    public LedgerRecorder ledger() {
        return ledger;
    }

    private final class LedgerHost implements LedgerRecorder.Host {
        @Override
        public boolean records() {
            return working() && StorageServerConfig.RECORD_LEDGER.getAsBoolean();
        }

        @Override
        public void ledgerChanged() {
            owner.recordsChanged();
        }

        @Override
        public long gameTime() {
            Level level = owner.getLevel();
            return level == null ? 0 : level.getGameTime();
        }
    }

    // Alarm rules.

    /** The redstone alarm rules, in order; read-only. */
    public List<AlarmWorker> rules() {
        return Collections.unmodifiableList(rules);
    }

    /** The rules' settings, in order (a new list). */
    public List<AlarmSettings> ruleSettings() {
        List<AlarmSettings> out = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            out.add(rules.get(i).settings());
        }
        return out;
    }

    /** Whether any rule holds now, so the controller's blocks give a redstone signal. */
    public boolean output() {
        return output;
    }

    /** Rules holding now. */
    public int holding() {
        int count = 0;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).output()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Adds a rule with {@code settings} at the end; false when the controller holds
     * {@code controller.recorders.alarmRules} already. The rule starts at once on a running controller.
     */
    public boolean addRule(AlarmSettings settings) {
        if (rules.size() >= Math.min(MOST_RULES, StorageServerConfig.ALARM_RULES.getAsInt())) {
            return false;
        }
        AlarmWorker worker = new AlarmWorker(ruleHost);
        worker.load(settings, false);
        rules.add(worker);
        if (started) {
            worker.start();
            worker.networkChanged(owner.network());
        }
        owner.recordsChanged();
        return true;
    }

    /**
     * Applies view edit {@code op} to rule {@code index} ({@link #OP_ADD} ignores the index); false when it changed
     * nothing. The caller checks the player may.
     */
    public boolean edit(int index, int op) {
        if (op == OP_ADD) {
            return addRule(AlarmSettings.DEFAULT);
        }
        if (index < 0 || index >= rules.size()) {
            return false;
        }
        AlarmWorker worker = rules.get(index);
        if (op == OP_REMOVE) {
            worker.stop();
            rules.remove(index);
            ruleOutputChanged();
            owner.recordsChanged();
            return true;
        }
        AlarmSettings settings = worker.settings();
        AlarmSettings next;
        if (op == OP_CYCLE) {
            next = settings.nextType();
        } else if (op >= OP_FIRST_STEP && op < OP_FIRST_STEP + STEPS.length) {
            next = settings.step(STEPS[op - OP_FIRST_STEP]);
        } else {
            return false;
        }
        if (next.equals(settings)) {
            return false;
        }
        worker.setSettings(next);
        return true;
    }

    /** What the rules need from the controller. */
    private final class RuleHost implements AlarmWorker.Host {
        @Override
        public void schedule(StorageTask task) {
            owner.schedule(task);
        }

        @Override
        public void cancel(StorageTask task) {
            owner.cancel(task);
        }

        @Override
        public void wake(StorageTask task) {
            owner.wake(task);
        }

        @Override
        public void settingsChanged() {
            owner.recordsChanged();
        }

        @Override
        public void outputChanged() {
            ruleOutputChanged();
        }

        @Override
        public boolean active() {
            return working() && StorageServerConfig.RECORD_ALARMS.getAsBoolean();
        }
    }

    /** A rule's output changed: the controller's signal follows when the any-rule output does. */
    private void ruleOutputChanged() {
        boolean now = false;
        for (int i = 0; i < rules.size(); i++) {
            now |= rules.get(i).output();
        }
        owner.recordsChanged();
        if (now != output) {
            output = now;
            owner.signalChanged();
        }
    }

    // Life cycle.

    /**
     * The owner became a working primary (loaded, or handed the state, or after a standby change): the rules' tasks are
     * scheduled and look at their network, the ledger listens and the history runs. Several calls cost one.
     */
    public void start() {
        if (!started) {
            started = true;
            for (int i = 0; i < rules.size(); i++) {
                rules.get(i).start();
            }
        }
        refresh(owner.network());
    }

    /** The owner joined {@code network} or left its network (null): the ledger and the rules move along. */
    public void networkChanged(@Nullable NetworkState network) {
        ledger.bind(network);
        for (int i = 0; started && i < rules.size(); i++) {
            rules.get(i).networkChanged(network);
        }
        refreshTelemetry();
    }

    /**
     * Brings the recorders in line with the owner's state and the config: on fills, standby changes and the load
     * display's cadence (which picks up a config reload). Cheap when nothing changed.
     */
    public void refresh(@Nullable NetworkState network) {
        ledger.bind(network);
        refreshTelemetry();
        boolean now = ruleHost.active();
        boolean changed = now != active;
        active = now;
        for (int i = 0; started && i < rules.size(); i++) {
            AlarmWorker rule = rules.get(i);
            if (rule.bound() != network) {
                rule.networkChanged(network);
            } else if (changed) {
                // Only a change: the load display's cadence calls this every few seconds.
                rule.clusterChanged();
            }
        }
    }

    /**
     * The owner stops being a working primary for good (it was merged into another controller or broken): the rules
     * stop, the ledger hears nothing and the history goes. The state stays until it is taken.
     */
    public void stop() {
        for (int i = 0; i < rules.size(); i++) {
            rules.get(i).stop();
        }
        started = false;
        ledger.bind(null);
        telemetry = null;
        owner.cancel(telemetryTask);
        sampling = false;
    }

    /**
     * Hands everything to {@code to}, the successor of a broken primary: the history (it goes on), the ledger and the
     * rules with their outputs. This one is empty afterwards. {@code to} starts once it is the primary.
     */
    public void handOver(ControllerRecorders to) {
        TelemetryRecorder history = telemetry;
        stop();
        to.telemetry = history;
        to.ledger.load(ledger.takeAll());
        to.clearRules();
        for (int i = 0; i < rules.size(); i++) {
            AlarmWorker from = rules.get(i);
            AlarmWorker worker = new AlarmWorker(to.ruleHost);
            worker.load(from.settings(), from.output());
            to.rules.add(worker);
        }
        to.output = output;
        rules.clear();
        output = false;
    }

    /**
     * This controller was taken over by {@code winner} in a merge: its ledger rows go into the winner's book and its
     * rules after the winner's, as far as there is room; its history ends. This one is empty afterwards.
     */
    public void mergeInto(ControllerRecorders winner) {
        stop();
        telemetry = null;
        winner.ledger.merge(ledger.takeAll());
        for (int i = 0; i < rules.size(); i++) {
            winner.addRule(rules.get(i).settings());
        }
        rules.clear();
        boolean had = output;
        output = false;
        if (had) {
            owner.signalChanged();
        }
    }

    /** Drops every rule (a block that leaves its multiblock starts with none). */
    private void clearRules() {
        for (int i = 0; i < rules.size(); i++) {
            rules.get(i).stop();
        }
        rules.clear();
        output = false;
    }

    /**
     * Forgets the state (this block left its multiblock or became a primary of its own): no rules, an empty ledger, no
     * history.
     */
    public void clear() {
        stop();
        clearRules();
        ledger.takeAll();
    }

    // Saving.

    /** Saves the ledger and the rules; nothing when they are empty. */
    public void save(ValueOutput output) {
        LedgerBook<StackKey> book = ledger.saved();
        if (book.rows() > 0 || book.unrecorded() > 0) {
            output.store(LEDGER, LEDGER_CODEC, book);
        }
        if (!rules.isEmpty()) {
            output.store(RULES, AlarmSettings.CODEC.listOf(), ruleSettings());
            int on = 0;
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).output()) {
                    on |= 1 << i;
                }
            }
            output.putInt(RULES_ON, on);
        }
    }

    /** Loads the ledger and the rules (not started yet: {@link #start} on load). */
    public void load(ValueInput input) {
        stop();
        ledger.load(input.read(LEDGER, LEDGER_CODEC).orElseGet(LedgerBook::new));
        rules.clear();
        List<AlarmSettings> saved = input.read(RULES, AlarmSettings.CODEC.listOf()).orElse(List.of());
        int on = input.getIntOr(RULES_ON, 0);
        for (int i = 0; i < saved.size() && i < MOST_RULES; i++) {
            AlarmWorker worker = new AlarmWorker(ruleHost);
            worker.load(saved.get(i), (on & (1 << i)) != 0);
            rules.add(worker);
        }
        output = on != 0 && !rules.isEmpty();
    }

    /** Replaces the rules with {@code settings} (placed from an item, or a snapshot restored). */
    public void setRules(List<AlarmSettings> settings) {
        if (settings.equals(ruleSettings())) {
            return;
        }
        clearRules();
        for (AlarmSettings next : settings) {
            if (!addRule(next)) {
                break;
            }
        }
        ruleOutputChanged();
        owner.recordsChanged();
    }
}
