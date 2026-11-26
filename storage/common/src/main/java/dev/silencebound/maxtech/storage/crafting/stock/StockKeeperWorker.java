package dev.silencebound.maxtech.storage.crafting.stock;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.job.CraftingMode;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.display.production.ProductionStats;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * What a stock keeper does (PRD 7.2, Stock keeping): it keeps its rules' keys stocked by submitting background crafts
 * to {@link BackgroundCrafts}. Nothing polls. The keeper listens to each rule's key ({@link KeyListener}) and asks for
 * an evaluation only when a rule may need a job: its key fell below the restart threshold, or it is filling and has no
 * job out. A rule has at most one job out at a time; when it ends the keeper looks again, so a large shortfall is made
 * in batches one after the other. A refused or failed request waits {@code crafting.stockRetryTicks} before the rule
 * tries again (the only timer, and only while a rule is short and cannot get a job). Rules submit in priority order,
 * highest first.
 *
 * <p>
 * The keeper requests nothing unless its owner's team has researched {@link ResearchGates#STOCK_KEEPER}; completed
 * research wakes every keeper. In classic mode ({@code crafting.autocraftingMode = classic}) a keeper does nothing; it
 * looks again on its next event after the mode changes back. Jobs that are out are not saved: after a restart a filling
 * rule submits again.
 *
 * <p>
 * Logic (M7L2 chunk 15) reads this state through {@link Listener}s: a rule's settings, whether it is filling, whether
 * it has a job out, and whether it is short ({@link #isShort}: its last request was refused or its last job ended
 * without everything, until a job ends done or the stock is reached). Listeners are told after a change, never per
 * tick. Server thread only.
 */
public final class StockKeeperWorker implements StorageTask, KeyListener {
    /** The most rules a keeper holds; {@code crafting.stockKeeperRules} says how many of them the GUI offers. */
    public static final int MAX_RULES = 16;
    private static final String RULES = "rules";
    private static final String FILLING = "filling";
    private static final String SHORT = "short";
    private static final ObjectArrayList<StockKeeperWorker> STARTED = new ObjectArrayList<>();
    private static final int SWEEP_EVERY = 64;
    private static int startsSinceSweep;

    /** What the worker needs from the block that holds it. */
    public interface Host {
        /** The level the keeper is in; null before it is placed. */
        @Nullable
        ServerLevel serverLevel();

        /** Whether the keeper gets a redstone signal. */
        boolean powered();

        /** The rules changed: save and sync. */
        void rulesChanged();
    }

    /**
     * Told after a rule, its filling or short state, or its job out changed; reads the keeper's state then. Must not
     * change the keeper from inside the call.
     */
    public interface Listener {
        void stockChanged(StockKeeperWorker keeper);
    }

    private final NetworkDevice device;
    private final Host host;
    private final StockRule[] rules = new StockRule[MAX_RULES];
    private final boolean[] filling = new boolean[MAX_RULES];
    private final boolean[] outstanding = new boolean[MAX_RULES];
    /** The rule's last request was refused or its last job ended without everything; saved. */
    private final boolean[] shortOf = new boolean[MAX_RULES];
    private final ObjectArrayList<Listener> listeners = new ObjectArrayList<>(1);
    private final long[] retryAt = new long[MAX_RULES];
    /** Bumped when a rule changes, so the end of a job submitted for the old rule does not touch the new one. */
    private final int[] generation = new int[MAX_RULES];
    private final int[] order = new int[MAX_RULES];
    private boolean pending;
    private boolean started;
    private int evaluations;
    private int submitted;

    public StockKeeperWorker(NetworkDevice device, Host host) {
        this.device = device;
        this.host = host;
        Arrays.fill(rules, StockRule.EMPTY);
    }

    /** The rules a keeper offers now ({@code crafting.stockKeeperRules}). */
    public static int ruleSlots() {
        return Math.clamp(StorageServerConfig.CRAFTING_STOCK_KEEPER_RULES.getAsInt(), 1, MAX_RULES);
    }

    public StockRule rule(int index) {
        return rules[index];
    }

    /** Every rule, used or not, in slot order. */
    public List<StockRule> rules() {
        return List.of(rules);
    }

    /** Whether rule {@code index} is filling: it fell below its restart threshold and has not reached keep since. */
    public boolean filling(int index) {
        return filling[index];
    }

    /** Whether rule {@code index} has a job out. */
    public boolean outstanding(int index) {
        return outstanding[index];
    }

    /**
     * Whether rule {@code index} cannot be satisfied now: its last request was refused (nothing can craft it, the queue
     * is full) or its last job ended partial or failed (something missing). Cleared when a job ends done, the stock
     * reaches the keep amount, or the rule changes item or is switched off. A job waiting for a CPU is not short.
     */
    public boolean isShort(int index) {
        return shortOf[index];
    }

    /** Tells {@code listener} of every change from now on; once per listener. */
    public void listen(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unlisten(Listener listener) {
        listeners.remove(listener);
    }

    private void changed() {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stockChanged(this);
        }
    }

    private void setShort(int index, boolean value) {
        if (shortOf[index] != value) {
            shortOf[index] = value;
            host.rulesChanged();
        }
    }

    /** Evaluations since the keeper was made; tests use it to see that nothing polls. */
    public int evaluations() {
        return evaluations;
    }

    /** Requests the keeper's queue took since it was made. */
    public int submitted() {
        return submitted;
    }

    /** Starts listening and evaluates once. Call when the keeper is in a loaded level. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        if (++startsSinceSweep >= SWEEP_EVERY) {
            startsSinceSweep = 0;
            sweep();
        }
        STARTED.add(this);
        for (int i = 0; i < MAX_RULES; i++) {
            listen(rules[i]);
        }
        device.deviceLink().schedule(this);
        request();
    }

    /** Replaces rule {@code index}; its job, if one is out, keeps running but no longer counts for the rule. */
    public void setRule(int index, StockRule rule) {
        StockRule old = rules[index];
        if (old.equals(rule)) {
            return;
        }
        if (started) {
            unlisten(index, old);
        }
        rules[index] = rule;
        if (!old.key().equals(rule.key())) {
            filling[index] = false;
            outstanding[index] = false;
            generation[index]++;
        }
        if (!old.key().equals(rule.key()) || rule.switchedOff()) {
            shortOf[index] = false;
        }
        retryAt[index] = 0;
        if (started) {
            listen(rule);
        }
        host.rulesChanged();
        changed();
        request();
    }

    /** The redstone input or the owner changed, or the keeper joined another network: look again. */
    public void inputsChanged() {
        if (started) {
            request();
        }
    }

    /** The keeper joined {@code network} or left its network: jobs out keep running, a filling rule asks again. */
    public void networkChanged(@Nullable StorageNetwork network) {
        if (network == null) {
            Arrays.fill(outstanding, false);
            for (int i = 0; i < MAX_RULES; i++) {
                generation[i]++;
            }
            changed();
        }
        inputsChanged();
    }

    private void listen(StockRule rule) {
        if (rule.key().isPresent()) {
            device.deviceLink().listen(rule.key().get(), this);
        }
    }

    private void unlisten(int index, StockRule rule) {
        if (rule.key().isPresent()) {
            StackKey key = rule.key().get();
            for (int i = 0; i < MAX_RULES; i++) {
                if (i != index && rules[i].key().isPresent() && rules[i].key().get().equals(key)) {
                    return; // another rule still listens to it
                }
            }
            device.deviceLink().unlisten(key, this);
        }
    }

    private void request() {
        if (!pending) {
            pending = true;
            device.deviceLink().wake(this);
        }
    }

    @Override
    public void keyChanged(StackKey key, long total) {
        boolean changed = false;
        for (int i = 0; i < MAX_RULES; i++) {
            StockRule rule = rules[i];
            if (!rule.active() || !rule.key().get().equals(key)) {
                continue;
            }
            if (total >= rule.keep()) {
                changed |= filling[i] || shortOf[i];
                filling[i] = false;
                setShort(i, false);
            } else if (!outstanding[i] && (filling[i] || rule.shouldStart(total))) {
                request();
            }
        }
        if (changed) {
            changed();
        }
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        pending = false;
        evaluations++;
        ServerLevel level = host.serverLevel();
        NetworkState network = transfer.network();
        if (level == null || !CraftingMode.current().extended()
                || !ResearchUnlocks.isUnlocked(level, device.ownerRecord(), ResearchGates.STOCK_KEEPER)) {
            return WAIT;
        }
        boolean powered = host.powered();
        int slots = ruleSlots();
        sortByPriority(slots);
        long soonest = Long.MAX_VALUE;
        boolean changed = false;
        for (int n = 0; n < slots; n++) {
            int i = order[n];
            StockRule rule = rules[i];
            if (!rule.active()) {
                continue;
            }
            long stored = network.contents().amount(rule.key().get());
            if (stored >= rule.keep()) {
                changed |= filling[i] || shortOf[i];
                filling[i] = false;
                setShort(i, false);
                continue;
            }
            if (!filling[i] && rule.shouldStart(stored)) {
                filling[i] = true;
                changed = true;
                host.rulesChanged();
            }
            if (!filling[i] || outstanding[i] || !rule.enabled(powered)) {
                continue;
            }
            if (gameTime < retryAt[i]) {
                soonest = Math.min(soonest, retryAt[i]);
                continue;
            }
            submit(network, i, rule, stored);
            changed = true;
            if (!outstanding[i]) {
                soonest = Math.min(soonest, retryAt[i]);
            }
        }
        if (changed) {
            changed();
        }
        return soonest == Long.MAX_VALUE ? WAIT : (int) Math.clamp(soonest - gameTime, 2, Integer.MAX_VALUE);
    }

    private void submit(NetworkState network, int index, StockRule rule, long stored) {
        long amount = rule.request(stored);
        if (amount <= 0) {
            return;
        }
        int gen = generation[index];
        BackgroundCrafting.Request request = new BackgroundCrafting.Request(rule.key().get(), amount, rule.priority(),
                rule.cpuTier(), device.ownerRecord());
        outstanding[index] = true;
        boolean taken = BackgroundCrafts.get().submit(network, request, outcome -> {
            if (outcome == BackgroundCrafting.Outcome.DONE) {
                ProductionStats.of(network).stocked(device.nodePos(), amount);
            }
            finished(index, gen, outcome);
        });
        if (taken) {
            submitted++;
        } else {
            outstanding[index] = false;
            retryAt[index] = now() + StorageServerConfig.CRAFTING_STOCK_RETRY_TICKS.getAsInt();
            setShort(index, true);
        }
    }

    private void finished(int index, int gen, BackgroundCrafting.Outcome outcome) {
        if (generation[index] != gen) {
            return;
        }
        outstanding[index] = false;
        if (outcome != BackgroundCrafting.Outcome.DONE) {
            retryAt[index] = now() + StorageServerConfig.CRAFTING_STOCK_RETRY_TICKS.getAsInt();
        }
        // A player's cancel says nothing about whether the rule can be satisfied, and a switched-off rule is not
        // trying: an old job ending without everything after the switch-off does not make it short.
        if (outcome == BackgroundCrafting.Outcome.DONE) {
            setShort(index, false);
        } else if (outcome != BackgroundCrafting.Outcome.CANCELLED && !rules[index].switchedOff()) {
            setShort(index, true);
        }
        changed();
        if (started && !device.isRemoved()) {
            request();
        }
    }

    private long now() {
        ServerLevel level = host.serverLevel();
        return level == null ? 0 : level.getGameTime();
    }

    /** Fills {@link #order} with the first {@code slots} rule indices, highest priority first, ties in slot order. */
    private void sortByPriority(int slots) {
        for (int i = 0; i < slots; i++) {
            int index = i;
            int j = i;
            while (j > 0 && rules[order[j - 1]].priority() < rules[index].priority()) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = index;
        }
    }

    /** The device that holds the keeper. */
    public NetworkDevice device() {
        return device;
    }

    /**
     * The started keepers in {@code network}, in no particular order, for the terminal's stock tab. Walks every started
     * keeper; called on a player's request, not per tick.
     */
    public static List<StockKeeperWorker> keepersOn(NetworkState network) {
        List<StockKeeperWorker> found = new ArrayList<>();
        for (int i = 0; i < STARTED.size(); i++) {
            StockKeeperWorker worker = STARTED.get(i);
            if (!worker.device.isRemoved() && worker.device.deviceLink().network() == network) {
                found.add(worker);
            }
        }
        return found;
    }

    /**
     * {@code team} completed {@code node}: the keepers it can start look again at their next chance. Only the stock
     * keeper unlock gates a keeper (a refused request retries on its own timer), so only keepers owned by a member of
     * {@code team} wake, and only when the node grants that unlock; other keepers stay asleep.
     */
    static void researchCompleted(MinecraftServer server, ResearchTeam team, ResearchTree.Entry node) {
        if (!node.grants(ResearchGates.STOCK_KEEPER)) {
            return;
        }
        sweep();
        for (int i = 0; i < STARTED.size(); i++) {
            StockKeeperWorker worker = STARTED.get(i);
            // Unowned keepers read every unlock as granted (ResearchUnlocks), so no completion changes them.
            OwnerRecord owner = worker.device.ownerRecord();
            if (owner.isOwned() && Research.inTeam(server, owner, team)) {
                worker.request();
            }
        }
    }

    private static void sweep() {
        for (int i = STARTED.size() - 1; i >= 0; i--) {
            if (STARTED.get(i).device.isRemoved()) {
                STARTED.remove(i);
            }
        }
    }

    /** Forgets every keeper when the server stops. */
    static void clear() {
        STARTED.clear();
    }

    public void save(ValueOutput out) {
        out.store(RULES, StockRule.CODEC.listOf(), List.of(rules));
        int mask = 0;
        for (int i = 0; i < MAX_RULES; i++) {
            if (filling[i]) {
                mask |= 1 << i;
            }
        }
        out.putInt(FILLING, mask);
        int shortMask = 0;
        for (int i = 0; i < MAX_RULES; i++) {
            if (shortOf[i]) {
                shortMask |= 1 << i;
            }
        }
        out.putInt(SHORT, shortMask);
    }

    public void load(ValueInput in) {
        loadRules(in.read(RULES, StockRule.CODEC.listOf()).orElse(List.of()));
        int mask = in.getIntOr(FILLING, 0);
        int shortMask = in.getIntOr(SHORT, 0);
        for (int i = 0; i < MAX_RULES; i++) {
            filling[i] = (mask & (1 << i)) != 0;
            shortOf[i] = (shortMask & (1 << i)) != 0;
        }
    }

    /** Takes rules from an item or a save without saving or evaluating; before the keeper starts. */
    public void loadRules(List<StockRule> loaded) {
        for (int i = 0; i < MAX_RULES; i++) {
            rules[i] = i < loaded.size() ? loaded.get(i) : StockRule.EMPTY;
        }
    }

    /** Replaces every rule (from an item): rules past the list's end are cleared. */
    public void setRules(List<StockRule> replaced) {
        for (int i = 0; i < MAX_RULES; i++) {
            setRule(i, i < replaced.size() ? replaced.get(i) : StockRule.EMPTY);
        }
    }

    /** The used rules, for the item and its tooltip: the list up to the last rule that names a key. */
    public List<StockRule> usedRules() {
        int last = -1;
        for (int i = 0; i < MAX_RULES; i++) {
            if (rules[i].key().isPresent()) {
                last = i;
            }
        }
        List<StockRule> used = new ArrayList<>(last + 1);
        for (int i = 0; i <= last; i++) {
            used.add(rules[i]);
        }
        return used;
    }

    /** The key rule {@code index} keeps, for the GUI. */
    public Optional<StackKey> key(int index) {
        return rules[index].key();
    }
}
