package dev.silencebound.maxtech.storage.crafting.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.UUIDUtil;
import dev.silencebound.maxtech.storage.network.NetworkState;
import org.jspecify.annotations.Nullable;

/**
 * The executor's progress on one job (M7c area A), kept beside the {@link CraftingJob} by its id in
 * {@link CraftingRuns}: what each step has sent and received, what the CPU holds (its buffer of inputs and
 * intermediates), what it still needs from storage and what is missing. Created when a job is queued; dropped when it
 * finishes. Mutable, server thread only; {@link #version()} changes with every visible change, so screens send deltas
 * only when it moved.
 */
public final class JobRun {
    /** No CPU runs the job. */
    public static final long NO_CPU = Long.MIN_VALUE;

    private record Amount(StackKey key, long amount) {
        static final Codec<Amount> CODEC = RecordCodecBuilder
                .create(i -> i.group(StackKey.CODEC.fieldOf("key").forGetter(Amount::key),
                        Codec.LONG.fieldOf("amount").forGetter(Amount::amount)).apply(i, Amount::new));
    }

    private record Saved(UUID job, long origin, long cpu, boolean started, List<Long> sent, List<Long> received,
            List<Long> inflight, List<Amount> buffer, List<Amount> extras, List<Amount> need, List<Amount> missing,
            List<Amount> pull, long delivered, int priority, int cpuTier, boolean cancelling) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i
                .group(UUIDUtil.CODEC.fieldOf("job").forGetter(Saved::job),
                        Codec.LONG.fieldOf("origin").forGetter(Saved::origin),
                        Codec.LONG.optionalFieldOf("cpu", NO_CPU).forGetter(Saved::cpu),
                        Codec.BOOL.optionalFieldOf("started", false).forGetter(Saved::started),
                        Codec.LONG.listOf().fieldOf("sent").forGetter(Saved::sent),
                        Codec.LONG.listOf().fieldOf("received").forGetter(Saved::received),
                        Codec.LONG.listOf().fieldOf("inflight").forGetter(Saved::inflight),
                        Amount.CODEC.listOf().fieldOf("buffer").forGetter(Saved::buffer),
                        Amount.CODEC.listOf().fieldOf("extras").forGetter(Saved::extras),
                        Amount.CODEC.listOf().fieldOf("need").forGetter(Saved::need),
                        Amount.CODEC.listOf().fieldOf("missing").forGetter(Saved::missing),
                        Amount.CODEC.listOf().optionalFieldOf("pull", List.of()).forGetter(Saved::pull),
                        Codec.LONG.optionalFieldOf("delivered", 0L).forGetter(Saved::delivered),
                        Codec.INT.optionalFieldOf("priority", 0).forGetter(Saved::priority),
                        Codec.INT.optionalFieldOf("cpu_tier", 0).forGetter(Saved::cpuTier),
                        Codec.BOOL.optionalFieldOf("cancelling", false).forGetter(Saved::cancelling))
                .apply(i, Saved::new));
    }

    public static final Codec<JobRun> CODEC = Saved.CODEC.xmap(JobRun::new, JobRun::save);

    private final UUID job;
    final long origin;
    long cpu = NO_CPU;
    boolean started;
    long[] sent;
    long[] received;
    long[] inflight;
    final Object2LongLinkedOpenHashMap<StackKey> buffer = new Object2LongLinkedOpenHashMap<>();
    /** Returned inputs and crafting remainders on their way back from machines. */
    final Object2LongLinkedOpenHashMap<StackKey> extras = new Object2LongLinkedOpenHashMap<>();
    /** What the job start still takes from storage (the plan's peak use). */
    final Object2LongLinkedOpenHashMap<StackKey> need = new Object2LongLinkedOpenHashMap<>();
    final Object2LongLinkedOpenHashMap<StackKey> missing = new Object2LongLinkedOpenHashMap<>();
    /** Outputs counted for the job that went into storage; the CPU's next run takes them into its buffer. */
    final Object2LongLinkedOpenHashMap<StackKey> pull = new Object2LongLinkedOpenHashMap<>();
    long delivered;
    /**
     * Cancelled while its CPU was unloaded and storage could not take everything the CPU holds: the CPU finishes the
     * cancel, returning or dropping the rest, when it loads. Counts no more outputs meanwhile.
     */
    boolean cancelling;
    final int priority;
    final int cpuTier;
    private long version;
    boolean stalled;
    /**
     * The player who cancelled the job, for the requester's toast (B09 item 21d); null when not a player or not
     * cancelled. Not saved: a cancel a restart interrupts reads as not by a player.
     */
    @Nullable
    UUID cancelledBy;
    /**
     * Set while the job waits only because a provider's lock mode held a step (B09 item 26): providers wake such runs
     * on an unlock or when they become free ({@link CraftingRuns#wakeHeld}). Not saved: a restart runs the job again.
     */
    boolean lockHeld;
    long lastCharge = Long.MIN_VALUE;
    long lastProgress;
    /** Networks whose outputs this job counts: its own, the CPU's and the linked ones; refreshed by each CPU run. */
    final ObjectArrayList<NetworkState> claimNetworks = new ObjectArrayList<>(2);
    /** Who hears when the job ends; not saved, so a restart drops it (documented for background requesters). */
    @Nullable
    Consumer<JobState> onEnd;
    @Nullable
    Runnable wake;

    JobRun(UUID job, long origin, int steps, int priority, int cpuTier) {
        this.job = job;
        this.origin = origin;
        this.sent = new long[steps];
        this.received = new long[steps];
        this.inflight = new long[steps];
        this.priority = priority;
        this.cpuTier = cpuTier;
    }

    private JobRun(Saved saved) {
        this(saved.job(), saved.origin(), saved.sent().size(), saved.priority(), saved.cpuTier());
        cancelling = saved.cancelling();
        cpu = saved.cpu();
        started = saved.started();
        for (int i = 0; i < sent.length; i++) {
            sent[i] = saved.sent().get(i);
            received[i] = i < saved.received().size() ? saved.received().get(i) : 0;
            inflight[i] = i < saved.inflight().size() ? saved.inflight().get(i) : 0;
        }
        load(buffer, saved.buffer());
        load(extras, saved.extras());
        load(need, saved.need());
        load(missing, saved.missing());
        load(pull, saved.pull());
        delivered = saved.delivered();
    }

    private static void load(Object2LongMap<StackKey> map, List<Amount> amounts) {
        for (Amount amount : amounts) {
            map.put(amount.key(), amount.amount());
        }
    }

    private Saved save() {
        return new Saved(job, origin, cpu, started, longs(sent), longs(received), longs(inflight), amounts(buffer),
                amounts(extras), amounts(need), amounts(missing), amounts(pull), delivered, priority, cpuTier,
                cancelling);
    }

    private static List<Long> longs(long[] values) {
        return new LongArrayList(values);
    }

    private static List<Amount> amounts(Object2LongMap<StackKey> map) {
        List<Amount> out = new ArrayList<>(map.size());
        for (Object2LongMap.Entry<StackKey> entry : map.object2LongEntrySet()) {
            out.add(new Amount(entry.getKey(), entry.getLongValue()));
        }
        return out;
    }

    /** Whether a cancel waits for the job's unloaded CPU to return what it holds. */
    public boolean cancelling() {
        return cancelling;
    }

    /** Forgets which networks the job counts outputs from, as after a restart; for tests. */
    public void forgetNetworks() {
        claimNetworks.clear();
    }

    public UUID job() {
        return job;
    }

    /** A node of the network the job was requested on; its storage gives and takes. */
    public long origin() {
        return origin;
    }

    /** The CPU core running the job, or {@link #NO_CPU}. */
    public long cpu() {
        return cpu;
    }

    /** Crafts sent so far for step {@code index}. */
    public long sent(int index) {
        return sent[index];
    }

    /** Main output received so far for step {@code index}. */
    public long received(int index) {
        return received[index];
    }

    /** Main output still expected back from machines for step {@code index}. */
    public long inflight(int index) {
        return inflight[index];
    }

    /** What the CPU holds now; read-only. */
    public Object2LongMap<StackKey> buffer() {
        return Object2LongMaps.unmodifiable(buffer);
    }

    /**
     * What the job lacks: the plan's shortfall plus anything the start could not take or a chance step did not yield.
     * Read-only. The extended report; classic mode shows only the state.
     */
    public Object2LongMap<StackKey> missing() {
        return Object2LongMaps.unmodifiable(missing);
    }

    /**
     * What the job start still has to take from storage; read-only. Empty once the job started. Inputs the network's FE
     * could not pay for yet stay here until a later run takes them (M7E).
     */
    public Object2LongMap<StackKey> need() {
        return Object2LongMaps.unmodifiable(need);
    }

    /**
     * Whether the CPU waits for FE: an unpowered CPU stalls and sends nothing (M7 C5), and a start whose inputs cost
     * more FE than the network holds waits for the next run (M7E).
     */
    public boolean stalled() {
        return stalled;
    }

    /** Priority of a background job (higher first among background jobs); 0 for players. */
    public int priority() {
        return priority;
    }

    /** The only CPU tier a background job may run on, 0 for any. */
    public int cpuTier() {
        return cpuTier;
    }

    /** Changes with every visible change of the progress; compare for equality. */
    public long version() {
        return version;
    }

    /** What to run when outputs were counted (the CPU wakes its task); set by the CPU running the job. */
    public void wake(Runnable wake) {
        this.wake = wake;
    }

    void changed() {
        version++;
    }

    /**
     * Counts up to {@code amount} of {@code key} that came back from a machine into storage against what the job
     * expects: first the main output of steps waiting on it, then returned inputs and remainders. Returns what was
     * counted; the CPU takes that much from storage into its buffer on its next run.
     */
    long count(StackKey key, long amount, List<CraftingJob.Step> steps) {
        long left = amount;
        for (int i = 0; i < steps.size() && left > 0; i++) {
            if (inflight[i] > 0 && steps.get(i).key().equals(key)) {
                long take = Math.min(left, inflight[i]);
                inflight[i] -= take;
                received[i] += take;
                left -= take;
            }
        }
        long extra = extras.getLong(key);
        if (left > 0 && extra > 0) {
            long take = Math.min(left, extra);
            subtract(extras, key, take);
            left -= take;
        }
        long counted = amount - left;
        if (counted > 0) {
            pull.addTo(key, counted);
            changed();
            Runnable current = wake;
            if (current != null) {
                current.run();
            }
        }
        return counted;
    }

    /** Whether the job waits for {@code key}: a step's main output or a returned input or remainder. */
    boolean expects(StackKey key, List<CraftingJob.Step> steps) {
        if (extras.getLong(key) > 0) {
            return true;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (inflight[i] > 0 && steps.get(i).key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    static void subtract(Object2LongMap<StackKey> map, StackKey key, long amount) {
        long next = map.getLong(key) - amount;
        if (next <= 0) {
            map.removeLong(key);
        } else {
            map.put(key, next);
        }
    }
}
