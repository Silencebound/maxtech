package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmCenter;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongSortedMap;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.network.ByteCapacity;
import dev.silencebound.maxtech.storage.network.EnergyWatcher;
import dev.silencebound.maxtech.storage.network.NetworkController;
import dev.silencebound.maxtech.storage.network.NetworkEnergyCell;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.condition.ConditionDevice;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafts;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperWorker;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.port.PortDevice;
import dev.silencebound.maxtech.storage.port.PortFilter;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.display.DisplayDevice;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplayText;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.world.WorldActors;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.network.LogicMover;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.display.task.TaskBoards;
import dev.silencebound.maxtech.storage.display.task.TaskNames;
import net.minecraft.server.MinecraftServer;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobEvents;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.logic.CompileResult;
import dev.silencebound.maxtech.storage.logic.CompiledGraph;
import dev.silencebound.maxtech.storage.sensor.SensorTexts;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicCompiler;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.LogicContext;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicMemory;
import dev.silencebound.maxtech.storage.logic.RuleCalls;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.Parameters;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.network.ItemMoveListener;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Pools;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import dev.silencebound.maxtech.storage.logic.Watch;
import dev.silencebound.maxtech.storage.logic.WatchKind;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.NetworkProfiler;
import dev.silencebound.maxtech.storage.network.ProfileTally;
import dev.silencebound.maxtech.storage.network.PowerPriority;
import dev.silencebound.maxtech.storage.network.RedstoneChannels;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.network.SwitchSource;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What a Logic Controller does (M7L area B): holds its graphs, compiles them, wires what they watch to network events
 * and runs them on the network's scheduler under the team's budgets of checks and action points ({@link TeamBudget}).
 *
 * <p>
 * Events only (PRD 8.1). The worker listens to exactly what its running graphs watch: the keys of item counts
 * ({@link KeyListener}), every committed total while a graph counts a tag ({@link ContentsWatcher}), the network's
 * device, status and power changes, finished crafting jobs ({@link JobEvents}), redstone into the controller and
 * sensors ({@link SensorSource}), the stored energy, and job changes in the level ({@link CraftWatch}). Energy and free
 * storage can change every tick, so like link load they are only marked and looked at once per
 * {@code condition.minIntervalTicks}. A listener computes the watched value and hands it to the graph only when it
 * differs from the last one and could change something ({@link LogicEvaluator#matters}: a count that crosses no limit
 * costs nothing), then wakes the task. The task runs each graph with work at most once per pass, so triggers within a
 * tick are one run; a delay asks for one timed wake; a loop asks for the next tick.
 *
 * <p>
 * Budget: a graph runs while its team has checks left this second, and when it can act also action points, and pays its
 * {@link LogicEvaluator#lastCost} and {@link LogicEvaluator#lastActionPoints} afterwards; over either budget it waits
 * for the next second in the queue ({@link #waitingFor} says which), and player work runs first in the scheduler. While
 * the network is not online (unloaded parts in pause mode, quarantine) nothing runs; when it is back, every graph runs
 * once.
 *
 * <p>
 * Actions (area E) run when the graph's run sends its batch, at the end of the run, each at most once per run (the
 * evaluator merges settings and drops repeated one-shot work): device on/off through the devices' saved switch,
 * priority through the shared priority setting, redstone through condition switches in logic mode, crafting requests as
 * background jobs of the controller's owner (one at a time per item), moves through the labelled ports' and interfaces'
 * own handlers ({@link LogicMover}), and notices to the network's log, its displays, the team's toasts and chat. Batch
 * 3 adds cancelling crafts through the job queue's cancel, texts on labelled displays (at most one write per display
 * every {@code logic.displayWriteTicks}, a later text waiting for one scheduled wake) and port filters set as the
 * port's screen would; every action changes only devices the owner may configure. What an action could not do goes to
 * the graph's why trace. Each action is also kept in {@link #actions()} for tests and a debug log line.
 *
 * <p>
 * Batch 4 (hooks): committed moves of the items a graph watches, heard only for those keys
 * ({@link NetworkState#listenItemMoves}); crafting jobs that stopped with something missing ({@link JobEvents});
 * terminals opened ({@link NetworkState#watchTerminals}); players near labelled devices, looked at every
 * {@code logic.playerCheckTicks} while a graph uses them; shared variables the graphs set and read by name (saved, at
 * most {@code logic.maxVariables}); and graphs running each other, where graphs that would run each other in a circle
 * are paused with an error and calls nest at most {@code logic.maxRuleDepth} deep.
 *
 * <p>
 * Debugging (M7L2 chunk 8): a graph in simulate mode runs as usual but only records its actions ({@link #setSimulate});
 * each save keeps the replaced version when it worked, for {@link #revert}; and pinned node values ({@link #setPins})
 * are read for the open controller screen without allocating. Server thread only.
 */
public final class LogicWorker
        implements
            StorageTask,
            LogicContext<LogicKey>,
            KeyListener,
            ContentsWatcher,
            JobEvents.KeyWatcher,
            JobEvents.FailWatcher,
            ItemMoveListener,
            NetworkState.TerminalWatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Collected actions kept for area E and tests; the oldest go first. */
    public static final int KEPT_ACTIONS = 64;

    /** What the worker needs from the block entity that holds it. */
    public interface Host {
        /** The level the controller is in; null before it is placed. */
        @Nullable
        ServerLevel serverLevel();

        /** The redstone strength (0 to 15) coming into the controller from its neighbours, read now. */
        int redstoneIn();

        /** Something to save changed. */
        void changed();
    }

    /** A graph's state as the screen and Jade show it. */
    public enum Status {
        RUNNING, PAUSED, DISABLED, WAITING, IDLE;

        /** A small number for the screen's data slots; never saved. */
        public int id() {
            return switch (this) {
                case RUNNING -> 0;
                case PAUSED -> 1;
                case DISABLED -> 2;
                case WAITING -> 3;
                case IDLE -> 4;
            };
        }

        public static Status byId(int id) {
            return switch (id) {
                case 0 -> RUNNING;
                case 1 -> PAUSED;
                case 2 -> DISABLED;
                case 3 -> WAITING;
                default -> IDLE;
            };
        }
    }

    /** The two budgets of a team ({@link TeamBudget}): checks for nodes, action points for actions. */
    public enum Budget {
        CHECKS, ACTIONS
    }

    /** One graph slot at run time. */
    private static final class Graph {
        GraphSlot slot;
        /** The graph's own compile result. */
        final CompileResult<LogicKey> compiled;
        /**
         * What runs: {@link #compiled}, or a paused result while the graph is on a loop of rules running each other.
         */
        CompileResult<LogicKey> result;
        /** The graph is on a loop of rules running each other, so {@link #result} holds the loop errors. */
        boolean looped;
        /**
         * The graph names another network that is missing or that its owner may not use (M7L2 row 13), so
         * {@link #result} holds those errors.
         */
        boolean netBlocked;
        /** The other networks {@link #compiled} names, made once on first use; empty for most graphs. */
        @Nullable
        List<CompiledGraph.NetworkUse> networks;
        /**
         * Per watch, the {@link NetworkRef#key} of the other network its label names, or empty for the controller's
         * own; looked up in {@link LogicWorker#remotes} without allocating.
         */
        String[] remoteKeys = new String[0];
        /**
         * Per watch on another network, the value saved with the controller ({@link LogicWorker#remoteValues}), which
         * stands in while that network is not usable and nothing was seen since loading; {@link Long#MIN_VALUE} for
         * none.
         */
        long[] remoteSeed = new long[0];
        /** How deep the call that runs the graph next is ("Run another rule"); 0 when nothing called it. */
        int pendingDepth;
        @Nullable
        LogicEvaluator<LogicKey> evaluator;
        /** Per watch, the value last handed to the evaluator; {@link Long#MIN_VALUE} for none. */
        long[] offered = new long[0];
        /** Per watch, its label as a {@link DeviceLabel#key()}, so lookups allocate nothing. */
        String[] labelKeys = new String[0];
        int lastCost;
        /** Action points the last run's actions cost. */
        int lastPoints;
        boolean waiting;
        /** While {@link #waiting}: whether it waits for action points ({@link Budget#ACTIONS}) or checks. */
        Budget waitingFor = Budget.CHECKS;
        /** The game time of the last run, or -1 before the first; for the controller screen. */
        long lastRun = -1;
        /** Actions the last run asked for; the newest of this graph in {@link #actions}. */
        int lastActions;
        /** Checks this graph paid recently, for the admin overview. */
        final RecentRate rate = new RecentRate();
        /** Action points this graph paid recently, for the admin overview. */
        final RecentRate pointsRate = new RecentRate();
        /** Server time of this graph's runs in the running or latest network profiler session. */
        final ProfileTally profile = new ProfileTally();
        /**
         * While the graph simulates: the memory it had when simulating began. Saved in its place and given back when
         * simulating ends, so simulated runs never change what the real graph remembers.
         */
        LogicMemory realMemory;
        /**
         * While the graph simulates: the shared variables its own runs set, which only it reads; null until the first.
         * Not saved.
         */
        @Nullable
        Object2LongOpenHashMap<String> shadow;
        /** Where its pinned values come from; made with the evaluator. */
        PinnedValues pins = PinnedValues.NONE;
        /** Which save made this graph ({@link LogicWorker#edit}), so an open editor hears of a revert. */
        final long edit;

        Graph(GraphSlot slot, CompileResult<LogicKey> compiled, long edit) {
            this.slot = slot;
            this.compiled = compiled;
            this.result = compiled;
            this.realMemory = slot.memory();
            this.edit = edit;
        }
    }

    /** An item or fluid tag some graph counts: its network total, kept from committed changes. */
    private static final class TagTally {
        /** A {@link LogicKey.Tag} or a {@link LogicKey.FluidTag}. */
        final LogicKey tag;
        /** Key id to 1 (in the tag) or 2 (not), so each id is matched once. */
        final Int2ByteOpenHashMap matches = new Int2ByteOpenHashMap();
        final Int2LongOpenHashMap totals = new Int2LongOpenHashMap();
        long total;
        /** Watches counting this tag, as {@link #ref}s. */
        final IntArrayList refs = new IntArrayList(1);

        TagTally(LogicKey tag) {
            this.tag = tag;
        }
    }

    /** Adds up one provider's items (or fluids) in a tag without allocating per call. */
    private static final class TagSum implements StorageProvider.Visitor {
        @Nullable
        LogicKey tag;
        long sum;

        @Override
        public void visit(StackKey key, long amount) {
            LogicKey current = tag;
            if (current != null && inTag(key, current)) {
                sum += amount;
            }
        }
    }

    /** Most characters of a failed script's message shown on its node. */
    private static final int MAX_SCRIPT_MESSAGE = 200;

    /** How a named other network stands for this controller's rules (M7L2 row 13). */
    public enum RemoteState {
        /** Loaded, online, and the owner has the logic access level on it: reads and actions go through. */
        OK,
        /**
         * Known by name but its controller is not loaded, or the network is not online: reads keep their last value.
         */
        UNAVAILABLE,
        /** No network has the name: the rules naming it pause. */
        MISSING,
        /** The owner lacks the logic access level on it (or the controller has no owner): the rules naming it pause. */
        DENIED
    }

    /**
     * Another network the graphs name (M7L2 row 13), by name. While it is loaded the controller listens to it like to
     * its own network, but only marks it: every listener event sets {@link #dirty}, and the task offers the watches on
     * it again at most once per {@code condition.minIntervalTicks}. Its devices watcher also hears status changes,
     * which resolve the network again. Listeners go when the graphs stop naming it, the network is let go or replaced,
     * or the controller leaves its own network.
     */
    private final class Remote implements KeyListener, ContentsWatcher {
        /** {@link NetworkRef#key} of the name. */
        final String key;
        /** The name as the first graph typed it. */
        final String name;
        RemoteState status = RemoteState.MISSING;
        /** The network while its controller is loaded, online or not; null otherwise. */
        @Nullable
        NetworkState network;
        /** Where the listeners are; null while none are. */
        @Nullable
        NetworkState listening;
        /** Watches on this network, as {@link #ref}s. */
        final IntArrayList refs = new IntArrayList();
        final ObjectArrayList<StackKey> keys = new ObjectArrayList<>();
        final ObjectArrayList<SensorSource> sensors = new ObjectArrayList<>();
        boolean contents;
        boolean energy;
        boolean fill;
        /** A listener fired since the last look. */
        boolean dirty;
        /** The devices changed since the last look: sensors are matched again. */
        boolean devices;
        final Runnable devicesWatcher = this::devicesChanged;
        final Runnable markWatcher = this::mark;
        final EnergyWatcher energyWatcher = (stored, capacity) -> mark();

        Remote(String key, String name) {
            this.key = key;
            this.name = name;
        }

        /** The network to read and act on, or null when it is not usable now. */
        @Nullable
        NetworkState usable() {
            return status == RemoteState.OK ? network : null;
        }

        @Override
        public void keyChanged(StackKey changed, long total) {
            mark();
        }

        @Override
        public void changed(int id, long total) {
            mark();
        }

        @Override
        public void released(int id) {
            // Nothing is kept per id.
        }

        private void devicesChanged() {
            devices = true;
            NetworkState now = network;
            boolean online = now != null && now.status() == StorageNetwork.Status.ONLINE;
            if (online != (status == RemoteState.OK)) {
                // Paused or back online: resolve it again on the next pass.
                namesDirty = true;
            }
            mark();
        }

        void mark() {
            if (!dirty) {
                dirty = true;
                remotesDirty = true;
                wake();
            }
        }
    }
    private final NetworkDevice device;
    private final Host host;
    private final ObjectArrayList<Graph> graphs = new ObjectArrayList<>();
    private final Runnable devicesWatcher = this::devicesChanged;
    private final Runnable sensorWatcher = this::sensorsChanged;
    private final Runnable loadWatcher = this::loadChanged;
    private final Runnable fillWatcher = this::fillChanged;
    private final NetworkState.NeighbourWatcher neighbourWatcher = this::neighbourChanged;
    private final SkyWatch.Listener skyWatcher = this::skyChanged;
    private final EnergyWatcher energyWatcher = (stored, capacity) -> energyChanged();
    private final CraftWatch.Listener craftWatcher = new CraftWatch.Listener() {
        @Override
        public void jobChanged(ServerLevel level, CraftingJob job) {
            LogicWorker.this.jobChanged(level, job);
        }

        @Override
        public void cpusChanged(ServerLevel level) {
            craftChanged();
        }
    };
    /** This network's waiting and running jobs, while some running graph reads the crafting status. */
    private final CraftTally craftTally = new CraftTally();
    /** Some running graph reads energy: the energy watcher is bound. */
    private boolean watchesEnergy;
    /** Some running graph reads free storage: content changes mark it. */
    private boolean watchesSpace;
    /** Some running graph reads the crafting status: the craft watcher is bound. */
    private boolean watchesCraft;
    /** The level the craft watcher is bound in; null while unbound. */
    private @Nullable ServerLevel craftLevel;
    /** A crafting job or CPU changed since the last look. */
    private boolean craftDirty;
    /** The stored energy changed since the last look. */
    private boolean energyDirty;
    /** The network's contents or cells changed since the last look at free storage. */
    private boolean spaceDirty;
    /** The game time before which energy and free storage are not looked at again ({@code minIntervalTicks}). */
    private long nextSampleLook;
    /** Some running graph reads the day or the weather: the sky watcher is bound. */
    private boolean watchesSky;
    /** Some running graph watches item moves: the move listeners are bound for {@link #moveRefs}' keys. */
    private boolean watchesMoves;
    /** Some running graph looks for players nearby: the task looks every {@code logic.playerCheckTicks}. */
    private boolean watchesPlayers;
    /** Some running graph waits for a terminal to be opened: the terminal watcher is bound. */
    private boolean watchesTerminals;
    /** Some running graph waits for a crafting job that failed: the fail watcher is bound. */
    private boolean watchesFails;
    /** Whether a running graph reads a task board (M7C item 15); the boards then tell this controller of changes. */
    private boolean watchesTasks;
    private @Nullable MinecraftServer taskServer;
    private final Runnable taskWatcher = this::tasksChanged;
    /** The game time of the next look for players nearby. */
    private long nextPlayerLook;
    /** Watches of item moves, as {@link #ref}s, so a move walks only them. */
    private final IntArrayList moveRefs = new IntArrayList();
    /** The shared variables by name, in the order they were first set; values of yes/no ones are 1 or 0. */
    private final Object2LongLinkedOpenHashMap<String> variables = new Object2LongLinkedOpenHashMap<>();
    /** Names of the shared variables last set as a yes/no. */
    private final ObjectOpenHashSet<String> yesNoVariables = new ObjectOpenHashSet<>();
    /** How deep the call is that the graph running now was run by; 0 when nothing called it. */
    private int depthNow;
    /** The level the sky watcher is bound in; null while unbound. */
    private @Nullable ServerLevel skyLevel;
    /** A crafting job a graph of this controller requested; kept while the job runs and the controller is loaded. */
    private record OwnJob(int graph, StackKey key, UUID id) {
    }

    /** What logic last wrote to one display, and a text that waits for the display's next write. */
    private static final class DisplayWrite {
        long last = Long.MIN_VALUE;
        @Nullable
        String pending;
        /** The label key the waiting text was written for; checked again when it is shown. */
        String labelKey;
        long due;

        DisplayWrite(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    /**
     * A kept answer to "may the controller's owner configure this device", valid until {@code expires} while both
     * owners stay as they were; {@code logged} says the refusal is in the network's log already.
     */
    private static final class AccessAnswer {
        boolean allowed;
        long expires;
        OwnerRecord owner = OwnerRecord.UNOWNED;
        OwnerRecord target = OwnerRecord.UNOWNED;
        boolean logged;
    }

    /**
     * Block positions of condition switches a pulse of this controller turned on, with the strength it set; its end
     * turns them off without a new access check. Saved with the controller ({@link #pulsedSwitches}), so a pulse
     * running at a save still ends after the load.
     */
    private final Long2IntOpenHashMap pulsed = new Long2IntOpenHashMap();
    /**
     * This controller's share of each network redstone channel (M7L2), 0 to 15, as its graphs last wrote it (any graph,
     * the last write wins). Saved with the controller and handed to whatever network it joins; it stays when a graph is
     * removed, like a labelled switch's strength, until a graph writes another value or the controller is removed.
     */
    private final int[] channelShares = new int[RedstoneChannels.COUNT];
    private final RedstoneChannels.Listener channelWatcher = this::channelChanged;
    private final Runnable sheddingWatcher = this::sheddingChanged;
    /** Access answers per target device; weak, so a removed device is dropped. */
    private final WeakHashMap<NetworkDevice, AccessAnswer> access = new WeakHashMap<>();

    /** Jobs the graphs requested, for cancelling only a graph's own; not saved, so a restart forgets them. */
    private final ObjectArrayList<OwnJob> ownJobs = new ObjectArrayList<>();
    /** Per display written by logic, the last write and a waiting text; weak, so a removed display is dropped. */
    private final WeakHashMap<DisplayDevice, DisplayWrite> displayWrites = new WeakHashMap<>();
    /** Displays with a waiting text; the task wakes for them. */
    private int pendingDisplays;
    /** The game time the first waiting text is due, while {@link #pendingDisplays} is above 0. */
    private long displayDue = Long.MAX_VALUE;
    /** Items with a craft request of this controller out (submitted, not ended): never a second one. */
    private final ObjectOpenHashSet<StackKey> crafting = new ObjectOpenHashSet<>();
    /** Item and fluid keys listened to, to each watch counting it. */
    private final Object2ObjectOpenHashMap<StackKey, IntArrayList> keyRefs = new Object2ObjectOpenHashMap<>();
    private final ObjectArrayList<TagTally> tags = new ObjectArrayList<>();
    private final ObjectArrayList<SensorSource> sensors = new ObjectArrayList<>();
    /** The machine pools graphs read and send to (M7L2), by label key; built again on label and device changes. */
    private final MachinePools pools = new MachinePools(this::poolMachineChanged);
    /** A pool member's machine changed (neighbour update or capabilities); looked at on the task's next pass. */
    private boolean poolsDirty;
    /** The earliest game time pools are looked at again after a machine change ({@code condition.minIntervalTicks}). */
    private long nextPoolLook;
    /**
     * Per pool-member-idle watch ({@link #ref}), which members were idle at the last look, one bit each in member
     * order; a member whose bit turns on fires the event. Set without firing whenever the sensors are bound again.
     */
    private final Int2ObjectOpenHashMap<Pools.IdleBits> poolIdle = new Int2ObjectOpenHashMap<>();
    /** Reused while a pool send is planned and carried out, grown with the largest pool. */
    private final Pools.Planner poolPlanner = new Pools.Planner();
    private boolean[] poolCandidate = new boolean[8];
    private long[] poolHeld = new long[8];
    private long[] poolMoved = new long[8];
    /** The stock keepers a running graph reads (M7L2 chunk 15); each tells {@link #stockWatcher} of its changes. */
    private final ObjectArrayList<StockKeeperWorker> keepers = new ObjectArrayList<>();
    private final StockKeeperWorker.Listener stockWatcher = keeper -> offerAll(WatchKind.STOCK, false);
    private final ObjectArrayList<LogicAction> actions = new ObjectArrayList<>();
    private final TagSum tagSum = new TagSum();
    /** For each (M7L2 row 18): the last label a branch read and its key, so a pass's reads allocate nothing. */
    private String eachLabel = "";
    private String eachLabelKey = "";
    /** For each: the logic key of each stock rule's item, kept so a run over a keeper's rules allocates nothing. */
    private final Object2ObjectOpenHashMap<StackKey, LogicKey> stockKeys = new Object2ObjectOpenHashMap<>();
    /** Checks the running graph's team had left when its run started; a For each stops there. */
    private long budgetLeft = Long.MAX_VALUE;
    /** The action points the running graph's team had left when the run started; unlimited between runs. */
    private long pointsLeft = Long.MAX_VALUE;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private @Nullable NetworkState bound;
    /** Rules paused by errors the alarm panel last heard of ({@link #reportPaused}). */
    private int reportedPaused;
    /** The other networks the graphs name (M7L2 row 13); built again when the graphs change. */
    private final ObjectArrayList<Remote> remotes = new ObjectArrayList<>();
    /** Told by {@link NetworkNames} of every named network's change; registered while {@link #remotes} has any. */
    private final Runnable namesWatcher = this::namesChanged;
    /** A named network changed: every remote is resolved again on the next pass. */
    private boolean namesDirty;
    /** Some remote's listener fired: its watches are offered again on the next look. */
    private boolean remotesDirty;
    /** The game time before which remotes are not looked at again ({@code condition.minIntervalTicks}). */
    private long nextRemoteLook;
    /** The game time from which the access answers of the remotes are asked again ({@code claims.cacheSeconds}). */
    private long nextAccessLook = Long.MAX_VALUE;
    /** The surcharge the running graph owes for its reads and actions on other networks. */
    private int extraChecks;
    private int extraPoints;
    /** The last {@link #labelled} call found its network unavailable, so no "no device" line is added too. */
    private boolean targetsUnavailable;
    /** Items with a craft request of this controller out on another network, as network key and item. */
    private final ObjectOpenHashSet<RemoteCraft> remoteCrafting = new ObjectOpenHashSet<>();
    /** For each: the {@link NetworkRef#key} of the network {@link #eachLabel} names; empty for the own network. */
    private String eachNetworkKey = "";

    /** Values of other networks' watches saved with the controller ({@link #remoteValues}), by watch key. */
    private final Object2LongOpenHashMap<String> remoteSaved = new Object2LongOpenHashMap<>();

    /** A craft request out on another network. */
    private record RemoteCraft(String network, StackKey key) {
    }

    private boolean started;
    /** The network was seen not online; when it is back every graph runs once. */
    private boolean offline;
    private boolean powered;
    private int redstone;
    /** The graph running now, for the action calls; -1 between runs. */
    private int running = -1;
    /** The game time of the run in progress, for the action calls. */
    private long now;
    /** Some running graph watches link load: the load watcher is bound. */
    private boolean watchesLoad;
    /** Channel use changed since the last load look. */
    private boolean loadDirty;
    /** The game time before which load is not looked at again ({@code condition.minIntervalTicks}). */
    private long nextLoadLook;
    private long lastMoved;
    private long statSecond;
    private long statChecks;
    private long lastSecondChecks;
    private long statPoints;
    private long lastSecondPoints;
    private int waiting;
    private long runs;
    private long taskRuns;
    /** Counts saves, removals, switches and loads, so the screen's log knows a graph changed. */
    private long revision;
    /** Counts graph versions made by loads, saves and reverts ({@link Graph#edit}). */
    private long edits;
    /** Counts history entries added, graphs removed and loads ({@link #historyRevision}). */
    private long historyRevision;

    public LogicWorker(NetworkDevice device, Host host) {
        this.device = device;
        this.host = host;
    }

    // ---- graphs

    /** Graphs held, running or not. */
    public int size() {
        return graphs.size();
    }

    /** Most graphs that run ({@code logic.maxGraphs}); graphs past it are kept but do not run. */
    public static int maxGraphs() {
        return StorageServerConfig.LOGIC_MAX_GRAPHS.getAsInt();
    }

    /** The graphs as saved: their memory is taken from their evaluators now. Allocates; for saving. */
    public List<GraphSlot> slots() {
        List<GraphSlot> out = new ArrayList<>(graphs.size());
        for (Graph graph : graphs) {
            out.add(saved(graph));
        }
        return out;
    }

    /**
     * The graph's slot as it would be saved now: with its evaluator's memory, or while it simulates with the memory it
     * had when simulating began.
     */
    private static GraphSlot saved(Graph graph) {
        LogicEvaluator<LogicKey> evaluator = graph.evaluator;
        GraphSlot slot = graph.slot;
        if (evaluator == null) {
            return slot;
        }
        return slot.withMemory(slot.simulate() ? graph.realMemory : evaluator.memory());
    }

    /**
     * Takes saved graphs, as the block entity loads or is placed from an item: each is compiled and gets its memory, so
     * on the next run it reads everything once (memory keeps crossings from firing again).
     */
    public void load(List<GraphSlot> saved) {
        revision++;
        historyRevision++;
        graphs.clear();
        // Every rule gets an id once (older saves have none), before copies look for their templates by id.
        List<GraphSlot> withIds = new ArrayList<>(saved.size());
        for (GraphSlot slot : saved) {
            withIds.add(slot);
        }
        for (int g = 0; g < withIds.size(); g++) {
            GraphSlot slot = withIds.get(g);
            if (slot.id() == 0 || idTaken(withIds, g, slot.id())) {
                withIds.set(g, slot.withId(freshId(withIds)));
            }
        }
        for (GraphSlot slot : withIds) {
            // Pins and history follow the current config, which may be lower than when the slot was saved. A copy's
            // graph is made from its template's (M7L2 row 19), which cleans its pins then.
            GraphSlot kept = slot.withHistory(HistoryEntry.trim(slot.history(), historyEntries()));
            if (kept.instance().isEmpty()) {
                kept = kept.withPins(GraphSlot.cleanPins(slot.pins(), slot.graph(), maxPins()));
            }
            Graph graph = made(kept, withIds);
            graphs.add(graph);
            prepare(graph, slot.memory());
        }
        checkRuleLoops();
        if (started) {
            rebind();
            wake();
        }
    }

    /**
     * Saves {@code graph} as graph {@code index} (a new one at the end when {@code index} is the count), keeping its
     * pinned values where their nodes are still there. See {@link #setGraph(int, String, LogicGraph, List)}.
     */
    public Optional<CompileResult<LogicKey>> setGraph(int index, String name, LogicGraph<LogicKey> graph) {
        List<Integer> pins = index >= 0 && index < graphs.size() ? graphs.get(index).slot.pins() : List.of();
        return setGraph(index, name, graph, pins);
    }

    /**
     * Saves {@code graph} as graph {@code index} (a new one at the end when {@code index} is the count) with
     * {@code pins} pinned (ids of its nodes, at most {@code logic.maxPins}) and applies it at once: the previous
     * version stops; if the new one has errors it is kept but paused and never runs, not even in part, until a save
     * without errors. Unchanged nodes keep their memory; a simulating graph keeps simulating. The version it replaces
     * becomes the graph's last working version (for {@link #revert}) when it compiled and was not paused for a loop of
     * rules; otherwise the last working version stays. Returns the compile result for the per-node errors; empty when
     * {@code index} is out of range.
     */
    public Optional<CompileResult<LogicKey>> setGraph(int index, String name, LogicGraph<LogicKey> graph,
            List<Integer> pins) {
        if (index < 0 || index > graphs.size() || index >= GraphSlot.HARD_MAX) {
            return Optional.empty();
        }
        CompileResult<LogicKey> result = compile(graph);
        LogicMemory memory = LogicMemory.EMPTY;
        boolean enabled = true;
        boolean simulate = false;
        Optional<LogicGraph<LogicKey>> previous = Optional.empty();
        List<HistoryEntry> history = List.of();
        boolean waiting = false;
        long id = 0;
        if (index < graphs.size()) {
            Graph old = graphs.get(index);
            GraphSlot before = saved(old);
            id = before.id();
            memory = before.memory();
            enabled = before.enabled();
            simulate = before.simulate();
            previous = before.previous();
            history = before.history();
            waiting = before.waiting();
            if (working(old) && !before.graph().equals(graph)) {
                previous = Optional.of(before.graph());
            }
        }
        // A copy saved with a graph of its own is detached from its template (M7L2 row 19).
        Graph next = new Graph(new GraphSlot(name, enabled, graph, memory, simulate, previous,
                GraphSlot.cleanPins(pins, graph, maxPins()), history, waiting).withId(id != 0 ? id : freshId(rows())),
                result, ++edits);
        prepare(next, memory);
        if (index < graphs.size()) {
            graphs.set(index, next);
        } else {
            graphs.add(next);
        }
        // Copies of this rule follow it.
        refreshCopies(index);
        // A new name or a new run-rule node may close or open a loop of rules running each other.
        checkRuleLoops();
        changed();
        return Optional.of(graphs.get(index).result);
    }

    // ---- copies with their own values (M7L2 row 19)

    /** Whether a slot of {@code rows} other than row {@code row} has id {@code id}. */
    private static boolean idTaken(List<GraphSlot> rows, int row, long id) {
        for (int g = 0; g < rows.size(); g++) {
            if (g != row && rows.get(g).id() == id) {
                return true;
            }
        }
        return false;
    }

    /** A random rule id, not 0 and not used by any of {@code rows}. */
    private static long freshId(List<GraphSlot> rows) {
        while (true) {
            long id = java.util.concurrent.ThreadLocalRandom.current().nextLong();
            if (id != 0 && !idTaken(rows, -1, id)) {
                return id;
            }
        }
    }

    /**
     * The row of {@code rows} holding the template of {@code link}: the ordinary rule with its template id (wherever it
     * is now), or -1 when no such rule is there. A different rule on the linked row is never taken.
     */
    private static int templateRow(RuleInstance link, List<GraphSlot> rows) {
        for (int g = 0; g < rows.size(); g++) {
            GraphSlot row = rows.get(g);
            if (row.id() == link.templateId() && row.instance().isEmpty()) {
                return g;
            }
        }
        return -1;
    }

    /**
     * The runtime graph of {@code slot}: an ordinary rule compiles its own graph; a copy ({@link GraphSlot#instance})
     * gets its template's graph from {@code rows} (found by id, the link's row moved to it) with its values in
     * ({@link Parameters#apply}), its pins cleaned against it, and is paused with the problems naming each parameter,
     * or with {@link LogicLang#INSTANCE_NO_TEMPLATE} when its template is no longer on the controller (a rule that took
     * its row is not it). Such a copy keeps the graph it had, so Detach keeps it.
     */
    private Graph made(GraphSlot slot, List<GraphSlot> rows) {
        Optional<RuleInstance> link = slot.instance();
        if (link.isEmpty()) {
            return new Graph(slot, compile(slot.graph()), ++edits);
        }
        int template = templateRow(link.get(), rows);
        if (template < 0) {
            return new Graph(slot,
                    new CompileResult<>(slot.graph(), Optional.empty(),
                            List.of(new GraphError(GraphError.GRAPH, "", LogicLang.INSTANCE_NO_TEMPLATE, ""))),
                    ++edits);
        }
        Parameters.Applied<LogicKey> applied = Parameters.apply(rows.get(template).graph(), link.get().values());
        LogicGraph<LogicKey> graph = applied.graph();
        GraphSlot made = slot.withInstance(Optional.of(link.get().withTemplate(template))).withGraph(graph)
                .withPins(GraphSlot.cleanPins(slot.pins(), graph, maxPins()));
        CompileResult<LogicKey> result = applied.errors().isEmpty()
                ? compile(graph)
                : new CompileResult<>(graph, Optional.empty(), applied.errors());
        return new Graph(made, result, ++edits);
    }

    /** Every graph's slot now, by row; for {@link #made}. Allocates. */
    private List<GraphSlot> rows() {
        List<GraphSlot> rows = new ArrayList<>(graphs.size());
        for (Graph graph : graphs) {
            rows.add(graph.slot);
        }
        return rows;
    }

    /**
     * Makes the graph of every copy of rule {@code template} again from its graph now, keeping each copy's memory of
     * unchanged nodes, switch, simulate mode, pins and history. Copies whose graph comes out the same are left alone.
     */
    private void refreshCopies(int template) {
        List<GraphSlot> rows = null;
        for (int g = 0; g < graphs.size(); g++) {
            Graph old = graphs.get(g);
            Optional<RuleInstance> link = old.slot.instance();
            if (link.isEmpty() || link.get().template() != template) {
                continue;
            }
            if (rows == null) {
                rows = rows();
            }
            GraphSlot before = saved(old);
            Graph next = made(before, rows);
            if (next.slot.graph().equals(old.slot.graph()) && next.result.errors().equals(old.compiled.errors())) {
                continue;
            }
            prepare(next, before.memory());
            graphs.set(g, next);
        }
    }

    /** Graph {@code index}'s link to its template and its own values, when it is a copy with values. */
    public Optional<RuleInstance> instance(int index) {
        return graphs.get(index).slot.instance();
    }

    /** Whether graph {@code index} declares parameters (a copy: its template does). */
    public boolean hasParameters(int index) {
        return Parameters.any(graphs.get(index).slot.graph());
    }

    /**
     * Adds a copy of graph {@code index} with its own values at the end ("copy with values"), switched off so the
     * player sets the values first: linked to graph {@code index} with no values of its own yet (every parameter
     * follows the template's default until the copy sets it), or for a copy, to the same template with the same values.
     * Returns the new row, or -1 when the graph has no parameters or the controller is full.
     */
    public int addCopy(int index) {
        if (index < 0 || index >= graphs.size() || graphs.size() >= GraphSlot.HARD_MAX) {
            return -1;
        }
        GraphSlot from = graphs.get(index).slot;
        RuleInstance link;
        if (from.instance().isPresent()) {
            link = from.instance().get();
        } else if (Parameters.any(from.graph())) {
            link = new RuleInstance(index, from.id(), java.util.Map.of());
        } else {
            return -1;
        }
        GraphSlot slot = new GraphSlot(copyName(from.name()), false, LogicGraph.empty(), LogicMemory.EMPTY, false,
                Optional.empty(), List.of(), List.of(), false, Optional.of(link), freshId(rows()));
        Graph next = made(slot, rows());
        prepare(next, LogicMemory.EMPTY);
        graphs.add(next);
        checkRuleLoops();
        changed();
        return graphs.size() - 1;
    }

    /** {@code name} with the first number from 2 up that no graph's name has yet, within the name limit. */
    private String copyName(String name) {
        for (int k = 2;; k++) {
            String suffix = " " + k;
            String base = name.length() + suffix.length() > GraphSlot.MAX_NAME
                    ? name.substring(0, Math.max(0, GraphSlot.MAX_NAME - suffix.length()))
                    : name;
            String candidate = (base + suffix).strip();
            boolean taken = false;
            for (Graph graph : graphs) {
                taken |= graph.slot.name().equalsIgnoreCase(candidate);
            }
            if (!taken) {
                return candidate;
            }
        }
    }

    /**
     * Makes copy {@code index} a rule of its own with the graph it has now (its values become the defaults of its
     * parameters); later changes to the template no longer reach it. Returns false when it is no copy.
     */
    public boolean detach(int index) {
        if (index < 0 || index >= graphs.size() || graphs.get(index).slot.instance().isEmpty()) {
            return false;
        }
        GraphSlot before = saved(graphs.get(index)).withInstance(Optional.empty());
        Graph next = new Graph(before, compile(before.graph()), ++edits);
        prepare(next, before.memory());
        graphs.set(index, next);
        checkRuleLoops();
        changed();
        return true;
    }

    /**
     * Sets copy {@code index}'s value of parameter {@code key} ({@link Parameters#key}) to {@code value}; its graph is
     * made again, unchanged nodes keep their memory. Returns false when it is no copy.
     */
    public boolean setCopyValue(int index, String key, SettingValue<LogicKey> value) {
        if (index < 0 || index >= graphs.size()) {
            return false;
        }
        GraphSlot before = saved(graphs.get(index));
        Optional<RuleInstance> link = before.instance();
        if (link.isEmpty()) {
            return false;
        }
        Graph next = made(before.withInstance(Optional.of(link.get().withValue(key, value))), rows());
        prepare(next, before.memory());
        graphs.set(index, next);
        checkRuleLoops();
        changed();
        return true;
    }

    /** Renames graph {@code index} and sets its pins, leaving its graph as it is (a copy's editor save). */
    public void rename(int index, String name, List<Integer> pins) {
        if (index < 0 || index >= graphs.size()) {
            return;
        }
        Graph graph = graphs.get(index);
        graph.slot = graph.slot.withName(name);
        setPins(index, pins);
        checkRuleLoops();
        changed();
    }

    /**
     * Removes graph {@code index}; later graphs move up. Copies of it are detached first and keep the graph they have
     * (M7L2 row 19), with a line in their history.
     */
    public void removeGraph(int index) {
        if (index >= 0 && index < graphs.size()) {
            for (int g = 0; g < graphs.size(); g++) {
                Optional<RuleInstance> link = graphs.get(g).slot.instance();
                if (g != index && link.isPresent() && link.get().template() == index && detach(g)) {
                    ServerLevel level = host.serverLevel();
                    record(g, new HistoryEntry(level == null ? 0 : level.getGameTime(), System.currentTimeMillis(),
                            Optional.empty(), "", List.of(GraphChange.of(LogicLang.HISTORY_TEMPLATE_GONE))));
                }
            }
            graphs.remove(index);
            for (Graph graph : graphs) {
                Optional<RuleInstance> link = graph.slot.instance();
                if (link.isPresent() && link.get().template() > index) {
                    graph.slot = graph.slot
                            .withInstance(Optional.of(link.get().withTemplate(link.get().template() - 1)));
                }
            }
            historyRevision++;
            // Own jobs follow their graph: the removed graph's are no one's now, later graphs move up.
            for (int i = ownJobs.size() - 1; i >= 0; i--) {
                OwnJob job = ownJobs.get(i);
                if (job.graph() == index) {
                    ownJobs.remove(i);
                } else if (job.graph() > index) {
                    ownJobs.set(i, new OwnJob(job.graph() - 1, job.key(), job.id()));
                }
            }
            // Row numbers moved up: rules named by row may point elsewhere now.
            checkRuleLoops();
            changed();
        }
    }

    /** Switches graph {@code index} on or off; a graph switched on reads everything once. */
    public void setEnabled(int index, boolean enabled) {
        if (index < 0 || index >= graphs.size()) {
            return;
        }
        // Switched either way, a rule started as a blank card no longer waits to be switched on by card edits.
        if (graphs.get(index).slot.waiting()) {
            graphs.get(index).slot = graphs.get(index).slot.withWaiting(false);
        }
        if (graphs.get(index).slot.enabled() == enabled) {
            return;
        }
        Graph graph = graphs.get(index);
        graph.slot = saved(graph).withEnabled(enabled);
        LogicEvaluator<LogicKey> evaluator = graph.evaluator;
        if (enabled && evaluator != null) {
            // Switched on: the graph starts, so its graph-start nodes fire on the next run.
            evaluator.restart();
            Arrays.fill(graph.offered, Long.MIN_VALUE);
        }
        changed();
    }

    /**
     * Turns simulate mode of graph {@code index} on or off (M7L2 chunk 8). A simulating graph runs as before (events,
     * reads, checks, memory, the team's checks) but its actions are only recorded, in its why trace and in
     * {@link #actions()} marked simulated: no device, item, redstone, craft, notice, display, filter or rule is
     * touched. Its memory is a copy: it starts from the memory the graph has now, and when simulating ends the graph
     * gets back the memory it had when simulating began (simulated runs never change the real one). Shared variables it
     * sets go to a shadow copy only it reads, dropped when simulating ends. Pulses the graph has running are ended at
     * once when simulating begins, since a simulated pulse end would leave them on. Either way the graph starts afresh,
     * like a save: its graph-start nodes fire and its settings (on/off, redstone) are sent once more.
     */
    public void setSimulate(int index, boolean on) {
        if (index < 0 || index >= graphs.size() || graphs.get(index).slot.simulate() == on) {
            return;
        }
        Graph graph = graphs.get(index);
        GraphSlot before = saved(graph);
        LogicMemory real = before.memory();
        if (on) {
            endPulses(graph);
            real = withoutPulses(real);
        }
        graph.slot = before.withSimulate(on).withMemory(real);
        graph.realMemory = real;
        graph.shadow = null;
        prepare(graph, real);
        changed();
    }

    /** Whether graph {@code index} only simulates its actions. */
    public boolean simulating(int index) {
        return graphs.get(index).slot.simulate();
    }

    /** {@code memory} without the entries of pulse nodes, so no pulse is due in it. */
    private static LogicMemory withoutPulses(LogicMemory memory) {
        List<LogicMemory.Entry> kept = new ArrayList<>(memory.nodes().size());
        for (LogicMemory.Entry entry : memory.nodes()) {
            if (!entry.type().equals(NodeTypes.PULSE_REDSTONE.id())) {
                kept.add(entry);
            }
        }
        return new LogicMemory(kept);
    }

    /**
     * Ends the pulses graph {@code graph} has running now on the switches this controller's pulses turned on (as a
     * pulse's own end does, without a fresh access check); other switches are left as they are.
     */
    private void endPulses(Graph graph) {
        LogicEvaluator<LogicKey> evaluator = graph.evaluator;
        if (evaluator == null) {
            return;
        }
        for (String label : evaluator.pulsing()) {
            for (NetworkDevice target : labelled(label)) {
                long pos = target.blockPos();
                if (target instanceof ConditionDevice condition && pulsed.containsKey(pos)) {
                    int ours = pulsed.remove(pos);
                    if (condition.worker().logicStrength() == ours) {
                        condition.worker().setLogicStrength(0);
                    }
                }
            }
        }
        int channels = evaluator.pulsingChannels();
        for (int channel = 0; channel < RedstoneChannels.COUNT; channel++) {
            if ((channels & (1 << channel)) != 0) {
                setChannelShare(channel, 0);
            }
        }
    }

    /**
     * Whether graph {@code graph} counts as working: it compiled and is not paused for a loop of rules. Only such a
     * version is kept as the last working one.
     */
    private static boolean working(Graph graph) {
        return !graph.compiled.paused() && !graph.looped;
    }

    /** Whether graph {@code index} has a last working version to revert to. */
    public boolean hasPrevious(int index) {
        return graphs.get(index).slot.previous().isPresent();
    }

    /** Graph {@code index}'s last working version before the current one; empty when there is none. */
    public Optional<LogicGraph<LogicKey>> previous(int index) {
        return graphs.get(index).slot.previous();
    }

    /**
     * Puts back graph {@code index}'s last working version, switched off so the player checks it before it runs again;
     * the version it replaces becomes the last working one when it worked itself (so a second revert undoes the first),
     * else there is none. Unchanged nodes keep their memory and pins on nodes still there stay. Returns false when
     * there is nothing to revert to.
     */
    public boolean revert(int index) {
        if (index < 0 || index >= graphs.size()) {
            return false;
        }
        Graph old = graphs.get(index);
        GraphSlot before = saved(old);
        if (before.previous().isEmpty()) {
            return false;
        }
        LogicGraph<LogicKey> back = before.previous().get();
        Optional<LogicGraph<LogicKey>> keep = working(old) ? Optional.of(before.graph()) : Optional.empty();
        Graph next = new Graph(
                new GraphSlot(before.name(), false, back, before.memory(), before.simulate(), keep,
                        GraphSlot.cleanPins(before.pins(), back, maxPins()), before.history()).withId(before.id()),
                compile(back), ++edits);
        prepare(next, before.memory());
        graphs.set(index, next);
        refreshCopies(index);
        checkRuleLoops();
        changed();
        return true;
    }

    /** History entries kept per graph ({@code logic.historyEntries}). */
    public static int historyEntries() {
        return StorageServerConfig.LOGIC_HISTORY_ENTRIES.getAsInt();
    }

    /**
     * Adds {@code entry} to graph {@code index}'s change history (M7L2 chunk 9), keeping the newest
     * {@code logic.historyEntries}; saved with the graph. Called by whoever made the change, after it went through.
     */
    public void record(int index, HistoryEntry entry) {
        if (index < 0 || index >= graphs.size()) {
            return;
        }
        Graph graph = graphs.get(index);
        graph.slot = graph.slot.withHistory(HistoryEntry.append(graph.slot.history(), entry, historyEntries()));
        historyRevision++;
        host.changed();
    }

    /** Graph {@code index}'s change history, oldest first. */
    public List<HistoryEntry> history(int index) {
        return graphs.get(index).slot.history();
    }

    /** Counts history changes and loads, so an open screen knows to send the history again. */
    public long historyRevision() {
        return historyRevision;
    }

    /** Most pinned values per graph ({@code logic.maxPins}). */
    public static int maxPins() {
        return StorageServerConfig.LOGIC_MAX_PINS.getAsInt();
    }

    /** The ids of the nodes graph {@code index} pins to the controller screen. */
    public List<Integer> pins(int index) {
        return graphs.get(index).slot.pins();
    }

    /** Pins {@code pins} (node ids of graph {@code index}; unknown ids dropped, at most {@code logic.maxPins}). */
    public void setPins(int index, List<Integer> pins) {
        if (index < 0 || index >= graphs.size()) {
            return;
        }
        Graph graph = graphs.get(index);
        List<Integer> clean = GraphSlot.cleanPins(pins, graph.slot.graph(), maxPins());
        if (clean.equals(graph.slot.pins())) {
            return;
        }
        graph.slot = graph.slot.withPins(clean);
        graph.pins = PinnedValues.of(graph.slot, graph.evaluator);
        revision++;
        host.changed();
    }

    /** How many values graph {@code index} pins. */
    public int pinCount(int index) {
        return graphs.get(index).pins.size();
    }

    /** Graph {@code index}'s pinned value {@code i} now; {@link Long#MIN_VALUE} when not known. Allocates nothing. */
    public long pinValue(int index, int i) {
        Graph graph = graphs.get(index);
        return graph.pins.value(i, graph.evaluator, this, index);
    }

    /** Graph {@code index}'s pinned value {@code i} as the controller screen gets it. */
    ControllerLog.Pin pin(int index, int i) {
        return graphs.get(index).pins.pin(i, pinValue(index, i));
    }

    /**
     * Which save made graph {@code index}'s current version: changes on every save, load and revert, but not when the
     * graph is switched on or off, set to simulate, given pins or paused for a loop. The editor compares it to hear of
     * a revert.
     */
    public long edit(int index) {
        return graphs.get(index).edit;
    }

    /**
     * Whether graph {@code index} was started as a blank card and waits, switched off, to be switched on once card
     * edits give it a trigger and an action.
     */
    public boolean waiting(int index) {
        return graphs.get(index).slot.waiting();
    }

    /** Marks graph {@code index} as a blank card rule waiting to be filled. */
    public void startWaiting(int index) {
        if (index >= 0 && index < graphs.size()) {
            graphs.get(index).slot = graphs.get(index).slot.withWaiting(true);
        }
    }

    /** Whether graph {@code index} is switched on (it may still be paused by errors). */
    public boolean enabled(int index) {
        return graphs.get(index).slot.enabled();
    }

    private void changed() {
        revision++;
        if (started) {
            rebind();
            wake();
        }
        host.changed();
        reportPaused();
    }

    private static CompileResult<LogicKey> compile(LogicGraph<LogicKey> graph) {
        return LogicCompiler.compile(graph, StorageServerConfig.LOGIC_MAX_NODES.getAsInt());
    }

    /** Makes the evaluator of a compiled graph with {@code memory}; a paused graph gets none. */
    private static void prepare(Graph graph, LogicMemory memory) {
        if (graph.result.graph().isEmpty()) {
            graph.evaluator = null;
            graph.offered = new long[0];
            graph.labelKeys = new String[0];
            graph.remoteKeys = new String[0];
            graph.remoteSeed = new long[0];
            graph.pins = PinnedValues.of(graph.slot, null);
            return;
        }
        LogicEvaluator<LogicKey> evaluator = new LogicEvaluator<>(graph.result.graph().get());
        evaluator.load(memory);
        graph.evaluator = evaluator;
        graph.pins = PinnedValues.of(graph.slot, evaluator);
        List<Watch<LogicKey>> watches = evaluator.graph().watches();
        graph.offered = new long[watches.size()];
        Arrays.fill(graph.offered, Long.MIN_VALUE);
        graph.labelKeys = new String[watches.size()];
        graph.remoteKeys = new String[watches.size()];
        graph.remoteSeed = new long[watches.size()];
        Arrays.fill(graph.remoteSeed, Long.MIN_VALUE);
        for (int w = 0; w < watches.size(); w++) {
            // "intake@North Base" reads "intake" on another network (M7L2 row 13).
            String label = watches.get(w).label();
            graph.labelKeys[w] = DeviceLabel.key(NetworkRef.device(label));
            graph.remoteKeys[w] = NetworkRef.key(NetworkRef.network(label));
        }
    }

    /**
     * Pauses the graphs that would run each other in a circle ("Run another rule"), with an error on each run-rule node
     * of the circle, and lets graphs run again whose circle is gone (they start afresh with their saved memory). Runs
     * when graphs are loaded, saved, renamed or removed; allocates.
     */
    private void checkRuleLoops() {
        int n = graphs.size();
        List<LogicGraph<LogicKey>> sources = new ArrayList<>(n);
        List<String> names = new ArrayList<>(n);
        boolean[] compiles = new boolean[n];
        for (int g = 0; g < n; g++) {
            Graph graph = graphs.get(g);
            sources.add(graph.slot.graph());
            names.add(graph.slot.name());
            compiles[g] = !graph.compiled.paused();
        }
        List<List<GraphError>> loops = RuleCalls.check(sources, names, compiles);
        for (int g = 0; g < n; g++) {
            Graph graph = graphs.get(g);
            List<GraphError> errors = loops.get(g);
            if (!errors.isEmpty()) {
                if (graph.looped && graph.result.errors().equals(errors)) {
                    continue;
                }
                // Its memory is kept on the slot while it is paused.
                graph.slot = saved(graph);
                graph.result = new CompileResult<>(graph.slot.graph(), Optional.empty(), errors);
                graph.looped = true;
                prepare(graph, graph.slot.memory());
            } else if (graph.looped) {
                graph.result = graph.compiled;
                graph.looped = false;
                prepare(graph, graph.slot.memory());
            }
        }
    }

    private boolean runnable(int index) {
        Graph graph = graphs.get(index);
        return graph.evaluator != null && graph.slot.enabled() && index < maxGraphs();
    }

    // ---- what the screen, Jade and tests read

    public String name(int index) {
        return graphs.get(index).slot.name();
    }

    public Status status(int index) {
        Graph graph = graphs.get(index);
        if (graph.result.paused()) {
            return Status.PAUSED;
        }
        if (!graph.slot.enabled()) {
            return Status.DISABLED;
        }
        if (index >= maxGraphs()) {
            return Status.IDLE;
        }
        return graph.waiting ? Status.WAITING : Status.RUNNING;
    }

    /** Graph {@code index} as the player built it (with errors, if it has them); for the editor. */
    public LogicGraph<LogicKey> graph(int index) {
        return graphs.get(index).slot.graph();
    }

    /** The compile result of graph {@code index}; a new object after every save, for the editor. */
    public CompileResult<LogicKey> result(int index) {
        return graphs.get(index).result;
    }

    /** The compile errors of graph {@code index}; empty when it runs. */
    public List<GraphError> errors(int index) {
        return graphs.get(index).result.errors();
    }

    /** Rules switched on but paused by errors (compile, loop, missing network, failed script); the alarm panel's. */
    public int pausedRules() {
        int paused = 0;
        for (int i = 0; i < graphs.size(); i++) {
            Graph graph = graphs.get(i);
            if (graph.slot.enabled() && graph.result.paused()) {
                paused++;
            }
        }
        return paused;
    }

    /**
     * Tells the network's alarm panel (M7C item 16) when the number of rules paused by errors changed. Called where
     * graphs are compiled, paused or resumed, never per run.
     */
    private void reportPaused() {
        int paused = pausedRules();
        if (paused != reportedPaused) {
            reportedPaused = paused;
            AlarmCenter.rulesChanged(bound);
        }
    }

    /** Server time of graph {@code index}'s runs in the running or latest network profiler session. */
    public ProfileTally profile(int index) {
        return graphs.get(index).profile;
    }

    /** The checks the last run of graph {@code index} cost. */
    public int lastCost(int index) {
        return graphs.get(index).lastCost;
    }

    /** The action points the last run of graph {@code index} cost. */
    public int lastActionPoints(int index) {
        return graphs.get(index).lastPoints;
    }

    /**
     * Which budget graph {@code index} waits for while its status is {@link Status#WAITING}: {@link Budget#ACTIONS}
     * when its team's action points are used up, else {@link Budget#CHECKS}.
     */
    public Budget waitingFor(int index) {
        return graphs.get(index).waitingFor;
    }

    /** The action points per second graph {@code index} paid recently ({@link RecentRate}), as of {@code gameTime}. */
    public double recentActionPointsPerSecond(int index, long gameTime) {
        return graphs.get(index).pointsRate.perSecond(gameTime);
    }

    /** The game time graph {@code index} last ran, or -1 when it has not run since it was saved or loaded. */
    public long lastRun(int index) {
        return graphs.get(index).lastRun;
    }

    /** The checks per second graph {@code index} paid recently ({@link RecentRate}), as of {@code gameTime}. */
    public double recentChecksPerSecond(int index, long gameTime) {
        return graphs.get(index).rate.perSecond(gameTime);
    }

    /** How many actions the last run of graph {@code index} asked for. */
    public int lastActions(int index) {
        return graphs.get(index).lastActions;
    }

    /** A number that changes whenever a graph is saved, removed, switched on or off, or the graphs are loaded. */
    public long revision() {
        return revision;
    }

    /** Graph {@code index}'s evaluator; empty while it is paused. For live values and tests. */
    public Optional<LogicEvaluator<LogicKey>> evaluator(int index) {
        return Optional.ofNullable(graphs.get(index).evaluator);
    }

    /**
     * The value of watch {@code watch} of graph {@code index} as last handed to it (or read by a run), including
     * changes that crossed no limit and so cost no run; {@link Long#MIN_VALUE} before the first look. For the editor's
     * live view; allocates nothing.
     */
    public long watched(int index, int watch) {
        long[] offered = graphs.get(index).offered;
        return watch >= 0 && watch < offered.length ? offered[watch] : Long.MIN_VALUE;
    }

    /** Times the scheduler ran the controller's task, with or without graph runs; tests use it to see nothing polls. */
    public long taskRuns() {
        return taskRuns;
    }

    /** Graph runs since the controller loaded, over all graphs. */
    public long runs() {
        return runs;
    }

    /** Graphs that wanted to run in the last pass but waited for the budget. */
    public int waiting() {
        return waiting;
    }

    /** Checks this controller used in the last whole second before {@code gameTime}'s. */
    public long checksPerSecond(long gameTime) {
        long second = gameTime / TeamBudget.SECOND;
        if (statSecond == second) {
            return lastSecondChecks;
        }
        return statSecond == second - 1 ? statChecks : 0;
    }

    /** Action points this controller used in the last whole second before {@code gameTime}'s. */
    public long actionPointsPerSecond(long gameTime) {
        long second = gameTime / TeamBudget.SECOND;
        if (statSecond == second) {
            return lastSecondPoints;
        }
        return statSecond == second - 1 ? statPoints : 0;
    }

    /** The actions collected so far, oldest first; at most {@link #KEPT_ACTIONS}. */
    public List<LogicAction> actions() {
        return actions;
    }

    /** Forgets the collected actions. */
    public void clearActions() {
        actions.clear();
    }

    // ---- life cycle

    /** Starts listening and schedules the task; every graph reads everything once. Call once in a loaded level. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        redstone = host.redstoneIn();
        device.deviceLink().schedule(this);
        rebind();
        wake();
    }

    /** The device joined {@code network}, or left with null: watchers move, and every graph rechecks once. */
    public void networkChanged(@Nullable StorageNetwork network) {
        NetworkState old = bound;
        if (old != null && old != network) {
            old.channels().remove(this);
        }
        unbindNetwork();
        bound = network instanceof NetworkState state ? state : null;
        NetworkState current = bound;
        if (current == null || !started) {
            return;
        }
        powered = current.isPowered();
        bindNetwork(current);
        for (int i = 0; i < graphs.size(); i++) {
            Graph graph = graphs.get(i);
            LogicEvaluator<LogicKey> evaluator = graph.evaluator;
            if (evaluator != null) {
                evaluator.everythingChanged();
                Arrays.fill(graph.offered, Long.MIN_VALUE);
            }
        }
        wake();
    }

    /** The redstone into the controller may have changed (a neighbour update). */
    public void redstoneChanged() {
        int now = host.redstoneIn();
        if (now == redstone) {
            return;
        }
        redstone = now;
        offerAll(WatchKind.REDSTONE, true);
    }

    private void wake() {
        device.deviceLink().wake(this);
    }

    /** Rebuilds every subscription from the running graphs: keys, tags, sensors. Allocates; on changes only. */
    private void rebind() {
        ServerLevel level = host.serverLevel();
        if (level != null) {
            // Other networks first (M7L2 row 13): a missing or refused one pauses its graphs before they bind.
            syncRemotes(level, level.getGameTime());
            applyRemoteBlocks();
        }
        for (StackKey key : new ArrayList<>(keyRefs.keySet())) {
            device.deviceLink().unlisten(key, this);
        }
        keyRefs.clear();
        tags.clear();
        watchesLoad = false;
        watchesSky = false;
        watchesEnergy = false;
        watchesSpace = false;
        watchesCraft = false;
        watchesMoves = false;
        watchesPlayers = false;
        watchesTerminals = false;
        watchesFails = false;
        watchesTasks = false;
        moveRefs.clear();
        for (int g = 0; g < graphs.size(); g++) {
            if (!runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                if (!graphs.get(g).remoteKeys[w].isEmpty()) {
                    // A watch on another network listens there (bindRemotes).
                    continue;
                }
                if (watch.kind() == WatchKind.ITEM_MOVES) {
                    watchesMoves = true;
                    moveRefs.add(ref(g, w));
                }
                watchesPlayers |= watch.kind() == WatchKind.PLAYERS_NEAR;
                watchesTerminals |= watch.kind() == WatchKind.TERMINAL_OPENED;
                watchesFails |= watch.kind() == WatchKind.CRAFT_FAILED;
                watchesTasks |= watch.kind() == WatchKind.TASK;
                watchesLoad |= watch.kind() == WatchKind.LOAD;
                watchesSky |= watch.kind() == WatchKind.DAYTIME || watch.kind() == WatchKind.WEATHER;
                watchesEnergy |= watch.kind() == WatchKind.ENERGY || watch.kind() == WatchKind.ENERGY_CAPACITY;
                watchesSpace |= watch.kind() == WatchKind.BYTES_USED || watch.kind() == WatchKind.BYTES_TOTAL;
                watchesCraft |= watch.kind() == WatchKind.CRAFT_JOBS || watch.kind() == WatchKind.CRAFT_CPUS;
                if (watch.kind() != WatchKind.ITEM_COUNT || watch.item().isEmpty()) {
                    continue;
                }
                switch (watch.item().get()) {
                    case LogicKey.Stack stack ->
                        keyRefs.computeIfAbsent(stack.key(), k -> new IntArrayList(1)).add(ref(g, w));
                    case LogicKey.Tag tag -> tally(tag).refs.add(ref(g, w));
                    case LogicKey.FluidTag tag -> tally(tag).refs.add(ref(g, w));
                }
            }
        }
        for (StackKey key : keyRefs.keySet()) {
            device.deviceLink().listen(key, this);
        }
        NetworkState current = bound;
        if (current != null) {
            unbindNetwork();
            bound = current;
            bindNetwork(current);
        }
    }

    private TagTally tally(LogicKey tag) {
        for (int i = 0; i < tags.size(); i++) {
            if (tags.get(i).tag.equals(tag)) {
                return tags.get(i);
            }
        }
        TagTally tally = new TagTally(tag);
        tags.add(tally);
        return tally;
    }

    private void bindNetwork(NetworkState network) {
        // This controller's channel shares go to the network it is in now; graphs reading a channel hear changes.
        network.channels().listen(channelWatcher);
        network.watchShedding(sheddingWatcher);
        for (int channel = 0; channel < RedstoneChannels.COUNT; channel++) {
            if (channelShares[channel] > 0) {
                network.channels().write(this, device.ownerRecord(), channel, channelShares[channel]);
            }
        }
        network.watchDevices(devicesWatcher);
        network.watchNeighbours(neighbourWatcher);
        network.watchFill(fillWatcher);
        if (watchesLoad) {
            // Told after every charged operation; only marks and wakes once per look (see loadChanged).
            network.watchLoad(loadWatcher);
        }
        JobEvents.watchFinishedKeys(network, this);
        if (watchesFails) {
            JobEvents.watchFailed(network, this);
        }
        if (watchesTerminals) {
            network.watchTerminals(this);
        }
        if (watchesMoves) {
            bindMoves(network);
        }
        ServerLevel level = host.serverLevel();
        if (watchesSky && level != null) {
            // One check per level for every controller: told only when the day or the weather changed.
            SkyWatch.listen(level, skyWatcher);
            skyLevel = level;
        }
        if (watchesEnergy) {
            // Told from the network's tick when the stored FE changed; only marks and wakes (see energyChanged).
            network.watchEnergy(energyWatcher);
        }
        if (watchesCraft && level != null) {
            CraftWatch.listen(level, craftWatcher);
            craftLevel = level;
            craftTally.reset(network);
        }
        if (!tags.isEmpty() || watchesSpace) {
            network.watch(this);
        }
        if (!tags.isEmpty()) {
            for (Int2LongMap.Entry entry : network.contents().snapshot().totals().int2LongEntrySet()) {
                changed(entry.getIntKey(), entry.getLongValue());
            }
        }
        bindSensors(network);
        bindKeepers(network);
        bindRemotes();
        if (watchesTasks && level != null) {
            taskServer = level.getServer();
            TaskBoards.listenAll(level.getServer(), taskWatcher);
        }
    }

    private void unbindNetwork() {
        NetworkState network = bound;
        if (network != null) {
            // The channel shares stay: a rebind to the same network keeps them; networkChanged drops them on a move.
            network.channels().unlisten(channelWatcher);
            network.unwatchShedding(sheddingWatcher);
            network.unwatchDevices(devicesWatcher);
            network.unwatchNeighbours(neighbourWatcher);
            network.unwatchFill(fillWatcher);
            network.unwatchLoad(loadWatcher);
            network.unwatchEnergy(energyWatcher);
            network.unwatch(this);
            JobEvents.unwatchFinishedKeys(network, this);
            JobEvents.unwatchFailed(network, this);
            network.unwatchTerminals(this);
            network.unlistenItemMoves(this);
        }
        ServerLevel level = skyLevel;
        if (level != null) {
            SkyWatch.unlisten(level, skyWatcher);
            skyLevel = null;
        }
        MinecraftServer boards = taskServer;
        if (boards != null) {
            TaskBoards.unlistenAll(boards, taskWatcher);
            taskServer = null;
        }
        ServerLevel jobs = craftLevel;
        if (jobs != null) {
            CraftWatch.unlisten(jobs, craftWatcher);
            craftLevel = null;
        }
        craftTally.clear();
        for (int i = 0; i < sensors.size(); i++) {
            sensors.get(i).unlistenLogic(sensorWatcher);
        }
        sensors.clear();
        pools.clear();
        poolIdle.clear();
        for (int i = 0; i < keepers.size(); i++) {
            keepers.get(i).unlisten(stockWatcher);
        }
        keepers.clear();
        for (int i = 0; i < tags.size(); i++) {
            TagTally tally = tags.get(i);
            tally.matches.clear();
            tally.totals.clear();
            tally.total = 0;
        }
        for (int r = 0; r < remotes.size(); r++) {
            unbindRemote(remotes.get(r));
        }
        bound = null;
    }

    /**
     * Listens to the committed moves of every item a graph's item-moves node names, or of every key when one names a
     * tag or no item, so the network records moves only of those keys.
     */
    private void bindMoves(NetworkState network) {
        for (int i = 0; i < moveRefs.size(); i++) {
            int ref = moveRefs.getInt(i);
            Watch<LogicKey> watch = watches(ref >>> 16).get(ref & 0xFFFF);
            if (watch.item().isEmpty() || !(watch.item().get() instanceof LogicKey.Stack)) {
                // Told about every key once, which covers the single items too: never per key as well, or a move
                // would be told twice.
                network.listenItemMoves(null, this);
                return;
            }
        }
        for (int i = 0; i < moveRefs.size(); i++) {
            int ref = moveRefs.getInt(i);
            if (watches(ref >>> 16).get(ref & 0xFFFF).item().orElseThrow() instanceof LogicKey.Stack(StackKey key)) {
                network.listenItemMoves(key, this);
            }
        }
    }

    /** Listens to every sensor a running graph reads; again whenever the network's devices change. */
    // Sensors are devices, compared by identity.
    @SuppressWarnings("ReferenceEquality")
    private void bindSensors(NetworkState network) {
        for (int i = 0; i < sensors.size(); i++) {
            sensors.get(i).unlistenLogic(sensorWatcher);
        }
        sensors.clear();
        for (int g = 0; g < graphs.size(); g++) {
            if (!runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                WatchKind kind = watches.get(w).kind();
                if ((kind != WatchKind.SENSOR && kind != WatchKind.POOL && kind != WatchKind.POOL_IDLE)
                        || !graphs.get(g).remoteKeys[w].isEmpty()) {
                    continue;
                }
                List<NetworkDevice> labelled = network.labels().devicesByKey(graphs.get(g).labelKeys[w]);
                for (int d = 0; d < labelled.size(); d++) {
                    if (labelled.get(d) instanceof SensorSource sensor && !sensors.contains(sensor)) {
                        sensors.add(sensor);
                        sensor.listenLogic(sensorWatcher);
                    }
                }
            }
        }
        // Who is idle now is where the pool events start from: a change of devices fires nothing by itself.
        poolIdle.clear();
        poolIdleChanged(false);
    }

    /**
     * Looks at every pool-member-idle watch of the running graphs: members whose idle bit turned on since the last look
     * fire the event with how many did (when {@code fire}; otherwise only the bits are kept). Allocates only when a
     * pool's size changed since the last look, which starts its bits over.
     */
    private void poolIdleChanged(boolean fire) {
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        boolean any = false;
        long time = network.level().getGameTime();
        for (int g = 0; g < graphs.size(); g++) {
            LogicEvaluator<LogicKey> evaluator = graphs.get(g).evaluator;
            if (evaluator == null || !runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = evaluator.graph().watches();
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                if (watch.kind() != WatchKind.POOL_IDLE) {
                    continue;
                }
                MachinePools.Pool pool = pools.pool(network, graphs.get(g).labelKeys[w]);
                Pools.IdleBits bits = poolIdle.get(ref(g, w));
                if (bits == null) {
                    bits = new Pools.IdleBits();
                    poolIdle.put(ref(g, w), bits);
                }
                // A pool whose size changed starts over: its member numbers moved.
                boolean seen = bits.begin(pool.size());
                int turned = 0;
                for (int i = 0; i < pool.size(); i++) {
                    if (bits.set(i, pools.idle(pool, i, watch.name(), watch.number(), time, Long.MAX_VALUE))) {
                        turned++;
                    }
                }
                if (fire && seen && turned > 0) {
                    evaluator.signal(w, turned);
                    any = true;
                }
            }
        }
        if (any) {
            wake();
        }
    }

    /** Listens to every stock keeper a running graph reads; again whenever the network's devices change. */
    private void bindKeepers(NetworkState network) {
        for (int i = 0; i < keepers.size(); i++) {
            keepers.get(i).unlisten(stockWatcher);
        }
        keepers.clear();
        for (int g = 0; g < graphs.size(); g++) {
            if (!runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                if (watches.get(w).kind() != WatchKind.STOCK) {
                    continue;
                }
                List<NetworkDevice> labelled = network.labels().devicesByKey(graphs.get(g).labelKeys[w]);
                for (int d = 0; d < labelled.size(); d++) {
                    StockKeeperWorker keeper = StockLogic.keeper(labelled.get(d));
                    if (keeper != null && !keepers.contains(keeper)) {
                        keepers.add(keeper);
                        keeper.listen(stockWatcher);
                    }
                }
            }
        }
    }

    private List<Watch<LogicKey>> watches(int g) {
        LogicEvaluator<LogicKey> evaluator = graphs.get(g).evaluator;
        return evaluator == null ? List.of() : evaluator.graph().watches();
    }

    private static int ref(int graph, int watch) {
        return graph << 16 | watch;
    }

    // ---- other networks (M7L2 row 13)

    /**
     * A named network changed somewhere on the server (possibly its security rules): resolve the remotes on the next
     * pass, and ask the devices' access again rather than keep an answer the new rules may change.
     */
    private void namesChanged() {
        if (!remotes.isEmpty()) {
            namesDirty = true;
            access.clear();
            wake();
        }
    }

    /** The other networks graph {@code graph} names; made once per compile result. */
    private static List<CompiledGraph.NetworkUse> networks(Graph graph) {
        List<CompiledGraph.NetworkUse> uses = graph.networks;
        if (uses == null) {
            uses = graph.compiled.graph().map(CompiledGraph::networks).orElse(List.of());
            graph.networks = uses;
        }
        return uses;
    }

    /** The remote with {@link NetworkRef#key} {@code key}, or null (also for an empty key). Allocates nothing. */
    private @Nullable Remote remote(String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (int r = 0; r < remotes.size(); r++) {
            Remote remote = remotes.get(r);
            if (remote.key.equals(key)) {
                return remote;
            }
        }
        return null;
    }

    /** The remote named {@code name} (any case), or -1. Allocates the key; for actions and binding, not reads. */
    private int remoteIndex(String name) {
        String key = NetworkRef.key(name);
        for (int r = 0; r < remotes.size(); r++) {
            if (remotes.get(r).key.equals(key)) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Makes {@link #remotes} match the networks the graphs name (paused ones too, so their errors show and they resume
     * when the network comes back), resolving new ones and dropping the rest with their listeners. Allocates; on graph
     * changes only.
     */
    private void syncRemotes(ServerLevel level, long gameTime) {
        ObjectArrayList<Remote> kept = new ObjectArrayList<>();
        for (int g = 0; g < graphs.size(); g++) {
            for (CompiledGraph.NetworkUse use : networks(graphs.get(g))) {
                String key = NetworkRef.key(use.network());
                boolean known = false;
                for (int k = 0; k < kept.size() && !known; k++) {
                    known = kept.get(k).key.equals(key);
                }
                if (known) {
                    continue;
                }
                int old = remoteIndex(use.network());
                Remote remote = old >= 0 ? remotes.get(old) : new Remote(key, use.network());
                if (old < 0) {
                    resolve(remote, level);
                }
                kept.add(remote);
            }
        }
        for (int r = 0; r < remotes.size(); r++) {
            if (!kept.contains(remotes.get(r))) {
                unbindRemote(remotes.get(r));
            }
        }
        remotes.clear();
        remotes.addAll(kept);
        if (remotes.isEmpty()) {
            NetworkNames.unlisten(namesWatcher);
            nextAccessLook = Long.MAX_VALUE;
        } else {
            NetworkNames.listen(namesWatcher);
            nextAccessLook = Math.min(nextAccessLook, gameTime + accessTicks());
        }
    }

    private static long accessTicks() {
        return Math.max(1, StorageServerConfig.CLAIMS_CACHE_SECONDS.getAsInt()) * 20L;
    }

    /**
     * Finds {@code remote}'s network again: by name in {@link NetworkNames}, its controller only when its chunk is
     * loaded (logic never loads one), and the owner's access level on it through the owner's stand-in player, as for
     * the devices' own checks. Returns whether its state or network changed.
     */
    private boolean resolve(Remote remote, ServerLevel level) {
        RemoteState was = remote.status;
        NetworkState wasNetwork = remote.network;
        MinecraftServer server = level.getServer();
        Optional<NetworkNames.Entry> entry = NetworkNames.get(server).find(remote.key);
        NetworkState found = entry.isPresent() ? RemoteNetworks.loaded(server, entry.get()) : null;
        remote.network = found;
        if (entry.isEmpty()) {
            remote.status = RemoteState.MISSING;
        } else if (!device.ownerRecord().isOwned()) {
            // A controller placed by a machine or a command reaches no other network.
            remote.status = RemoteState.DENIED;
        } else if (found == null || found.status() != StorageNetwork.Status.ONLINE) {
            remote.status = RemoteState.UNAVAILABLE;
        } else {
            remote.status = RemoteNetworks.mayUse(level, device.ownerRecord(), found)
                    ? RemoteState.OK
                    : RemoteState.DENIED;
        }
        return remote.status != was || remote.network != wasNetwork;
    }

    /** Resolves every remote again; whether any changed. */
    private boolean resolveAll(ServerLevel level, long gameTime) {
        boolean changed = false;
        for (int r = 0; r < remotes.size(); r++) {
            changed |= resolve(remotes.get(r), level);
        }
        nextAccessLook = remotes.isEmpty() ? Long.MAX_VALUE : gameTime + accessTicks();
        return changed;
    }

    /**
     * Pauses the graphs that name a missing network or one their owner may not use, with an error on each node naming
     * it, and lets them run again once that is over (with their saved memory, like a loop of rules). A graph paused for
     * a loop of rules keeps that pause first.
     */
    private void applyRemoteBlocks() {
        for (int g = 0; g < graphs.size(); g++) {
            Graph graph = graphs.get(g);
            List<GraphError> errors = remoteErrors(graph);
            if (!errors.isEmpty()) {
                if (graph.looped || (graph.netBlocked && graph.result.errors().equals(errors))) {
                    continue;
                }
                graph.slot = saved(graph);
                graph.result = new CompileResult<>(graph.slot.graph(), Optional.empty(), errors);
                graph.netBlocked = true;
                prepare(graph, graph.slot.memory());
                revision++;
            } else if (graph.netBlocked) {
                graph.netBlocked = false;
                if (!graph.looped) {
                    graph.result = graph.compiled;
                    prepare(graph, graph.slot.memory());
                }
                revision++;
            }
        }
    }

    /** The errors of graph {@code graph}'s uses of missing or refused networks; empty when there are none. */
    private List<GraphError> remoteErrors(Graph graph) {
        List<CompiledGraph.NetworkUse> uses = networks(graph);
        if (uses.isEmpty()) {
            return List.of();
        }
        List<GraphError> errors = new ArrayList<>();
        for (CompiledGraph.NetworkUse use : uses) {
            int r = remoteIndex(use.network());
            RemoteState state = r < 0 ? RemoteState.MISSING : remotes.get(r).status;
            if (state == RemoteState.MISSING) {
                errors.add(new GraphError(use.node(), use.port(), LogicLang.NET_MISSING, use.network()));
            } else if (state == RemoteState.DENIED) {
                errors.add(new GraphError(use.node(), use.port(), LogicLang.NET_DENIED, use.network()));
            }
        }
        return errors;
    }

    /**
     * Listens to every remote the running graphs read, like {@link #bindNetwork} does on the own network: keys of
     * counts, contents for tags and free space, energy, full cells, the labelled sensors, and always the devices (which
     * also hear its status change). Then offers every watch on it once. Allocates; on changes only.
     */
    private void bindRemotes() {
        for (int r = 0; r < remotes.size(); r++) {
            Remote remote = remotes.get(r);
            unbindRemote(remote);
            remote.refs.clear();
            remote.keys.clear();
            remote.contents = false;
            remote.energy = false;
            remote.fill = false;
        }
        for (int g = 0; g < graphs.size(); g++) {
            Graph graph = graphs.get(g);
            if (!runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                Remote remote = remote(graph.remoteKeys[w]);
                if (remote == null) {
                    continue;
                }
                remote.refs.add(ref(g, w));
                if (!remoteSaved.isEmpty() && graph.remoteSeed[w] == Long.MIN_VALUE) {
                    graph.remoteSeed[w] = remoteSaved.getOrDefault(remoteValueKey(graph, watch), Long.MIN_VALUE);
                }
                switch (watch.kind()) {
                    case ITEM_COUNT -> {
                        if (graph.labelKeys[w].isEmpty() && watch.item().isPresent()
                                && watch.item().get() instanceof LogicKey.Stack(StackKey key)) {
                            if (!remote.keys.contains(key)) {
                                remote.keys.add(key);
                            }
                        } else {
                            // Tags and labelled counts follow every committed change.
                            remote.contents = true;
                        }
                    }
                    case BYTES_USED, BYTES_TOTAL -> remote.contents = true;
                    case ENERGY, ENERGY_CAPACITY -> remote.energy = true;
                    case FULL -> remote.fill = true;
                    default -> {
                    }
                }
            }
        }
        for (int r = 0; r < remotes.size(); r++) {
            bindRemote(remotes.get(r));
        }
    }

    private void bindRemote(Remote remote) {
        if (bound == null) {
            return;
        }
        if (remote.status != RemoteState.OK) {
            // Not usable: the watches on it read their last value on the next run, with a why line saying why.
            for (int i = 0; i < remote.refs.size(); i++) {
                int ref = remote.refs.getInt(i);
                touch(ref >>> 16, ref & 0xFFFF);
            }
        }
        NetworkState network = remote.network;
        if (network == null) {
            return;
        }
        remote.listening = network;
        network.watchDevices(remote.devicesWatcher);
        if (remote.status != RemoteState.OK || remote.refs.isEmpty()) {
            return;
        }
        for (int k = 0; k < remote.keys.size(); k++) {
            network.listenKey(remote.keys.get(k), remote);
        }
        if (remote.contents) {
            network.watch(remote);
        }
        if (remote.energy) {
            network.watchEnergy(remote.energyWatcher);
        }
        if (remote.fill) {
            network.watchFill(remote.markWatcher);
        }
        bindRemoteSensors(remote, network);
        offerRemote(remote);
    }

    /** Listens to the sensors the remote's sensor watches read; again when its devices change. */
    @SuppressWarnings("ReferenceEquality") // sensors are devices, compared by identity
    private void bindRemoteSensors(Remote remote, NetworkState network) {
        for (int i = 0; i < remote.sensors.size(); i++) {
            remote.sensors.get(i).unlistenLogic(remote.markWatcher);
        }
        remote.sensors.clear();
        for (int i = 0; i < remote.refs.size(); i++) {
            int ref = remote.refs.getInt(i);
            int g = ref >>> 16;
            int w = ref & 0xFFFF;
            if (watches(g).get(w).kind() != WatchKind.SENSOR) {
                continue;
            }
            List<NetworkDevice> labelled = network.labels().devicesByKey(graphs.get(g).labelKeys[w]);
            for (int d = 0; d < labelled.size(); d++) {
                if (labelled.get(d) instanceof SensorSource sensor && !remote.sensors.contains(sensor)) {
                    remote.sensors.add(sensor);
                    sensor.listenLogic(remote.markWatcher);
                }
            }
        }
    }

    private void unbindRemote(Remote remote) {
        NetworkState network = remote.listening;
        if (network != null) {
            network.unwatchDevices(remote.devicesWatcher);
            for (int k = 0; k < remote.keys.size(); k++) {
                network.unlistenKey(remote.keys.get(k), remote);
            }
            network.unwatch(remote);
            network.unwatchEnergy(remote.energyWatcher);
            network.unwatchFill(remote.markWatcher);
        }
        for (int i = 0; i < remote.sensors.size(); i++) {
            remote.sensors.get(i).unlistenLogic(remote.markWatcher);
        }
        remote.sensors.clear();
        remote.listening = null;
        remote.dirty = false;
        remote.devices = false;
    }

    /** Offers every watch on {@code remote} its value now. Allocates nothing. */
    private void offerRemote(Remote remote) {
        for (int i = 0; i < remote.refs.size(); i++) {
            int ref = remote.refs.getInt(i);
            int g = ref >>> 16;
            int w = ref & 0xFFFF;
            offer(g, w, value(g, watches(g).get(w), graphs.get(g).labelKeys[w]));
        }
    }

    /**
     * The task's part for other networks, before the graphs run: resolves them again after a change of named networks
     * (and when the access answers are due), pausing or resuming graphs, and offers the watches of remotes whose
     * listeners fired, at most once per {@code condition.minIntervalTicks}. Returns the next game time it needs a wake,
     * or {@link Long#MAX_VALUE}.
     */
    private long remotePass(ServerLevel level, long gameTime) {
        if (remotes.isEmpty()) {
            namesDirty = false;
            remotesDirty = false;
            return Long.MAX_VALUE;
        }
        boolean accessDue = gameTime >= nextAccessLook;
        if (namesDirty || accessDue) {
            namesDirty = false;
            if (resolveAll(level, gameTime)) {
                rebind();
                // A graph paused or resumed: the screen, the save and the alarm panel follow.
                host.changed();
                reportPaused();
            }
        }
        long due = Long.MAX_VALUE;
        if (remotesDirty) {
            if (gameTime >= nextRemoteLook) {
                remotesDirty = false;
                nextRemoteLook = gameTime + StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt();
                for (int r = 0; r < remotes.size(); r++) {
                    Remote remote = remotes.get(r);
                    if (!remote.dirty) {
                        continue;
                    }
                    remote.dirty = false;
                    NetworkState network = remote.usable();
                    if (remote.devices && network != null) {
                        remote.devices = false;
                        bindRemoteSensors(remote, network);
                    }
                    offerRemote(remote);
                }
            } else {
                due = nextRemoteLook;
            }
        }
        for (int r = 0; r < remotes.size(); r++) {
            if (remotes.get(r).status == RemoteState.DENIED) {
                // No event says a trust list or a team changed: while a graph waits for access, ask again on the
                // claims.cacheSeconds timer. It stops once no remote is refused.
                due = Math.min(due, nextAccessLook);
                break;
            }
        }
        return due;
    }

    /** Most values of other networks saved with the controller. */
    private static final int MAX_REMOTE_VALUES = 256;

    /**
     * A watch's key among the saved values: the rule's id and what it watches, so edits that keep it keep its value.
     */
    private static String remoteValueKey(Graph graph, Watch<LogicKey> watch) {
        return graph.slot.id() + "|" + watch.kind().name() + "|" + watch.item().map(Object::toString).orElse("") + "|"
                + watch.label() + "|" + watch.name() + "|" + watch.number();
    }

    /**
     * The last value of each watch on another network, to save with the controller, so a network that is not loaded
     * after a restart reads what the rule last saw rather than 0. Values loaded but not seen again are kept while their
     * rule is still here. Allocates; for saving.
     */
    public Map<String, Long> remoteValues() {
        Object2LongOpenHashMap<String> out = new Object2LongOpenHashMap<>();
        for (int g = 0; g < graphs.size(); g++) {
            Graph graph = graphs.get(g);
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size() && w < graph.remoteKeys.length; w++) {
                if (graph.remoteKeys[w].isEmpty() || out.size() >= MAX_REMOTE_VALUES) {
                    continue;
                }
                long value = graph.offered[w] != Long.MIN_VALUE ? graph.offered[w] : graph.remoteSeed[w];
                if (value != Long.MIN_VALUE) {
                    out.put(remoteValueKey(graph, watches.get(w)), value);
                }
            }
        }
        for (Object2LongMap.Entry<String> entry : remoteSaved.object2LongEntrySet()) {
            if (out.size() < MAX_REMOTE_VALUES && !out.containsKey(entry.getKey()) && ruleHere(entry.getKey())) {
                out.put(entry.getKey(), entry.getLongValue());
            }
        }
        return new java.util.TreeMap<>(out);
    }

    private boolean ruleHere(String key) {
        for (int g = 0; g < graphs.size(); g++) {
            if (key.startsWith(graphs.get(g).slot.id() + "|")) {
                return true;
            }
        }
        return false;
    }

    /** Takes the values {@link #remoteValues} saved; at most {@link #MAX_REMOTE_VALUES}. */
    public void loadRemoteValues(Map<String, Long> saved) {
        remoteSaved.clear();
        for (Map.Entry<String, Long> entry : saved.entrySet()) {
            if (remoteSaved.size() < MAX_REMOTE_VALUES) {
                remoteSaved.put(entry.getKey(), entry.getValue().longValue());
            }
        }
        if (started) {
            rebind();
        }
    }

    /** How a remote named {@code name} stands now; for the screens and tests. Empty when no graph names it. */
    public Optional<RemoteState> remoteState(String name) {
        int r = remoteIndex(name);
        return r < 0 ? Optional.empty() : Optional.of(remotes.get(r).status);
    }

    // ---- events (they only mark work and wake the task)

    @Override
    public void keyChanged(StackKey key, long total) {
        IntArrayList refs = keyRefs.get(key);
        if (refs == null) {
            return;
        }
        for (int i = 0; i < refs.size(); i++) {
            int ref = refs.getInt(i);
            int g = ref >>> 16;
            int w = ref & 0xFFFF;
            Graph graph = graphs.get(g);
            offer(g, w, graph.labelKeys[w].isEmpty() ? total : value(watches(g).get(w), graph.labelKeys[w]));
        }
    }

    @Override
    public void changed(int id, long total) {
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        if (watchesSpace) {
            spaceChanged();
        }
        for (int t = 0; t < tags.size(); t++) {
            TagTally tally = tags.get(t);
            byte match = tally.matches.get(id);
            if (match == 0) {
                StackKey key = network.contents().key(id);
                match = key != null && inTag(key, tally.tag) ? (byte) 1 : (byte) 2;
                tally.matches.put(id, match);
            }
            if (match != 1) {
                continue;
            }
            tally.total += total - tally.totals.get(id);
            if (total == 0) {
                tally.totals.remove(id);
            } else {
                tally.totals.put(id, total);
            }
            for (int i = 0; i < tally.refs.size(); i++) {
                int ref = tally.refs.getInt(i);
                int g = ref >>> 16;
                int w = ref & 0xFFFF;
                if (graphs.get(g).labelKeys[w].isEmpty()) {
                    offer(g, w, tally.total);
                } else {
                    // A labelled tag count is summed from the devices' providers; doing that per change would walk
                    // their contents each time, so only mark it: the next run sums it once.
                    touch(g, w);
                }
            }
        }
    }

    @Override
    public void released(int id) {
        // The id's total was already 0 (its change to 0 came through changed), so the tallies lose nothing here; only
        // the cached match goes, since the id may name another key from now on.
        for (int t = 0; t < tags.size(); t++) {
            tags.get(t).matches.remove(id);
            tags.get(t).totals.remove(id);
        }
    }

    @Override
    public void jobFinished(StackKey key) {
        boolean any = false;
        for (int g = 0; g < graphs.size(); g++) {
            LogicEvaluator<LogicKey> evaluator = graphs.get(g).evaluator;
            if (evaluator == null || !runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = evaluator.graph().watches();
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                if (watch.kind() == WatchKind.JOB_FINISHED && (watch.item().isEmpty()
                        || (watch.item().get() instanceof LogicKey.Stack(StackKey item) && item.equals(key)))) {
                    evaluator.signal(w);
                    any = true;
                }
            }
        }
        if (any) {
            wake();
        }
    }

    /**
     * A crafting job of the network stopped with something missing: craft-failed nodes for its item fire, with the
     * first missing item and its amount, and the list of every missing item for the why line (made once, only when a
     * node listens).
     */
    @Override
    public void jobFailed(StackKey key, Object2LongSortedMap<StackKey> missingItems) {
        StackKey first = missingItems.isEmpty() ? null : missingItems.firstKey();
        long missing = first == null ? 0 : missingItems.getLong(first);
        LogicKey firstKey = first == null ? null : LogicKey.of(first);
        String listed = null;
        boolean any = false;
        for (int g = 0; g < graphs.size(); g++) {
            LogicEvaluator<LogicKey> evaluator = graphs.get(g).evaluator;
            if (evaluator == null || !runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = evaluator.graph().watches();
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                // A tag matches a job for any item in it: one holder check per failed job, which is rare.
                if (watch.kind() == WatchKind.CRAFT_FAILED
                        && (watch.item().isEmpty() || matches(key, watch.item().get()))) {
                    if (listed == null) {
                        listed = missingList(missingItems);
                    }
                    evaluator.signal(w, missing, firstKey, listed);
                    any = true;
                }
            }
        }
        if (any) {
            wake();
        }
    }

    /** Most missing items the why line names; the rest are counted. */
    private static final int MISSING_LISTED = 6;

    /** "12 Iron Ingot, 3 Gold Ingot", up to {@link #MISSING_LISTED} items and "+n" for the rest; empty for none. */
    private static String missingList(Object2LongSortedMap<StackKey> missing) {
        StringBuilder text = new StringBuilder();
        int listed = 0;
        for (Object2LongMap.Entry<StackKey> entry : missing.object2LongEntrySet()) {
            if (listed == MISSING_LISTED) {
                text.append(" +").append(missing.size() - listed);
                break;
            }
            if (listed > 0) {
                text.append(", ");
            }
            text.append(InfoFormat.number(entry.getLongValue())).append(' ')
                    .append(JobToastPayload.name(entry.getKey()).getString());
            listed++;
        }
        // The editor's why payload carries at most 256 characters a line.
        return text.length() > MAX_MISSING_TEXT ? text.substring(0, MAX_MISSING_TEXT) + "..." : text.toString();
    }

    /** Most characters of the missing list before it is cut. */
    private static final int MAX_MISSING_TEXT = 200;

    /** A failed job's missing item in a notice's {item}: its name (English on a dedicated server). */
    @Override
    public String itemName(LogicKey item) {
        return switch (item) {
            case LogicKey.Stack(StackKey key) -> JobToastPayload.name(key).getString();
            case LogicKey.Tag(var tag) -> "#" + tag.location();
            case LogicKey.FluidTag(var tag) -> "#" + tag.location();
        };
    }

    /**
     * A committed move of a key some item-moves node listens to: each such node whose item, label and direction match
     * fires on its graph's next run, with the amount. Walks only the item-moves watches; allocates nothing.
     */
    @Override
    public void itemsMoved(StackKey key, long amount, @Nullable NetworkDevice by) {
        boolean any = false;
        for (int i = 0; i < moveRefs.size(); i++) {
            int ref = moveRefs.getInt(i);
            int g = ref >>> 16;
            int w = ref & 0xFFFF;
            Graph graph = graphs.get(g);
            LogicEvaluator<LogicKey> evaluator = graph.evaluator;
            if (evaluator == null) {
                continue;
            }
            Watch<LogicKey> watch = evaluator.graph().watches().get(w);
            long direction = watch.number();
            if ((direction == 1 && amount < 0) || (direction == 2 && amount > 0)) {
                continue;
            }
            String labelKey = graph.labelKeys[w];
            if (!labelKey.isEmpty() && (by == null || !by.label().key().equals(labelKey))) {
                continue;
            }
            if (watch.item().isPresent() && !matches(key, watch.item().get())) {
                continue;
            }
            evaluator.signal(w, Math.abs(amount));
            any = true;
        }
        if (any) {
            wake();
        }
    }

    /** Whether {@code key} is {@code item}, or in it for a tag. */
    static boolean matches(StackKey key, LogicKey item) {
        return item instanceof LogicKey.Stack(StackKey stack) ? stack.equals(key) : inTag(key, item);
    }

    /** A player opened a terminal of the network: terminal nodes for its label (or for any terminal) fire. */
    @Override
    public void terminalOpened(@Nullable NetworkDevice terminal) {
        boolean any = false;
        for (int g = 0; g < graphs.size(); g++) {
            Graph graph = graphs.get(g);
            LogicEvaluator<LogicKey> evaluator = graph.evaluator;
            if (evaluator == null || !runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = evaluator.graph().watches();
            for (int w = 0; w < watches.size(); w++) {
                if (watches.get(w).kind() != WatchKind.TERMINAL_OPENED) {
                    continue;
                }
                String labelKey = graph.labelKeys[w];
                if (labelKey.isEmpty() || (terminal != null && terminal.label().key().equals(labelKey))) {
                    evaluator.signal(w);
                    any = true;
                }
            }
        }
        if (any) {
            wake();
        }
    }

    /** The network's devices, status or power changed. */
    private void devicesChanged() {
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        if (network.status() != StorageNetwork.Status.ONLINE) {
            offline = true;
            return;
        }
        if (offline) {
            wake();
            return;
        }
        boolean now = network.isPowered();
        if (now != powered) {
            powered = now;
            offerAll(WatchKind.POWER, false);
        }
        // Devices joining, leaving or being relabelled change what labels hold.
        offerAll(WatchKind.DEVICE, false);
        offerAll(WatchKind.REDSTONE, false);
        offerAll(WatchKind.SENSOR, false);
        offerAll(WatchKind.DEVICE_COUNT, false);
        offerAll(WatchKind.FULL, false);
        // CPUs, energy cells and drives join and leave with the devices; a split may take a job's origin away.
        if (watchesCraft) {
            craftTally.reset(network);
        }
        craftChanged();
        energyChanged();
        spaceChanged();
        // Routes change with the devices; the next look reads the load again.
        loadChanged();
        offerLabelled(WatchKind.ITEM_COUNT);
        // Pools follow their labels: members and the sensors on their machines are matched again.
        pools.clear();
        bindSensors(network);
        offerAll(WatchKind.POOL, false);
        // Keepers joining, leaving or being relabelled change what the stock nodes read.
        bindKeepers(network);
        offerAll(WatchKind.STOCK, false);
        // A new network (a merge or a split) may shed at another level.
        offerAll(WatchKind.SHED_LEVEL, false);
    }

    /**
     * A task board changed (M7C item 15): graphs reading tasks are offered their values; unchanged ones stay asleep.
     */
    private void tasksChanged() {
        offerAll(WatchKind.TASK, false);
    }

    /** The network's load shedding level changed (M7L2), from its tick: graphs reading it are offered the new level. */
    private void sheddingChanged() {
        offerAll(WatchKind.SHED_LEVEL, false);
    }

    /** A network redstone channel changed (M7L2): graphs reading that channel are offered its value. */
    private void channelChanged(int channel, int value) {
        for (int g = 0; g < graphs.size(); g++) {
            if (!runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                if (watch.kind() == WatchKind.CHANNEL && watch.number() == channel) {
                    offer(g, w, value);
                }
            }
        }
    }

    /**
     * A labelled device had a neighbour update: redstone into it may have changed, and a pool member's machine may have
     * (its direct reading is read again when the pools are next looked at).
     */
    private void neighbourChanged(NetworkDevice changed) {
        if (pools.neighbourChanged(changed)) {
            poolMachineChanged();
        }
        String key = changed.label().key();
        for (int g = 0; g < graphs.size(); g++) {
            if (!runnable(g)) {
                continue;
            }
            Graph graph = graphs.get(g);
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                if (watch.kind() == WatchKind.REDSTONE && graph.labelKeys[w].equals(key)) {
                    offer(g, w, value(watch, graph.labelKeys[w]));
                }
            }
        }
    }

    /**
     * A pool member's machine changed (a neighbour update, or its capabilities, possibly from inside a lookup): only
     * marks it and wakes the task once; the task looks at the pools at most once per
     * {@code condition.minIntervalTicks}, like link load, so a machine that changes every tick costs one look per
     * interval.
     */
    private void poolMachineChanged() {
        if (!poolsDirty) {
            poolsDirty = true;
            wake();
        }
    }

    /** A drive cell turned full or stopped being full somewhere on the network. */
    private void fillChanged() {
        offerAll(WatchKind.FULL, false);
        spaceChanged();
    }

    /**
     * The network's stored FE changed (told from its tick, so possibly every tick), or its devices did. Only marks it
     * and wakes the task once; the task looks at most once per {@code condition.minIntervalTicks}.
     */
    private void energyChanged() {
        if (watchesEnergy && !energyDirty) {
            energyDirty = true;
            wake();
        }
    }

    /**
     * The network's contents changed (any committed total), or its cells did. Bytes are summed over the cells, so only
     * mark it and wake the task once; the task looks at most once per {@code condition.minIntervalTicks}.
     */
    private void spaceChanged() {
        if (watchesSpace && !spaceDirty) {
            spaceDirty = true;
            wake();
        }
    }

    /**
     * A crafting job in the controller's level was submitted, started or ended ({@link CraftWatch}), or the network's
     * devices changed. Called from inside the job queue, so it only marks and wakes; the task reads the status once for
     * every change in a tick.
     */
    private void jobChanged(ServerLevel level, CraftingJob job) {
        NetworkState network = bound;
        if (network != null && watchesCraft) {
            craftTally.changed(network, level, job);
            craftChanged();
        }
    }

    private void craftChanged() {
        if (watchesCraft && !craftDirty) {
            craftDirty = true;
            wake();
        }
    }

    /**
     * Channel use changed: the load may have risen. Only marks it and wakes the task once; the task looks at most once
     * per {@code condition.minIntervalTicks}, like the condition switch.
     */
    private void loadChanged() {
        if (watchesLoad && !loadDirty) {
            loadDirty = true;
            wake();
        }
    }

    private void sensorsChanged() {
        offerAll(WatchKind.SENSOR, false);
        offerAll(WatchKind.POOL, false);
        poolIdleChanged(true);
    }

    /** The level's day/night or weather changed ({@link SkyWatch}, at most once a second per level). */
    private void skyChanged(boolean dayChanged, boolean weatherChanged) {
        if (dayChanged) {
            offerAll(WatchKind.DAYTIME, false);
        }
        if (weatherChanged) {
            offerAll(WatchKind.WEATHER, false);
        }
    }

    /** Offers the current value of every watch of {@code kind}; with {@code own}, only those about the controller. */
    private void offerAll(WatchKind kind, boolean own) {
        for (int g = 0; g < graphs.size(); g++) {
            if (!runnable(g)) {
                continue;
            }
            Graph graph = graphs.get(g);
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                if (watch.kind() == kind && (!own || graph.labelKeys[w].isEmpty())) {
                    offer(g, w, value(g, watch, graph.labelKeys[w]));
                }
            }
        }
    }

    /** Offers every labelled watch of {@code kind}. */
    private void offerLabelled(WatchKind kind) {
        for (int g = 0; g < graphs.size(); g++) {
            if (!runnable(g)) {
                continue;
            }
            Graph graph = graphs.get(g);
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                Watch<LogicKey> watch = watches.get(w);
                if (watch.kind() == kind && !graph.labelKeys[w].isEmpty()) {
                    offer(g, w, value(watch, graph.labelKeys[w]));
                }
            }
        }
    }

    /** Marks watch {@code w} of graph {@code g} for a fresh read on the next run, unfiltered, and wakes the task. */
    private void touch(int g, int w) {
        LogicEvaluator<LogicKey> evaluator = graphs.get(g).evaluator;
        if (evaluator != null) {
            evaluator.changed(w);
            wake();
        }
    }

    /**
     * Hands {@code value} of watch {@code w} to graph {@code g} when it differs from the last value handed over and
     * could change something, and wakes the task.
     */
    private void offer(int g, int w, long value) {
        Graph graph = graphs.get(g);
        LogicEvaluator<LogicKey> evaluator = graph.evaluator;
        if (evaluator == null || graph.offered[w] == value) {
            return;
        }
        graph.offered[w] = value;
        if (evaluator.matters(w, value)) {
            evaluator.changed(w);
            wake();
        }
    }

    // ---- the task

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        taskRuns++;
        NetworkState network = bound;
        ServerLevel level = host.serverLevel();
        if (network == null || level == null) {
            return WAIT;
        }
        if (network.status() != StorageNetwork.Status.ONLINE) {
            // Unloaded parts in pause mode or a quarantine: nothing runs; the status change wakes the task again.
            offline = true;
            return WAIT;
        }
        if (offline) {
            offline = false;
            powered = network.isPowered();
            for (int g = 0; g < graphs.size(); g++) {
                LogicEvaluator<LogicKey> evaluator = graphs.get(g).evaluator;
                if (evaluator != null) {
                    evaluator.everythingChanged();
                    Arrays.fill(graphs.get(g).offered, Long.MIN_VALUE);
                }
            }
        }
        UUID team = TeamBudget.team(level, device);
        long due = remotePass(level, gameTime);
        waiting = 0;
        if (pendingDisplays > 0 && displayDue <= gameTime) {
            flushDisplays(gameTime);
        }
        if (loadDirty) {
            if (gameTime >= nextLoadLook) {
                loadDirty = false;
                nextLoadLook = gameTime + StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt();
                offerAll(WatchKind.LOAD, false);
            } else {
                due = nextLoadLook;
            }
        }
        if (poolsDirty) {
            if (gameTime >= nextPoolLook) {
                poolsDirty = false;
                nextPoolLook = gameTime + StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt();
                offerAll(WatchKind.POOL, false);
                poolIdleChanged(true);
            } else {
                due = Math.min(due, nextPoolLook);
            }
        }
        if (craftDirty) {
            craftDirty = false;
            offerAll(WatchKind.CRAFT_JOBS, false);
            offerAll(WatchKind.CRAFT_CPUS, false);
        }
        if (energyDirty || spaceDirty) {
            if (gameTime >= nextSampleLook) {
                nextSampleLook = gameTime + StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt();
                if (energyDirty) {
                    energyDirty = false;
                    offerAll(WatchKind.ENERGY, false);
                    offerAll(WatchKind.ENERGY_CAPACITY, false);
                }
                if (spaceDirty) {
                    spaceDirty = false;
                    offerAll(WatchKind.BYTES_USED, false);
                    offerAll(WatchKind.BYTES_TOTAL, false);
                }
            } else {
                due = Math.min(due, nextSampleLook);
            }
        }
        if (watchesPlayers) {
            // No event says a player moved: while a graph looks for players, look on the configured timer.
            if (gameTime >= nextPlayerLook) {
                nextPlayerLook = gameTime + StorageServerConfig.LOGIC_PLAYER_CHECK_TICKS.getAsInt();
                offerAll(WatchKind.PLAYERS_NEAR, false);
            }
            due = Math.min(due, nextPlayerLook);
        }
        for (int g = 0; g < graphs.size(); g++) {
            Graph graph = graphs.get(g);
            LogicEvaluator<LogicKey> evaluator = graph.evaluator;
            if (evaluator == null || !runnable(g)) {
                graph.waiting = false;
                continue;
            }
            if (!evaluator.pending(gameTime)) {
                graph.waiting = false;
                due = Math.min(due, evaluator.nextWake());
                continue;
            }
            // A run needs checks, and action points too when the graph can act; the one used up is what it waits for.
            int wait = TeamBudget.wait(team, gameTime);
            Budget needed = Budget.CHECKS;
            if (wait == 0 && evaluator.graph().acts()) {
                wait = TeamBudget.waitActions(team, gameTime);
                needed = Budget.ACTIONS;
            }
            if (wait > 0) {
                graph.waiting = true;
                graph.waitingFor = needed;
                waiting++;
                due = Math.min(due, gameTime + wait);
                continue;
            }
            graph.waiting = false;
            graph.lastActions = 0;
            graph.lastRun = gameTime;
            running = g;
            now = gameTime;
            depthNow = graph.pendingDepth;
            graph.pendingDepth = 0;
            budgetLeft = Math.max(1, TeamBudget.limit() - TeamBudget.used(team, gameTime));
            pointsLeft = Math.max(1, TeamBudget.actionLimit() - TeamBudget.actionsUsed(team, gameTime));
            extraChecks = 0;
            extraPoints = 0;
            boolean timed = NetworkProfiler.on();
            long started = timed ? System.nanoTime() : 0;
            try {
                evaluator.run(this, gameTime);
                traceMissing(graph, evaluator);
            } finally {
                running = -1;
                depthNow = 0;
                budgetLeft = Long.MAX_VALUE;
                pointsLeft = Long.MAX_VALUE;
            }
            if (timed) {
                graph.profile.add(System.nanoTime() - started);
            }
            runs++;
            // Reads and actions on other networks pay a surcharge (M7L2 row 13) on top of the evaluator's own count.
            int cost = evaluator.lastCost() + extraChecks;
            graph.lastCost = cost;
            graph.rate.add(cost, gameTime);
            TeamBudget.spend(team, cost, gameTime);
            int points = evaluator.lastActionPoints() + extraPoints;
            graph.lastPoints = points;
            graph.pointsRate.add(points, gameTime);
            TeamBudget.spendActions(team, points, gameTime);
            countChecks(cost, points, gameTime);
            if (evaluator.scriptFailedNode() >= 0) {
                pauseForScript(graph, evaluator.scriptFailedNode(), evaluator.scriptFailure());
                continue;
            }
            LogicEvents.Listener events = LogicEvents.listener();
            if (events != null && events.wantsRuns()) {
                events.ruleRan(level, position(), graph.slot.name(), g, cost, graph.lastActions, graph.slot.simulate(),
                        gameTime);
            }
            // An action of this run may have changed what a graph watches (a device turned off): run again then.
            due = Math.min(due,
                    evaluator.wantsRerun() || evaluator.pending(gameTime) ? gameTime + 1 : evaluator.nextWake());
        }
        if (watchesLoad && loadPast()) {
            // Load falls with time and no event says so: while a graph sees a link past its limit, look again.
            loadDirty = true;
            due = Math.min(due, Math.max(gameTime + 1, nextLoadLook));
        }
        if (pendingDisplays > 0) {
            // A text waits for its display's next write: one wake then.
            due = Math.min(due, Math.max(displayDue, gameTime + 1));
        }
        if (due == Long.MAX_VALUE || due == LogicEvaluator.NO_WAKE) {
            return WAIT;
        }
        long delay = due - gameTime;
        return delay <= 1 ? NEXT_TICK : (int) Math.min(delay, Integer.MAX_VALUE);
    }

    /** Whether some running graph sees a link load past its limit. */
    private boolean loadPast() {
        for (int g = 0; g < graphs.size(); g++) {
            LogicEvaluator<LogicKey> evaluator = graphs.get(g).evaluator;
            if (evaluator == null || !runnable(g)) {
                continue;
            }
            List<Watch<LogicKey>> watches = evaluator.graph().watches();
            for (int w = 0; w < watches.size(); w++) {
                if (watches.get(w).kind() == WatchKind.LOAD && evaluator.past(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void countChecks(int checks, int points, long gameTime) {
        long second = gameTime / TeamBudget.SECOND;
        if (second != statSecond) {
            boolean next = second == statSecond + 1;
            lastSecondChecks = next ? statChecks : 0;
            lastSecondPoints = next ? statPoints : 0;
            statSecond = second;
            statChecks = 0;
            statPoints = 0;
        }
        statChecks += checks;
        statPoints += points;
    }

    // ---- reads

    @Override
    public long read(Watch<LogicKey> watch) {
        int g = running;
        if (g < 0) {
            return 0;
        }
        Graph graph = graphs.get(g);
        int w = watch.index();
        String remoteKey = graph.remoteKeys[w];
        if (!remoteKey.isEmpty()) {
            extraChecks += StorageServerConfig.LOGIC_CROSS_NETWORK_CHECKS.getAsInt();
            Remote remote = remote(remoteKey);
            LogicEvaluator<LogicKey> evaluator = graph.evaluator;
            if ((remote == null || remote.usable() == null) && evaluator != null) {
                // Not loaded or not online: the last value seen stands in, and the why line says so.
                CompiledGraph<LogicKey> compiled = evaluator.graph();
                String name = remote == null ? remoteKey : remote.name;
                for (int i = 0; i < compiled.readers(w); i++) {
                    evaluator.trace().add(compiled.reader(w, i), LogicLang.WHY_NET_UNAVAILABLE, 0, 0, name);
                }
            }
        }
        long value = value(g, watch, graph.labelKeys[w]);
        // What the run saw is what later events compare against.
        graph.offered[w] = value;
        return value;
    }

    /**
     * The current value of {@code watch} as graph {@code g} sees it: a simulating graph reads the shared variables it
     * set itself from its shadow copy.
     */
    private long value(int g, Watch<LogicKey> watch, String labelKey) {
        if (watch.kind() == WatchKind.VARIABLE) {
            Object2LongOpenHashMap<String> shadow = graphs.get(g).shadow;
            if (shadow != null && shadow.containsKey(watch.name())) {
                return shadow.getLong(watch.name());
            }
        }
        Graph graph = graphs.get(g);
        String remoteKey = graph.remoteKeys[watch.index()];
        if (!remoteKey.isEmpty()) {
            // Another network (M7L2 row 13): while it is not usable, the last value seen stands in (0 before any).
            Remote remote = remote(remoteKey);
            NetworkState network = remote == null ? null : remote.usable();
            if (network == null) {
                long last = graph.offered[watch.index()];
                if (last == Long.MIN_VALUE) {
                    // Nothing seen since loading: the value saved with the controller, else 0.
                    last = graph.remoteSeed[watch.index()];
                }
                return last == Long.MIN_VALUE ? 0 : last;
            }
            return value(network, watch, labelKey);
        }
        return value(watch, labelKey);
    }

    /** The current value of {@code watch} on the controller's own network; {@code labelKey} is its label's key. */
    private long value(Watch<LogicKey> watch, String labelKey) {
        NetworkState network = bound;
        return network == null ? 0 : value(network, watch, labelKey);
    }

    /** The current value of {@code watch} on {@code network}; {@code labelKey} is its label's key. */
    private long value(NetworkState network, Watch<LogicKey> watch, String labelKey) {
        return switch (watch.kind()) {
            case ITEM_COUNT -> count(network, watch, labelKey);
            case POWER -> network.isPowered() ? 1 : 0;
            case REDSTONE -> labelKey.isEmpty() ? redstone : labelRedstone(network, labelKey);
            case SENSOR -> sensor(network, labelKey, watch.name());
            case DEVICE -> deviceOn(network, labelKey) ? 1 : 0;
            case LOAD -> load(network, labelKey);
            case DEVICE_COUNT -> labelKey.isEmpty() ? 0 : network.labels().devicesByKey(labelKey).size();
            case FULL -> fullCells(network, labelKey);
            case JOB_FINISHED -> 0;
            case DAYTIME -> {
                ServerLevel level = host.serverLevel();
                yield level == null ? 0 : SkyWatch.daytime(level);
            }
            case WEATHER -> {
                ServerLevel level = host.serverLevel();
                yield level == null ? 0 : SkyWatch.weather(level);
            }
            case ENERGY -> labelKey.isEmpty() ? network.storedEnergy() : labelEnergy(network, labelKey, false);
            case ENERGY_CAPACITY ->
                labelKey.isEmpty() ? network.energyCapacity() : labelEnergy(network, labelKey, true);
            case BYTES_USED -> labelKey.isEmpty() ? network.usedBytes() : labelBytes(network, labelKey, false);
            case BYTES_TOTAL -> labelKey.isEmpty() ? network.totalBytes() : labelBytes(network, labelKey, true);
            case CRAFT_JOBS -> craftJobs(watch);
            case CRAFT_CPUS -> WatchKind.packCpus(JobQueue.freeCpus(network), JobQueue.freeForAutomation(network));
            case PLAYERS_NEAR -> playersNear(network, labelKey, watch.number());
            case VARIABLE -> variables.getLong(watch.name());
            case STOCK -> StockLogic.read(network, labelKey, watch.item().orElse(null), watch.number());
            case CHANNEL -> network.channels().value((int) watch.number());
            case SHED_LEVEL -> network.shedding().level();
            case TASK -> TaskBoards.logicValue(network, watch.name());
            case POOL -> pools.status(network, labelKey, watch.name(), watch.number(), network.level().getGameTime());
            // Signals: never read.
            case ITEM_MOVES, CRAFT_FAILED, TERMINAL_OPENED, RULE_CALLED, POOL_IDLE -> 0;
        };
    }

    /**
     * Players (not spectators) within {@code radius} blocks (at most {@code logic.playerRadiusMax}) of a device
     * labelled {@code labelKey}, or of the controller when it is empty; each player counts once. O(players in the level
     * times labelled devices); allocates nothing.
     */
    private long playersNear(NetworkState network, String labelKey, long radius) {
        ServerLevel level = host.serverLevel();
        if (level == null) {
            return 0;
        }
        long r = Math.clamp(radius, 1, StorageServerConfig.LOGIC_PLAYER_RADIUS_MAX.getAsInt());
        double reach = (double) r * r;
        List<ServerPlayer> players = level.players();
        List<NetworkDevice> labelled = labelKey.isEmpty() ? List.of() : network.labels().devicesByKey(labelKey);
        long count = 0;
        for (int p = 0; p < players.size(); p++) {
            ServerPlayer player = players.get(p);
            if (player.isSpectator()) {
                continue;
            }
            if (labelKey.isEmpty()) {
                count += near(player, device.blockPos(), reach) ? 1 : 0;
                continue;
            }
            for (int d = 0; d < labelled.size(); d++) {
                if (near(player, labelled.get(d).blockPos(), reach)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** Whether {@code player} is within the square root of {@code reach} blocks of the centre of block {@code pos}. */
    private static boolean near(ServerPlayer player, long pos, double reach) {
        double dx = player.getX() - (BlockPos.getX(pos) + 0.5);
        double dy = player.getY() - (BlockPos.getY(pos) + 0.5);
        double dz = player.getZ() - (BlockPos.getZ(pos) + 0.5);
        return dx * dx + dy * dy + dz * dz <= reach;
    }

    /**
     * FE stored in (or the capacity of) the devices labelled {@code labelKey} that hold the network's energy: energy
     * cells and controllers. Running totals each, so O(labelled devices).
     */
    private static long labelEnergy(NetworkState network, String labelKey, boolean capacity) {
        long sum = 0;
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            @Nullable
            FeBuffer buffer = switch (labelled.get(d)) {
                case NetworkEnergyCell cell -> cell.cellStore();
                case NetworkController controller -> controller.buffer();
                default -> null;
            };
            if (buffer != null) {
                sum = saturatedAdd(sum, capacity ? buffer.capacity() : buffer.stored());
            }
        }
        return sum;
    }

    /** Bytes used in (or the capacity of) the storage cells of the devices labelled {@code labelKey}. */
    private static long labelBytes(NetworkState network, String labelKey, boolean total) {
        long sum = 0;
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            List<StorageProvider> providers = labelled.get(d).providers();
            for (int p = 0; p < providers.size(); p++) {
                if (providers.get(p) instanceof ByteCapacity bytes) {
                    sum = saturatedAdd(sum, total ? bytes.totalBytes() : bytes.usedBytes());
                }
            }
        }
        return sum;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /**
     * The network's jobs for the watch's item (any item when empty) as {@link WatchKind#packCraft}: how many wait for a
     * CPU, and whether one runs. A tag names no job.
     */
    private long craftJobs(Watch<LogicKey> watch) {
        @Nullable
        StackKey key = null;
        if (watch.item().isPresent()) {
            if (!(watch.item().get() instanceof LogicKey.Stack(StackKey stack))) {
                return 0;
            }
            key = stack;
        }
        return WatchKind.packCraft(craftTally.count(key, JobState.PLANNED),
                craftTally.count(key, JobState.RUNNING) > 0);
    }

    /** Whether some device labelled {@code labelKey} is on (not turned off by logic); a label no device has is off. */
    private static boolean deviceOn(NetworkState network, String labelKey) {
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            if (!labelled.get(d).switchedOff()) {
                return true;
            }
        }
        return false;
    }

    /** The busiest link, in percent, on the route of the devices labelled {@code labelKey}, or the controller's. */
    private long load(NetworkState network, String labelKey) {
        if (labelKey.isEmpty()) {
            return network.routeLoad(device.nodePos());
        }
        int load = 0;
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            load = Math.max(load, network.routeLoad(labelled.get(d).nodePos()));
        }
        return load;
    }

    /**
     * Full storage among the devices labelled {@code labelKey}, or every device of the network: each full cell of a
     * drive, each full bulk drive, and each full storage chest a port faces (the chest is no network device, so the
     * port stands for it and carries the label). On events only.
     */
    private static long fullCells(NetworkState network, String labelKey) {
        long full = 0;
        if (labelKey.isEmpty()) {
            for (NetworkDevice member : network.devices()) {
                full += fullIn(member);
            }
            return full;
        }
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            full += fullIn(labelled.get(d));
        }
        return full;
    }

    private static int fullIn(NetworkDevice device) {
        return switch (device) {
            case DriveBlockEntity drive -> drive.fullCells();
            case BulkDriveBlockEntity bulk -> bulk.isFull() ? 1 : 0;
            case StorageBlockEntity block -> block.isFull() ? 1 : 0;
            case PortDevice port -> port.worker().facedChestFull() ? 1 : 0;
            default -> 0;
        };
    }

    private long count(NetworkState network, Watch<LogicKey> watch, String labelKey) {
        return watch.item().isEmpty() ? 0 : count(network, watch.item().get(), labelKey);
    }

    /** The count of {@code key} in the network or in the devices labelled {@code labelKey}; tags add up their items. */
    private long count(NetworkState network, LogicKey key, String labelKey) {
        if (labelKey.isEmpty()) {
            if (network != bound && !(key instanceof LogicKey.Stack)) {
                // Another network (M7L2 row 13) keeps no tally: sum its contents for the tag once per look.
                tagSum.tag = key;
                tagSum.sum = 0;
                network.forEachNative(tagSum);
                tagSum.tag = null;
                return tagSum.sum;
            }
            return switch (key) {
                case LogicKey.Stack stack -> network.contents().amount(stack.key());
                case LogicKey.Tag tag -> tally(tag).total;
                case LogicKey.FluidTag tag -> tally(tag).total;
            };
        }
        long sum = 0;
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            List<StorageProvider> providers = labelled.get(d).providers();
            for (int p = 0; p < providers.size(); p++) {
                StorageProvider provider = providers.get(p);
                if (key instanceof LogicKey.Stack stack) {
                    sum += provider.amount(stack.key());
                } else {
                    tagSum.tag = key;
                    tagSum.sum = 0;
                    provider.forEachStored(tagSum);
                    sum += tagSum.sum;
                    tagSum.tag = null;
                }
            }
        }
        return sum;
    }

    /** The strongest redstone signal into any device labelled {@code labelKey}; unloaded blocks read 0. */
    private long labelRedstone(NetworkState network, String labelKey) {
        ServerLevel level = host.serverLevel();
        if (level == null) {
            return 0;
        }
        int best = 0;
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            cursor.set(labelled.get(d).blockPos());
            if (level.isLoaded(cursor)) {
                best = Math.max(best, level.getBestNeighborSignal(cursor));
            }
        }
        return best;
    }

    /**
     * Adds a why line to each node of the run that reads a sensor value the sensor did not find (a pack reader value
     * whose path is missing, or a name the sensor does not know), so the editor says why it reads 0. Allocates nothing.
     */
    private void traceMissing(Graph graph, LogicEvaluator<LogicKey> evaluator) {
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        CompiledGraph<LogicKey> compiled = evaluator.graph();
        List<Watch<LogicKey>> watches = compiled.watches();
        for (int w = 0; w < watches.size(); w++) {
            Watch<LogicKey> watch = watches.get(w);
            if (watch.kind() != WatchKind.SENSOR || graph.offered[w] != 0) {
                continue;
            }
            Remote remote = remote(graph.remoteKeys[w]);
            NetworkState on = graph.remoteKeys[w].isEmpty() ? network : remote == null ? null : remote.usable();
            // A value that was not found reads 0, so only a 0 is worth asking the sensor about.
            if (on == null || sensorFound(on, graph.labelKeys[w], watch.name())) {
                continue;
            }
            for (int i = 0; i < compiled.readers(w); i++) {
                evaluator.trace().add(compiled.reader(w, i), SensorTexts.WHY_NOT_FOUND, 0, 0);
            }
        }
    }

    /** Whether the sensor labelled {@code labelKey} found {@code name}; true when no device has the label. */
    private static boolean sensorFound(NetworkState network, String labelKey, String name) {
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            if (labelled.get(d) instanceof SensorSource sensor) {
                return sensor.logicValueFound(name);
            }
        }
        return true;
    }

    private static long sensor(NetworkState network, String labelKey, String name) {
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        for (int d = 0; d < labelled.size(); d++) {
            if (labelled.get(d) instanceof SensorSource sensor) {
                return sensor.logicValue(name);
            }
        }
        return 0;
    }

    /** Whether {@code key} is in {@code tag}, an item or a fluid tag; a stack key is in no tag. */
    private static boolean inTag(StackKey key, LogicKey tag) {
        return switch (tag) {
            case LogicKey.Tag items ->
                key instanceof ItemKey item && item.item().builtInRegistryHolder().is(items.tag());
            case LogicKey.FluidTag fluids ->
                key instanceof FluidKey fluid && fluid.fluid().builtInRegistryHolder().is(fluids.tag());
            case LogicKey.Stack _ -> false;
        };
    }

    @Override
    public boolean craftRunning(LogicKey item) {
        return craftRunning(item, "");
    }

    /**
     * Whether a craft of {@code item} this controller asked for, or any job for it, is out on the network {@code label}
     * names ({@code @name}, M7L2 row 13; empty: the own network). A network that is not usable runs nothing.
     */
    @Override
    public boolean craftRunning(LogicKey item, String label) {
        String network = NetworkRef.network(label);
        NetworkState on = bound;
        if (!network.isEmpty()) {
            int r = remoteIndex(network);
            on = r < 0 ? null : remotes.get(r).usable();
        }
        if (on == null || !(item instanceof LogicKey.Stack(StackKey key))) {
            return false;
        }
        if (network.isEmpty()
                ? crafting.contains(key)
                : remoteCrafting.contains(new RemoteCraft(NetworkRef.key(network), key))) {
            return true;
        }
        for (CraftingJob job : JobQueue.jobsOn(on)) {
            if (job.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ---- For each (M7L2 row 18)

    /**
     * A read for a For each's pass: the count of one item, or a stock keeper value for it. Only single items come here
     * (tags are gone through item by item); allocates nothing.
     */
    @Override
    public long readEach(WatchKind kind, LogicKey item, String label, long number) {
        String labelKey = eachLabelKey(label);
        NetworkState network = bound;
        if (!eachNetworkKey.isEmpty()) {
            Remote remote = remote(eachNetworkKey);
            network = remote == null ? null : remote.usable();
            extraChecks += StorageServerConfig.LOGIC_CROSS_NETWORK_CHECKS.getAsInt();
            LogicEvaluator<LogicKey> evaluator = evaluatorNow();
            if (network == null && evaluator != null) {
                // Per-item reads keep no last value: they read 0, and the why line says why (once per run).
                evaluator.whyRead(LogicLang.WHY_NET_UNAVAILABLE, remote == null ? eachNetworkKey : remote.name);
            }
        }
        if (network == null || !(item instanceof LogicKey.Stack)) {
            return 0;
        }
        return switch (kind) {
            case ITEM_COUNT -> count(network, item, labelKey);
            case STOCK -> StockLogic.read(network, labelKey, item, number);
            default -> 0;
        };
    }

    /** {@code label} as a label key; the last one is kept, so a pass's reads allocate nothing. */
    private String eachLabelKey(String label) {
        if (!label.equals(eachLabel)) {
            eachLabel = label;
            eachLabelKey = DeviceLabel.key(NetworkRef.device(label));
            eachNetworkKey = NetworkRef.key(NetworkRef.network(label));
        }
        return eachLabelKey;
    }

    /** An item tag stands for its items (from {@link EachItems}, kept until tags reload), an item for itself. */
    @Override
    public void eachItem(LogicKey key, List<LogicKey> out, int max) {
        switch (key) {
            case LogicKey.Tag tag -> {
                List<LogicKey> items = EachItems.of(tag.tag());
                for (int i = 0; i < items.size() && out.size() < max; i++) {
                    out.add(items.get(i));
                }
            }
            case LogicKey.Stack stack -> {
                if (out.size() < max) {
                    out.add(stack);
                }
            }
            // A fluid tag names no items to go through.
            case LogicKey.FluidTag _ -> {
            }
        }
    }

    /**
     * The items of the rules the stock keepers labelled {@code label} run, keepers in label order; a switched-off rule
     * is skipped, as the keeper skips it.
     */
    @Override
    public void stockItems(String label, List<LogicKey> out, int max) {
        NetworkState network = bound;
        if (network == null || label.isBlank()) {
            return;
        }
        List<NetworkDevice> labelled = network.labels().devicesByKey(eachLabelKey(label));
        int slots = StockKeeperWorker.ruleSlots();
        for (int d = 0; d < labelled.size() && out.size() < max; d++) {
            StockKeeperWorker keeper = StockLogic.keeper(labelled.get(d));
            if (keeper == null) {
                continue;
            }
            for (int i = 0; i < slots && out.size() < max; i++) {
                StockRule rule = keeper.rule(i);
                if (rule.key().isPresent() && !rule.switchedOff()) {
                    StackKey key = rule.key().get();
                    LogicKey logic = stockKeys.get(key);
                    if (logic == null) {
                        if (stockKeys.size() >= 256) {
                            stockKeys.clear();
                        }
                        logic = LogicKey.of(key);
                        stockKeys.put(key, logic);
                    }
                    out.add(logic);
                }
            }
        }
    }

    @Override
    public int maxEachItems() {
        return StorageServerConfig.LOGIC_FOR_EACH_MAX_ITEMS.getAsInt();
    }

    @Override
    public long checksLeft() {
        return budgetLeft;
    }

    @Override
    public long actionPointsLeft() {
        return pointsLeft;
    }

    // ---- actions (area E): carried out as the run sends its batch

    /**
     * Keeps {@code action} for {@link #actions()} and logs it at debug. Returns whether to carry it out: false while
     * the running graph simulates, when the action is kept marked as simulated and its why line says so, and the caller
     * must change nothing.
     */
    private boolean collect(LogicAction action) {
        if (remoteLabel(action.label()) || remoteLabel(action.to())) {
            // An action on another network (M7L2 row 13) pays its surcharge, simulated or not, like its own points.
            extraPoints += StorageServerConfig.LOGIC_CROSS_NETWORK_POINTS.getAsInt();
        }
        boolean simulated = simulatingNow();
        keep(simulated ? action.asSimulated() : action);
        if (simulated) {
            why(LogicLang.WHY_SIMULATED, 0, 0);
        }
        return !simulated;
    }

    /** Keeps {@code kept} for {@link #actions()} and logs it at debug, without a why line. */
    private void keep(LogicAction kept) {
        if (actions.size() >= KEPT_ACTIONS) {
            actions.remove(0);
        }
        actions.add(kept);
        int g = kept.graph();
        if (g >= 0 && g < graphs.size()) {
            graphs.get(g).lastActions++;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Logic controller at {} graph {}: {}", BlockPos.of(device.nodePos()).toShortString(),
                    kept.graph(), kept.describe());
        }
    }

    /** Whether the graph running now simulates its actions. */
    private boolean simulatingNow() {
        int g = running;
        return g >= 0 && graphs.get(g).slot.simulate();
    }

    /** The evaluator of the graph running now, for why lines; null between runs. */
    private @Nullable LogicEvaluator<LogicKey> evaluatorNow() {
        int g = running;
        return g < 0 ? null : graphs.get(g).evaluator;
    }

    /** Whether {@code label} names another network; allocates only for a label with an {@code @}. */
    private static boolean remoteLabel(String label) {
        return label.indexOf(NetworkRef.MARK) >= 0 && NetworkRef.remote(label);
    }

    /**
     * Adds a why line for the action being carried out; after a network was found unavailable it does not add "no
     * device with this label" too.
     */
    private void why(String reasonKey, long a, long b) {
        if (targetsUnavailable && LogicLang.WHY_NO_TARGET.equals(reasonKey)) {
            return;
        }
        LogicEvaluator<LogicKey> evaluator = evaluatorNow();
        if (evaluator != null) {
            evaluator.why(reasonKey, a, b);
        }
    }

    /** A why line whose first {@code %s} is {@code text} (a network's name). */
    private void why(String reasonKey, String text) {
        LogicEvaluator<LogicKey> evaluator = evaluatorNow();
        if (evaluator != null) {
            evaluator.why(reasonKey, text);
        }
    }

    /**
     * The network {@code label} names when it names another one that is usable now; null with a why line when it is not
     * loaded or not online, and the controller's own network for a label naming none.
     */
    private @Nullable NetworkState targetNetwork(String label) {
        String network = NetworkRef.network(label);
        if (network.isEmpty()) {
            return bound;
        }
        int r = remoteIndex(network);
        NetworkState usable = r < 0 ? null : remotes.get(r).usable();
        if (usable == null) {
            targetsUnavailable = true;
            why(LogicLang.WHY_NET_UNAVAILABLE_ACT, network);
        }
        return usable;
    }

    /**
     * The devices labelled {@code label} on the network, copied: carrying out an action may change the network's
     * devices (a device turned off tells the device watchers). Empty without a network.
     */
    private List<NetworkDevice> labelled(String label) {
        targetsUnavailable = false;
        if (label.isEmpty()) {
            return List.of();
        }
        // "intake@North Base" acts on another network (M7L2 row 13); each device still asks its own access check.
        NetworkState network = targetNetwork(label);
        String deviceLabel = NetworkRef.device(label);
        if (network == null || deviceLabel.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(network.labels().devices(deviceLabel));
    }

    /**
     * Turns the switchable devices labelled {@code label} on or off; the state is saved on each device and its work
     * pauses ({@link NetworkDevice#switchable()}). Storage can't be turned off; the why line says so.
     */
    @Override
    public void switchDevice(String label, boolean on) {
        if (!collect(LogicAction.device(running, LogicAction.Kind.SWITCH_DEVICE, label, on ? 1 : 0))) {
            return;
        }
        boolean any = false;
        boolean storage = false;
        int g = running;
        // Kept on the device so its screen and Jade can say which graph turned it off.
        SwitchSource by = on || g < 0
                ? null
                : new SwitchSource(BlockPos.of(device.nodePos()), g, graphs.get(g).slot.name());
        int denied = 0;
        for (NetworkDevice target : labelled(label)) {
            if (!target.switchable()) {
                storage = true;
            } else if (!mayConfigure(target, now)) {
                denied++;
            } else {
                target.setSwitchedOff(!on, by);
                any = true;
            }
        }
        if (denied > 0) {
            why(LogicLang.WHY_DENIED, denied, 0);
        }
        if (storage) {
            why(LogicLang.CANT_SWITCH, 0, 0);
        } else if (!any && denied == 0) {
            why(LogicLang.WHY_NO_TARGET, 0, 0);
        }
    }

    /** Sets the priority of the devices labelled {@code label} that have one, through the shared priority setting. */
    @Override
    public void setPriority(String label, long priority) {
        if (!collect(LogicAction.device(running, LogicAction.Kind.SET_PRIORITY, label, priority))) {
            return;
        }
        int clamped = (int) Math.clamp(priority, PriorityControl.MIN, PriorityControl.MAX);
        boolean any = false;
        int denied = 0;
        for (NetworkDevice target : labelled(label)) {
            if (!(target instanceof Prioritized prioritized)) {
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            prioritized.setPriority(clamped);
            any = true;
        }
        whyTargets(any, denied);
    }

    /**
     * Sets the power priority (M7L2 load shedding) of the devices labelled {@code label} that load shedding can pause,
     * each only with the owner's access to it; the network pauses or resumes them at once by its shed level.
     */
    @Override
    public void setPowerPriority(String label, long priority) {
        int clamped = PowerPriority.clamp(priority);
        if (!collect(LogicAction.device(running, LogicAction.Kind.POWER_PRIORITY, label, clamped))) {
            return;
        }
        boolean any = false;
        int denied = 0;
        for (NetworkDevice target : labelled(label)) {
            if (!target.sheddable()) {
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            target.setPowerPriority(clamped);
            any = true;
        }
        whyTargets(any, denied);
    }

    /**
     * Sets the strength of the condition switches labelled {@code label} that are in logic mode; the controller's own
     * faces give no signal.
     */
    @Override
    public void redstone(String label, int strength) {
        if (!collect(LogicAction.device(running, LogicAction.Kind.REDSTONE, label, strength))) {
            return;
        }
        boolean any = false;
        int denied = 0;
        for (NetworkDevice target : labelled(label)) {
            if (!(target instanceof ConditionDevice condition)
                    || condition.worker().settings().type() != ConditionType.LOGIC) {
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            condition.worker().setLogicStrength(strength);
            any = true;
        }
        whyTargets(any, denied);
    }

    /**
     * Sets this controller's share of network redstone channel {@code channel} (M7L2). A channel is network state, not
     * a device, so there is no per-device access check: joining the network was the check. Recorded as a
     * {@link LogicAction.Kind#CHANNEL} action with the colour as its label.
     */
    @Override
    public void channel(int channel, int strength) {
        if (!collect(LogicAction.device(running, LogicAction.Kind.CHANNEL, NodeTypes.channelId(channel), strength))) {
            return;
        }
        setChannelShare(channel, strength);
    }

    /**
     * Starts or ends a pulse on network redstone channel {@code channel}; the end sets the share back to 0 only while
     * it is still the pulse's strength, so a later write by another node is not undone.
     */
    @Override
    public void pulseChannel(int channel, int strength, boolean on) {
        if (!collect(LogicAction.device(running, LogicAction.Kind.CHANNEL, NodeTypes.channelId(channel),
                on ? strength : 0))) {
            return;
        }
        if (on) {
            setChannelShare(channel, strength);
        } else if (channel >= 0 && channel < RedstoneChannels.COUNT && channelShares[channel] == strength) {
            setChannelShare(channel, 0);
        }
    }

    private void setChannelShare(int channel, int strength) {
        if (channel < 0 || channel >= RedstoneChannels.COUNT) {
            return;
        }
        int next = Math.clamp(strength, 0, 15);
        if (channelShares[channel] == next) {
            return;
        }
        channelShares[channel] = next;
        host.changed();
        NetworkState network = bound;
        if (network != null) {
            network.channels().write(this, device.ownerRecord(), channel, next);
        }
    }

    /** The controller's owner changed: its channel shares now count as the new owner's for channel switches. */
    public void ownerChanged() {
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        for (int channel = 0; channel < RedstoneChannels.COUNT; channel++) {
            network.channels().write(this, device.ownerRecord(), channel, channelShares[channel]);
        }
    }

    /** This controller's share of each network redstone channel, 16 values, for saving; tests read it too. */
    public int[] channelShares() {
        return channelShares.clone();
    }

    /** Takes the saved shares of {@link #channelShares}; a network the controller is in already gets them at once. */
    public void loadChannelShares(int[] saved) {
        NetworkState network = bound;
        for (int channel = 0; channel < RedstoneChannels.COUNT; channel++) {
            channelShares[channel] = channel < saved.length ? Math.clamp(saved[channel], 0, 15) : 0;
            if (network != null) {
                network.channels().write(this, device.ownerRecord(), channel, channelShares[channel]);
            }
        }
    }

    /**
     * Starts or ends a pulse on the condition switches labelled {@code label} in logic mode. Starting is a change like
     * {@link #redstone} and needs access; the switches it turned on are remembered with the strength. Ending turns
     * those back off without asking again (it only undoes this controller's own pulse, so access lost meanwhile cannot
     * leave a pulse on), and only while the switch still gives the pulse's strength; other switches with the label are
     * turned off as by {@link #redstone}.
     */
    @Override
    public void pulse(String label, int strength, boolean on) {
        if (!collect(LogicAction.device(running, LogicAction.Kind.REDSTONE, label, on ? strength : 0))) {
            return;
        }
        boolean any = false;
        int denied = 0;
        for (NetworkDevice target : labelled(label)) {
            if (!(target instanceof ConditionDevice condition)
                    || condition.worker().settings().type() != ConditionType.LOGIC) {
                continue;
            }
            long pos = target.blockPos();
            if (!on && pulsed.containsKey(pos)) {
                int ours = pulsed.remove(pos);
                if (condition.worker().logicStrength() == ours) {
                    condition.worker().setLogicStrength(0);
                }
                any = true;
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            condition.worker().setLogicStrength(on ? strength : 0);
            if (on) {
                pulsed.put(pos, strength);
            }
            any = true;
        }
        whyTargets(any, denied);
    }

    /**
     * Requests {@code amount} of {@code item} as a background craft, like a stock keeper: after every player request,
     * as the controller's owner, one at a time per item. A refusal (nothing can craft it) and a failed job go to the
     * network's log; a refusal also to the why line. The network's craft limits (M7L2 chunk 20) show there too: an
     * amount capped to the most per job, and a job that will wait because the free CPUs are kept for players.
     */
    @Override
    public void requestCraft(LogicKey item, long amount) {
        requestCraft(item, amount, "");
    }

    /**
     * Requests the craft on the network {@code label} names ({@code @name}, M7L2 row 13; empty: the own network), with
     * that network's patterns, CPUs and limits; nothing when it is not loaded or not online (a why line says so).
     */
    @Override
    public void requestCraft(LogicKey item, long amount, String label) {
        if (!collect(new LogicAction(running, LogicAction.Kind.REQUEST_CRAFT, label, Optional.of(item), "", amount, "",
                0))) {
            return;
        }
        targetsUnavailable = false;
        NetworkState network = targetNetwork(label);
        if (network == null) {
            return;
        }
        if (!(item instanceof LogicKey.Stack(StackKey key))) {
            why(LogicLang.WHY_TAG, 0, 0);
            return;
        }
        String networkName = NetworkRef.network(label);
        RemoteCraft remote = networkName.isEmpty() ? null : new RemoteCraft(NetworkRef.key(networkName), key);
        if (remote == null ? crafting.contains(key) : remoteCrafting.contains(remote)) {
            why(LogicLang.WHY_CRAFT_RUNNING, 0, 0);
            return;
        }
        long asked = Math.max(1, amount);
        long capped = network.craftLimits().cap(asked);
        if (capped < asked) {
            why(LogicLang.WHY_CRAFT_CAPPED, capped, asked);
        }
        BackgroundCrafting.Request request = new BackgroundCrafting.Request(key, capped, 0, 0, device.ownerRecord());
        if (remote == null) {
            crafting.add(key);
        } else {
            remoteCrafting.add(remote);
        }
        int graph = running;
        UUID[] queued = new UUID[1];
        boolean taken = BackgroundCrafts.get().submit(network, request, outcome -> {
            if (remote != null) {
                remoteCrafting.remove(remote);
            }
            craftEnded(network, key, outcome, remote == null);
            UUID id = queued[0];
            if (id != null) {
                forgetOwnJob(id);
            }
        }, id -> {
            queued[0] = id;
            ownJobs.add(new OwnJob(graph, key, id));
        });
        if (!taken) {
            if (remote == null) {
                crafting.remove(key);
            } else {
                remoteCrafting.remove(remote);
            }
            why(LogicLang.WHY_NOT_CRAFTABLE, 0, 0);
            post(network, LogicLang.LOG_CRAFT_REFUSED, key);
        } else if (JobQueue.automationBlocked(network)) {
            why(LogicLang.WHY_CRAFT_NO_CPU, JobQueue.cpus(network), network.craftLimits().effectiveCpusKeptFree());
        } else if (JobQueue.freeCpus(network) > 0 && JobQueue.freeForAutomation(network) == 0) {
            why(LogicLang.WHY_CRAFT_KEPT_FREE, network.craftLimits().effectiveCpusKeptFree(), 0);
        }
    }

    private void craftEnded(NetworkState network, StackKey key, BackgroundCrafting.Outcome outcome, boolean own) {
        if (own) {
            crafting.remove(key);
        }
        if (outcome == BackgroundCrafting.Outcome.FAILED) {
            post(network, LogicLang.LOG_CRAFT_FAILED, key);
        }
    }

    /** Posts a line about {@code key} to the network's log. */
    private void post(NetworkState network, String reasonKey, StackKey key) {
        String argKey = key instanceof ItemKey stack ? stack.item().getDescriptionId() : "";
        network.notices()
                .post(new NetworkNotices.Notice(network.level().getGameTime(), reasonKey, key.toString(), argKey));
    }

    /**
     * Moves up to {@code amount} of {@code item} between the network's storage and the devices labelled by one side:
     * the labelled ports and interfaces do the moving through their own handlers, rates and claims, one after another
     * until the amount moved ({@link LogicMover}). What moved short goes to the why line.
     */
    @Override
    public void moveItems(LogicKey item, String from, String to, long amount) {
        if (!collect(
                new LogicAction(running, LogicAction.Kind.MOVE_ITEMS, from, Optional.of(item), to, amount, "", 0))) {
            return;
        }
        if (!(item instanceof LogicKey.Stack(StackKey key))) {
            why(LogicLang.WHY_TAG, 0, 0);
            return;
        }
        // A side naming only a network ("@North Base") is that network's storage, like an empty one (M7L2 row 13).
        boolean fromStorage = NetworkRef.device(from).isEmpty();
        if (fromStorage == NetworkRef.device(to).isEmpty()) {
            why(LogicLang.WHY_MOVE_SIDES, 0, 0);
            return;
        }
        boolean intoDevice = fromStorage;
        long moved = 0;
        boolean any = false;
        int denied = 0;
        for (NetworkDevice target : labelled(intoDevice ? to : from)) {
            if (moved >= amount) {
                break;
            }
            if (!(target instanceof LogicMover mover)) {
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            any = true;
            moved += mover.logicMove(key, amount - moved, intoDevice, now);
        }
        if (denied > 0) {
            why(LogicLang.WHY_DENIED, denied, 0);
        }
        if (!any && denied == 0) {
            why(LogicLang.WHY_NO_TARGET, 0, 0);
        } else if (any && moved < amount) {
            why(LogicLang.WHY_MOVED, moved, amount);
        }
        lastMoved = moved;
    }

    /** How many times the controller read pool member {@code member}'s machine directly; for tests. */
    public int poolDirectReads(NetworkDevice member) {
        return pools.directReads(member);
    }

    /**
     * Sends up to {@code amount} of {@code item} from the network's storage to the idle members of the machine pool
     * labelled {@code label} (M7L2 row 17), shared out by {@code strategy} ({@link Pools.Planner}). Members the owner
     * may not change are skipped (the first refusal per member goes to the network's log); each member moves through
     * its own handler, rate and claim check ({@link LogicMover#logicMove}), so only what moved counts and a member that
     * takes less leaves the rest for the next ones. Simulating, nothing moves: each planned share is recorded as "would
     * send", and the turn stays. One action per member that took (or would take) items, with the member's number from
     * 1. Idle is decided by {@link MachinePools#idle}: a sensor on the member's machine, else a direct read.
     */
    @Override
    public long sendToPool(LogicKey item, String label, long amount, int strategy, String idleValue, long idle,
            long cursor) {
        lastMoved = 0;
        NetworkState network = bound;
        if (!(item instanceof LogicKey.Stack(StackKey key))) {
            collect(new LogicAction(running, LogicAction.Kind.POOL_SEND, label, Optional.of(item), "", 0, "", 0));
            why(LogicLang.WHY_TAG, 0, 0);
            return cursor;
        }
        if (network == null || label.isEmpty()) {
            return cursor;
        }
        MachinePools.Pool pool = pools.pool(network, DeviceLabel.key(label));
        int n = pool.size();
        if (n == 0) {
            why(LogicLang.WHY_NO_TARGET, 0, 0);
            return cursor;
        }
        if (poolCandidate.length < n) {
            int size = Math.max(n, poolCandidate.length * 2);
            poolCandidate = new boolean[size];
            poolHeld = new long[size];
            poolMoved = new long[size];
        }
        int denied = 0;
        int unseen = 0;
        int busy = 0;
        for (int i = 0; i < n; i++) {
            NetworkDevice member = pool.member(i);
            poolMoved[i] = 0;
            poolHeld[i] = 0;
            poolCandidate[i] = false;
            if (!mayConfigure(member, now)) {
                denied++;
                continue;
            }
            if (!idleValue.isEmpty() && pool.sensor(i) == null && !pool.hasMachine(i)) {
                unseen++;
            }
            if (!pools.idle(pool, i, idleValue, idle, now, StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt())) {
                busy++;
                continue;
            }
            poolCandidate[i] = true;
            if (strategy == Pools.LEAST_FULL && member instanceof LogicMover mover) {
                poolHeld[i] = mover.logicHeld(key);
            }
        }
        poolPlanner.plan(strategy, amount, n, poolCandidate, poolHeld, (int) Math.floorMod(cursor, (long) n));
        if (denied > 0) {
            why(LogicLang.WHY_DENIED, denied, 0);
        }
        if (unseen > 0) {
            why(LogicLang.WHY_POOL_NO_SENSOR, unseen, 0);
        }
        int count = poolPlanner.count();
        if (count == 0) {
            if (busy > 0) {
                why(LogicLang.WHY_POOL_BUSY, busy, n);
            }
            return cursor;
        }
        if (simulatingNow()) {
            for (int j = 0; j < count; j++) {
                int i = poolPlanner.order(j);
                long share = poolPlanner.share(i);
                if (share > 0) {
                    keep(new LogicAction(running, LogicAction.Kind.POOL_SEND, label, Optional.of(item), "#" + (i + 1),
                            share, "", 0).asSimulated());
                }
            }
            why(LogicLang.WHY_SIMULATED, 0, 0);
            // Simulating moves nothing, so whose turn it is stays as it was.
            return cursor;
        }
        long total = 0;
        int served = -1;
        // Each member its share, then what some took short of to the members that took all of theirs, in turn.
        for (int pass = 0; pass < 2 && total < amount; pass++) {
            for (int j = 0; j < count && total < amount; j++) {
                int i = poolPlanner.order(j);
                long share = poolPlanner.share(i);
                long ask = pass == 0 ? share : poolMoved[i] < share ? 0 : amount - total;
                if (ask <= 0 || !(pool.member(i) instanceof LogicMover mover)) {
                    continue;
                }
                long moved = mover.logicMove(key, ask, true, now);
                if (moved > 0) {
                    poolMoved[i] += moved;
                    total += moved;
                    if (served < 0) {
                        served = i;
                    }
                }
            }
        }
        for (int j = 0; j < count; j++) {
            int i = poolPlanner.order(j);
            if (poolMoved[i] > 0) {
                keep(new LogicAction(running, LogicAction.Kind.POOL_SEND, label, Optional.of(item), "#" + (i + 1),
                        poolMoved[i], "", 0));
            }
        }
        if (total < amount) {
            why(LogicLang.WHY_MOVED, total, amount);
        }
        lastMoved = total;
        return Pools.nextCursor(strategy, served, n, (int) Math.floorMod(cursor, (long) n));
    }

    /**
     * Sets shared variable {@code name}: graphs reading it get the new value on their next run (only when it changed).
     * A new name past {@code logic.maxVariables} is refused with a why line. Saved with the controller.
     */
    @Override
    public void setVariable(String name, long value, boolean yesNo) {
        if (!collect(
                new LogicAction(running, LogicAction.Kind.SET_VARIABLE, "", Optional.empty(), "", value, name, 0))) {
            simulateVariable(name, value);
            return;
        }
        if (name.isEmpty()) {
            return;
        }
        boolean known = variables.containsKey(name);
        int most = StorageServerConfig.LOGIC_MAX_VARIABLES.getAsInt();
        if (!known && variables.size() >= most) {
            why(LogicLang.WHY_VARIABLES_FULL, most, 0);
            return;
        }
        long before = variables.getLong(name);
        boolean kindChanged = yesNo != yesNoVariables.contains(name);
        if (known && before == value && !kindChanged) {
            return;
        }
        variables.put(name, value);
        if (yesNo) {
            yesNoVariables.add(name);
        } else {
            yesNoVariables.remove(name);
        }
        host.changed();
        if (!known || before != value) {
            offerAll(WatchKind.VARIABLE, false);
        }
    }

    /**
     * Ticks or opens the task {@code name} names on a board of the controller's network (M7C item 15), by this
     * controller (its label as the "by"). A task that does not exist gets a why line.
     */
    @Override
    public void setTask(String name, boolean done) {
        if (!collect(new LogicAction(running, LogicAction.Kind.SET_TASK, "", Optional.empty(), "", done ? 1 : 0,
                TaskNames.describe(name), 0))) {
            return;
        }
        NetworkState network = bound;
        if (network == null || !TaskBoards.setByLogic(network, name, done, device.deviceLink().label().text())) {
            why(LogicLang.WHY_NO_TASK, 0, 0);
        }
    }

    /**
     * A simulating graph sets shared variable {@code name}: only its own shadow copy changes, which only it reads (the
     * controller's variables and other graphs see nothing). Shadow names the real set lacks count against
     * {@code logic.maxVariables} with the real names (a name in both counts once), so a simulation refuses what the
     * real graph would.
     */
    private void simulateVariable(String name, long value) {
        int g = running;
        if (name.isEmpty() || g < 0) {
            return;
        }
        Graph graph = graphs.get(g);
        Object2LongOpenHashMap<String> shadow = graph.shadow;
        if (shadow == null) {
            shadow = new Object2LongOpenHashMap<>();
            graph.shadow = shadow;
        }
        boolean known = shadow.containsKey(name) || variables.containsKey(name);
        int most = StorageServerConfig.LOGIC_MAX_VARIABLES.getAsInt();
        // Names in both the shadow and the real set count once: only shadow names the real set lacks are extra.
        int extra = 0;
        for (String shadowed : shadow.keySet()) {
            if (!variables.containsKey(shadowed)) {
                extra++;
            }
        }
        if (!known && variables.size() + extra >= most) {
            why(LogicLang.WHY_VARIABLES_FULL, most, 0);
            return;
        }
        long before = shadow.containsKey(name) ? shadow.getLong(name) : variables.getLong(name);
        shadow.put(name, value);
        if (!known || before != value) {
            List<Watch<LogicKey>> watches = watches(g);
            for (int w = 0; w < watches.size(); w++) {
                if (watches.get(w).kind() == WatchKind.VARIABLE) {
                    offer(g, w, value(g, watches.get(w), graph.labelKeys[w]));
                }
            }
        }
    }

    /** The value graph {@code index} sees for shared variable {@code name}: its shadow copy while it simulates. */
    public long variable(int index, String name) {
        Object2LongOpenHashMap<String> shadow = graphs.get(index).shadow;
        return shadow != null && shadow.containsKey(name) ? shadow.getLong(name) : variables.getLong(name);
    }

    /** The shared variables, in the order they were first set, for saving and tests. Allocates. */
    public List<SharedVariable> variables() {
        List<SharedVariable> out = new ArrayList<>(variables.size());
        for (var entry : variables.object2LongEntrySet()) {
            out.add(new SharedVariable(entry.getKey(), entry.getLongValue(), yesNoVariables.contains(entry.getKey())));
        }
        return out;
    }

    /** The value of shared variable {@code name} (as the compiler keys it); 0 while no graph set it. */
    public long variable(String name) {
        return variables.getLong(name);
    }

    /**
     * Takes saved variables, as the block entity loads or is placed from an item; graphs reading them see the values on
     * their next run. The item component is untrusted, so names are keyed as the compiler keys them (blank ones
     * dropped, the first of two equal names kept), values kept within the number range (a yes/no is 1 or 0), and only
     * the first {@code logic.maxVariables} are kept; one log line says how many were dropped.
     */
    public void loadVariables(List<SharedVariable> saved) {
        variables.clear();
        yesNoVariables.clear();
        int most = StorageServerConfig.LOGIC_MAX_VARIABLES.getAsInt();
        int dropped = 0;
        for (SharedVariable variable : saved) {
            String name = LogicCompiler.variableName(variable.name());
            if (name.isEmpty() || variables.containsKey(name) || variables.size() >= most) {
                dropped++;
                continue;
            }
            long value = variable.yesNo()
                    ? (variable.value() != 0 ? 1 : 0)
                    : Math.clamp(variable.value(), -SettingSpec.MAX_NUMBER, SettingSpec.MAX_NUMBER);
            variables.put(name, value);
            if (variable.yesNo()) {
                yesNoVariables.add(name);
            }
        }
        if (dropped > 0) {
            LOGGER.warn(
                    "Logic controller at {}: dropped {} of {} saved shared variables (blank, repeated or past "
                            + "logic.maxVariables {})",
                    BlockPos.of(device.nodePos()).toShortString(), dropped, saved.size(), most);
        }
        if (started) {
            offerAll(WatchKind.VARIABLE, false);
        }
    }

    /**
     * Runs the graph {@code rule} names (a row number or a name, {@link RuleCalls#target}): its "Run by another rule"
     * nodes fire on its next run, in this pass when it comes later in the list, else in the next. A graph off, paused
     * or past the limit, a rule naming no graph, a graph without such a node, and a call deeper than
     * {@code logic.maxRuleDepth} give a why line instead. Circles cannot happen: graphs on one are paused.
     */
    @Override
    public void runRule(String rule) {
        runRule(rule, null, "", 0, "");
    }

    /**
     * {@link #runRule(String)} with arguments (M7L2 row 19): a rule with parameters and a "Run by another rule" node
     * gets the call queued with the item and the amount for the parameters keyed {@code itemParam} and
     * {@code amountParam} ({@link LogicEvaluator#call}); its call branch runs once for it on its next run. Past
     * {@link LogicEvaluator#MAX_CALLS} waiting calls, a call is refused with a why line.
     */
    @Override
    public void runRule(String rule, @Nullable LogicKey item, String itemParam, long amount, String amountParam) {
        if (!collect(new LogicAction(running, LogicAction.Kind.RUN_RULE, "", Optional.ofNullable(item), "", amount,
                rule, 0))) {
            return;
        }
        int target = RuleCalls.target(rule, graphs.size(), this::name);
        if (target < 0) {
            why(LogicLang.WHY_NO_RULE, 0, 0);
            return;
        }
        Graph graph = graphs.get(target);
        LogicEvaluator<LogicKey> evaluator = graph.evaluator;
        if (evaluator == null || !runnable(target)) {
            why(LogicLang.WHY_RULE_OFF, 0, 0);
            return;
        }
        int depth = depthNow + 1;
        int most = StorageServerConfig.LOGIC_MAX_RULE_DEPTH.getAsInt();
        if (depth > most) {
            why(LogicLang.WHY_RULE_DEPTH, most, 0);
            return;
        }
        CompiledGraph<LogicKey> called = evaluator.graph();
        if (called.callable()) {
            int itemAt = itemParam.isEmpty() ? -1 : called.param(itemParam);
            int amountAt = amountParam.isEmpty() ? -1 : called.param(amountParam);
            if (!evaluator.call(itemAt, item, amountAt, amount)) {
                why(LogicLang.WHY_CALLS_FULL, LogicEvaluator.MAX_CALLS, 0);
                return;
            }
            graph.pendingDepth = Math.max(graph.pendingDepth, depth);
            wake();
            return;
        }
        boolean any = false;
        List<Watch<LogicKey>> watches = evaluator.graph().watches();
        for (int w = 0; w < watches.size(); w++) {
            if (watches.get(w).kind() == WatchKind.RULE_CALLED) {
                evaluator.signal(w);
                any = true;
            }
        }
        if (!any) {
            why(LogicLang.WHY_RULE_NO_ENTRY, 0, 0);
            return;
        }
        graph.pendingDepth = Math.max(graph.pendingDepth, depth);
        wake();
    }

    /**
     * The switches pulses are holding on, as block position and strength pairs, for saving; empty when none. Allocates.
     */
    public List<Long> pulsedSwitches() {
        List<Long> out = new ArrayList<>(pulsed.size() * 2);
        for (var entry : pulsed.long2IntEntrySet()) {
            out.add(entry.getLongKey());
            out.add((long) entry.getIntValue());
        }
        return out;
    }

    /** Takes the saved pairs of {@link #pulsedSwitches}; an odd last value is ignored. */
    public void loadPulsedSwitches(List<Long> saved) {
        pulsed.clear();
        for (int i = 0; i + 1 < saved.size(); i += 2) {
            pulsed.put(saved.get(i).longValue(), (int) Math.clamp(saved.get(i + 1), 0, 15));
        }
    }

    /** What the last move action moved; tests read it. */
    public long lastMoved() {
        return lastMoved;
    }

    /**
     * Posts {@code text} to the channels in {@code channels}: the network's log, the network's display panels set to
     * show logic notices, and a toast and a chat line for the online players of the controller owner's team
     * ({@link NoticeDelivery}).
     */
    @Override
    public void notify(String text, int channels) {
        if (!collect(new LogicAction(running, LogicAction.Kind.NOTIFY, "", Optional.empty(), "", 0, text, channels))) {
            return;
        }
        NetworkState network = bound;
        if (network != null && (channels & LogicContext.CHANNEL_LOG) != 0) {
            network.notices().post(new NetworkNotices.Notice(now, "", text, ""));
        }
        if (network != null && (channels & LogicContext.CHANNEL_DISPLAYS) != 0) {
            network.postDisplayNotice(new NetworkNotices.Notice(now, "", text, ""));
        }
        ServerLevel level = host.serverLevel();
        if (level != null) {
            NoticeDelivery.deliver(level, device, text, channels);
            LogicEvents.Listener events = LogicEvents.listener();
            if (events != null) {
                String rule = running >= 0 && running < graphs.size() ? graphs.get(running).slot.name() : "";
                events.notice(level, position(), rule, text, channels, TeamBudget.team(level, device));
            }
        }
    }

    /** The controller's position, for {@link LogicEvents}; the origin for a device that is not a block entity. */
    private BlockPos position() {
        return device instanceof BlockEntity entity ? entity.getBlockPos() : BlockPos.ZERO;
    }

    /**
     * Pauses {@code graph} after one of its script nodes ({@code node}) failed: the node shows {@code message}, the
     * graph keeps its memory and stays paused until it is saved again or the controller loads again.
     */
    private void pauseForScript(Graph graph, int node, String message) {
        String shown = message.length() > MAX_SCRIPT_MESSAGE
                ? message.substring(0, MAX_SCRIPT_MESSAGE) + "..."
                : message;
        graph.slot = saved(graph);
        graph.result = new CompileResult<>(graph.slot.graph(), Optional.empty(),
                List.of(new GraphError(node, "", LogicLang.SCRIPT_FAILED, shown)));
        graph.looped = false;
        graph.waiting = false;
        prepare(graph, graph.slot.memory());
        revision++;
        host.changed();
        reportPaused();
    }

    private void forgetOwnJob(UUID id) {
        for (int i = ownJobs.size() - 1; i >= 0; i--) {
            if (ownJobs.get(i).id().equals(id)) {
                ownJobs.remove(i);
            }
        }
    }

    private boolean ownJob(int graph, UUID id) {
        for (int i = 0; i < ownJobs.size(); i++) {
            OwnJob job = ownJobs.get(i);
            if (job.graph() == graph && job.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** The controller owner's stand-in player, for access checks; null outside a server level. */
    private @Nullable ServerPlayer actor() {
        ServerLevel level = host.serverLevel();
        return level == null ? null : WorldActors.get().player(level, device.ownerRecord());
    }

    /**
     * Whether the controller's owner may change {@code target}, as at the device's screen
     * ({@link NetworkAccess#canConfigure} with the owner's fake player: the network's security rules, else the device's
     * owner security). On another network (M7L2 row 13, owner decision 2026-10-09) the logic level there is enough, the
     * level a rule needs to name that network at all. An unowned controller (placed by a machine or a command) may
     * change only unowned devices. The answer is kept for {@code claims.cacheSeconds} while neither owner changes, like
     * {@code FacingAccess}; the first refusal goes to the network's log once, and again only after an answer that
     * allowed it.
     */
    private boolean mayConfigure(NetworkDevice target, long gameTime) {
        OwnerRecord owner = device.ownerRecord();
        OwnerRecord targetOwner = target.ownerRecord();
        AccessAnswer answer = access.get(target);
        if (answer != null && gameTime < answer.expires && answer.owner.equals(owner)
                && answer.target.equals(targetOwner)) {
            return answer.allowed;
        }
        boolean allowed;
        if (!owner.isOwned()) {
            allowed = !targetOwner.isOwned();
        } else {
            ServerPlayer actor = actor();
            boolean remote = target.deviceLink().network() != bound;
            allowed = actor != null && (remote
                    ? NetworkAccess.level(actor, target).atLeast(AccessLevel.LOGIC)
                    : NetworkAccess.canConfigure(actor, target));
        }
        if (answer == null) {
            answer = new AccessAnswer();
            access.put(target, answer);
        }
        answer.allowed = allowed;
        answer.owner = owner;
        answer.target = targetOwner;
        answer.expires = gameTime + StorageServerConfig.CLAIMS_CACHE_SECONDS.getAsInt() * 20L;
        if (allowed) {
            answer.logged = false;
        } else if (!answer.logged) {
            answer.logged = true;
            NetworkState network = bound;
            if (network != null) {
                network.notices()
                        .post(new NetworkNotices.Notice(gameTime, LogicLang.LOG_DENIED, target.label().text(), ""));
            }
        }
        return allowed;
    }

    /** The why line for an action on labelled devices: how many were refused, or that none could do it. */
    private void whyTargets(boolean any, int denied) {
        if (denied > 0) {
            why(LogicLang.WHY_DENIED, denied, 0);
        } else if (!any) {
            why(LogicLang.WHY_NO_TARGET, 0, 0);
        }
    }

    /**
     * Cancels the network's waiting and running jobs for {@code item}, or only those graph {@link #running} requested,
     * through {@link JobQueue#cancel}: a running job's CPU gives back what it holds. Like the jobs screen, a job not
     * requested by this graph is cancelled only when the controller's owner requested it or has full access to the
     * network.
     */
    @Override
    public void cancelCraft(LogicKey item, boolean ownOnly) {
        if (!collect(new LogicAction(running, LogicAction.Kind.CANCEL_CRAFT, "", Optional.of(item), "", ownOnly ? 1 : 0,
                "", 0))) {
            return;
        }
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        if (!(item instanceof LogicKey.Stack(StackKey key))) {
            why(LogicLang.WHY_TAG, 0, 0);
            return;
        }
        boolean owned = device.ownerRecord().isOwned();
        UUID owner = device.ownerRecord().owner();
        boolean checked = false;
        boolean fullAccess = false;
        int found = 0;
        int denied = 0;
        for (CraftingJob job : JobQueue.jobsOn(network)) {
            if (!job.key().equals(key)) {
                continue;
            }
            boolean own = ownJob(running, job.id());
            if (ownOnly && !own) {
                continue;
            }
            found++;
            boolean requester = owned && job.requester().isPresent() && job.requester().get().equals(owner);
            if (!own && !requester) {
                if (!checked) {
                    checked = true;
                    ServerPlayer actor = actor();
                    fullAccess = actor != null && network.accessLevel(actor) == AccessLevel.FULL;
                }
                if (!fullAccess) {
                    denied++;
                    continue;
                }
            }
            JobQueue.cancel(network.level(), job.id());
        }
        if (found == 0) {
            why(LogicLang.WHY_NO_JOB, 0, 0);
        } else if (denied > 0) {
            why(LogicLang.WHY_CANCEL_DENIED, denied, 0);
        }
    }

    /**
     * Shows {@code text} with the inputs filled in on the displays labelled {@code label} that the controller's owner
     * may change, as their text source ({@link DisplayDevice#showText}). A display already showing it is left alone (so
     * a wall of labelled panels is written once); one written less than {@code logic.displayWriteTicks} ago gets the
     * text when that time is up, through one scheduled wake.
     */
    @Override
    public void writeDisplay(String label, String text, long a, long b) {
        String markup = LogicContext.fillValues(text, a, b);
        if (!collect(
                new LogicAction(running, LogicAction.Kind.WRITE_DISPLAY, label, Optional.empty(), "", 0, markup, 0))) {
            return;
        }
        if (DisplayText.problem(markup).isPresent()) {
            why(LogicLang.WHY_DISPLAY_TEXT, 0, 0);
            return;
        }
        boolean any = false;
        int denied = 0;
        long waitLeft = 0;
        String labelKey = DeviceLabel.key(label);
        for (NetworkDevice target : labelled(label)) {
            if (!(target instanceof DisplayDevice display)) {
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            any = true;
            waitLeft = Math.max(waitLeft, write(display, markup, labelKey, now));
        }
        if (denied > 0) {
            why(LogicLang.WHY_DENIED, denied, 0);
        } else if (!any) {
            why(LogicLang.WHY_NO_TARGET, 0, 0);
        } else if (waitLeft > 0) {
            why(LogicLang.WHY_DISPLAY_WAIT, waitLeft, 0);
        }
    }

    /**
     * Writes {@code markup} to {@code display} now, or keeps it for the display's next allowed write; returns the ticks
     * until then, 0 when written or already shown.
     */
    private long write(DisplayDevice display, String markup, String labelKey, long gameTime) {
        DisplayWrite entry = displayWrites.get(display);
        if (shows(display, markup)) {
            if (entry != null && entry.pending != null) {
                entry.pending = null;
                pendingDisplays--;
            }
            return 0;
        }
        if (entry == null) {
            entry = new DisplayWrite(labelKey);
            displayWrites.put(display, entry);
        }
        long interval = StorageServerConfig.LOGIC_DISPLAY_WRITE_TICKS.getAsInt();
        if (entry.last == Long.MIN_VALUE || gameTime - entry.last >= interval) {
            display.showText(markup);
            entry.last = gameTime;
            if (entry.pending != null) {
                entry.pending = null;
                pendingDisplays--;
            }
            return 0;
        }
        if (entry.pending == null) {
            pendingDisplays++;
        }
        entry.pending = markup;
        entry.labelKey = labelKey;
        entry.due = entry.last + interval;
        displayDue = Math.min(displayDue, entry.due);
        return entry.due - gameTime;
    }

    /** Whether {@code display} shows {@code markup} as its text already. */
    private static boolean shows(DisplayDevice display, String markup) {
        DisplaySettings settings = display.settings();
        return settings.equals(settings.withSource(DisplaySourceKind.TEXT).withText(markup));
    }

    /**
     * Writes the waiting texts whose time has come and works out when the next one is due. Runs only when a waiting
     * text is due; walks the displays logic has written.
     */
    private void flushDisplays(long gameTime) {
        long next = Long.MAX_VALUE;
        int left = 0;
        for (var each : displayWrites.entrySet()) {
            DisplayWrite entry = each.getValue();
            String pending = entry.pending;
            if (pending == null) {
                continue;
            }
            if (entry.due > gameTime) {
                left++;
                next = Math.min(next, entry.due);
                continue;
            }
            entry.pending = null;
            DisplayDevice display = each.getKey();
            // Checked again: the display may have left the network, lost the label or changed owner meanwhile.
            if (display instanceof NetworkDevice target && target.deviceLink().network() == bound
                    && target.label().key().equals(entry.labelKey) && mayConfigure(target, gameTime)
                    && !shows(display, pending)) {
                display.showText(pending);
                entry.last = gameTime;
            }
        }
        pendingDisplays = left;
        displayDue = next;
    }

    /**
     * Changes the ports labelled {@code label} that the controller's owner may change: filter slot {@code slot} (1 to
     * 9) to {@code item} or empty, and the mode, through the same calls as the port's screen, so it is saved with the
     * port and its item. Only ports have a filter and a mode to set.
     */
    @Override
    public void setFilter(String label, @Nullable LogicKey item, int slot, int portMode) {
        if (!collect(new LogicAction(running, LogicAction.Kind.SET_FILTER, label, Optional.ofNullable(item), "",
                slot * 10L + portMode, "", 0))) {
            return;
        }
        ItemStack stack = ItemStack.EMPTY;
        if (slot > 0 && item != null) {
            stack = filterStack(item);
            if (stack.isEmpty()) {
                why(LogicLang.WHY_TAG, 0, 0);
                return;
            }
        }
        boolean any = false;
        int denied = 0;
        for (NetworkDevice target : labelled(label)) {
            if (!(target instanceof PortDevice port)) {
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            any = true;
            if (slot >= 1 && slot <= PortFilter.SIZE && !ItemStack.matches(port.filter().getItem(slot - 1), stack)) {
                port.filter().setItem(slot - 1, stack.copy());
            }
            if (portMode == LogicContext.PORT_IMPORT) {
                port.setMode(PortMode.IMPORT);
            } else if (portMode == LogicContext.PORT_EXPORT) {
                port.setMode(PortMode.EXPORT);
            }
        }
        if (denied > 0) {
            why(LogicLang.WHY_DENIED, denied, 0);
        } else if (!any) {
            why(LogicLang.WHY_NO_TARGET, 0, 0);
        }
    }

    /**
     * Switches the rules for {@code item} (every rule when null) of the stock keepers labelled {@code label} that the
     * controller's owner may change, through the keeper's own rule edit, so the keeper's screen shows it and it is
     * saved and dropped with the keeper.
     */
    @Override
    public void switchStockRule(String label, @Nullable LogicKey item, boolean on) {
        if (!collect(new LogicAction(running, LogicAction.Kind.STOCK_SWITCH, label, Optional.ofNullable(item), "",
                on ? 1 : 0, "", 0))) {
            return;
        }
        editStock(label, item, rule -> rule.withSwitchedOff(!on));
    }

    /**
     * Sets one amount of the rules for {@code item} of the stock keepers labelled {@code label} that the controller's
     * owner may change, clamped as the keeper's screen clamps it. A keeper without a rule for the item gets none.
     */
    @Override
    public void setStockAmount(String label, LogicKey item, int field, long amount) {
        if (!collect(new LogicAction(running, LogicAction.Kind.STOCK_SET, label, Optional.of(item), "", amount,
                StockLogic.fieldName(field), 0))) {
            return;
        }
        editStock(label, item, rule -> StockLogic.withAmount(rule, field, amount));
    }

    private void editStock(String label, @Nullable LogicKey item, java.util.function.UnaryOperator<StockRule> edit) {
        boolean any = false;
        int denied = 0;
        int rules = 0;
        for (NetworkDevice target : labelled(label)) {
            StockKeeperWorker keeper = StockLogic.keeper(target);
            if (keeper == null) {
                continue;
            }
            if (!mayConfigure(target, now)) {
                denied++;
                continue;
            }
            any = true;
            rules += StockLogic.edit(keeper, item, edit);
        }
        if (denied > 0) {
            why(LogicLang.WHY_DENIED, denied, 0);
        } else if (!any) {
            why(LogicLang.WHY_NO_TARGET, 0, 0);
        } else if (rules == 0) {
            why(LogicLang.WHY_NO_STOCK_RULE, 0, 0);
        }
    }

    /**
     * What a filter slot holds for {@code item}: the item itself, or a filled bucket for a fluid (the port's filter
     * reads a bucket as its fluid); empty for a tag or a fluid without a bucket.
     */
    private static ItemStack filterStack(LogicKey item) {
        if (!(item instanceof LogicKey.Stack(StackKey key))) {
            return ItemStack.EMPTY;
        }
        if (key instanceof ItemKey stack) {
            return stack.toStack(1);
        }
        if (key instanceof FluidKey fluid) {
            // A fluid without a bucket gives air, an empty stack.
            return new ItemStack(fluid.fluid().getBucket());
        }
        return ItemStack.EMPTY;
    }
}
