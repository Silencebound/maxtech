package dev.silencebound.maxtech.storage.crafting.cpu;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;

/**
 * What a crafting CPU cluster can do (M7 C3, C4): the tier sets the caps, the parts fill them. A core alone holds
 * {@code coreSteps} job steps, runs one lane and sends one craft per dispatch; each storage block adds steps, each lane
 * block a lane and each cache block crafts per dispatch, never beyond the tier's caps. Pure arithmetic, unit-tested.
 *
 * @param steps
 *            most steps of one job the CPU takes
 * @param lanes
 *            most steps waiting on machines at once
 * @param batch
 *            most crafts one dispatch sends
 * @param muPerLane
 *            FE per active lane per tick
 */
public record CpuStats(int steps, int lanes, int batch, long muPerLane) {
    /** A CPU that can run nothing: a core in a conflict or without a network. */
    public static final CpuStats NONE = new CpuStats(0, 0, 0, 0);

    /** The caps of one tier. */
    public record Caps(int maxSteps, int maxLanes, int maxBatch, long muPerLane) {
        /** The configured caps of {@code tier}; server thread. */
        public static Caps of(CpuTier tier) {
            int i = tier.level() - 1;
            return new Caps(StorageServerConfig.CPU_MAX_STEPS.get(i).getAsInt(),
                    StorageServerConfig.CPU_MAX_LANES.get(i).getAsInt(),
                    StorageServerConfig.CPU_MAX_BATCH.get(i).getAsInt(),
                    StorageServerConfig.CPU_MU_PER_LANE.get(i).getAsLong());
        }
    }

    /** What the parts add. */
    public record Parts(int coreSteps, int stepsPerStorage, int batchPerCache) {
        /** The configured numbers; server thread. */
        public static Parts fromConfig() {
            return new Parts(StorageServerConfig.CPU_CORE_STEPS.getAsInt(),
                    StorageServerConfig.CPU_STEPS_PER_STORAGE.getAsInt(),
                    StorageServerConfig.CPU_BATCH_PER_CACHE.getAsInt());
        }
    }

    /** The stats of a core with {@code storage}, {@code lanes} and {@code caches} part blocks. */
    public static CpuStats of(Caps caps, Parts parts, int storage, int lanes, int caches) {
        long steps = parts.coreSteps() + (long) Math.max(0, storage) * parts.stepsPerStorage();
        long batch = 1 + (long) Math.max(0, caches) * parts.batchPerCache();
        return new CpuStats(clamp(steps, caps.maxSteps()), clamp(1L + Math.max(0, lanes), caps.maxLanes()),
                clamp(batch, caps.maxBatch()), Math.max(0, caps.muPerLane()));
    }

    /**
     * How many blocks of {@code part} reach the tier's cap for what that part adds; more add nothing. Zero when the
     * part adds nothing or the core alone reaches the cap. Same arithmetic as {@link #of}.
     */
    public static int partsToCap(Caps caps, Parts parts, CpuPart part) {
        return switch (part) {
            case STORAGE -> blocksFor((long) caps.maxSteps() - parts.coreSteps(), parts.stepsPerStorage());
            case LANE -> blocksFor((long) caps.maxLanes() - 1, 1);
            case CACHE -> blocksFor((long) caps.maxBatch() - 1, parts.batchPerCache());
        };
    }

    private static int blocksFor(long missing, int each) {
        if (missing <= 0 || each <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (missing + each - 1) / each);
    }

    private static int clamp(long value, int cap) {
        return (int) Math.max(1, Math.min(value, Math.max(1, cap)));
    }
}
