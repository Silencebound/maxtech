package dev.silencebound.maxtech.storage.crafting.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A network's limits for automated crafting (M7L2 chunk 20): jobs that stock keepers, logic rules and crafting cards
 * queue, never a player's request. {@code maxPerJob} caps the amount one automated job asks for (a larger request is
 * capped, not split: the requester asks again for the rest once its job ends); {@code cpusKeptFree} is how many free
 * CPUs automated jobs leave for players. Either is {@link #FOLLOW_CONFIG} to use the server config's default
 * ({@code crafting.automationMaxPerJob}, {@code crafting.automationCpusKeptFree}). Saved on the controller core and
 * kept on its item. Immutable.
 *
 * @param maxPerJob
 *            items (or mB) per automated job, 0 for no limit, or {@link #FOLLOW_CONFIG}
 * @param cpusKeptFree
 *            free CPUs automation leaves for players, or {@link #FOLLOW_CONFIG}
 */
public record CraftLimits(int maxPerJob, int cpusKeptFree) {
    /** A value that follows the server config. */
    public static final int FOLLOW_CONFIG = -1;
    /** The largest per-job maximum a network or the config may set. */
    public static final int MAX_PER_JOB = 1_000_000;
    /** The most CPUs a network or the config may keep free. */
    public static final int MAX_KEPT_FREE = 64;
    /** Both values from the config. */
    public static final CraftLimits DEFAULT = new CraftLimits(FOLLOW_CONFIG, FOLLOW_CONFIG);
    /** The steps the controller screen's buttons move the per-job maximum through; 0 is no limit. */
    private static final int[] STEPS = {0, 16, 64, 256, 1024, 4096, 16_384, 65_536, 262_144, MAX_PER_JOB};

    public static final Codec<CraftLimits> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.INT.optionalFieldOf("max_per_job", FOLLOW_CONFIG).forGetter(CraftLimits::maxPerJob),
                    Codec.INT.optionalFieldOf("cpus_kept_free", FOLLOW_CONFIG).forGetter(CraftLimits::cpusKeptFree))
            .apply(instance, CraftLimits::new));
    public static final StreamCodec<ByteBuf, CraftLimits> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
            CraftLimits::maxPerJob, ByteBufCodecs.VAR_INT, CraftLimits::cpusKeptFree, CraftLimits::new);

    /** Out-of-range values (an edited save, an older range) are clamped, never refused, so the rest still loads. */
    public CraftLimits {
        maxPerJob = Math.clamp(maxPerJob, FOLLOW_CONFIG, MAX_PER_JOB);
        cpusKeptFree = Math.clamp(cpusKeptFree, FOLLOW_CONFIG, MAX_KEPT_FREE);
    }

    /** The per-job maximum in force: this network's, else the config's; 0 for no limit. */
    public long effectiveMaxPerJob() {
        return maxPerJob >= 0 ? maxPerJob : StorageServerConfig.CRAFTING_AUTOMATION_MAX_PER_JOB.getAsInt();
    }

    /** The CPUs kept free in force: this network's, else the config's. */
    public int effectiveCpusKeptFree() {
        return cpusKeptFree >= 0 ? cpusKeptFree : StorageServerConfig.CRAFTING_AUTOMATION_CPUS_KEPT_FREE.getAsInt();
    }

    /** {@code amount} capped to the per-job maximum in force; at least 1 when {@code amount} is. */
    public long cap(long amount) {
        long max = effectiveMaxPerJob();
        return max > 0 ? Math.min(amount, max) : amount;
    }

    /** These limits with the per-job maximum one step up ({@code up}) or down from the one in force. */
    public CraftLimits stepMaxPerJob(boolean up) {
        long now = effectiveMaxPerJob();
        int next = up ? STEPS[STEPS.length - 1] : STEPS[0];
        // No limit (0) sits above every number for the buttons: down from it goes to the largest step.
        if (now == 0) {
            next = up ? 0 : STEPS[STEPS.length - 1];
        } else if (up) {
            for (int i = STEPS.length - 1; i >= 1; i--) {
                if (STEPS[i] > now) {
                    next = STEPS[i];
                }
            }
            if (now >= MAX_PER_JOB) {
                next = 0;
            }
        } else {
            next = STEPS[1];
            for (int i = 1; i < STEPS.length; i++) {
                if (STEPS[i] < now) {
                    next = STEPS[i];
                }
            }
        }
        return new CraftLimits(next, cpusKeptFree);
    }

    /** These limits with one more ({@code up}) or one fewer CPU kept free than in force. */
    public CraftLimits stepKeptFree(boolean up) {
        int next = Math.clamp(effectiveCpusKeptFree() + (up ? 1 : -1), 0, MAX_KEPT_FREE);
        return new CraftLimits(maxPerJob, next);
    }
}
