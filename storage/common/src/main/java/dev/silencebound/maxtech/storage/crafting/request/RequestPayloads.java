package dev.silencebound.maxtech.storage.crafting.request;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The payloads between a terminal's crafting screens and its {@link CraftingSession}. Every one names the menu's
 * container id; the server acts only for the sender's open terminal menu with that id, and the client applies them only
 * to its open menu with that id.
 */
public final class RequestPayloads {
    /** Jobs listed per network; more are left out of the list. */
    public static final int MAX_JOBS = 64;
    /** Longest notice key a client accepts. */
    private static final int MAX_NOTICE = 128;

    private RequestPayloads() {
    }

    /** What a client asks of the session. */
    public enum Action {
        /** Plan {@code amount} of {@code key}. */
        PLAN(0),
        /**
         * Start the last plan as a job; {@code amount} is the only CPU tier it may run on (1 to 3), 0 for any. A plan
         * with missing items starts only in extended mode (a partial job).
         */
        START(1),
        /** Send the network's job list, and again whenever it changes. */
        WATCH_JOBS(2),
        /** Stop sending the job list and any watched job. */
        UNWATCH(3),
        /** Watch job {@code job}: its tree once, then its progress as it runs. */
        VIEW_JOB(4),
        /** Cancel job {@code job}. */
        CANCEL_JOB(5),
        /** Resume partial job {@code job} (extended mode). */
        RESUME_JOB(6);

        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = ByteBufCodecs.idMapper(Action::byId,
                Action::id);

        private final int id;

        Action(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        static Action byId(int id) {
            for (Action action : values()) {
                if (action.id == id) {
                    return action;
                }
            }
            return UNWATCH;
        }
    }

    /** A job state's id on the wire. */
    public static int stateId(JobState state) {
        return switch (state) {
            case PLANNED -> 0;
            case RUNNING -> 1;
            case PARTIAL -> 2;
            case DONE -> 3;
            case CANCELLED -> 4;
        };
    }

    static JobState stateById(int id) {
        return switch (id) {
            case 1 -> JobState.RUNNING;
            case 2 -> JobState.PARTIAL;
            case 3 -> JobState.DONE;
            case 4 -> JobState.CANCELLED;
            default -> JobState.PLANNED;
        };
    }

    static final StreamCodec<ByteBuf, JobState> STATE_CODEC = ByteBufCodecs.idMapper(RequestPayloads::stateById,
            RequestPayloads::stateId);

    /**
     * Client to server: one crafting action on the terminal menu {@code containerId}.
     *
     * @param request
     *            the client's number for a plan request; the answer carries it back
     */
    public record Request(int containerId, Action action, int request, Optional<StackKey> key, long amount,
            Optional<UUID> job) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(MaxtechStorage.id("craft_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::containerId, Action.STREAM_CODEC, Request::action,
                ByteBufCodecs.VAR_INT, Request::request, ByteBufCodecs.optional(StackKey.STREAM_CODEC), Request::key,
                ByteBufCodecs.VAR_LONG, Request::amount, ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Request::job,
                Request::new);

        /** A request that names no key or job. */
        public static Request of(int containerId, Action action) {
            return new Request(containerId, action, 0, Optional.empty(), 0, Optional.empty());
        }

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** Server to client: the answer to plan request {@code request}. */
    public record Plan(int containerId, int request, PlanView view) implements CustomPacketPayload {
        public static final Type<Plan> TYPE = new Type<>(MaxtechStorage.id("craft_plan"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Plan> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Plan::containerId, ByteBufCodecs.VAR_INT, Plan::request, PlanView.STREAM_CODEC,
                Plan::view, Plan::new);

        @Override
        public Type<Plan> type() {
            return TYPE;
        }
    }

    /**
     * One line of the job list.
     *
     * @param crafts
     *            crafts the whole job runs
     * @param remaining
     *            crafts still to run
     * @param mine
     *            whether the viewer asked for it
     * @param cpu
     *            the position of the CPU core running it ({@code BlockPos.asLong}), or {@link #NO_CPU}
     * @param cpuTier
     *            that CPU's tier, 0 when it has none or the core is not loaded
     */
    public record JobSummary(UUID id, StackKey key, long amount, JobState state, long crafts, long remaining,
            boolean mine, long cpu, int cpuTier) {
        /** {@link #cpu} of a job no CPU runs. */
        public static final long NO_CPU = Long.MIN_VALUE;
        public static final StreamCodec<RegistryFriendlyByteBuf, JobSummary> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, JobSummary::id, StackKey.STREAM_CODEC, JobSummary::key, ByteBufCodecs.VAR_LONG,
                JobSummary::amount, STATE_CODEC, JobSummary::state, ByteBufCodecs.VAR_LONG, JobSummary::crafts,
                ByteBufCodecs.VAR_LONG, JobSummary::remaining, ByteBufCodecs.BOOL, JobSummary::mine, ByteBufCodecs.LONG,
                JobSummary::cpu, ByteBufCodecs.VAR_INT, JobSummary::cpuTier, JobSummary::new);

        /** Whether a CPU runs it. */
        public boolean onCpu() {
            return cpu != NO_CPU;
        }
    }

    /** Server to client: the network's jobs. */
    public record Jobs(int containerId, List<JobSummary> jobs) implements CustomPacketPayload {
        public static final Type<Jobs> TYPE = new Type<>(MaxtechStorage.id("craft_jobs"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Jobs> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Jobs::containerId, JobSummary.CODEC.apply(ByteBufCodecs.list(MAX_JOBS)),
                Jobs::jobs, Jobs::new);

        public Jobs {
            jobs = List.copyOf(jobs);
        }

        @Override
        public Type<Jobs> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the watched job's tree and state; its progress follows in the {@link RequestContent#PROGRESS}
     * list.
     *
     * @param missing
     *            what the job lacks (its extended report, shown for a partial job); empty in classic mode
     */
    public record Job(int containerId, UUID id, JobState state, PlanView view,
            List<PlanView.Missing> missing) implements CustomPacketPayload {
        public static final Type<Job> TYPE = new Type<>(MaxtechStorage.id("craft_job"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Job> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Job::containerId, UUIDUtil.STREAM_CODEC, Job::id, STATE_CODEC, Job::state,
                PlanView.STREAM_CODEC, Job::view,
                PlanView.Missing.STREAM_CODEC.apply(ByteBufCodecs.list(PlanView.MAX_MISSING)), Job::missing, Job::new);

        public Job {
            missing = List.copyOf(missing);
        }

        @Override
        public Type<Job> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the watched job's live tile grid, summed per key over its steps (see {@link PlanView.Tile});
     * sent when its progress changed, at most every {@link CraftingSession#STATUS_INTERVAL} ticks. Empty once it ended.
     */
    public record JobStatus(int containerId, UUID id, List<PlanView.Tile> tiles,
            List<Stuck> stuck) implements CustomPacketPayload {
        public static final Type<JobStatus> TYPE = new Type<>(MaxtechStorage.id("craft_job_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, JobStatus> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, JobStatus::containerId, UUIDUtil.STREAM_CODEC, JobStatus::id,
                PlanView.Tile.LIST_CODEC, JobStatus::tiles, Stuck.CODEC.apply(ByteBufCodecs.list(MAX_STUCK)),
                JobStatus::stuck, JobStatus::new);

        public JobStatus {
            tiles = List.copyOf(tiles);
            stuck = List.copyOf(stuck);
        }

        /** A status without stuck providers. */
        public JobStatus(int containerId, UUID id, List<PlanView.Tile> tiles) {
            this(containerId, id, tiles, List.of());
        }

        @Override
        public Type<JobStatus> type() {
            return TYPE;
        }
    }

    /** Stuck providers a status names at most. */
    public static final int MAX_STUCK = 8;

    /**
     * A provider holding up a step of the watched job (M7C item 9).
     *
     * @param pos
     *            the provider's block (a part's cable)
     * @param key
     *            what the held-up step makes
     */
    public record Stuck(BlockPos pos, StackKey key, StuckReason reason) {
        static final StreamCodec<RegistryFriendlyByteBuf, Stuck> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
                Stuck::pos, StackKey.STREAM_CODEC, Stuck::key, StuckReason.STREAM_CODEC.cast(), Stuck::reason,
                Stuck::new);
    }

    /** Server to client: a short message for the open crafting screen, as a translation key. */
    public record Notice(int containerId, String key) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = new Type<>(MaxtechStorage.id("craft_notice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Notice::containerId, ByteBufCodecs.stringUtf8(MAX_NOTICE), Notice::key,
                Notice::new);

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }
}
