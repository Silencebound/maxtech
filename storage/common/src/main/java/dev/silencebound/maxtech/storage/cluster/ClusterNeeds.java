package dev.silencebound.maxtech.storage.cluster;

import dev.silencebound.maxtech.storage.crafting.cpu.CpuPart;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuStats;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What a controller or crafting CPU cluster lacks or wastes (M7C item 22): free or missing slots, a conflict with
 * another core, how many more parts reach the tier's caps and parts that add nothing. Shown in the controller's Network
 * tab, the CPU core's info lines and the action bar of the in-world preview. Built from the numbers the cores already
 * keep; the CPU numbers come from {@link CpuStats#partsToCap}. Pure.
 */
public final class ClusterNeeds {
    /** Most needs one list carries on the wire. */
    public static final int MAX = 64;

    /** A kind of need, with its tone and lang key ({@code gui.maxtech_storage.cluster_need.<name>}). */
    public enum Kind {
        /** a: slots used, b: cap, c: free. */
        CONTROLLER_FREE(StatusPalette.Tone.GOOD),
        /** a: slots used, b: cap; research raises the cap. */
        CONTROLLER_FULL(StatusPalette.Tone.WARN),
        /** a: slots used, b: cap, the highest there is. */
        CONTROLLER_FULL_MAX(StatusPalette.Tone.WARN),
        /** b: cap; expansions past it stay dark. */
        CONTROLLER_OVER(StatusPalette.Tone.BAD),
        /** Another core runs the cluster. */
        CONTROLLER_IDLE(StatusPalette.Tone.BAD),
        /** A CPU's heading; a: tier, pos: the core. */
        CPU(StatusPalette.Tone.DIM),
        /** Another core touches the parts. */
        CPU_CONFLICT(StatusPalette.Tone.BAD),
        /** a: the part limit, reached. */
        CPU_PART_LIMIT(StatusPalette.Tone.WARN),
        /** a: more storage blocks, b: the tier's step cap. */
        CPU_STORAGE_MORE(StatusPalette.Tone.DIM),
        /** a: more lane blocks, b: the tier's lane cap. */
        CPU_LANE_MORE(StatusPalette.Tone.DIM),
        /** a: more cache blocks, b: the tier's batch cap. */
        CPU_CACHE_MORE(StatusPalette.Tone.DIM),
        /** a: storage blocks that add nothing. */
        CPU_STORAGE_EXTRA(StatusPalette.Tone.WARN),
        /** a: lane blocks that add nothing. */
        CPU_LANE_EXTRA(StatusPalette.Tone.WARN),
        /** a: cache blocks that add nothing. */
        CPU_CACHE_EXTRA(StatusPalette.Tone.WARN),
        /** Every cap of the tier is reached. */
        CPU_COMPLETE(StatusPalette.Tone.GOOD);

        private final StatusPalette.Tone tone;
        private final String key;

        Kind(StatusPalette.Tone tone) {
            this.tone = tone;
            this.key = "gui.maxtech_storage.cluster_need." + name().toLowerCase(Locale.ROOT);
        }

        public StatusPalette.Tone tone() {
            return tone;
        }

        public String key() {
            return key;
        }
    }

    /**
     * One line: a {@link Kind}, the core it is about ({@code BlockPos.asLong()}) and up to three numbers, whose meaning
     * the kind gives.
     */
    public record Need(Kind kind, long pos, int a, int b, int c) {
        static Need of(Kind kind, long pos, int a, int b) {
            return new Need(kind, pos, a, b, 0);
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeEnum(kind);
            buf.writeLong(pos);
            buf.writeVarInt(a);
            buf.writeVarInt(b);
            buf.writeVarInt(c);
        }

        public static Need read(FriendlyByteBuf buf) {
            return new Need(buf.readEnum(Kind.class), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt());
        }
    }

    private ClusterNeeds() {
    }

    /**
     * Adds the controller's line: a member block, over the size cap, full (with whether research raises the cap, that
     * is {@code cap < maxCap}) or how many more blocks fit. {@code slotted} counts the multiblock's active blocks (M7R
     * item 1).
     */
    public static void controller(long core, int slotted, int cap, int maxCap, boolean over, boolean idle,
            List<Need> out) {
        if (idle) {
            out.add(Need.of(Kind.CONTROLLER_IDLE, core, 0, 0));
        } else if (over) {
            out.add(Need.of(Kind.CONTROLLER_OVER, core, slotted, cap));
        } else if (slotted >= cap) {
            out.add(Need.of(cap < maxCap ? Kind.CONTROLLER_FULL : Kind.CONTROLLER_FULL_MAX, core, slotted, cap));
        } else {
            out.add(new Need(Kind.CONTROLLER_FREE, core, slotted, cap, cap - slotted));
        }
    }

    /**
     * Adds a CPU's lines: a heading, then a conflict (and nothing else), or the part limit when reached, then per part
     * how many more reach the tier's cap or how many add nothing, or that every cap is reached.
     */
    public static void cpu(long core, int tier, int storage, int lanes, int caches, boolean conflict, int maxParts,
            CpuStats.Caps caps, CpuStats.Parts parts, List<Need> out) {
        out.add(Need.of(Kind.CPU, core, tier, 0));
        if (conflict) {
            out.add(Need.of(Kind.CPU_CONFLICT, core, 0, 0));
            return;
        }
        boolean limit = storage + lanes + caches >= maxParts;
        if (limit) {
            out.add(Need.of(Kind.CPU_PART_LIMIT, core, maxParts, 0));
        }
        int before = out.size();
        part(core, storage, CpuStats.partsToCap(caps, parts, CpuPart.STORAGE), caps.maxSteps(), limit,
                Kind.CPU_STORAGE_MORE, Kind.CPU_STORAGE_EXTRA, out);
        part(core, lanes, CpuStats.partsToCap(caps, parts, CpuPart.LANE), caps.maxLanes(), limit, Kind.CPU_LANE_MORE,
                Kind.CPU_LANE_EXTRA, out);
        part(core, caches, CpuStats.partsToCap(caps, parts, CpuPart.CACHE), caps.maxBatch(), limit, Kind.CPU_CACHE_MORE,
                Kind.CPU_CACHE_EXTRA, out);
        if (out.size() == before && !limit) {
            out.add(Need.of(Kind.CPU_COMPLETE, core, 0, 0));
        }
    }

    /** Whether one more block of {@code part} would raise what the CPU can do (below its cap and the part limit). */
    public static boolean helps(int count, int total, int maxParts, CpuStats.Caps caps, CpuStats.Parts parts,
            CpuPart part) {
        return total < maxParts && count < CpuStats.partsToCap(caps, parts, part);
    }

    private static void part(long core, int count, int toCap, int cap, boolean limit, Kind more, Kind extra,
            List<Need> out) {
        if (count < toCap) {
            // At the part limit no more parts count, so "more" would mislead.
            if (!limit) {
                out.add(Need.of(more, core, toCap - count, cap));
            }
        } else if (count > toCap) {
            out.add(Need.of(extra, core, count - toCap, 0));
        }
    }
}
