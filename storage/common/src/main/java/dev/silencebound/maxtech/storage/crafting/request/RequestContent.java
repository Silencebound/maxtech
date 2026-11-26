package dev.silencebound.maxtech.storage.crafting.request;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.NetworkHelper;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.sync.SyncedListType;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.overview.CrafterManager;
import dev.silencebound.maxtech.storage.crafting.overview.PatternOverview;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.terminal.RemoteCost;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Registers the crafting request payloads and synced lists, and holds the texts of the terminal's crafting screens.
 */
public final class RequestContent {
    /** The network's craftable keys, ids of the session's own. */
    public static final SyncedListType<StackKey> CRAFTABLES = SyncedListType.register(MaxtechStorage.MOD_ID,
            MaxtechStorage.id("craftable_keys"), StackKey.STREAM_CODEC);
    /** The watched job's progress: step index to crafts still to run. */
    public static final SyncedListType<StackKey> PROGRESS = SyncedListType.register(MaxtechStorage.MOD_ID,
            MaxtechStorage.id("craft_progress"), StackKey.STREAM_CODEC);

    public static final String NO_ACCESS_KEY = notice("no_access");
    public static final String BUSY_KEY = notice("busy");
    public static final String POOL_FULL_KEY = notice("pool_full");
    public static final String NO_PLAN_KEY = notice("no_plan");
    public static final String INCOMPLETE_KEY = notice("incomplete");
    public static final String REFUSED_KEY = notice("refused");
    public static final String JOB_ENDED_KEY = notice("job_ended");
    public static final String NOT_YOURS_KEY = notice("not_yours");
    public static final String CANCELLED_KEY = notice("cancelled");
    public static final String RESUMING_KEY = notice("resuming");
    public static final String RESUME_FAILED_KEY = notice("resume_failed");

    public static final String PROVIDER_CRAFTING_KEY = gui("provider.crafting");
    public static final String PROVIDER_MACHINE_KEY = gui("provider.machine");
    public static final String REQUEST_TITLE_KEY = gui("request.title");
    public static final String AMOUNT_KEY = gui("request.amount");
    public static final String PLAN_KEY = gui("request.plan");
    public static final String START_KEY = gui("request.start");
    public static final String BACK_KEY = gui("back");
    public static final String PLANNING_KEY = gui("request.planning");
    public static final String STATUS_COMPLETE_KEY = gui("status.complete");
    public static final String STATUS_MISSING_KEY = gui("status.missing");
    public static final String STATUS_TOO_MANY_KEY = gui("status.too_many_nodes");
    public static final String STATUS_TIME_KEY = gui("status.time_limit");
    public static final String STATUS_ERROR_KEY = gui("status.error");
    public static final String STALE_KEY = gui("status.stale");
    public static final String MISSING_LINE_KEY = gui("missing_line");
    public static final String NODE_NEEDED_KEY = gui("node.needed");
    public static final String NODE_STORED_KEY = gui("node.stored");
    public static final String NODE_CRAFT_KEY = gui("node.craft");
    public static final String NODE_MISSING_KEY = gui("node.missing");
    public static final String NODE_PROVIDER_KEY = gui("node.provider");
    public static final String NODE_TIME_KEY = gui("node.time");
    public static final String NODE_LEFT_KEY = gui("node.left");
    public static final String NODE_DONE_KEY = gui("node.done");
    public static final String HIDDEN_KEY = gui("hidden");
    public static final String ESTIMATE_KEY = gui("estimate");
    public static final String ESTIMATE_UNKNOWN_KEY = gui("estimate.unknown");
    public static final String JOBS_TITLE_KEY = gui("jobs.title");
    public static final String JOBS_EMPTY_KEY = gui("jobs.empty");
    public static final String CANCEL_KEY = gui("jobs.cancel");
    public static final String RESUME_KEY = gui("jobs.resume");
    public static final String CRAFTABLE_KEY = gui("craftable");
    public static final String CRAFT_HINT_KEY = gui("craft_hint");
    public static final String FILTER_CRAFTABLE_KEY = gui("filter.craftable");
    public static final String FILTER_ALL_KEY = gui("filter.all");
    public static final String JEI_CRAFT_KEY = gui("jei.craft");
    public static final String JEI_MISSING_KEY = gui("jei.missing");
    public static final String NEXT_KEY = gui("request.next");
    public static final String AMOUNT_HINT_KEY = gui("request.amount_hint");
    public static final String START_PARTIAL_KEY = gui("request.start_partial");
    public static final String CPU_AUTO_KEY = gui("request.cpu_auto");
    public static final String CPU_TIER_KEY = gui("request.cpu_tier");
    public static final String CPU_HINT_KEY = gui("request.cpu_hint");
    public static final String MISSING_WARNING_KEY = gui("request.missing_warning");
    public static final String PARTIAL_HINT_KEY = gui("request.partial_hint");
    public static final String VIEW_TILES_KEY = gui("view.tiles");
    public static final String VIEW_TREE_KEY = gui("view.tree");
    public static final String TILE_STORED_KEY = gui("tile.stored");
    public static final String TILE_CRAFTING_KEY = gui("tile.crafting");
    public static final String TILE_MISSING_KEY = gui("tile.missing");
    public static final String TILE_SCHEDULED_KEY = gui("tile.scheduled");
    public static final String TILE_WAITING_KEY = gui("tile.waiting");
    public static final String TILE_NOT_STORED_KEY = gui("tile.not_stored");
    public static final String TILE_IN_CPU_KEY = gui("tile.in_cpu");
    public static final String TILE_FROM_STORAGE_KEY = gui("tile.from_storage");
    public static final String TILES_EMPTY_KEY = gui("tile.empty");
    public static final String JOB_CPU_KEY = gui("jobs.cpu");
    public static final String JOB_CPU_TIER_KEY = gui("jobs.cpu_tier");
    public static final String JOB_NO_CPU_KEY = gui("jobs.no_cpu");
    public static final String JOB_INDEX_KEY = gui("jobs.index");
    public static final String JOB_PROGRESS_KEY = gui("jobs.progress");
    public static final String STUCK_KEY = gui("jobs.stuck");

    static {
        ModContent.CONTENT.translation(NO_ACCESS_KEY, "You need craft access on this network");
        ModContent.CONTENT.translation(BUSY_KEY, "Still planning the last request");
        ModContent.CONTENT.translation(POOL_FULL_KEY, "The server is busy planning; try again in a moment");
        ModContent.CONTENT.translation(NO_PLAN_KEY, "Nothing planned to start");
        ModContent.CONTENT.translation(INCOMPLETE_KEY, "Some items are missing, so the job cannot start");
        ModContent.CONTENT.translation(REFUSED_KEY, "The job queue refused the job");
        ModContent.CONTENT.translation(JOB_ENDED_KEY, "That job is no longer on the network");
        ModContent.CONTENT.translation(NOT_YOURS_KEY, "Only its requester or a player with full access can cancel it");
        ModContent.CONTENT.translation(CANCELLED_KEY, "Job cancelled");
        ModContent.CONTENT.translation(RESUMING_KEY, "Planning the rest of the job again");
        ModContent.CONTENT.translation(RESUME_FAILED_KEY, "The job cannot resume now");
        ModContent.CONTENT.translation(PROVIDER_CRAFTING_KEY, "Crafting assembler");
        ModContent.CONTENT.translation(PROVIDER_MACHINE_KEY, "Machine");
        ModContent.CONTENT.translation(REQUEST_TITLE_KEY, "Craft %s");
        ModContent.CONTENT.translation(AMOUNT_KEY, "Amount");
        ModContent.CONTENT.translation(PLAN_KEY, "Plan");
        ModContent.CONTENT.translation(START_KEY, "Start");
        ModContent.CONTENT.translation(BACK_KEY, "Back");
        ModContent.CONTENT.translation(PLANNING_KEY, "Planning...");
        ModContent.CONTENT.translation(STATUS_COMPLETE_KEY, "Ready to craft");
        ModContent.CONTENT.translation(STATUS_MISSING_KEY, "Missing items");
        ModContent.CONTENT.translation(STATUS_TOO_MANY_KEY,
                "Too large: the tree has more steps than the server allows");
        ModContent.CONTENT.translation(STATUS_TIME_KEY, "Too large: planning took longer than the server allows");
        ModContent.CONTENT.translation(STATUS_ERROR_KEY, "Planning failed; see the server log");
        ModContent.CONTENT.translation(STALE_KEY, "Stock changed while planning; the job takes what is there");
        ModContent.CONTENT.translation(MISSING_LINE_KEY, "%s x %s");
        ModContent.CONTENT.translation(NODE_NEEDED_KEY, "Needed: %s");
        ModContent.CONTENT.translation(NODE_STORED_KEY, "From storage: %s");
        ModContent.CONTENT.translation(NODE_CRAFT_KEY, "To craft: %s (%s crafts)");
        ModContent.CONTENT.translation(NODE_MISSING_KEY, "Missing: %s");
        ModContent.CONTENT.translation(NODE_PROVIDER_KEY, "Made by: %s");
        ModContent.CONTENT.translation(NODE_TIME_KEY, "About %s");
        ModContent.CONTENT.translation(NODE_LEFT_KEY, "%s crafts left");
        ModContent.CONTENT.translation(NODE_DONE_KEY, "Done");
        ModContent.CONTENT.translation(HIDDEN_KEY, "%s more steps not shown");
        ModContent.CONTENT.translation(ESTIMATE_KEY, "Up to %s");
        ModContent.CONTENT.translation(ESTIMATE_UNKNOWN_KEY, "Time unknown");
        ModContent.CONTENT.translation(JOBS_TITLE_KEY, "Crafting jobs");
        ModContent.CONTENT.translation(JOBS_EMPTY_KEY, "No jobs on this network");
        ModContent.CONTENT.translation(CANCEL_KEY, "Cancel");
        ModContent.CONTENT.translation(RESUME_KEY, "Resume");
        ModContent.CONTENT.translation(CRAFTABLE_KEY, "Craftable");
        ModContent.CONTENT.translation(CRAFT_HINT_KEY, "Middle-click or Ctrl-click to request crafting");
        ModContent.CONTENT.translation(FILTER_CRAFTABLE_KEY, "Showing craftable entries");
        ModContent.CONTENT.translation(FILTER_ALL_KEY, "Showing every entry");
        ModContent.CONTENT.translation(JEI_CRAFT_KEY, "Click to plan crafting %s");
        ModContent.CONTENT.translation(JEI_MISSING_KEY, "Missing from the network");
        ModContent.CONTENT.translation(NEXT_KEY, "Next");
        ModContent.CONTENT.translation(AMOUNT_HINT_KEY, "Shift-click for ten times the step");
        ModContent.CONTENT.translation(START_PARTIAL_KEY, "Start partial");
        ModContent.CONTENT.translation(CPU_AUTO_KEY, "CPU: Auto");
        ModContent.CONTENT.translation(CPU_TIER_KEY, "CPU: Tier %s");
        ModContent.CONTENT.translation(CPU_HINT_KEY,
                "Which CPU tier may run the job; Auto takes the smallest free CPU");
        ModContent.CONTENT.translation(MISSING_WARNING_KEY, "Some items are missing (%s)");
        ModContent.CONTENT.translation(PARTIAL_HINT_KEY,
                "Start partial crafts what it can, then waits for the missing items");
        ModContent.CONTENT.translation(VIEW_TILES_KEY, "Tiles");
        ModContent.CONTENT.translation(VIEW_TREE_KEY, "Tree");
        ModContent.CONTENT.translation(TILE_STORED_KEY, "Stored: %s");
        ModContent.CONTENT.translation(TILE_CRAFTING_KEY, "Crafting: %s");
        ModContent.CONTENT.translation(TILE_MISSING_KEY, "Missing: %s");
        ModContent.CONTENT.translation(TILE_SCHEDULED_KEY, "Scheduled: %s");
        ModContent.CONTENT.translation(TILE_WAITING_KEY, "Waiting: %s");
        ModContent.CONTENT.translation(TILE_NOT_STORED_KEY, "not in storage");
        ModContent.CONTENT.translation(TILE_IN_CPU_KEY, "Stored is what the CPU holds now");
        ModContent.CONTENT.translation(TILE_FROM_STORAGE_KEY, "Stored is taken from storage");
        ModContent.CONTENT.translation(TILES_EMPTY_KEY, "Nothing left to do");
        ModContent.CONTENT.translation(JOB_CPU_KEY, "CPU at %s %s %s");
        ModContent.CONTENT.translation(JOB_CPU_TIER_KEY, "Tier %s CPU at %s %s %s");
        ModContent.CONTENT.translation(JOB_NO_CPU_KEY, "Not on a CPU");
        ModContent.CONTENT.translation(JOB_INDEX_KEY, "%s / %s");
        ModContent.CONTENT.translation(JOB_PROGRESS_KEY, "%s%% done");
        ModContent.CONTENT.translation(STUCK_KEY, "Stuck making %s: the provider at %s %s %s says %s");
        for (JobState state : JobState.values()) {
            ModContent.CONTENT.translation(stateKey(state), switch (state) {
                case PLANNED -> "Waiting for a CPU";
                case RUNNING -> "Running";
                case PARTIAL -> "Stopped: something is missing";
                case DONE -> "Done";
                case CANCELLED -> "Cancelled";
            });
        }
    }

    private RequestContent() {
    }

    /** The translation key of a job state's name. */
    public static String stateKey(JobState state) {
        return gui("state." + state.getSerializedName());
    }

    /** Registers the payloads; called once during mod construction. */
    public static void init() {
        JobQueue.listen(CraftingSession::jobChanged);
        PatternOverview.init();
        CrafterManager.init();
        OrderList.init();
        CraftDestinations.register();
        // Loads the stuck reasons' texts before datagen runs.
        StuckReason.values();
        NetworkHelper network = Platform.get().network();
        network.registerToServer(MaxtechStorage.MOD_ID, RequestPayloads.Request.TYPE, RequestPayloads.Request.CODEC,
                (payload, player) -> handle(player, payload));
        network.registerToClient(MaxtechStorage.MOD_ID, RequestPayloads.Plan.TYPE, RequestPayloads.Plan.CODEC,
                (payload, player) -> {
                    CraftingClientData data = data(player, payload.containerId());
                    if (data != null) {
                        data.accept(payload);
                    }
                });
        network.registerToClient(MaxtechStorage.MOD_ID, RequestPayloads.Jobs.TYPE, RequestPayloads.Jobs.CODEC,
                (payload, player) -> {
                    CraftingClientData data = data(player, payload.containerId());
                    if (data != null) {
                        data.accept(payload);
                    }
                });
        network.registerToClient(MaxtechStorage.MOD_ID, RequestPayloads.Job.TYPE, RequestPayloads.Job.CODEC,
                (payload, player) -> {
                    CraftingClientData data = data(player, payload.containerId());
                    if (data != null) {
                        data.accept(payload);
                    }
                });
        network.registerToClient(MaxtechStorage.MOD_ID, RequestPayloads.JobStatus.TYPE, RequestPayloads.JobStatus.CODEC,
                (payload, player) -> {
                    CraftingClientData data = data(player, payload.containerId());
                    if (data != null) {
                        data.accept(payload);
                    }
                });
        network.registerToClient(MaxtechStorage.MOD_ID, RequestPayloads.Notice.TYPE, RequestPayloads.Notice.CODEC,
                (payload, player) -> {
                    CraftingClientData data = data(player, payload.containerId());
                    if (data != null) {
                        data.accept(payload);
                    }
                });
    }

    /**
     * Runs {@code request} for {@code player}'s open, valid terminal session; false when there is none or it was
     * refused. Requests that act (plan, start, cancel, resume) pay the terminal's action cost ({@link RemoteCost}: a
     * wireless terminal's charge), only when they go through; watching the job list or a job is free, like browsing the
     * terminal's contents.
     */
    public static boolean handle(Player player, RequestPayloads.Request request) {
        CraftingSession session = session(player, request.containerId());
        if (session == null) {
            return false;
        }
        return switch (request.action()) {
            case PLAN, START, CANCEL_JOB, RESUME_JOB ->
                RemoteCost.run(source(player, request.containerId()), () -> session.handle(request));
            case WATCH_JOBS, VIEW_JOB, UNWATCH -> session.handle(request);
        };
    }

    /** The source of the sender's open, valid terminal menu with {@code containerId}, or null. */
    public static @Nullable TerminalSource source(Player player, int containerId) {
        return player.containerMenu instanceof TerminalMenu menu && menu.containerId == containerId
                && menu.stillValid(player) ? menu.source() : null;
    }

    /** The sender's open, valid terminal session with {@code containerId}, or null. */
    public static @Nullable CraftingSession session(Player player, int containerId) {
        return player.containerMenu instanceof TerminalMenu menu && menu.containerId == containerId
                && menu.stillValid(player) ? menu.crafting() : null;
    }

    private static @Nullable CraftingClientData data(Player player, int containerId) {
        return player.containerMenu instanceof TerminalMenu menu && menu.containerId == containerId
                ? menu.craftingData()
                : null;
    }

    private static String notice(String name) {
        return "message." + MaxtechStorage.MOD_ID + ".craft." + name;
    }

    private static String gui(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".craft." + name;
    }
}
