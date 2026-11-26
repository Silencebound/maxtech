package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorDispatch;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternReturns;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternTable;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDispatcher;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStackTemplate;
// PLATFORM-LEAK: every network move runs in NeoForge's transfer transaction (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Works one running job a step further (M7c area A): the job start takes what the plan needs from storage, then each
 * run pays the CPU's FE for its active lanes, sends what the CPU's buffer allows, from the leaves up, to a provider
 * that has the step's pattern, and finishes the job once every step's output came back. Outputs are counted by
 * {@link CraftingOutputs}. Called by a CPU's task on its network's scheduler, never per tick; server thread.
 *
 * <p>
 * The CPU's own moves (the start's extraction, the delivery) are instant, like a player's: they pay the network's FE
 * per operation, and the items never travel a cable to a device. The dispatch to a provider is the provider's business.
 */
public final class JobDriver {
    /** Ticks between runs while steps are out or can be sent; a performance bound, not a balance value. */
    public static final int RUN_TICKS = 10;
    /** Ticks a stalled CPU waits before it looks for FE again. */
    public static final int STALL_TICKS = 20;
    /**
     * Ticks without any output before a job whose only open steps have chance outputs counts as stuck: a chance step
     * cannot say whether more will come.
     */
    static final int CHANCE_PATIENCE_TICKS = 600;
    /** Run again when woken; nothing to do until an output comes back. */
    public static final int WAIT = -1;
    public static final int DONE = -10;
    public static final int PARTIAL = -11;
    public static final int CANCELLED = -12;
    /** {@link #start}: every input is taken or reported missing. */
    static final int START_TAKEN = 0;
    /** {@link #start}: the network's FE ran out; the rest of the inputs waits in {@code need} for the next run. */
    static final int START_WAITING = 1;
    /** {@link #start}: classic mode and something is missing; this run took nothing. */
    static final int START_REFUSED = 2;

    /** What the CPU allows. */
    public record Limits(int lanes, int batch, long muPerLane) {
    }

    private final Object2LongOpenHashMap<StackKey> inputs = new Object2LongOpenHashMap<>();
    private long seenVersion = -1;
    /** Set by a dispatch pass when a step had inputs but its providers were locked (B09 item 26). */
    private boolean lockHeld;

    /** The network a job's storage belongs to, or null while its origin node is in none (unloaded). */
    public static @Nullable NetworkState network(ServerLevel level, long origin) {
        return StorageNetworks.network(level, StorageNetworks.ID, BlockPos.of(origin));
    }

    /**
     * One run: returns the ticks until the next one, {@link #WAIT}, or {@link #DONE}, {@link #PARTIAL} or
     * {@link #CANCELLED} when the job ended (the caller tells the queue).
     */
    public int run(ServerLevel level, CraftingJob job, JobRun run, Limits limits, @Nullable NetworkState cpuNetwork,
            long gameTime) {
        run.lockHeld = false;
        NetworkState network = network(level, run.origin);
        if (network == null) {
            network = cpuNetwork;
        }
        if (network == null) {
            return STALL_TICKS;
        }
        run.claimNetworks.clear();
        run.claimNetworks.add(network);
        if (cpuNetwork != null && cpuNetwork != network) {
            run.claimNetworks.add(cpuNetwork);
        }
        List<NetworkState> linked = network.linkedNetworks();
        for (int i = 0; i < linked.size(); i++) {
            if (!run.claimNetworks.contains(linked.get(i))) {
                run.claimNetworks.add(linked.get(i));
            }
        }
        if (run.version() != seenVersion) {
            seenVersion = run.version();
            run.lastProgress = gameTime;
        }
        // Power first: an unpowered CPU or network stalls (C5) rather than reporting everything missing.
        boolean wasStalled = run.stalled;
        boolean paid = pay(run, limits, cpuNetwork, network, gameTime);
        if (wasStalled != run.stalled) {
            JobNotices.changed(level, job, run);
        }
        if (!paid) {
            return STALL_TICKS;
        }
        if (!run.started) {
            int taken = start(network, run);
            if (taken == START_REFUSED) {
                return CANCELLED;
            }
            if (taken == START_WAITING) {
                // Out of FE part way: the rest of the inputs comes in the next runs, so the buffer never limits a
                // job's size (M7E). Shown as stalled; the next payment clears it.
                if (!run.stalled) {
                    run.stalled = true;
                    run.changed();
                    JobNotices.changed(level, job, run);
                }
                return STALL_TICKS;
            }
            run.started = true;
            run.lastProgress = gameTime;
            run.changed();
        }
        pullCounted(network, run);
        settleChance(job, run);
        int sent = dispatch(job, run, limits, network);
        if (complete(job, run)) {
            // A delivery (M7C item 5) gets one try: to the player before the buffer goes to storage, through
            // labelled ports once it is all in storage.
            CraftDelivery.toPlayer(level, job, run, network);
            if (!flush(network, run)) {
                return STALL_TICKS;
            }
            CraftDelivery.toLabel(level, job, network, gameTime);
            return DONE;
        }
        if (sent > 0) {
            run.lastProgress = gameTime;
            // A job a provider's lock held runs again: its requester's notice says so (B09 item 21d).
            JobNotices.progressed(level, job, run);
            return RUN_TICKS;
        }
        if (waiting(job, run, gameTime, network)) {
            return RUN_TICKS;
        }
        if (lockHeld) {
            // A provider's lock mode holds the next batch: the job waits and names the lock as its stuck reason. A
            // provider on the network wakes it when its lock lets go or when it finishes its batches and may take
            // work again (B09 item 26; CraftingRuns.wakeHeld).
            run.lockHeld = true;
            JobNotices.changed(level, job, run);
            return WAIT;
        }
        return CraftingMode.current().extended() ? PARTIAL : WAIT;
    }

    /**
     * One input run of the job start: takes what the plan still needs ({@code run.need}) from storage in one
     * transaction, re-checking the stock. When the network's FE runs out part way, what was taken stays in the CPU's
     * buffer, the rest stays in {@code need} for the next run, and this returns {@link #START_WAITING}: the FE buffer
     * never limits a job's size (M7E). Otherwise a plan made against stock that moved ({@code Result.stale()}) or a
     * shortfall gets only what the network holds, the rest is reported missing and this returns {@link #START_TAKEN}.
     * In classic mode a shortfall takes nothing in this run and returns {@link #START_REFUSED}; what earlier runs took
     * is in the buffer, which the cancel returns.
     */
    static int start(NetworkState network, JobRun run) {
        boolean extended = CraftingMode.current().extended();
        boolean whole = true;
        boolean energyShort = false;
        // Applied after the commit only, so a refused classic run leaves buffer and need as they were.
        Object2LongOpenHashMap<StackKey> took = new Object2LongOpenHashMap<>();
        Object2LongOpenHashMap<StackKey> lacking = new Object2LongOpenHashMap<>();
        try (Transaction transaction = Transaction.openRoot()) {
            for (Object2LongMap.Entry<StackKey> entry : run.need.object2LongEntrySet()) {
                // An extraction short of FE either takes nothing or leaves less than the smallest operation costs.
                if (!network.isPowered()) {
                    energyShort = true;
                    break;
                }
                long want = entry.getLongValue();
                long got = network.extract(entry.getKey(), want, transaction);
                if (got > 0) {
                    took.put(entry.getKey(), got);
                }
                if (got < want) {
                    if (!network.isPowered()) {
                        energyShort = true;
                        break;
                    }
                    whole = false;
                    lacking.put(entry.getKey(), want - got);
                }
            }
            if (!whole && !extended) {
                return START_REFUSED;
            }
            transaction.commit();
        }
        for (Object2LongMap.Entry<StackKey> entry : took.object2LongEntrySet()) {
            run.buffer.addTo(entry.getKey(), entry.getLongValue());
            JobRun.subtract(run.need, entry.getKey(), entry.getLongValue());
        }
        for (Object2LongMap.Entry<StackKey> entry : lacking.object2LongEntrySet()) {
            run.missing.addTo(entry.getKey(), entry.getLongValue());
            run.need.removeLong(entry.getKey());
        }
        if (!took.isEmpty() || !lacking.isEmpty()) {
            run.changed();
        }
        if (energyShort) {
            return START_WAITING;
        }
        run.need.clear();
        return START_TAKEN;
    }

    /**
     * Pays FE for the lanes active since the last run from the CPU's network (M7 C5). Returns false, stalled, when it
     * cannot or when either the CPU's or the job's network is unpowered: a stalled CPU sends nothing. Outputs still
     * count while it is stalled (they wait in storage on the run's {@code pull}) and come into the buffer once it runs
     * again. {@code lastCharge} moves on even when the payment fails, so the stalled time is never charged later.
     */
    private static boolean pay(JobRun run, Limits limits, @Nullable NetworkState cpuNetwork, NetworkState network,
            long gameTime) {
        FeBuffer buffer = cpuNetwork == null || !cpuNetwork.isPowered() || !network.isPowered()
                ? null
                : cpuNetwork.energyBuffer();
        int lanes = activeLanes(run);
        long elapsed = run.lastCharge == Long.MIN_VALUE ? 0 : Math.max(0, gameTime - run.lastCharge);
        long cost = saturated(saturated(elapsed, lanes), limits.muPerLane());
        boolean paid = buffer != null && buffer.tryConsume(cost);
        run.lastCharge = gameTime;
        if (paid == run.stalled) {
            run.stalled = !paid;
            run.changed();
        }
        return paid;
    }

    private static long saturated(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return high == 0 && low >= 0 ? low : Long.MAX_VALUE;
    }

    static int activeLanes(JobRun run) {
        int lanes = 0;
        for (long out : run.inflight) {
            if (out > 0) {
                lanes++;
            }
        }
        return lanes;
    }

    /** Takes the outputs counted for the job out of storage into the CPU's buffer; what is not there stays owed. */
    static void pullCounted(NetworkState network, JobRun run) {
        if (run.pull.isEmpty()) {
            return;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            var iterator = run.pull.object2LongEntrySet().iterator();
            while (iterator.hasNext()) {
                Object2LongMap.Entry<StackKey> entry = iterator.next();
                long got = network.extract(entry.getKey(), entry.getLongValue(), transaction);
                if (got > 0) {
                    run.buffer.addTo(entry.getKey(), got);
                }
                if (got >= entry.getLongValue()) {
                    iterator.remove();
                } else if (got > 0) {
                    entry.setValue(entry.getLongValue() - got);
                }
            }
            transaction.commit();
        }
        run.changed();
    }

    /** A chance step that made enough expects nothing more; what its machines still hold goes to storage. */
    private static void settleChance(CraftingJob job, JobRun run) {
        for (int i = 0; i < run.inflight.length; i++) {
            if (run.inflight[i] > 0 && run.received[i] >= job.steps().get(i).toCraft()) {
                run.inflight[i] = 0;
                run.changed();
            }
        }
    }

    /** Sends every step the buffer and the lanes allow, leaves first; returns the crafts sent. */
    private int dispatch(CraftingJob job, JobRun run, Limits limits, NetworkState network) {
        int lanes = activeLanes(run);
        int sentTotal = 0;
        lockHeld = false;
        List<CraftingJob.Step> steps = job.steps();
        // Pre-order reversed visits every child before its parent.
        for (int i = steps.size() - 1; i >= 0; i--) {
            CraftingJob.Step step = steps.get(i);
            if (step.pattern() == PlanTree.GENERATOR) {
                boolean active = run.inflight[i] > 0;
                if (!active && lanes >= limits.lanes()) {
                    continue;
                }
                long ordered = orderGenerated(job, step, run, i, network);
                if (ordered > 0) {
                    if (!active) {
                        lanes++;
                    }
                    sentTotal = (int) Math.min(Integer.MAX_VALUE, sentTotal + ordered);
                }
                continue;
            }
            if (step.pattern() < 0 || run.sent[i] >= step.crafts() || run.received[i] >= step.toCraft()) {
                continue;
            }
            boolean active = run.inflight[i] > 0;
            if (!active && lanes >= limits.lanes()) {
                continue;
            }
            CraftingPattern pattern = job.patterns().get(step.pattern());
            boolean chance = pattern.mainOutput().chance() < 1f;
            if (chance && active) {
                // One batch of a chance step at a time, so it stops once enough came out.
                continue;
            }
            long crafts = Math.min(step.crafts() - run.sent[i], limits.batch());
            if (pattern.kind() == CraftingPattern.Kind.PROCESSING && PatternReturns.any(pattern)) {
                // The returned input (a tool, a catalyst) must be back before the machine can run the next craft.
                crafts = Math.min(crafts, 1);
            }
            crafts = Math.min(crafts, affordable(job, run, i, pattern, active));
            if (crafts <= 0) {
                continue;
            }
            int batch = (int) Math.min(Integer.MAX_VALUE, crafts);
            fillInputs(job, i, pattern, batch);
            Object2LongOpenHashMap<StackKey> wanted = new Object2LongOpenHashMap<>(inputs);
            int done = CraftingDispatcher.dispatch(network, pattern, batch, inputs);
            List<NetworkState> linked = done > 0 ? List.of() : network.linkedNetworks();
            for (int l = 0; l < linked.size() && done == 0; l++) {
                done = CraftingDispatcher.dispatch(linked.get(l), pattern, batch, inputs);
            }
            if (done <= 0) {
                lockHeld |= CraftingDispatcher.locked(network, pattern);
                continue;
            }
            // What the providers took leaves the CPU's buffer; the rest stays for a later run.
            for (Object2LongMap.Entry<StackKey> entry : wanted.object2LongEntrySet()) {
                long handed = entry.getLongValue() - inputs.getLong(entry.getKey());
                if (handed > 0) {
                    JobRun.subtract(run.buffer, entry.getKey(), handed);
                }
            }
            run.sent[i] += done;
            run.inflight[i] += saturated(done, pattern.mainOutput().amount());
            expectExtras(run, pattern, wanted, done);
            if (!active) {
                lanes++;
            }
            sentTotal += done;
            run.changed();
        }
        return sentTotal;
    }

    /**
     * Orders what a generator step still needs (M7C item 11) from the network's generator providers for the step's key,
     * counted as sent and in flight. The whole rest goes in one order, not the CPU's batch: an order holds no inputs,
     * and the generator collects only what its machine makes, so the machine's speed is the only limit. A step whose
     * units are all ordered but still out while the generators no longer owe them to the job lost them (a generator
     * gave up, or another job counted the arrivals first): it orders them again, without counting them twice. Returns
     * the units newly sent.
     */
    private static long orderGenerated(CraftingJob job, CraftingJob.Step step, JobRun run, int index,
            NetworkState network) {
        if (run.received[index] >= step.toCraft()) {
            return 0;
        }
        long owed = step.crafts() - run.sent[index];
        if (owed > 0) {
            long ordered = GeneratorDispatch.order(network, step.key(), run.job(), owed);
            if (ordered > 0) {
                run.sent[index] += ordered;
                run.inflight[index] += ordered;
                run.changed();
            }
            return ordered;
        }
        if (lastGeneratorStep(job, index)) {
            // Units out for this key, over every generator step with it, that no generator owes the job any more.
            long out = 0;
            for (int i = 0; i <= index; i++) {
                CraftingJob.Step other = job.steps().get(i);
                if (other.pattern() == PlanTree.GENERATOR && other.key().equals(step.key())) {
                    out += run.inflight[i];
                }
            }
            long lost = out - GeneratorDispatch.owed(network, step.key(), run.job());
            if (lost > 0) {
                GeneratorDispatch.order(network, step.key(), run.job(), lost);
            }
        }
        return 0;
    }

    /** Whether no generator step after {@code index} makes the same key, so re-orders for it run once per pass. */
    private static boolean lastGeneratorStep(CraftingJob job, int index) {
        StackKey key = job.steps().get(index).key();
        for (int i = index + 1; i < job.steps().size(); i++) {
            CraftingJob.Step other = job.steps().get(i);
            if (other.pattern() == PlanTree.GENERATOR && other.key().equals(key)) {
                return false;
            }
        }
        return true;
    }

    /** Crafts of step {@code index} the buffer holds inputs for. */
    private static long affordable(CraftingJob job, JobRun run, int index, CraftingPattern pattern, boolean active) {
        long most = Long.MAX_VALUE;
        int child = index + 1;
        for (int n = 0; n < pattern.inputs().size(); n++) {
            PatternInput input = pattern.inputs().get(n);
            if (child >= index + job.steps().get(index).size()) {
                return 0;
            }
            StackKey key = job.steps().get(child).key();
            long held = run.buffer.getLong(key);
            if (PatternReturns.returned(pattern, n)) {
                if (active || held < input.amount()) {
                    return 0;
                }
            } else {
                most = Math.min(most, held / input.amount());
            }
            child += job.steps().get(child).size();
        }
        return most;
    }

    /** The inputs for {@code crafts} crafts of step {@code index} into {@link #inputs}. */
    private void fillInputs(CraftingJob job, int index, CraftingPattern pattern, int crafts) {
        inputs.clear();
        int child = index + 1;
        for (int n = 0; n < pattern.inputs().size(); n++) {
            PatternInput input = pattern.inputs().get(n);
            StackKey key = job.steps().get(child).key();
            inputs.addTo(key, PatternReturns.returned(pattern, n) ? input.amount() : saturated(input.amount(), crafts));
            child += job.steps().get(child).size();
        }
    }

    /** Returned inputs and crafting remainders come back with the outputs; the job expects them. */
    private static void expectExtras(JobRun run, CraftingPattern pattern, Object2LongMap<StackKey> sent, int crafts) {
        for (int n = 0; n < pattern.inputs().size(); n++) {
            PatternInput input = pattern.inputs().get(n);
            if (PatternReturns.returned(pattern, n)) {
                for (Object2LongMap.Entry<StackKey> entry : sent.object2LongEntrySet()) {
                    if (input.ingredient().matches(entry.getKey())) {
                        run.extras.addTo(entry.getKey(), input.amount());
                        break;
                    }
                }
            } else if (PatternTable.hasRemainders(pattern)) {
                for (Object2LongMap.Entry<StackKey> entry : sent.object2LongEntrySet()) {
                    if (entry.getKey() instanceof ItemKey item && input.ingredient().matches(item)) {
                        ItemStackTemplate remainder = item.item().getCraftingRemainder(item.toStack(1));
                        if (remainder != null) {
                            run.extras.addTo(ItemKey.of(remainder.item().value(), remainder.components()),
                                    saturated(remainder.count(), saturated(input.amount(), crafts)));
                        }
                        break;
                    }
                }
            }
        }
    }

    /** Whether every crafting step got its output back. */
    static boolean complete(CraftingJob job, JobRun run) {
        if (!run.started || !run.pull.isEmpty()) {
            return false;
        }
        for (int i = 0; i < job.steps().size(); i++) {
            CraftingJob.Step step = job.steps().get(i);
            if (PlanTree.crafts(step.pattern()) && run.received[i] < step.toCraft()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether outputs are still due: a guaranteed step out, a generator step out while a generator for its key is on
     * the network (M7C item 11), or a chance step out or a returned input on its way back (M7C item 3) and recently
     * busy.
     */
    private static boolean waiting(CraftingJob job, JobRun run, long gameTime, NetworkState network) {
        boolean chanceOut = false;
        for (int i = 0; i < run.inflight.length; i++) {
            if (run.inflight[i] > 0) {
                int id = job.steps().get(i).pattern();
                if (id == PlanTree.GENERATOR) {
                    // A generator keeps its machine on until the units arrive, or names why not (stuck reasons);
                    // with every generator for the key gone nothing will come.
                    if (GeneratorDispatch.present(network, job.steps().get(i).key())) {
                        return true;
                    }
                    continue;
                }
                CraftingPattern pattern = job.patterns().get(id);
                if (pattern.mainOutput().chance() >= 1f) {
                    return true;
                }
                chanceOut = true;
            }
        }
        return (chanceOut || !run.extras.isEmpty()) && gameTime - run.lastProgress < CHANCE_PATIENCE_TICKS;
    }

    /**
     * Puts everything the CPU holds into {@code network}'s storage; returns whether the buffer is empty now. What does
     * not fit stays in the buffer for the next try.
     */
    static boolean flush(NetworkState network, JobRun run) {
        if (run.buffer.isEmpty()) {
            return true;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            var iterator = run.buffer.object2LongEntrySet().iterator();
            while (iterator.hasNext()) {
                Object2LongMap.Entry<StackKey> entry = iterator.next();
                long moved = network.insert(entry.getKey(), entry.getLongValue(), transaction);
                if (moved >= entry.getLongValue()) {
                    iterator.remove();
                } else if (moved > 0) {
                    entry.setValue(entry.getLongValue() - moved);
                }
            }
            transaction.commit();
        }
        run.changed();
        return run.buffer.isEmpty();
    }

    /** Returns what job {@code run}'s CPU holds to {@code network}; true when nothing is left. For cancel. */
    public static boolean flushTo(NetworkState network, JobRun run) {
        return flush(network, run);
    }

    /** Empties the CPU's buffer and returns what it held, for dropping what storage would not take. */
    public static Object2LongMap<StackKey> drain(JobRun run) {
        Object2LongOpenHashMap<StackKey> out = new Object2LongOpenHashMap<>(run.buffer);
        run.buffer.clear();
        run.changed();
        return out;
    }
}
