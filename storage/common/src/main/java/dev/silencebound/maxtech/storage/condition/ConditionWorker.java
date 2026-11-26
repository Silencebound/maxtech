package dev.silencebound.maxtech.storage.condition;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorDispatch;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorOrders;
import dev.silencebound.maxtech.storage.crafting.job.CraftingDemand;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingRuns;
import dev.silencebound.maxtech.storage.crafting.job.JobDriver;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.pattern.GeneratorSource;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.network.EnergyWatcher;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.RedstoneChannels;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.world.WorldActors;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * What a condition switch does, shared by the block and the part: it watches one value of its network and sets a
 * redstone output from it. Nothing polls. The switch subscribes to exactly the events its condition depends on (the
 * watched key's committed totals, every committed total for a tag, the network's stored energy from its tick, charged
 * operations for link load, completed research) and asks for an evaluation only when the watched value crosses the
 * threshold. Evaluations run as a task on the network's scheduler, at most once per {@code condition.minIntervalTicks},
 * so a value that swings round the threshold cannot make the output flap. Load falls with time and no event says so:
 * while the switch sees a link over its threshold it checks again after each interval, and stops once it is not.
 *
 * <p>
 * In {@link ConditionType#LOGIC} mode (M7L) the switch watches nothing: a logic graph's redstone-out action addressed
 * by its label sets the strength ({@link #setLogicStrength}).
 *
 * <p>
 * Bundled redstone (M7L2): in {@link ConditionType#CHANNEL_IN} mode the redstone coming into the switch is its share of
 * one of the network's {@link RedstoneChannels}, written on the neighbour update that changed it; in
 * {@link ConditionType#CHANNEL_OUT} mode the switch listens to that channel and gives its value, evaluated like any
 * other condition (on the scheduler, at most once per interval).
 *
 * <p>
 * Crafting (B09 item 14): in {@link ConditionType#WHILE_CRAFTING} mode the switch is on while a running job of its
 * network makes the item in its slot ({@link CraftingDemand#making}); job changes ({@link #jobChanged}) and, while it
 * is on, the item's total changing ask for an evaluation. In {@link ConditionType#CRAFT_REQUEST} mode it is a
 * {@link GeneratorSource} for that item ({@link #craftSource()}), so the item counts as craftable and the planner makes
 * it with a generator step; the job's order keeps the switch on. It cannot pull the item itself, so it counts what
 * arrives: the item's total in the network above what was there when the order came in, less what jobs counted already
 * and have still to take out ({@link CraftingDemand#counted}). Its evaluation reports that much to
 * {@link CraftingDeliveries}, at most what its orders still owe, and lowers each order to what its job still waits for
 * (units an interface reported reach the job directly). With nothing owed, or the job ended or cancelled
 * ({@link GeneratorDispatch#cancel}), the signal goes off.
 *
 * <p>
 * The switch only gives a signal while its owner's team has researched {@link ResearchGates#CONDITION_SWITCH}. Without
 * a network its value is 0 (research conditions still read the owner's research). Server thread only.
 */
public final class ConditionWorker implements StorageTask, KeyListener, EnergyWatcher, ContentsWatcher {
    private static final String SETTINGS = "condition";
    private static final String OUTPUT = "output";
    private static final String STRENGTH = "strength";
    private static final String LOGIC_STRENGTH = "logic_strength";
    private static final String CHANNEL_INPUT = "channel_input";
    private static final String CRAFT_ORDERS = "craft_orders";
    private static final String CRAFT_CARRIED = "craft_carried";
    /** Every started switch, for research completions; removed ones are dropped when the list is next walked. */
    private static final ObjectArrayList<ConditionWorker> STARTED = new ObjectArrayList<>();
    private static final byte UNSEEN = 0;
    private static final byte MET = 1;
    private static final byte UNMET = 2;
    /** Removed switches are swept from {@link #STARTED} once per this many starts, so chunk churn cannot grow it. */
    private static final int SWEEP_EVERY = 64;
    private static int startsSinceSweep;

    /** What the worker needs from the block or part that holds it. */
    public interface Host {
        /** The level the switch is in; null before it is placed. */
        @Nullable
        ServerLevel serverLevel();

        /** The redstone output changed: update the look and the neighbours, and save. */
        void outputChanged();

        /** The settings changed: save and sync. */
        void settingsChanged();

        /**
         * The redstone strength (0 to 15) coming into the switch now, for {@link ConditionType#CHANNEL_IN}: from every
         * side for the block, from the faced block for the part; -1 when a chunk the reading needs is not loaded.
         * Server thread, in a loaded level; never loads a chunk.
         */
        int redstoneIn();
    }

    private final NetworkDevice device;
    private final Host host;
    private final Runnable loadWatcher = this::request;
    private final RedstoneChannels.Listener channelWatcher = this::channelChanged;
    private final RedstoneChannels.ShareFilter shareFilter = this::counts;
    /** Per writer owner, whether its channel shares count for this switch ({@link #counts}). */
    private final Object2ObjectOpenHashMap<UUID, ShareAnswer> shareAnswers = new Object2ObjectOpenHashMap<>();
    /** Writers whose refusal was logged since they were last allowed. */
    private final ReferenceOpenHashSet<Object> deniedLogged = new ReferenceOpenHashSet<>();
    /**
     * The redstone into the switch as last read in {@link ConditionType#CHANNEL_IN} mode, 0 to 15; saved, so the
     * switch's share of its channel is back as soon as it joins a network after a load.
     */
    private int input;
    private ConditionSettings settings = ConditionSettings.DEFAULT;
    private boolean output;
    /** The strength a logic graph last set (M7L), 0 to 15; used only in {@link ConditionType#LOGIC} mode. */
    private int logicStrength;
    /** The redstone strength given now: 15 or 0, or the logic strength in logic mode. */
    private int strength;
    private boolean pending;
    private boolean started;
    private long nextEvaluation = Long.MIN_VALUE;
    private int evaluations;
    /** The network the watchers are registered with. */
    private @Nullable NetworkState bound;
    /**
     * The side of the threshold the watched value was last seen on: {@link #UNSEEN}, {@link #MET} or {@link #UNMET}.
     */
    private byte side = UNSEEN;
    private long tagTotal;
    /** Key id to 1 (in the tag) or 2 (not), so each id is matched against the tag once. */
    private final Int2ByteOpenHashMap tagMatches = new Int2ByteOpenHashMap();
    /** Totals of the ids in the tag, as last reported; their sum is {@link #tagTotal}. */
    private final Int2LongOpenHashMap tagTotals = new Int2LongOpenHashMap();
    /** Request mode: what each job still has ordered from the switch. */
    private final GeneratorOrders orders = new GeneratorOrders();
    private final CraftSource craftSource = new CraftSource();
    private final ToLongFunction<UUID> jobWaiting = this::jobWaiting;
    /**
     * Request mode: the item's total in the network that is not an arrival for the orders, lowered whenever the total
     * falls below it; what is above it (less what jobs counted already) arrived.
     */
    private long baseline;
    /**
     * Request mode: units that had arrived but were not settled yet when the switch left its network or was saved; the
     * next bind counts them as arrived again, up to what the new network holds.
     */
    private long carried;
    /** The level {@link GeneratorDispatch} knows the switch in: while it holds orders; null otherwise. */
    private @Nullable ServerLevel dispatchLevel;

    public ConditionWorker(NetworkDevice device, Host host) {
        this.device = device;
        this.host = host;
    }

    public ConditionSettings settings() {
        return settings;
    }

    /** Whether the switch gives a signal now. */
    public boolean output() {
        return output;
    }

    /** The redstone strength given now, 0 to 15: 15 while on, or what logic set in logic mode. */
    public int strength() {
        return strength;
    }

    /** The strength logic last set (M7L), 0 to 15, whatever the mode. */
    public int logicStrength() {
        return logicStrength;
    }

    /** The value of the network channel the switch reads or writes, 0 to 15; 0 without a network. */
    public int channelValue() {
        NetworkState network = bound;
        if (network == null) {
            return 0;
        }
        return settings.type() == ConditionType.CHANNEL_OUT
                ? network.channels().value(settings.channel(), shareFilter)
                : network.channels().value(settings.channel());
    }

    /**
     * Whether a channel share by {@code owner} counts for this switch in {@link ConditionType#CHANNEL_OUT} mode: the
     * share's owner may configure the switch ({@link NetworkAccess#canConfigure} with the owner's fake player), and an
     * unowned writer counts only for an unowned switch. Answers are kept per writer owner for
     * {@code claims.cacheSeconds} and dropped when the switch's owner changes; the first refusal per writer goes to the
     * network's log once, and again only after it was allowed in between. Allocates only on a writer owner's first
     * look.
     */
    private boolean counts(Object writer, OwnerRecord owner) {
        OwnerRecord mine = device.ownerRecord();
        if (!owner.isOwned()) {
            return !mine.isOwned();
        }
        if (owner.owner().equals(mine.owner())) {
            return true;
        }
        ServerLevel level = host.serverLevel();
        if (level == null) {
            return false;
        }
        long now = level.getGameTime();
        ShareAnswer answer = shareAnswers.get(owner.owner());
        if (answer == null || now >= answer.expires) {
            ServerPlayer actor = WorldActors.get().player(level, owner);
            if (answer == null) {
                answer = new ShareAnswer();
                shareAnswers.put(owner.owner(), answer);
            }
            answer.allowed = actor != null && NetworkAccess.canConfigure(actor, device);
            answer.expires = now + StorageServerConfig.CLAIMS_CACHE_SECONDS.getAsInt() * 20L;
        }
        if (answer.allowed) {
            deniedLogged.remove(writer);
        } else if (deniedLogged.add(writer)) {
            NetworkState network = bound;
            if (network != null) {
                network.notices()
                        .post(new NetworkNotices.Notice(now, ConditionTexts.LOG_CHANNEL_DENIED, owner.ownerName(), ""));
            }
        }
        return answer.allowed;
    }

    /** A kept answer of {@link #counts}. */
    private static final class ShareAnswer {
        boolean allowed;
        long expires;
    }

    /** The redstone into the switch as last read in {@link ConditionType#CHANNEL_IN} mode, 0 to 15. */
    public int input() {
        return input;
    }

    /**
     * A neighbour changed (M7L2): in {@link ConditionType#CHANNEL_IN} mode the switch reads the redstone coming in and,
     * when it changed, writes its share of the channel at once. The channel tells its readers only when its value
     * changed, and they evaluate on the network's scheduler, so a loop of switches cannot recurse.
     */
    public void inputChanged() {
        if (!started || settings.type() != ConditionType.CHANNEL_IN) {
            return;
        }
        ServerLevel level = host.serverLevel();
        if (level == null) {
            return;
        }
        readInput(level);
    }

    private void readInput(ServerLevel level) {
        int read = host.redstoneIn();
        if (read < 0) {
            // A neighbouring chunk is not loaded: keep the last input rather than load it.
            writeShare(level);
            return;
        }
        int now = Math.min(read, 15);
        if (now != input) {
            input = now;
            host.settingsChanged();
        }
        writeShare(level);
    }

    /** Writes this switch's share of its channel: the input (inverted: 15 minus it), 0 without the research. */
    private void writeShare(@Nullable ServerLevel level) {
        NetworkState network = bound;
        if (network == null) {
            return;
        }
        boolean allowed = level == null
                || ResearchUnlocks.isUnlocked(level, device.ownerRecord(), ResearchGates.CONDITION_SWITCH);
        int share = allowed ? (settings.inverted() ? 15 - input : input) : 0;
        network.channels().write(this, device.ownerRecord(), settings.channel(), share);
    }

    private void channelChanged(int channel, int value) {
        if (channel == settings.channel()) {
            request();
        }
    }

    /**
     * A logic graph's redstone-out action (M7L): sets the strength the switch gives in {@link ConditionType#LOGIC}
     * mode, clamped to 0 to 15, at once (no interval: the graph already coalesces its changes). Kept in other modes
     * until the switch is put in logic mode. Saved with the switch.
     */
    public void setLogicStrength(int strength) {
        int next = Math.clamp(strength, 0, 15);
        if (next == logicStrength) {
            return;
        }
        logicStrength = next;
        host.settingsChanged();
        if (settings.type() == ConditionType.LOGIC) {
            evaluate();
        }
    }

    /** Evaluations run since the switch was made; tests use it to see that nothing polls. */
    public int evaluations() {
        return evaluations;
    }

    /** Starts watching: schedules the evaluation task and evaluates once. Call when the device is in a loaded level. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        if (++startsSinceSweep >= SWEEP_EVERY) {
            startsSinceSweep = 0;
            sweep();
        }
        STARTED.add(this);
        ServerLevel level = host.serverLevel();
        if (level != null && !orders.isEmpty()) {
            // Jobs that ended while the switch was unloaded never withdrew their orders.
            if (orders.dropEnded(level)) {
                host.settingsChanged();
            }
            if (!orders.isEmpty()) {
                joinDispatch(level);
            }
        }
        listenKey(settings);
        device.deviceLink().schedule(this);
        request();
    }

    /** Replaces the settings; the switch evaluates again at its next chance. */
    public void setSettings(ConditionSettings settings) {
        if (settings.equals(this.settings)) {
            return;
        }
        NetworkState network = bound;
        unbind();
        unlistenKey(this.settings);
        boolean craftChanged = craftOutput(this.settings) != craftOutput(settings)
                || (craftOutput(settings) && !settings.item().equals(this.settings.item()));
        this.settings = settings;
        if (craftChanged) {
            // The orders were for the old item; the network re-reads what the switch offers.
            orders.clear();
            leaveDispatch();
            device.deviceLink().patternsChanged();
        }
        if (started) {
            listenKey(settings);
        }
        bind(network);
        side = UNSEEN;
        host.settingsChanged();
        request();
    }

    /** The owner changed: its research decides whether the switch may give a signal, so look again. */
    public void ownerChanged() {
        shareAnswers.clear();
        deniedLogged.clear();
        if (started) {
            request();
        }
    }

    /** The device joined {@code network}, or left its network with null: the watchers move along. */
    public void networkChanged(@Nullable StorageNetwork network) {
        unbind();
        bind(network instanceof NetworkState state ? state : null);
        side = UNSEEN;
        if (bound == null) {
            // No scheduler without a network: the value is 0 now, and nothing changes it until it joins one.
            evaluate();
        } else {
            pending = false;
            request();
        }
    }

    private void bind(@Nullable NetworkState network) {
        bound = network;
        if (network == null) {
            return;
        }
        switch (settings.type()) {
            case TAG_COUNT -> {
                if (settings.tag().isPresent()) {
                    network.watch(this);
                    recountTag(network.contents());
                }
            }
            case POWER -> network.watchEnergy(this);
            case OVERLOAD -> network.watchLoad(loadWatcher);
            case CHANNEL_OUT -> network.channels().listen(channelWatcher);
            // The saved input goes back at once, so readers see the channel as it was before a load or a split.
            case CHANNEL_IN -> writeShare(host.serverLevel());
            case CRAFT_REQUEST -> rebase(network);
            case ITEM_COUNT, RESEARCH, LOGIC, WHILE_CRAFTING -> {
            }
        }
    }

    /**
     * Request mode, on joining {@code network}: the baseline becomes what the network holds that its jobs have not
     * counted already, less the units {@link #carried} over from before (a load, a rebind or a split), as far as the
     * network holds them. Other stock of the new network never counts as arrived.
     */
    private void rebase(NetworkState network) {
        Optional<ItemKey> key = settings.item();
        long fresh = key.isPresent() ? unaccounted(network, key.get()) : 0;
        long carry = Math.min(Math.min(carried, fresh), orders.remaining());
        baseline = fresh - Math.max(0, carry);
        carried = 0;
    }

    /**
     * What {@code network} holds of {@code key} that its jobs have not counted already: counted units still in storage
     * wait for their job to take them. Never below 0, also when a job counted units storage does not hold.
     */
    private static long unaccounted(NetworkState network, ItemKey key) {
        long amount = network.contents().amount(key);
        return amount - Math.min(amount, CraftingDemand.counted(network, key));
    }

    /** Request mode: units that arrived on {@code network} and are not settled yet, at most what the orders owe. */
    private long unsettled(@Nullable NetworkState network) {
        Optional<ItemKey> key = settings.item();
        if (network == null || key.isEmpty() || orders.isEmpty()) {
            return 0;
        }
        return Math.clamp(unaccounted(network, key.get()) - baseline, 0, orders.remaining());
    }

    /** Request mode: units carried over to the next bind (a save or a network change); for tests and diagnostics. */
    public long carried() {
        return carried;
    }

    private void unbind() {
        NetworkState network = bound;
        if (network != null && settings.type() == ConditionType.CRAFT_REQUEST) {
            carried = Math.max(carried, unsettled(network));
        }
        if (network != null) {
            network.unwatch(this);
            network.unwatchEnergy(this);
            network.unwatchLoad(loadWatcher);
            network.channels().unlisten(channelWatcher);
            network.channels().remove(this);
        }
        bound = null;
        tagMatches.clear();
        tagTotals.clear();
        tagTotal = 0;
    }

    private void listenKey(ConditionSettings watched) {
        if ((watched.type() == ConditionType.ITEM_COUNT || watched.type().crafting()) && watched.item().isPresent()) {
            device.deviceLink().listen(watched.item().get(), this);
        }
    }

    private void unlistenKey(ConditionSettings watched) {
        if (watched.item().isPresent()) {
            device.deviceLink().unlisten(watched.item().get(), this);
        }
    }

    private void recountTag(StorageContents contents) {
        for (Int2LongMap.Entry entry : contents.snapshot().totals().int2LongEntrySet()) {
            changed(entry.getIntKey(), entry.getLongValue());
        }
    }

    /** Asks for an evaluation on the network's next pass, once; repeated events before it runs cost nothing. */
    private void request() {
        if (!pending) {
            pending = true;
            device.deviceLink().wake(this);
        }
    }

    /** Asks for an evaluation when the watched value is now on the other side of the threshold. */
    private void sideSeen(long value) {
        byte now = settings.compare(value) ? MET : UNMET;
        if (side != now) {
            side = now;
            request();
        }
    }

    @Override
    public void keyChanged(StackKey key, long total) {
        switch (settings.type()) {
            case ITEM_COUNT -> sideSeen(total);
            // Only a step finishing can turn it off here; a job starting comes through jobChanged.
            case WHILE_CRAFTING -> {
                if (side == MET) {
                    request();
                }
            }
            case CRAFT_REQUEST -> {
                if (total < baseline) {
                    baseline = total;
                } else if (total > baseline && !orders.isEmpty()) {
                    request();
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void energyChanged(long stored, long capacity) {
        sideSeen(percent(stored, capacity));
    }

    @Override
    public void changed(int id, long total) {
        TagKey<Item> tag = settings.tag().orElse(null);
        NetworkState network = bound;
        if (tag == null || network == null) {
            return;
        }
        byte match = tagMatches.get(id);
        if (match == 0) {
            StackKey key = network.contents().key(id);
            match = key instanceof ItemKey item && item.item().builtInRegistryHolder().is(tag) ? (byte) 1 : (byte) 2;
            tagMatches.put(id, match);
        }
        if (match == 1) {
            tagTotal += total - tagTotals.get(id);
            if (total == 0) {
                tagTotals.remove(id);
            } else {
                tagTotals.put(id, total);
            }
            sideSeen(tagTotal);
        }
    }

    @Override
    public void released(int id) {
        // The id may name another key from now on; its total was 0 already.
        tagMatches.remove(id);
        tagTotals.remove(id);
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        if (gameTime < nextEvaluation) {
            return (int) Math.max(2, nextEvaluation - gameTime);
        }
        int interval = StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt();
        nextEvaluation = gameTime + interval;
        pending = false;
        if (settings.type() == ConditionType.CRAFT_REQUEST) {
            settleArrivals();
        }
        boolean met = evaluate();
        if (settings.type() == ConditionType.OVERLOAD && met == settings.above()) {
            // Load falls with time, which no event reports: look again while the link is over the threshold.
            pending = true;
            return Math.max(2, interval);
        }
        return WAIT;
    }

    /** Reads the watched value now and sets the output; returns whether the condition holds. */
    private boolean evaluate() {
        evaluations++;
        ServerLevel level = host.serverLevel();
        boolean met = level != null && met(level);
        boolean allowed = level == null
                || ResearchUnlocks.isUnlocked(level, device.ownerRecord(), ResearchGates.CONDITION_SWITCH);
        int desired;
        if (settings.type() == ConditionType.LOGIC) {
            desired = allowed ? (settings.inverted() ? 15 - logicStrength : logicStrength) : 0;
        } else if (settings.type() == ConditionType.CHANNEL_OUT) {
            int value = channelValue();
            desired = allowed ? (settings.inverted() ? 15 - value : value) : 0;
        } else if (settings.type() == ConditionType.CHANNEL_IN) {
            // An input gives no signal; it reads again here in case a neighbour changed while it was not listening.
            if (level != null) {
                readInput(level);
            }
            desired = 0;
        } else {
            desired = allowed && met != settings.inverted() ? 15 : 0;
        }
        if (desired != strength) {
            strength = desired;
            output = desired > 0;
            host.outputChanged();
        }
        return met;
    }

    private boolean met(ServerLevel level) {
        NetworkState network = bound;
        if (settings.type() == ConditionType.LOGIC) {
            return logicStrength > 0;
        }
        if (settings.type().channel()) {
            return channelValue() > 0;
        }
        if (settings.type() == ConditionType.CRAFT_REQUEST) {
            return network != null && !orders.isEmpty();
        }
        if (settings.type() == ConditionType.WHILE_CRAFTING) {
            boolean making = network != null && settings.item().isPresent()
                    && CraftingDemand.making(network, settings.item().get());
            side = making ? MET : UNMET;
            return making;
        }
        if (settings.type() == ConditionType.RESEARCH) {
            Optional<Identifier> node = settings.node();
            return node.isPresent() && ResearchUnlocks.isUnlocked(level, device.ownerRecord(), node.get());
        }
        long value = 0;
        if (network != null) {
            value = switch (settings.type()) {
                case ITEM_COUNT -> settings.item().map(key -> network.contents().amount(key)).orElse(0L);
                case TAG_COUNT -> tagTotal;
                case POWER -> percent(network.storedEnergy(), network.energyCapacity());
                case OVERLOAD -> network.routeLoad(device.nodePos());
                case RESEARCH, LOGIC, CHANNEL_OUT, CHANNEL_IN, WHILE_CRAFTING, CRAFT_REQUEST -> 0;
            };
        }
        return settings.compare(value);
    }

    private static long percent(long stored, long capacity) {
        // Doubles, since a creative network's stored FE times 100 would overflow a long.
        return capacity <= 0 ? 0 : (long) (100.0 * stored / capacity);
    }

    /**
     * {@code team} completed {@code node}: the switches whose output can depend on it evaluate again at their next
     * chance. Those are the switches owned by a member of {@code team}, when the node grants the switch's own unlock
     * or, in {@link ConditionType#RESEARCH} mode, the watched node. Other switches stay asleep, so research elsewhere
     * on the server costs them nothing.
     */
    static void researchCompleted(MinecraftServer server, ResearchTeam team, ResearchTree.Entry node) {
        sweep();
        for (int i = 0; i < STARTED.size(); i++) {
            ConditionWorker worker = STARTED.get(i);
            if (worker.dependsOn(server, team, node)) {
                worker.request();
            }
        }
    }

    private boolean dependsOn(MinecraftServer server, ResearchTeam team, ResearchTree.Entry node) {
        boolean watched = settings.type() == ConditionType.RESEARCH && settings.node().isPresent()
                && node.grants(settings.node().get());
        if (!watched && !node.grants(ResearchGates.CONDITION_SWITCH)) {
            return false;
        }
        // Unowned switches read every unlock as granted (ResearchUnlocks), so no completion changes them.
        OwnerRecord owner = device.ownerRecord();
        return owner.isOwned() && Research.inTeam(server, owner, team);
    }

    private static void sweep() {
        for (int i = STARTED.size() - 1; i >= 0; i--) {
            if (STARTED.get(i).device.isRemoved()) {
                STARTED.remove(i);
            }
        }
    }

    /**
     * A crafting job in {@code level} was submitted, started, resumed or ended: the switches in
     * {@link ConditionType#WHILE_CRAFTING} mode whose item the job makes look again, if the job belongs to their
     * network (or one linked to it). A job that ended has no network any more; then only the switches that are on look
     * again. Others stay asleep.
     */
    static void jobChanged(ServerLevel level, CraftingJob job) {
        JobRun run = CraftingRuns.get(level).get(job.id());
        NetworkState origin = run == null ? null : JobDriver.network(level, run.origin());
        for (int i = 0; i < STARTED.size(); i++) {
            ConditionWorker worker = STARTED.get(i);
            NetworkState network = worker.bound;
            if (worker.settings.type() != ConditionType.WHILE_CRAFTING || network == null
                    || !network.level().equals(level) || worker.settings.item().isEmpty()
                    || !makes(job, worker.settings.item().get())) {
                continue;
            }
            if (origin == null ? worker.side == MET : origin.equals(network) || network.linkedTo(origin)) {
                worker.request();
            }
        }
    }

    /** Whether one of {@code job}'s steps crafts or generates {@code key}. */
    private static boolean makes(CraftingJob job, StackKey key) {
        List<CraftingJob.Step> steps = job.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (PlanTree.crafts(steps.get(i).pattern()) && steps.get(i).key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code settings} offer an item to the planner: request mode with an item. */
    private static boolean craftOutput(ConditionSettings settings) {
        return settings.type() == ConditionType.CRAFT_REQUEST && settings.item().isPresent();
    }

    /** What the switch offers its network's planner: the item in request mode, nothing in other modes. */
    public PatternProvider craftSource() {
        return craftSource;
    }

    /** Request mode: units of the item still owed on open orders; 0 in other modes. */
    public long requested() {
        return orders.remaining();
    }

    /**
     * Request mode, on the scheduler: lowers each order to what its job still waits for, then reports what arrived
     * since the last look to the running jobs and counts it against the orders, oldest first. What is above the orders
     * becomes plain stock.
     */
    private void settleArrivals() {
        NetworkState network = bound;
        ServerLevel level = host.serverLevel();
        ItemKey key = settings.item().orElse(null);
        if (orders.isEmpty() || network == null || level == null || key == null) {
            return;
        }
        boolean changed = orders.capTo(jobWaiting);
        long free = unaccounted(network, key) - baseline;
        if (free > 0 && !orders.isEmpty()) {
            long report = Math.min(free, orders.remaining());
            orders.fill(report);
            changed = true;
            CraftingDeliveries.delivered(network, key, report);
        }
        // What jobs took out is gone from the total; what they could not take yet is counted, not new.
        baseline = unaccounted(network, key);
        if (orders.isEmpty()) {
            leaveDispatch();
        }
        if (changed) {
            host.settingsChanged();
        }
    }

    /** Units of the item job {@code job} still waits for from generator sources; 0 without a level or item. */
    private long jobWaiting(UUID job) {
        ServerLevel level = host.serverLevel();
        Optional<ItemKey> key = settings.item();
        return level == null || key.isEmpty() ? 0 : CraftingDemand.generatorWaiting(level, job, key.get());
    }

    private void joinDispatch(ServerLevel level) {
        if (dispatchLevel == null) {
            dispatchLevel = level;
            GeneratorDispatch.register(level, craftSource);
        }
    }

    /**
     * The switch is gone (broken, its part taken off, or its chunk unloaded): job ends no longer need to reach it. Its
     * orders stay saved with it; a job still running orders the units again from another source.
     */
    public void stop() {
        leaveDispatch();
    }

    private void leaveDispatch() {
        ServerLevel level = dispatchLevel;
        if (level != null) {
            GeneratorDispatch.unregister(level, craftSource);
            dispatchLevel = null;
        }
    }

    /**
     * The switch in request mode as the planner and executor see it: a generator source for the item in its slot, with
     * no patterns. Offers nothing in other modes.
     */
    private final class CraftSource implements PatternProvider, GeneratorSource {
        @Override
        public Optional<StackKey> output() {
            Optional<ItemKey> item = settings.item();
            return craftOutput(settings) && item.isPresent() ? Optional.of(item.get()) : Optional.empty();
        }

        /** No priority of its own: on a tie the planner takes generators before patterns, as AE2 takes emitters. */
        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean canGenerate() {
            ServerLevel level = host.serverLevel();
            return craftOutput(settings) && bound != null && level != null && !device.isRemoved()
                    && ResearchUnlocks.isUnlocked(level, device.ownerRecord(), ResearchGates.CONDITION_SWITCH);
        }

        @Override
        public boolean busy() {
            return !orders.isEmpty();
        }

        @Override
        public long owed(UUID job) {
            return orders.owed(job);
        }

        @Override
        public long order(UUID job, long amount) {
            ServerLevel level = host.serverLevel();
            NetworkState network = bound;
            ItemKey key = settings.item().orElse(null);
            if (amount <= 0 || level == null || network == null || key == null || !canGenerate()) {
                return 0;
            }
            if (orders.isEmpty()) {
                // Only what arrives from now on counts.
                baseline = unaccounted(network, key);
                joinDispatch(level);
            }
            orders.add(job, amount);
            host.settingsChanged();
            request();
            return amount;
        }

        @Override
        public void cancel(UUID job) {
            if (!orders.cancel(job)) {
                return;
            }
            if (orders.isEmpty()) {
                leaveDispatch();
            }
            host.settingsChanged();
            request();
        }

        @Override
        public boolean gone() {
            return device.isRemoved();
        }

        @Override
        public long ticksPerThousand() {
            return 0;
        }

        @Override
        public List<CraftingPattern> patterns() {
            return List.of();
        }

        @Override
        public boolean canAccept(CraftingPattern pattern) {
            return false;
        }

        @Override
        public boolean push(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs) {
            return false;
        }
    }

    /** Forgets every switch when the server stops. */
    static void clear() {
        STARTED.clear();
    }

    public void save(ValueOutput out) {
        out.store(SETTINGS, ConditionSettings.CODEC, settings);
        out.putBoolean(OUTPUT, output);
        out.putInt(STRENGTH, strength);
        out.putInt(LOGIC_STRENGTH, logicStrength);
        if (input > 0) {
            out.putInt(CHANNEL_INPUT, input);
        }
        orders.save(out, CRAFT_ORDERS);
        if (!orders.isEmpty()) {
            long unsettled = Math.max(carried, unsettled(bound));
            if (unsettled > 0) {
                out.store(CRAFT_CARRIED, Codec.LONG, unsettled);
            }
        }
    }

    public void load(ValueInput in) {
        settings = in.read(SETTINGS, ConditionSettings.CODEC).orElse(ConditionSettings.DEFAULT);
        output = in.getBooleanOr(OUTPUT, false);
        strength = Math.clamp(in.getIntOr(STRENGTH, output ? 15 : 0), 0, 15);
        logicStrength = Math.clamp(in.getIntOr(LOGIC_STRENGTH, 0), 0, 15);
        input = Math.clamp(in.getIntOr(CHANNEL_INPUT, 0), 0, 15);
        orders.load(in, CRAFT_ORDERS);
        carried = orders.isEmpty() ? 0 : Math.max(0, in.read(CRAFT_CARRIED, Codec.LONG).orElse(0L));
    }

    /** Takes the settings from an item without saving or evaluating; before the switch starts. */
    public void loadSettings(ConditionSettings settings) {
        this.settings = settings;
    }
}
