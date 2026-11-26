package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import java.util.function.IntFunction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import org.jspecify.annotations.Nullable;

/**
 * What a player hears about a crafting job they started (B09 item 21d): its live state (waiting for a CPU or for power,
 * running) and how it ended (done, stopped and why, cancelled). The server sends one {@link JobToastPayload} whenever
 * this changes; the ending ones show a toast, and the stopped and waiting ones badge the terminal's jobs tab until the
 * job moves on. Pure: no game state, so the mapping is unit-tested.
 */
public enum JobNotice {
    /** A CPU runs it. Clears a badge. */
    RUNNING("running", Alert.NONE),
    /** Planned and waiting for a free CPU. */
    WAITING_CPU("waiting_cpu", Alert.WARN),
    /** Its CPU or network has no power, so it waits. */
    WAITING_POWER("waiting_power", Alert.WARN),
    /** Everything came out. */
    DONE("done", Alert.NONE),
    /** Stopped: an input of a pattern is missing from storage. */
    MISSING("missing", Alert.ERROR),
    /**
     * Held by a pattern provider's lock mode (a live state that toasts once per job), or stopped because a land claim
     * locks the provider out of its machine.
     */
    PROVIDER_LOCKED("provider_locked", Alert.ERROR),
    /** Stopped: a pattern's recipe no longer makes the pattern's output. */
    RECIPE_CHANGED("recipe_changed", Alert.ERROR),
    /** Stopped for another reason: nothing more came out. */
    STOPPED("stopped", Alert.ERROR),
    /** Cancelled by someone other than the player who started it, by logic, or because its CPU went away. */
    CANCELLED("cancelled", Alert.NONE);

    /** How urgent a badge is; mapped to Lib's badge colours on the client. */
    public enum Alert {
        NONE(0), WARN(1), ERROR(2);

        private final int rank;

        Alert(int rank) {
            this.rank = rank;
        }

        /** Higher is more urgent. */
        public int rank() {
            return rank;
        }
    }

    private static final IntFunction<JobNotice> BY_ID = ByIdMap.continuous(Enum::ordinal, values(),
            ByIdMap.OutOfBoundsStrategy.ZERO);
    /** On the wire by declaration order; never saved. */
    public static final StreamCodec<ByteBuf, JobNotice> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    private final String name;
    private final Alert alert;

    JobNotice(String name, Alert alert) {
        this.name = name;
        this.alert = alert;
    }

    /** The badge the notice puts on the jobs tab while it is the job's latest. */
    public Alert alert() {
        return alert;
    }

    /** Whether the job is over: done or cancelled. Its badge goes. */
    public boolean gone() {
        return this == DONE || this == CANCELLED;
    }

    /** Whether the notice reports how the job ended (a toast), rather than its live state. */
    public boolean ending() {
        return switch (this) {
            case RUNNING, WAITING_CPU, WAITING_POWER -> false;
            default -> true;
        };
    }

    /** The lang key of the toast's title. */
    public String toastKey() {
        return "toast." + MaxtechStorage.MOD_ID + ".crafting." + name;
    }

    /** The lang key of the badge's reason. */
    public String reasonKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".jobs.alert." + name;
    }

    /**
     * The live notice for a job in {@code state}: waiting for a CPU while planned, waiting for power while its CPU is
     * {@code stalled}, held while a provider's lock mode holds its next step ({@code held}), else running; null for a
     * job that ended or stopped (those notices come from {@link #ended}).
     */
    public static @Nullable JobNotice live(JobState state, boolean stalled, boolean held) {
        return switch (state) {
            case PLANNED -> WAITING_CPU;
            case RUNNING -> stalled ? WAITING_POWER : held ? PROVIDER_LOCKED : RUNNING;
            default -> null;
        };
    }

    /**
     * How a job that ended in {@code state} reads. {@code failed} says it stopped because something was missing (it
     * ended partial, or classic mode could not start it); {@code missing} that the job named what; {@code locked} and
     * {@code recipeChanged} what its pattern providers report. A failed job names the first cause in that order.
     */
    public static JobNotice ended(JobState state, boolean failed, boolean missing, boolean locked,
            boolean recipeChanged) {
        if (state == JobState.DONE) {
            return DONE;
        }
        if (state == JobState.PARTIAL || failed) {
            if (missing) {
                return MISSING;
            }
            if (locked) {
                return PROVIDER_LOCKED;
            }
            if (recipeChanged) {
                return RECIPE_CHANGED;
            }
            return state == JobState.PARTIAL ? STOPPED : CANCELLED;
        }
        return CANCELLED;
    }

    /**
     * The badge for a pattern provider or generator that reports why it holds a craft up: none without a reason, amber
     * for no power, red for anything else (a full machine, blocked outputs, a changed recipe, a land claim).
     */
    public static Alert stuck(boolean reason, boolean noPower) {
        if (!reason) {
            return Alert.NONE;
        }
        return noPower ? Alert.WARN : Alert.ERROR;
    }
}
