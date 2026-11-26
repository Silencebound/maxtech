package dev.silencebound.maxtech.storage.crafting.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * A crafting job as it is saved: the request, who made it, the planned tree and the patterns its steps name, and its
 * state. The steps keep the planner's pre-order with parent links and sub-tree sizes ({@link PlanTree}), so a v2 split
 * of sub-trees across CPUs hands out step ranges without a format change. Immutable; a state change makes a new job.
 * The executor's progress (what each step has made so far) is area A's and lives beside this, keyed by {@link #id()}.
 *
 * @param requester
 *            the player who asked for it, empty for stock keeping
 * @param key
 *            what was asked for; an order list's first request
 * @param amount
 *            how much of {@code key}
 * @param created
 *            game time of the request
 * @param more
 *            an order list's further requests (M7C item 6), one more root of {@link #steps()} each, in order; empty for
 *            a single request
 */
public record CraftingJob(UUID id, Optional<UUID> requester, StackKey key, long amount, List<Step> steps,
        List<CraftingPattern> patterns, JobState state, long created, List<CraftingPlanner.Root> more) {
    private static final Codec<CraftingPlanner.Root> ROOT_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(StackKey.CODEC.fieldOf("key").forGetter(CraftingPlanner.Root::key),
                    Codec.LONG.fieldOf("amount").forGetter(CraftingPlanner.Root::amount))
            .apply(instance, CraftingPlanner.Root::new));

    /** One saved step; the fields of {@link PlanTree.Step}. */
    public record Step(int parent, int size, StackKey key, long needed, long stored, long toCraft, long missing,
            long crafts, int pattern) {
        public static final Codec<Step> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(Codec.INT.fieldOf("parent").forGetter(Step::parent),
                                Codec.INT.fieldOf("size").forGetter(Step::size),
                                StackKey.CODEC.fieldOf("key").forGetter(Step::key),
                                Codec.LONG.fieldOf("needed").forGetter(Step::needed),
                                Codec.LONG.optionalFieldOf("stored", 0L).forGetter(Step::stored),
                                Codec.LONG.optionalFieldOf("to_craft", 0L).forGetter(Step::toCraft),
                                Codec.LONG.optionalFieldOf("missing", 0L).forGetter(Step::missing),
                                Codec.LONG.optionalFieldOf("crafts", 0L).forGetter(Step::crafts),
                                Codec.INT.optionalFieldOf("pattern", -1).forGetter(Step::pattern))
                        .apply(instance, Step::new));
    }

    public static final Codec<CraftingJob> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUIDUtil.CODEC.fieldOf("id").forGetter(CraftingJob::id),
                    UUIDUtil.CODEC.optionalFieldOf("requester").forGetter(CraftingJob::requester),
                    StackKey.CODEC.fieldOf("key").forGetter(CraftingJob::key),
                    Codec.LONG.fieldOf("amount").forGetter(CraftingJob::amount),
                    Step.CODEC.listOf().fieldOf("steps").forGetter(CraftingJob::steps),
                    PatternCodecs.PATTERN.listOf().fieldOf("patterns").forGetter(CraftingJob::patterns),
                    JobState.CODEC.fieldOf("state").forGetter(CraftingJob::state),
                    Codec.LONG.fieldOf("created").forGetter(CraftingJob::created),
                    ROOT_CODEC.listOf().optionalFieldOf("more", List.of()).forGetter(CraftingJob::more))
            .apply(instance, CraftingJob::new));

    public CraftingJob {
        steps = List.copyOf(steps);
        patterns = List.copyOf(patterns);
        more = List.copyOf(more);
    }

    /** A job for a single request. */
    public CraftingJob(UUID id, Optional<UUID> requester, StackKey key, long amount, List<Step> steps,
            List<CraftingPattern> patterns, JobState state, long created) {
        this(id, requester, key, amount, steps, patterns, state, created, List.of());
    }

    /** Every request of the job, the first one first: one root of the steps each, in this order. */
    public List<CraftingPlanner.Root> roots() {
        List<CraftingPlanner.Root> all = new ArrayList<>(1 + more.size());
        all.add(new CraftingPlanner.Root(key, amount));
        all.addAll(more);
        return all;
    }

    /**
     * A planned job from a plan. Only the patterns the steps use are kept, renumbered, so a job does not carry the
     * network's whole pattern list.
     */
    public static CraftingJob planned(CraftingPlanner.Result plan, Optional<UUID> requester, long created) {
        List<CraftingPattern> used = new ArrayList<>();
        int[] remap = new int[plan.patterns().size()];
        Arrays.fill(remap, -1);
        List<Step> steps = new ArrayList<>(plan.tree().steps().size());
        for (PlanTree.Step<StackKey> step : plan.tree().steps()) {
            int pattern = step.pattern();
            if (pattern >= 0) {
                if (remap[pattern] < 0) {
                    remap[pattern] = used.size();
                    used.add(plan.patterns().get(pattern));
                }
                pattern = remap[pattern];
            }
            steps.add(new Step(step.parent(), step.size(), step.key(), step.needed(), step.stored(), step.toCraft(),
                    step.missing(), step.crafts(), pattern));
        }
        return new CraftingJob(UUID.randomUUID(), requester, plan.key(), plan.amount(), steps, used, JobState.PLANNED,
                created, plan.more());
    }

    /** This job in {@code next} state. */
    public CraftingJob withState(JobState next) {
        return new CraftingJob(id, requester, key, amount, steps, patterns, next, created, more);
    }

    /** The pattern step {@code index} runs, or empty for a step that only takes from stock. */
    public Optional<CraftingPattern> pattern(int index) {
        int pattern = steps.get(index).pattern();
        return pattern >= 0 ? Optional.of(patterns.get(pattern)) : Optional.empty();
    }

    /** The steps of the sub-tree rooted at {@code index}, itself first; a range of {@link #steps()}. */
    public List<Step> subTree(int index) {
        return steps.subList(index, index + steps.get(index).size());
    }
}
