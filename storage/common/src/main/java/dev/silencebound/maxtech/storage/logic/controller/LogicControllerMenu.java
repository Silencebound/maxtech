package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Logic Controller's screen (M7L area B, prototype): the graph list with each graph's name, status and last cost
 * (checks and action points), a switch per graph, the team's two budgets, and the label tab. Graph names come with the
 * opening packet; the rest travels as data slots, and switching a graph is a menu button click ({@code id} = graph
 * index); so are simulate mode ({@link #BUTTON_SIMULATE} + index) and reverting to the last working version
 * ({@link #BUTTON_REVERT} + index). The node editor (area F) opens over this menu and keeps its session here
 * ({@link LogicEditing}). The screen's card view gets the selected rule's graph from here once per selection
 * ({@link CardPayloads}). Opening and every click need the logic access level.
 */
public final class LogicControllerMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 230;
    /** Graph rows the screen shows. */
    public static final int ROWS = 8;
    public static final int ROW_HEIGHT = 18;
    public static final int LIST_Y = 18;
    /** The list, two budget lines, two rows of sharing buttons and the sharing line. */
    public static final int HEIGHT = LIST_Y + ROWS * ROW_HEIGHT + 82;

    private static final int COUNT = 0;
    private static final int TEAM_USED = 1;
    private static final int TEAM_LIMIT = 3;
    private static final int OWN_CHECKS = 5;
    private static final int WAITING = 7;
    private static final int POINTS_USED = 8;
    private static final int POINTS_LIMIT = 10;
    private static final int OWN_POINTS = 12;
    /** Per row: status, last cost in checks, last cost in action points. */
    private static final int FIRST_ROW = 14;
    private static final int PER_ROW = 3;
    private static final int SLOTS = FIRST_ROW + ROWS * PER_ROW;
    /** Menu button id base: {@code BUTTON_SIMULATE + i} switches simulate mode of graph {@code i}. */
    public static final int BUTTON_SIMULATE = GraphSlot.HARD_MAX;
    /** Menu button id base: {@code BUTTON_REVERT + i} puts back graph {@code i}'s last working version. */
    public static final int BUTTON_REVERT = 2 * GraphSlot.HARD_MAX;
    /**
     * Menu button id base (M7L2 row 19): {@code BUTTON_COPY + i} adds a copy of graph {@code i} with its own values, or
     * detaches graph {@code i} when it is such a copy.
     */
    public static final int BUTTON_COPY = 3 * GraphSlot.HARD_MAX;
    /** Status data slot bits next to the status id: the graph simulates, it has a version to revert to. */
    private static final int FLAG_SIMULATE = 8;
    private static final int FLAG_PREVIOUS = 16;
    private static final int FLAG_COPY = 32;
    private static final int FLAG_PARAMETERS = 64;
    /** Status data slot bit: the graph waits for the team's action points rather than its checks. */
    private static final int FLAG_WAITS_ACTIONS = 128;
    private static final int STATUS_MASK = 7;

    private final @Nullable LogicControllerBlockEntity controller;
    private final List<String> names;
    private final DataSlot[] data = new DataSlot[SLOTS];
    /** The open node editor's session (area F), server side; null while no editor is open. */
    private LogicEditing.@Nullable Session session;
    /** The player the server's menu sends the log to; null on the client and in tests. */
    private final @Nullable ServerPlayer viewer;
    // What the log last sent was built from: graph runs, edits and the network's notices (server side).
    private long logRuns = -1;
    private long logRevision = -1;
    private long logPosted = -1;
    private @Nullable NetworkNotices logNotices;
    private long logSent = Long.MIN_VALUE / 2;
    /** The pinned values the last log carried, per row and pin; compared on the update timer. */
    private final long[] logPins = new long[ROWS * GraphSlot.MAX_PINS];
    private final int[] logPinCounts = new int[ROWS];
    /** The last log the server sent, on the client; null until the first arrives. */
    private ControllerLog.@Nullable Update log;
    // The card view (server side): the rule the screen shows as a card, whether its card is due, when the last went
    // out and the graph it carried.
    private int cardIndex = -1;
    private boolean cardWanted;
    private long cardSent = Long.MIN_VALUE / 2;
    private @Nullable LogicGraph<LogicKey> cardGraph;
    /** The card the server last sent, on the client; null until the first arrives. */
    private @Nullable CardView card;
    // The change history (server side): the history list each row last sent, by identity (a change makes a new list),
    // the history revision it was built from and when the last went out; on the client, the lists received.
    @SuppressWarnings("unchecked")
    private final List<HistoryEntry>[] historySent = new List[ROWS];
    private long historyRevision = -1;
    private long historyAt = Long.MIN_VALUE / 2;
    @SuppressWarnings("unchecked")
    private final List<HistoryEntry>[] history = new List[ROWS];
    /** The pinned values' history revision the last sparklines were built from (server side). */
    private long sparksRevision = -1;
    /** The sparklines the server last sent, on the client. */
    private List<PinSparks.Spark> sparks = List.of();

    /**
     * A rule as the client's card view has it.
     *
     * @param index
     *            the rule's row
     * @param graph
     *            its graph as saved
     * @param values
     *            its live values, by node and live entry
     * @param labels
     *            the network's labels, for the card's label picker
     * @param maxNodes
     *            the most nodes a rule may have ({@code logic.maxNodes})
     */
    public record CardView(int index, LogicGraph<LogicKey> graph, List<EditorPayloads.LiveValue> values,
            List<EditorPayloads.LabelInfo> labels, int maxNodes) {
        public CardView {
            values = List.copyOf(values);
            labels = List.copyOf(labels);
        }
    }

    private LogicControllerMenu(int containerId, @Nullable LogicControllerBlockEntity controller, List<String> names,
            @Nullable ServerPlayer viewer) {
        super(ModMenus.LOGIC_CONTROLLER.get(), containerId);
        this.controller = controller;
        this.names = names;
        this.viewer = viewer;
        for (int i = 0; i < SLOTS; i++) {
            data[i] = controller == null ? DataSlot.standalone() : new Live(controller, i);
            addDataSlot(data[i]);
        }
    }

    /** The client's menu: the graph names from the opening packet, the rest from data slots. */
    public static LogicControllerMenu fromNetwork(int containerId, Inventory inventory,
            RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        int count = Math.min(buffer.readVarInt(), ROWS);
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(buffer.readUtf(GraphSlot.MAX_NAME));
        }
        return new LogicControllerMenu(containerId, null, names, null);
    }

    /** The server's menu over {@code controller}, sending no log (tests read {@link #logUpdate}). */
    public static LogicControllerMenu forController(int containerId, LogicControllerBlockEntity controller) {
        return new LogicControllerMenu(containerId, controller, names(controller.worker()), null);
    }

    /** The server's menu over {@code controller} for {@code viewer}, who gets the log while it is open. */
    static LogicControllerMenu forController(int containerId, LogicControllerBlockEntity controller,
            @Nullable ServerPlayer viewer) {
        return new LogicControllerMenu(containerId, controller, names(controller.worker()), viewer);
    }

    /** Opens {@code controller}'s screen for {@code player}; the caller checked the access level. */
    public static void open(ServerPlayer player, LogicControllerBlockEntity controller, BlockPos pos, Component title) {
        List<String> names = names(controller.worker());
        Platform.get().menus().open(player, new SimpleMenuProvider((id, inventory, opener) -> forController(id,
                controller, opener instanceof ServerPlayer server ? server : null), title), buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeVarInt(names.size());
                    for (String name : names) {
                        buffer.writeUtf(name, GraphSlot.MAX_NAME);
                    }
                });
    }

    private static List<String> names(LogicWorker worker) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(worker.size(), ROWS); i++) {
            names.add(worker.name(i));
        }
        return names;
    }

    /** Graphs the controller holds. */
    public int count() {
        return data[COUNT].get();
    }

    /** Graph {@code row}'s name as it was when the screen opened; empty when it has none. */
    public String name(int row) {
        return row < names.size() ? names.get(row) : "";
    }

    /** Client side: graph {@code row} was saved as {@code name} from the editor, so the list shows it at once. */
    public void setName(int row, String name) {
        if (row >= 0 && row < ROWS) {
            while (names.size() <= row) {
                names.add("");
            }
            names.set(row, name);
        }
    }

    /** The controller, on the server; null on the client. */
    public @Nullable LogicControllerBlockEntity controller() {
        return controller;
    }

    /** The open editor session, on the server; null while none is open. */
    public LogicEditing.@Nullable Session session() {
        return session;
    }

    /** Starts {@code next} as the open editor session, ending the previous one. */
    void setSession(LogicEditing.Session next) {
        endSession();
        session = next;
    }

    /** Ends the open editor session, if any: its graph is free to edit and live values stop. */
    void endSession() {
        LogicEditing.Session current = session;
        LogicControllerBlockEntity entity = controller;
        session = null;
        if (current != null && entity != null) {
            entity.editors().release(current);
        }
    }

    /** Sends the open editor what changed: live values (only while a session is open) or a graph saved by another. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        LogicEditing.Session current = session;
        LogicControllerBlockEntity entity = controller;
        ServerLevel level = entity == null ? null : entity.serverLevel();
        CustomPacketPayload update = level == null ? null : editorUpdate(level.getGameTime());
        // GameTest players have no connection.
        if (current != null && update != null && current.player.connection != null) {
            Platform.get().network().sendToPlayer(current.player, update);
        }
        ServerPlayer player = viewer;
        if (player != null && player.connection != null && level != null) {
            ControllerLog.Update log = logUpdate(level.getGameTime());
            if (log != null) {
                Platform.get().network().sendToPlayer(player, log);
                CardPayloads.Values values = cardValues();
                if (values != null) {
                    Platform.get().network().sendToPlayer(player, values);
                }
            }
            CardPayloads.Card shown = cardUpdate(level.getGameTime());
            if (shown != null) {
                Platform.get().network().sendToPlayer(player, shown);
            }
            for (RuleHistory.Update changed : historyUpdate(level.getGameTime())) {
                Platform.get().network().sendToPlayer(player, changed);
            }
            PinSparks lines = sparkUpdate();
            if (lines != null) {
                Platform.get().network().sendToPlayer(player, lines);
            }
        }
    }

    /**
     * Server side: the screen shows rule {@code index} as a card, so its card goes out with the next broadcast. A row
     * the controller does not have is ignored.
     */
    public void selectCard(int index) {
        LogicControllerBlockEntity entity = controller;
        if (entity == null || index < 0 || index >= Math.min(entity.worker().size(), ROWS)) {
            return;
        }
        cardIndex = index;
        cardWanted = true;
    }

    /**
     * The card for the screen at {@code gameTime}: the selected rule's graph and live values once after each
     * {@link #selectCard} (or a save of that rule), at most every {@link CardPayloads#INTERVAL} ticks; null otherwise.
     * While nothing is due this is one check of a flag. Server side.
     */
    public CardPayloads.@Nullable Card cardUpdate(long gameTime) {
        LogicControllerBlockEntity entity = controller;
        ServerLevel level = entity == null ? null : entity.serverLevel();
        if (!cardWanted || entity == null || level == null || gameTime - cardSent < CardPayloads.INTERVAL) {
            return null;
        }
        cardWanted = false;
        LogicWorker worker = entity.worker();
        if (cardIndex < 0 || cardIndex >= worker.size()) {
            return null;
        }
        LogicGraph<LogicKey> graph = worker.graph(cardIndex);
        cardGraph = graph;
        cardSent = gameTime;
        return new CardPayloads.Card(containerId, cardIndex, LogicEditing.encode(level.registryAccess(), graph),
                LogicEditing.snapshot(worker, cardIndex), LogicEditing.labelList(entity),
                StorageServerConfig.LOGIC_MAX_NODES.getAsInt());
    }

    /** When the log is rebuilt: a save of the card's rule since its card went out makes the card due again. */
    @SuppressWarnings("ReferenceEquality") // a save makes a new graph object; an equal graph is still sent again
    private void noteCardSave(LogicWorker worker) {
        if (cardIndex >= 0 && cardIndex < worker.size() && worker.graph(cardIndex) != cardGraph) {
            cardWanted = true;
        }
    }

    /** After a log update (a graph ran or was saved): the card rule's live values; null while its card is due. */
    private CardPayloads.@Nullable Values cardValues() {
        LogicControllerBlockEntity entity = controller;
        if (entity == null || cardWanted || cardIndex < 0 || cardIndex >= entity.worker().size()) {
            return null;
        }
        return new CardPayloads.Values(containerId, cardIndex, LogicEditing.snapshot(entity.worker(), cardIndex));
    }

    /** Client side: keeps the card the server sent; a graph that does not decode leaves the last one. */
    void acceptCard(CardPayloads.Card payload, RegistryAccess registries) {
        LogicEditing.decode(registries, payload.graph()).ifPresent(graph -> card = new CardView(payload.index(), graph,
                payload.values(), payload.labels(), payload.maxNodes()));
    }

    /** Client side: new live values for the card's rule. */
    void acceptValues(CardPayloads.Values payload) {
        CardView current = card;
        if (current != null && current.index() == payload.index()) {
            card = new CardView(current.index(), current.graph(), payload.values(), current.labels(),
                    current.maxNodes());
        }
    }

    /**
     * The sparklines of the first {@link #ROWS} rules' pinned values when their recorded history changed since the
     * last; null otherwise. Compares one number while nothing was recorded. Server side.
     */
    public @Nullable PinSparks sparkUpdate() {
        LogicControllerBlockEntity entity = controller;
        if (entity == null || entity.pinHistory().revision() == sparksRevision) {
            return null;
        }
        PinHistory recorded = entity.pinHistory();
        sparksRevision = recorded.revision();
        LogicWorker worker = entity.worker();
        long[] points = new long[PinHistory.SPARK_POINTS];
        List<PinSparks.Spark> out = new ArrayList<>();
        for (int g = 0; g < Math.min(worker.size(), ROWS); g++) {
            for (int i = 0; i < Math.min(worker.pinCount(g), GraphSlot.MAX_PINS); i++) {
                int count = recorded.sparkline(g, i, points);
                if (count > 0) {
                    out.add(new PinSparks.Spark(g, i, new LongArrayList(points, 0, count)));
                }
            }
        }
        return new PinSparks(containerId, out);
    }

    /** Client side: keeps the sparklines the server sent. */
    void acceptSparks(PinSparks payload) {
        sparks = payload.sparks();
    }

    /** The sparkline of rule {@code graph}'s pinned value {@code pin}, or null without history. Client side. */
    public @Nullable LongList spark(int graph, int pin) {
        for (int i = 0; i < sparks.size(); i++) {
            PinSparks.Spark spark = sparks.get(i);
            if (spark.graph() == graph && spark.pin() == pin) {
                return spark.points();
            }
        }
        return null;
    }

    /** The card the server last sent; empty until the first arrives. Client side. */
    public Optional<CardView> card() {
        return Optional.ofNullable(card);
    }

    /**
     * The log for the screen at {@code gameTime} when a graph ran, was edited or the network posted a notice since the
     * last one, at most every {@link ControllerLog#INTERVAL} ticks; null otherwise. The check compares four numbers, so
     * an idle controller costs nothing more. Server side.
     */
    public ControllerLog.@Nullable Update logUpdate(long gameTime) {
        LogicControllerBlockEntity entity = controller;
        if (entity == null || gameTime - logSent < ControllerLog.INTERVAL) {
            return null;
        }
        LogicWorker worker = entity.worker();
        NetworkState network = entity.network();
        NetworkNotices notices = network == null ? null : network.notices();
        long posted = notices == null ? 0 : notices.posted();
        boolean pinsChanged = pinsChanged(worker);
        if (worker.runs() == logRuns && worker.revision() == logRevision && notices == logNotices && posted == logPosted
                && !pinsChanged) {
            return null;
        }
        logRuns = worker.runs();
        logRevision = worker.revision();
        logNotices = notices;
        logPosted = posted;
        logSent = gameTime;
        noteCardSave(worker);
        return ControllerLog.build(containerId, worker, network);
    }

    /**
     * Whether a pinned value of the first {@link #ROWS} graphs differs from what the last log carried; takes the new
     * values. A watched count can change without a run, so values are compared, not run counts. Allocates nothing.
     */
    private boolean pinsChanged(LogicWorker worker) {
        boolean changed = false;
        int rows = Math.min(worker.size(), ROWS);
        for (int g = 0; g < ROWS; g++) {
            int count = g < rows ? Math.min(worker.pinCount(g), GraphSlot.MAX_PINS) : 0;
            if (count != logPinCounts[g]) {
                logPinCounts[g] = count;
                changed = true;
            }
            for (int i = 0; i < count; i++) {
                long value = worker.pinValue(g, i);
                int at = g * GraphSlot.MAX_PINS + i;
                if (logPins[at] != value) {
                    logPins[at] = value;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * The rules' histories for the screen at {@code gameTime}: one update per row whose history changed since the last
     * sent (all rows on the first call), at most every {@link ControllerLog#INTERVAL} ticks; empty while nothing
     * changed, which is one comparison of the worker's history revision. Server side.
     */
    @SuppressWarnings("ReferenceEquality") // a changed history is a new list
    public List<RuleHistory.Update> historyUpdate(long gameTime) {
        LogicControllerBlockEntity entity = controller;
        if (entity == null || entity.worker().historyRevision() == historyRevision
                || gameTime - historyAt < ControllerLog.INTERVAL) {
            return List.of();
        }
        LogicWorker worker = entity.worker();
        historyRevision = worker.historyRevision();
        historyAt = gameTime;
        List<RuleHistory.Update> out = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            List<HistoryEntry> now = i < worker.size() ? worker.history(i) : List.of();
            if (now != historySent[i]) {
                historySent[i] = now;
                out.add(new RuleHistory.Update(containerId, i, now));
            }
        }
        return out;
    }

    /** Client side: keeps rule {@code update.index()}'s history. */
    void acceptHistory(RuleHistory.Update update) {
        if (update.index() >= 0 && update.index() < ROWS) {
            history[update.index()] = update.entries();
        }
    }

    /** Rule {@code row}'s change history as the server last sent it, oldest first; empty before any arrived. */
    public List<HistoryEntry> history(int row) {
        List<HistoryEntry> entries = row >= 0 && row < ROWS ? history[row] : null;
        return entries == null ? List.of() : entries;
    }

    /** Client side: keeps the server's log and takes its rule names, so a rename elsewhere shows. */
    void acceptLog(ControllerLog.Update update) {
        log = update;
        for (int i = 0; i < update.rules().size(); i++) {
            setName(i, update.rules().get(i).name());
        }
    }

    /** The last log the server sent; empty until the first arrives. Client side. */
    public Optional<ControllerLog.Update> log() {
        return Optional.ofNullable(log);
    }

    /**
     * What the open editor should get at {@code gameTime}: live values when they changed, or the graph again for a
     * read-only viewer when someone else saved it; null while no editor is open or nothing changed. Server side.
     */
    public @Nullable CustomPacketPayload editorUpdate(long gameTime) {
        LogicEditing.Session current = session;
        return current == null ? null : LogicEditing.update(current, gameTime);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        endSession();
    }

    public LogicWorker.Status status(int row) {
        return LogicWorker.Status.byId(data[FIRST_ROW + row * PER_ROW].get() & STATUS_MASK);
    }

    /** Whether graph {@code row} only simulates its actions. */
    public boolean simulating(int row) {
        return (data[FIRST_ROW + row * PER_ROW].get() & FLAG_SIMULATE) != 0;
    }

    /** Whether graph {@code row} has a last working version to revert to. */
    public boolean hasPrevious(int row) {
        return (data[FIRST_ROW + row * PER_ROW].get() & FLAG_PREVIOUS) != 0;
    }

    /** Whether graph {@code row} is a copy of another rule with its own values (M7L2 row 19). */
    public boolean isCopy(int row) {
        return (data[FIRST_ROW + row * PER_ROW].get() & FLAG_COPY) != 0;
    }

    /** Whether graph {@code row} declares parameters, so it can be copied with values. */
    public boolean hasParameters(int row) {
        return (data[FIRST_ROW + row * PER_ROW].get() & FLAG_PARAMETERS) != 0;
    }

    public int lastCost(int row) {
        return data[FIRST_ROW + row * PER_ROW + 1].get();
    }

    /** The action points graph {@code row}'s last run cost. */
    public int lastActionPoints(int row) {
        return data[FIRST_ROW + row * PER_ROW + 2].get();
    }

    /** Whether graph {@code row}, while waiting, waits for the team's action points rather than its checks. */
    public boolean waitsForActions(int row) {
        return (data[FIRST_ROW + row * PER_ROW].get() & FLAG_WAITS_ACTIONS) != 0;
    }

    /** Action points the team used this second. */
    public int teamPointsUsed() {
        return wide(POINTS_USED);
    }

    /** Action points the team may use per second. */
    public int teamPointsLimit() {
        return wide(POINTS_LIMIT);
    }

    /** Action points this controller used in the last second. */
    public int ownPoints() {
        return wide(OWN_POINTS);
    }

    /** Checks the team used this second. */
    public int teamUsed() {
        return wide(TEAM_USED);
    }

    /** Checks the team may use per second. */
    public int teamLimit() {
        return wide(TEAM_LIMIT);
    }

    /** Checks this controller used in the last second. */
    public int ownChecks() {
        return wide(OWN_CHECKS);
    }

    public int waiting() {
        return data[WAITING].get();
    }

    private int wide(int index) {
        return (data[index].get() & 0xFFFF) | (data[index + 1].get() & 0xFFFF) << 16;
    }

    /**
     * Switches graph {@code id} on or off; {@link #BUTTON_SIMULATE} + index switches its simulate mode,
     * {@link #BUTTON_REVERT} + index puts back its last working version (switched off), and {@link #BUTTON_COPY} +
     * index adds a copy with values or detaches a copy.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        LogicControllerBlockEntity current = controller;
        if (current == null || !LogicControllerBlock.canEdit(player, current) || id < 0) {
            return false;
        }
        LogicWorker worker = current.worker();
        int index = id % GraphSlot.HARD_MAX;
        if (id >= BUTTON_COPY + GraphSlot.HARD_MAX || index >= worker.size()) {
            return false;
        }
        if (id >= BUTTON_COPY) {
            Optional<RuleInstance> link = worker.instance(index);
            if (link.isPresent()) {
                String template = link.get().template() < worker.size() ? worker.name(link.get().template()) : "";
                if (!worker.detach(index)) {
                    return false;
                }
                RuleHistory.record(worker, index, player, LogicLang.HISTORY_DETACHED, template);
                return true;
            }
            if (worker.size() >= ROWS) {
                return false;
            }
            int added = worker.addCopy(index);
            if (added < 0) {
                return false;
            }
            RuleHistory.record(worker, added, player, LogicLang.HISTORY_INSTANCE, worker.name(index));
            return true;
        }
        if (id >= BUTTON_REVERT) {
            // Another player's open editor would lose its unsaved edits without a word: refuse and name them.
            LogicEditing.Session holder = current.editors().holder(index);
            if (holder != null && !holder.player.getUUID().equals(player.getUUID())) {
                if (player instanceof ServerPlayer server && server.connection != null) {
                    Platform.get().network().sendToPlayer(server, new SharePayloads.Imported(containerId, false, index,
                            "", ControllerTexts.REVERT_EDITING, List.of(holder.player.getGameProfile().name())));
                }
                return false;
            }
            if (!worker.revert(index)) {
                return false;
            }
            RuleHistory.record(worker, index, player, LogicLang.HISTORY_REVERTED);
            return true;
        }
        if (id >= BUTTON_SIMULATE) {
            boolean on = !worker.simulating(index);
            worker.setSimulate(index, on);
            RuleHistory.record(worker, index, player,
                    on ? LogicLang.HISTORY_SIMULATE_ON : LogicLang.HISTORY_SIMULATE_OFF);
            return true;
        }
        boolean on = worker.status(index) == LogicWorker.Status.DISABLED;
        worker.setEnabled(index, on);
        RuleHistory.record(worker, index, player, on ? LogicLang.HISTORY_SWITCHED_ON : LogicLang.HISTORY_SWITCHED_OFF);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        LogicControllerBlockEntity current = controller;
        return current == null || (current.stillValid(player) && LogicControllerBlock.canEdit(player, current));
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return controller;
    }

    /** A data slot read from the live controller on the server. */
    private static final class Live extends DataSlot {
        private final LogicControllerBlockEntity controller;
        private final int index;

        Live(LogicControllerBlockEntity controller, int index) {
            this.controller = controller;
            this.index = index;
        }

        @Override
        public int get() {
            LogicWorker worker = controller.worker();
            ServerLevel level = controller.serverLevel();
            long time = level == null ? 0 : level.getGameTime();
            if (index >= FIRST_ROW) {
                int row = (index - FIRST_ROW) / PER_ROW;
                if (row >= worker.size()) {
                    return 0;
                }
                return switch ((index - FIRST_ROW) % PER_ROW) {
                    case 0 -> worker.status(row).id() | (worker.simulating(row) ? FLAG_SIMULATE : 0)
                            | (worker.hasPrevious(row) ? FLAG_PREVIOUS : 0)
                            | (worker.instance(row).isPresent() ? FLAG_COPY : 0)
                            | (worker.hasParameters(row) ? FLAG_PARAMETERS : 0)
                            | (worker.waitingFor(row) == LogicWorker.Budget.ACTIONS ? FLAG_WAITS_ACTIONS : 0);
                    case 1 -> Math.min(worker.lastCost(row), Short.MAX_VALUE);
                    default -> Math.min(worker.lastActionPoints(row), Short.MAX_VALUE);
                };
            }
            return switch (index) {
                case COUNT -> worker.size();
                case TEAM_USED, TEAM_USED + 1 -> half(level == null ? 0 : teamUsed(level, time), index - TEAM_USED);
                case TEAM_LIMIT, TEAM_LIMIT + 1 -> half(TeamBudget.limit(), index - TEAM_LIMIT);
                case OWN_CHECKS, OWN_CHECKS + 1 -> half(worker.checksPerSecond(time), index - OWN_CHECKS);
                case WAITING -> worker.waiting();
                case POINTS_USED, POINTS_USED + 1 ->
                    half(level == null ? 0 : teamPointsUsed(level, time), index - POINTS_USED);
                case POINTS_LIMIT, POINTS_LIMIT + 1 -> half(TeamBudget.actionLimit(), index - POINTS_LIMIT);
                case OWN_POINTS, OWN_POINTS + 1 -> half(worker.actionPointsPerSecond(time), index - OWN_POINTS);
                default -> 0;
            };
        }

        private long teamUsed(ServerLevel level, long time) {
            UUID team = TeamBudget.team(level, controller);
            return TeamBudget.used(team, time);
        }

        private long teamPointsUsed(ServerLevel level, long time) {
            return TeamBudget.actionsUsed(TeamBudget.team(level, controller), time);
        }

        private static int half(long value, int which) {
            int clamped = (int) Math.min(value, Integer.MAX_VALUE);
            return which == 0 ? clamped & 0xFFFF : clamped >>> 16;
        }

        @Override
        public void set(int value) {
        }
    }
}
