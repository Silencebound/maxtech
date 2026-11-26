package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.CardEdits;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.GraphDiff;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Parameters;
import dev.silencebound.maxtech.storage.logic.RuleCard;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.logic.CompileResult;
import dev.silencebound.maxtech.storage.logic.CompiledGraph;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.Port;
import dev.silencebound.maxtech.storage.logic.PortType;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import dev.silencebound.maxtech.storage.logic.editor.EditorNote;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import net.minecraft.server.level.ServerLevel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkLabels;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The server side of the node editor (M7L area F): editor sessions, the one-editor-per-graph lock, saving and live
 * values. A session belongs to a player's open {@link LogicControllerMenu}, so every request needs the menu with the
 * payload's container id to be open and still valid (reach and the logic access level, as for every click on the
 * controller). One player edits a graph at a time; others who open it get it read-only with the editor's name, with
 * live values, and hear when the editor leaves ({@link EditorPayloads#LIVE_LOCK_FREE}) so they can open it again to
 * edit (M7L2 chunk 9). Every change a player makes goes into the rule's history ({@link RuleHistory}). A save comes
 * only from the graph's editor; it is decoded here (a graph that does not decode is refused and nothing changes),
 * cleaned of a bad name and handed to {@link LogicWorker#setGraph}, which compiles it: a graph with errors, or with
 * more nodes than {@code logic.maxNodes}, is saved but paused and its previous version stops. Rate limits per player
 * and controller ({@link Locks}): every save request, refused or not, counts against {@link #SAVE_INTERVAL}, and open
 * requests against {@link #OPEN_INTERVAL} (an open asked too soon gets no answer; the screen asks again). Live values
 * go out from the menu's per-tick {@code broadcastChanges} only while a session is open and only when they changed, so
 * a closed editor costs nothing. Server thread only.
 */
public final class LogicEditing {
    /** Ticks between two saves of one player. */
    public static final int SAVE_INTERVAL = 5;
    /** Ticks between two live updates of one session. */
    public static final int LIVE_INTERVAL = 4;
    // Where a live entry's value comes from: an output's value slot, a watch's last value, a delay's due tick.
    private static final int SLOT = 0;
    private static final int WATCH = 1;
    private static final int DUE = 2;
    /** Ticks between two open requests of one player at one controller; the client asks again if none is answered. */
    public static final int OPEN_INTERVAL = 2;

    private LogicEditing() {
    }

    /** Registers the packets and declares the editor's lang lines; during mod construction. */
    static void init() {
        EditorTexts.init();
        EditorPayloads.register();
    }

    /**
     * Who edits which graph of one controller, and when each player last asked to open or save, for the rate limits.
     * Kept per controller and player, so closing and reopening the menu does not reset them. Not saved; a session ends
     * when its menu closes.
     */
    public static final class Locks {
        private final Int2ObjectOpenHashMap<Session> editors = new Int2ObjectOpenHashMap<>();
        private final Object2LongOpenHashMap<UUID> lastOpen = new Object2LongOpenHashMap<>();
        private final Object2LongOpenHashMap<UUID> lastSave = new Object2LongOpenHashMap<>();

        Locks() {
            lastOpen.defaultReturnValue(Long.MIN_VALUE / 2);
            lastSave.defaultReturnValue(Long.MIN_VALUE / 2);
        }

        /** Counts an open request; false when the player asked less than {@link #OPEN_INTERVAL} ticks ago. */
        boolean allowOpen(Player player, long now) {
            return allow(lastOpen, player.getUUID(), now, OPEN_INTERVAL);
        }

        /** Counts a save request, refused or not; false when the last was less than {@link #SAVE_INTERVAL} ago. */
        boolean allowSave(Player player, long now) {
            return allow(lastSave, player.getUUID(), now, SAVE_INTERVAL);
        }

        private static boolean allow(Object2LongOpenHashMap<UUID> last, UUID player, long now, int interval) {
            if (now - last.getLong(player) < interval) {
                return false;
            }
            last.put(player, now);
            return true;
        }

        /** The session editing graph {@code index}, if it is still open. */
        @Nullable
        Session holder(int index) {
            Session session = editors.get(index);
            if (session != null && !session.open()) {
                editors.remove(index);
                return null;
            }
            return session;
        }

        /** Makes {@code session} the editor of its graph unless another player holds it; true when it does. */
        boolean acquire(Session session) {
            Session holder = holder(session.index);
            if (holder != null && !holder.player.getUUID().equals(session.player.getUUID())) {
                return false;
            }
            editors.put(session.index, session);
            return true;
        }

        void release(Session session) {
            if (editors.get(session.index) == session) {
                editors.remove(session.index);
            }
        }

        /** The name of the player editing graph {@code index}, if anyone does. */
        public Optional<String> editor(int index) {
            Session holder = holder(index);
            return holder == null ? Optional.empty() : Optional.of(holder.player.getGameProfile().name());
        }
    }

    /** One player's open editor on one graph. */
    public static final class Session {
        final ServerPlayer player;
        final LogicControllerMenu menu;
        final LogicControllerBlockEntity controller;
        final int index;
        final boolean readOnly;
        /** The compile result the player last got, so a viewer hears of a save by someone else. */
        @Nullable
        CompileResult<LogicKey> shown;
        /** The graph version the player last got ({@link LogicWorker#edit}), so the editor hears of a revert. */
        long shownEdit = -1;
        int sentFlags = -1;
        long lastLive = Long.MIN_VALUE / 2;
        boolean liveSent;
        // The live view: per entry, its node, name, where its value comes from (SLOT, WATCH or DUE) and that slot,
        // watch index or node position; rebuilt when the evaluator changes.
        @Nullable
        LogicEvaluator<LogicKey> evaluator;
        int[] nodes = new int[0];
        String[] outputs = new String[0];
        int[] slots = new int[0];
        int[] sources = new int[0];
        long[] sent = new long[0];
        int sentStatus = -1;
        int sentCost = -1;
        int sentPoints = -1;
        long sentRuns = -1;
        int[] sentWhyNodes = new int[0];
        String[] sentWhyKeys = new String[0];
        long[] sentWhyValues = new long[0];
        String[] sentWhyTexts = new String[0];

        Session(ServerPlayer player, LogicControllerMenu menu, LogicControllerBlockEntity controller, int index,
                boolean readOnly) {
            this.player = player;
            this.menu = menu;
            this.controller = controller;
            this.index = index;
            this.readOnly = readOnly;
        }

        /** Whether the session's menu is still the player's open menu and holds this session. */
        @SuppressWarnings("ReferenceEquality") // menus and sessions are compared by identity
        boolean open() {
            return !player.hasDisconnected() && player.containerMenu == menu && menu.session() == this;
        }

        public int index() {
            return index;
        }

        public boolean readOnly() {
            return readOnly;
        }
    }

    /** The sender's open controller menu with {@code containerId}, if it is still valid for them. */
    private static @Nullable LogicControllerMenu menu(Player player, int containerId) {
        if (player.containerMenu instanceof LogicControllerMenu menu && menu.containerId == containerId
                && menu.controller() != null && menu.stillValid(player)) {
            return menu;
        }
        return null;
    }

    /**
     * Opens graph {@code index} ({@code index} equal to the graph count starts a new one) for {@code player}'s open
     * menu {@code containerId}: as its editor when nobody else edits it, else read-only. Returns the graph to send, or
     * null when the menu, access or index is wrong or the player asked less than {@link #OPEN_INTERVAL} ticks ago.
     */
    public static EditorPayloads.@Nullable Graph open(ServerPlayer player, int containerId, int index) {
        LogicControllerMenu menu = menu(player, containerId);
        LogicControllerBlockEntity controller = menu == null ? null : menu.controller();
        if (menu == null || controller == null || !controller.editors().allowOpen(player, player.level().getGameTime())
                || index < 0 || index > controller.worker().size() || index >= LogicControllerMenu.ROWS) {
            return null;
        }
        menu.endSession();
        Session editing = new Session(player, menu, controller, index, false);
        Session session = editing;
        if (!controller.editors().acquire(editing)) {
            session = new Session(player, menu, controller, index, true);
        }
        menu.setSession(session);
        return graph(session);
    }

    /** The graph payload for {@code session}: the graph as saved, its notes, labels and who edits it. */
    private static EditorPayloads.Graph graph(Session session) {
        LogicControllerBlockEntity controller = session.controller;
        LogicWorker worker = controller.worker();
        int index = session.index;
        boolean exists = index < worker.size();
        LogicGraph<LogicKey> graph = exists ? worker.graph(index) : LogicGraph.empty();
        NetworkState network = controller.network();
        List<EditorNote> notes = List.of();
        if (exists) {
            CompileResult<LogicKey> result = worker.result(index);
            session.shown = result;
            session.shownEdit = worker.edit(index);
            notes = EditorNote.of(result, labels(controller));
        }
        String editor = session.readOnly ? controller.editors().editor(index).orElse("") : "";
        return new EditorPayloads.Graph(session.menu.containerId, index, exists ? worker.name(index) : "",
                encode(session.player.registryAccess(), graph), notes, session.readOnly, editor, labelList(controller),
                network != null, StorageServerConfig.LOGIC_MAX_NODES.getAsInt(),
                exists ? worker.pins(index) : List.of(), LogicWorker.maxPins());
    }

    /** Ends {@code player}'s editor session on menu {@code containerId}. */
    public static void close(Player player, int containerId) {
        if (player.containerMenu instanceof LogicControllerMenu menu && menu.containerId == containerId) {
            menu.endSession();
        }
    }

    /**
     * Saves a graph from {@code player}'s editor: only from the graph's editor, only to the graph the session opened,
     * at most every {@link #SAVE_INTERVAL} ticks per player and controller. Returns the answer to send (saved with its
     * notes, or refused with a reason), or null when the menu or access is wrong.
     */
    public static EditorPayloads.@Nullable Saved save(ServerPlayer player, EditorPayloads.Save payload) {
        LogicControllerMenu menu = menu(player, payload.containerId());
        LogicControllerBlockEntity controller = menu == null ? null : menu.controller();
        if (menu == null || controller == null) {
            return null;
        }
        // Every request counts against the limit, refused ones too, so a client cannot spam saves that fail.
        if (!controller.editors().allowSave(player, player.level().getGameTime())) {
            return refused(payload, EditorTexts.REFUSED_TOO_FAST);
        }
        int index = payload.index();
        Session session = menu.session();
        if (session == null || session.readOnly || session.index != index
                || controller.editors().holder(index) != session) {
            return refused(payload, EditorTexts.REFUSED_NOT_EDITOR);
        }
        LogicWorker worker = controller.worker();
        if (index > worker.size() || index >= LogicControllerMenu.ROWS) {
            return refused(payload, EditorTexts.REFUSED_NO_SLOT);
        }
        Optional<LogicGraph<LogicKey>> graph = decode(player.registryAccess(), payload.graph());
        if (graph.isEmpty()) {
            return refused(payload, EditorTexts.REFUSED_UNREADABLE);
        }
        boolean created = index == worker.size();
        String oldName = created ? "" : worker.name(index);
        LogicGraph<LogicKey> before = created ? LogicGraph.empty() : worker.graph(index);
        List<Integer> oldPins = created ? List.of() : worker.pins(index);
        String name = DeviceLabel.clean(payload.name());
        if (!created && worker.instance(index).isPresent()) {
            // A copy with values (M7L2 row 19) keeps its template's graph: only its name and pins save here.
            if (!graph.get().equals(before)) {
                // The editor shows an older version when the template changed after it opened: say so.
                return refused(payload,
                        session.shownEdit != worker.edit(index)
                                ? EditorTexts.REFUSED_COPY_CHANGED
                                : EditorTexts.REFUSED_COPY_GRAPH);
            }
            worker.rename(index, name, payload.pins());
            RuleHistory.record(worker, index, player,
                    RuleHistory.saved(false, oldName, before, oldPins, name, before, worker.pins(index)));
            session.shown = worker.result(index);
            session.shownEdit = worker.edit(index);
            return new EditorPayloads.Saved(payload.containerId(), index, true, "",
                    EditorNote.of(worker.result(index), labels(controller)));
        }
        Optional<CompileResult<LogicKey>> result = worker.setGraph(index, name, graph.get(), payload.pins());
        if (result.isEmpty()) {
            return refused(payload, EditorTexts.REFUSED_NO_SLOT);
        }
        RuleHistory.record(worker, index, player,
                RuleHistory.saved(created, oldName, before, oldPins, name, graph.get(), worker.pins(index)));
        session.shown = result.get();
        session.shownEdit = worker.edit(index);
        return new EditorPayloads.Saved(payload.containerId(), index, true, "",
                EditorNote.of(result.get(), labels(controller)));
    }

    private static EditorPayloads.Saved refused(EditorPayloads.Save payload, String reason) {
        return new EditorPayloads.Saved(payload.containerId(), payload.index(), false, reason, List.of());
    }

    /**
     * Applies one edit from the controller screen's card view ({@link CardPayloads.Edit}, M7L2 chunk 11) to the rule as
     * saved and saves it the way an editor save is saved: the same menu, access and rate checks (each request, refused
     * or not, counts against {@link #SAVE_INTERVAL}), refused while another player has the rule open in the editor
     * (naming them), refused when it would add nodes past {@code logic.maxNodes}, then {@link LogicWorker#setGraph}
     * (errors keep it paused) and a history entry with a short summary. {@link CardPayloads#OP_NEW} starts a new rule
     * switched off and waiting ({@link GraphSlot#waiting}): card edits switch it on once it has a trigger and an
     * action, unless the player switched it by hand meanwhile. The card is sent again. Returns the answer, or null when
     * the menu or access is wrong.
     */
    public static CardPayloads.@Nullable Edited cardEdit(ServerPlayer player, CardPayloads.Edit payload) {
        LogicControllerMenu menu = menu(player, payload.containerId());
        LogicControllerBlockEntity controller = menu == null ? null : menu.controller();
        if (menu == null || controller == null) {
            return null;
        }
        if (!controller.editors().allowSave(player, player.level().getGameTime())) {
            return cardRefused(payload, EditorTexts.REFUSED_TOO_FAST);
        }
        LogicWorker worker = controller.worker();
        int index = payload.index();
        Session holder = controller.editors().holder(index);
        if (holder != null && !holder.player.getUUID().equals(player.getUUID())) {
            return cardRefused(payload, EditorTexts.REFUSED_LOCKED, holder.player.getGameProfile().name());
        }
        if (payload.op() == CardPayloads.OP_NEW) {
            if (index != worker.size() || index >= LogicControllerMenu.ROWS
                    || worker.setGraph(index, "", LogicGraph.empty()).isEmpty()) {
                return cardRefused(payload, EditorTexts.REFUSED_NO_SLOT);
            }
            worker.setEnabled(index, false);
            worker.startWaiting(index);
            RuleHistory.record(worker, index, player, CardEdits.HISTORY_NEW);
            menu.selectCard(index);
            return new CardPayloads.Edited(payload.containerId(), index, true, "", List.of());
        }
        if (index < 0 || index >= worker.size() || index >= LogicControllerMenu.ROWS) {
            return cardRefused(payload, EditorTexts.REFUSED_NO_SLOT);
        }
        if (worker.instance(index).isPresent()) {
            return copyEdit(player, payload, menu, worker, index);
        }
        LogicGraph<LogicKey> before = worker.graph(index);
        CardEdits.Result<LogicKey> result;
        List<GraphChange> changes;
        switch (payload.op()) {
            case CardPayloads.OP_SET -> {
                SettingValue<LogicKey> value = decodeValue(player.registryAccess(), payload.value()).orElse(null);
                if (value == null) {
                    return cardRefused(payload, EditorTexts.REFUSED_UNREADABLE);
                }
                result = CardEdits.set(before, payload.node(), payload.setting(), value);
                changes = List.of();
            }
            case CardPayloads.OP_REMOVE -> {
                RuleCard.Section section = named(RuleCard.Section.values(), payload.part());
                LogicNode<LogicKey> node = before.node(payload.node());
                if (section == null || node == null) {
                    return cardRefused(payload, CardEdits.REFUSED_GONE);
                }
                result = CardEdits.remove(before, section, payload.node());
                changes = List.of(new GraphChange(CardEdits.HISTORY_REMOVED,
                        List.of(GraphChange.Arg.key(node.type().nameKey()))));
            }
            case CardPayloads.OP_ADD -> {
                CardEdits.Add add = named(CardEdits.Add.values(), payload.part());
                NodeType type = NodeTypes.byId(payload.nodeType()).orElse(null);
                if (add == null || type == null) {
                    return cardRefused(payload, CardEdits.REFUSED_TYPE);
                }
                result = CardEdits.add(before, add, type);
                String key = switch (add) {
                    case TRIGGER -> CardEdits.HISTORY_TRIGGER;
                    case CONDITION -> CardEdits.HISTORY_CONDITION;
                    case ACTION -> CardEdits.HISTORY_ACTION;
                };
                changes = List.of(new GraphChange(key, List.of(GraphChange.Arg.key(type.nameKey()))));
            }
            default -> {
                return cardRefused(payload, CardEdits.REFUSED_GONE);
            }
        }
        LogicGraph<LogicKey> after = result.graph().orElse(null);
        if (after == null) {
            return cardRefused(payload, result.refusal());
        }
        int maxNodes = StorageServerConfig.LOGIC_MAX_NODES.getAsInt();
        if (after.nodes().size() > maxNodes && after.nodes().size() > before.nodes().size()) {
            return cardRefused(payload, EditorTexts.FULL, String.valueOf(maxNodes));
        }
        if (worker.setGraph(index, worker.name(index), after).isEmpty()) {
            return cardRefused(payload, EditorTexts.REFUSED_NO_SLOT);
        }
        RuleHistory.record(worker, index, player,
                changes.isEmpty() ? GraphDiff.of(before, after, RuleHistory::item) : changes);
        if (worker.waiting(index) && CardEdits.hasTrigger(after) && CardEdits.hasAction(after)) {
            // A rule started as a blank card waits switched off until it has a trigger and an action.
            worker.setEnabled(index, true);
            RuleHistory.record(worker, index, player, LogicLang.HISTORY_SWITCHED_ON);
        }
        menu.selectCard(index);
        return new CardPayloads.Edited(payload.containerId(), index, true, "", List.of());
    }

    /**
     * A card edit of a copy with values (M7L2 row 19): only a parameter's value may change, and it changes only this
     * copy's value ({@link LogicWorker#setCopyValue}); anything else is refused naming the template.
     */
    private static CardPayloads.Edited copyEdit(ServerPlayer player, CardPayloads.Edit payload,
            LogicControllerMenu menu, LogicWorker worker, int index) {
        int template = worker.instance(index).map(RuleInstance::template).orElse(-1);
        String templateName = template >= 0 && template < worker.size() ? worker.name(template) : "";
        LogicGraph<LogicKey> before = worker.graph(index);
        LogicNode<LogicKey> node = before.node(payload.node());
        if (payload.op() != CardPayloads.OP_SET || node == null || node.type() != NodeTypes.PARAMETER
                || !payload.setting().equals(Parameters.valueSetting(Parameters.kind(node.settings())))) {
            return cardRefused(payload, EditorTexts.REFUSED_COPY, templateName, templateName);
        }
        SettingValue<LogicKey> value = decodeValue(player.registryAccess(), payload.value()).orElse(null);
        if (value == null) {
            return cardRefused(payload, EditorTexts.REFUSED_UNREADABLE);
        }
        CardEdits.Result<LogicKey> result = CardEdits.set(before, payload.node(), payload.setting(), value);
        LogicGraph<LogicKey> after = result.graph().orElse(null);
        LogicNode<LogicKey> changed = after == null ? null : after.node(payload.node());
        SettingValue<LogicKey> clean = changed == null ? null : changed.settings().values().get(payload.setting());
        if (clean == null) {
            return cardRefused(payload, result.refusal());
        }
        String key = Parameters.key(node.settings().text("name").orElse(""));
        if (!worker.setCopyValue(index, key, clean)) {
            return cardRefused(payload, EditorTexts.REFUSED_NO_SLOT);
        }
        RuleHistory.record(worker, index, player, GraphDiff.of(before, worker.graph(index), RuleHistory::item));
        menu.selectCard(index);
        return new CardPayloads.Edited(payload.containerId(), index, true, "", List.of());
    }

    /** The constant of {@code values} called {@code name}; null for none. */
    private static <E extends Enum<E>> @Nullable E named(E[] values, String name) {
        for (E value : values) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }

    private static CardPayloads.Edited cardRefused(CardPayloads.Edit payload, String reason, String... args) {
        return new CardPayloads.Edited(payload.containerId(), payload.index(), false, reason, List.of(args));
    }

    /** The codec of a card edit's value: a setting value whose items are {@link LogicKey}s. */
    private static final Codec<SettingValue<LogicKey>> VALUE_CODEC = SettingValue.codec(LogicKey.CODEC);

    /** {@code value} as the tag a card edit carries. */
    public static CompoundTag encodeValue(RegistryAccess registries, SettingValue<LogicKey> value) {
        Tag tag = VALUE_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value).result()
                .orElse(null);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    /** The setting value in {@code tag}; empty when it does not decode. */
    static Optional<SettingValue<LogicKey>> decodeValue(RegistryAccess registries, CompoundTag tag) {
        try {
            return VALUE_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).result();
        } catch (RuntimeException e) {
            // A codec's constructor may throw on bad input instead of returning an error (storage-facts.md).
            return Optional.empty();
        }
    }

    /** {@code graph} as the compound tag the payloads carry. */
    public static CompoundTag encode(RegistryAccess registries, LogicGraph<LogicKey> graph) {
        Tag tag = LogicKey.GRAPH_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), graph)
                .result().orElse(null);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    /** The graph in {@code tag}; empty when it does not decode (unknown node types, bad values, too large). */
    public static Optional<LogicGraph<LogicKey>> decode(RegistryAccess registries, CompoundTag tag) {
        try {
            return LogicKey.GRAPH_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).result();
        } catch (RuntimeException e) {
            // A codec's constructor may throw on bad input instead of returning an error (storage-facts.md).
            return Optional.empty();
        }
    }

    /** What the editor's notes know about {@code network}'s labels. */
    static EditorNote.Labels labels(LogicControllerBlockEntity controller) {
        NetworkState network = controller.network();
        if (network == null) {
            return EditorNote.Labels.UNKNOWN;
        }
        NetworkLabels labels = network.labels();
        List<String> networks = reachable(controller);
        return new EditorNote.Labels() {
            @Override
            public boolean has(String label) {
                // "@North Base" asks whether the rule may name that network (M7L2 row 13).
                return NetworkRef.remote(label)
                        ? RemoteNetworks.holds(networks, NetworkRef.network(label))
                        : labels.has(label);
            }

            @Override
            public boolean switchable(String label) {
                return LogicEditing.switchable(labels.devices(label));
            }
        };
    }

    private static boolean switchable(List<NetworkDevice> devices) {
        for (NetworkDevice device : devices) {
            if (device.switchable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The labels the pickers offer: the other networks the controller's owner may name, as {@code @name} entries with
     * no devices (M7L2 row 13), then the own network's labels, at most {@link EditorPayloads#MAX_LABELS} in all.
     */
    static List<EditorPayloads.LabelInfo> labelList(LogicControllerBlockEntity controller) {
        NetworkState network = controller.network();
        if (network == null) {
            return List.of();
        }
        List<EditorPayloads.LabelInfo> out = new ArrayList<>();
        for (String name : reachable(controller)) {
            out.add(new EditorPayloads.LabelInfo(DeviceLabel.of(NetworkRef.MARK + name, null), 0, false));
        }
        for (NetworkLabels.Entry entry : network.labels().entries(EditorPayloads.MAX_LABELS - out.size())) {
            out.add(new EditorPayloads.LabelInfo(entry.label(), entry.devices(),
                    switchable(network.labels().devicesByKey(entry.label().key()))));
        }
        return out;
    }

    /** The other networks {@code controller}'s owner may name; empty outside a server level. */
    private static List<String> reachable(LogicControllerBlockEntity controller) {
        ServerLevel level = controller.serverLevel();
        return level == null
                ? List.of()
                : RemoteNetworks.reachable(level, controller.ownerRecord(), controller.network());
    }

    // ---- per tick, from the menu

    /**
     * What to send {@code session}'s player this tick, if anything: the graph again when someone else saved it (for a
     * read-only viewer), else the live values when they changed. Allocates only when there is something to send.
     */
    @SuppressWarnings("ReferenceEquality") // a save makes a new compile result; equal graphs still count as new
    static @Nullable CustomPacketPayload update(Session session, long gameTime) {
        LogicWorker worker = session.controller.worker();
        int index = session.index;
        if (index < worker.size() && session.readOnly && worker.result(index) != session.shown) {
            return graph(session);
        }
        // A version the editor did not save itself (a revert, from the editor or the controller screen).
        if (index < worker.size() && !session.readOnly && session.shownEdit >= 0
                && worker.edit(index) != session.shownEdit) {
            return graph(session);
        }
        if (gameTime - session.lastLive < LIVE_INTERVAL) {
            return null;
        }
        EditorPayloads.Live live = live(session, worker);
        if (live != null) {
            session.lastLive = gameTime;
            session.liveSent = true;
        }
        return live;
    }

    /** The live values of {@code session}'s graph when they differ from what it last got; null otherwise. */
    static EditorPayloads.@Nullable Live live(Session session, LogicWorker worker) {
        int index = session.index;
        if (index >= worker.size()) {
            return null;
        }
        LogicEvaluator<LogicKey> evaluator = worker.evaluator(index).orElse(null);
        if (evaluator != session.evaluator) {
            session.evaluator = evaluator;
            watch(session, evaluator);
        }
        int status = worker.status(index).id();
        int cost = worker.lastCost(index);
        int points = worker.lastActionPoints(index);
        long runs = evaluator == null ? 0 : evaluator.runs();
        // A viewer hears when the editor left, so its banner can offer to edit now.
        int flags = (worker.simulating(index) ? EditorPayloads.LIVE_SIMULATE : 0)
                | (worker.hasPrevious(index) ? EditorPayloads.LIVE_PREVIOUS : 0)
                | (session.readOnly && session.controller.editors().holder(index) == null
                        ? EditorPayloads.LIVE_LOCK_FREE
                        : 0);
        boolean changed = status != session.sentStatus || cost != session.sentCost || points != session.sentPoints
                || runs != session.sentRuns || flags != session.sentFlags;
        if (evaluator != null) {
            for (int i = 0; i < session.slots.length; i++) {
                long value = switch (session.sources[i]) {
                    case WATCH -> worker.watched(index, session.slots[i]);
                    case DUE -> evaluator.due(session.slots[i]);
                    default -> evaluator.value(session.slots[i]);
                };
                // A watch not looked at yet (just reset for a fresh read) keeps the value last sent.
                if (session.sources[i] == WATCH && value == Long.MIN_VALUE) {
                    continue;
                }
                changed |= value != session.sent[i];
                session.sent[i] = value;
            }
            changed |= whyChanged(session, evaluator.trace());
        }
        if (!changed && session.liveSent) {
            return null;
        }
        session.sentStatus = status;
        session.sentCost = cost;
        session.sentPoints = points;
        session.sentRuns = runs;
        session.sentFlags = flags;
        List<EditorPayloads.LiveValue> values = new ArrayList<>(session.slots.length);
        for (int i = 0; i < session.slots.length; i++) {
            if (session.sources[i] == SLOT || session.sent[i] != Long.MIN_VALUE) {
                values.add(new EditorPayloads.LiveValue(session.nodes[i], session.outputs[i], session.sent[i]));
            }
        }
        List<EditorPayloads.Why> why = new ArrayList<>(session.sentWhyNodes.length);
        for (int i = 0; i < session.sentWhyNodes.length; i++) {
            why.add(new EditorPayloads.Why(session.sentWhyNodes[i], session.sentWhyKeys[i],
                    session.sentWhyValues[i * 2], session.sentWhyValues[i * 2 + 1], session.sentWhyTexts[i]));
        }
        return new EditorPayloads.Live(session.menu.containerId, status, cost, points, runs, values, why, flags);
    }

    /**
     * Builds the live view of {@code evaluator}'s graph: every number, yes/no and event output with its slot, then for
     * each node with a live line ({@link LiveLine}) that reads a watch or a delay's due tick, that entry.
     */
    private static void watch(Session session, @Nullable LogicEvaluator<LogicKey> evaluator) {
        if (evaluator == null) {
            session.nodes = new int[0];
            session.outputs = new String[0];
            session.slots = new int[0];
            session.sources = new int[0];
            session.sent = new long[0];
            session.sentWhyNodes = new int[0];
            return;
        }
        CompiledGraph<LogicKey> compiled = evaluator.graph();
        List<Integer> nodes = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<Integer> sources = new ArrayList<>();
        for (LogicNode<LogicKey> node : compiled.source().nodes()) {
            for (Port port : node.type().outputs(node.settings())) {
                PortType type = port.type();
                int slot = compiled.slot(node.id(), port.name());
                if (slot >= 0 && !type.fixed() && nodes.size() < EditorPayloads.MAX_LIVE) {
                    nodes.add(node.id());
                    outputs.add(port.name());
                    slots.add(slot);
                    sources.add(SLOT);
                }
            }
            String entry = LiveLine.of(node.type()).map(LiveLine.Spec::entry).orElse("");
            int position = compiled.position(node.id());
            if (position < 0 || nodes.size() >= EditorPayloads.MAX_LIVE) {
                continue;
            }
            if (entry.equals(LiveLine.WATCHED) && compiled.watchAt(position) >= 0) {
                nodes.add(node.id());
                outputs.add(entry);
                slots.add(compiled.watchAt(position));
                sources.add(WATCH);
            } else if (entry.equals(LiveLine.DUE)) {
                nodes.add(node.id());
                outputs.add(entry);
                slots.add(position);
                sources.add(DUE);
            }
        }
        session.nodes = new int[nodes.size()];
        session.outputs = outputs.toArray(new String[0]);
        session.slots = new int[slots.size()];
        session.sources = new int[slots.size()];
        for (int i = 0; i < nodes.size(); i++) {
            session.nodes[i] = nodes.get(i);
            session.slots[i] = slots.get(i);
            session.sources[i] = sources.get(i);
        }
        session.sent = new long[nodes.size()];
        Arrays.fill(session.sent, Long.MIN_VALUE);
        session.sentWhyNodes = new int[0];
        session.liveSent = false;
    }

    /**
     * Graph {@code index}'s live values as they are now, for the controller's card view: its number and yes/no outputs,
     * then for nodes with a live line the value a crossing watches or a delay's due tick (as the editor's live view,
     * without events). Empty while the graph is paused. Allocates; called only when a card or its values are sent.
     */
    static List<EditorPayloads.LiveValue> snapshot(LogicWorker worker, int index) {
        if (index < 0 || index >= worker.size()) {
            return List.of();
        }
        LogicEvaluator<LogicKey> evaluator = worker.evaluator(index).orElse(null);
        if (evaluator == null) {
            return List.of();
        }
        CompiledGraph<LogicKey> compiled = evaluator.graph();
        List<EditorPayloads.LiveValue> values = new ArrayList<>();
        for (LogicNode<LogicKey> node : compiled.source().nodes()) {
            for (Port port : node.type().outputs(node.settings())) {
                int slot = compiled.slot(node.id(), port.name());
                if (slot >= 0 && !port.type().fixed() && port.type() != PortType.EVENT
                        && values.size() < EditorPayloads.MAX_LIVE) {
                    values.add(new EditorPayloads.LiveValue(node.id(), port.name(), evaluator.value(slot)));
                }
            }
            String entry = LiveLine.of(node.type()).map(LiveLine.Spec::entry).orElse("");
            int position = compiled.position(node.id());
            if (position < 0 || values.size() >= EditorPayloads.MAX_LIVE) {
                continue;
            }
            if (entry.equals(LiveLine.WATCHED) && compiled.watchAt(position) >= 0) {
                long value = worker.watched(index, compiled.watchAt(position));
                if (value != Long.MIN_VALUE) {
                    values.add(new EditorPayloads.LiveValue(node.id(), entry, value));
                }
            } else if (entry.equals(LiveLine.DUE)) {
                values.add(new EditorPayloads.LiveValue(node.id(), entry, evaluator.due(position)));
            }
        }
        return values;
    }

    /** Copies the trace into the session when it differs from the last one sent; returns whether it did. */
    private static boolean whyChanged(Session session, RunTrace trace) {
        int size = trace.size();
        boolean same = size == session.sentWhyNodes.length;
        for (int i = 0; same && i < size; i++) {
            same = trace.node(i) == session.sentWhyNodes[i] && trace.reason(i).equals(session.sentWhyKeys[i])
                    && trace.first(i) == session.sentWhyValues[i * 2]
                    && trace.second(i) == session.sentWhyValues[i * 2 + 1]
                    && trace.text(i).equals(session.sentWhyTexts[i]);
        }
        if (same) {
            return false;
        }
        session.sentWhyNodes = new int[size];
        session.sentWhyKeys = new String[size];
        session.sentWhyValues = new long[size * 2];
        session.sentWhyTexts = new String[size];
        for (int i = 0; i < size; i++) {
            session.sentWhyNodes[i] = trace.node(i);
            session.sentWhyKeys[i] = trace.reason(i);
            session.sentWhyValues[i * 2] = trace.first(i);
            session.sentWhyValues[i * 2 + 1] = trace.second(i);
            session.sentWhyTexts[i] = trace.text(i);
        }
        return true;
    }
}
