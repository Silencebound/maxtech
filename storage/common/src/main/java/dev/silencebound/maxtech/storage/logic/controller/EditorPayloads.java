package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.controller.client.LogicEditorClient;
import dev.silencebound.maxtech.storage.logic.editor.EditorNote;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The node editor's packets (M7L area F). The client asks to {@link Open} graph {@code index} of the controller whose
 * menu it has open and gets the {@link Graph} back, read-only when another player edits it; {@link Save} sends the
 * whole graph and is answered by {@link Saved} with the notes on its nodes; {@link Close} ends the session. While a
 * session is open, and only then, the server sends {@link Live} values when they change. The graph travels as a
 * compound tag that the server decodes in its handler, so a graph that does not decode (bad values, unknown node types,
 * lists over the codec's caps) is refused and the player stays connected. A tag over the NBT quota (2 MiB,
 * {@code ByteBufCodecs.COMPOUND_TAG}) or a name over {@link GraphSlot#MAX_NAME} fails in the packet decoder, as for any
 * vanilla packet, and disconnects the sender; the editor never sends either. Every request is checked against the
 * sender's open {@link LogicControllerMenu} (container id, reach and the logic access level) in {@link LogicEditing}.
 */
public final class EditorPayloads {
    /** Most labels the editor lists. */
    public static final int MAX_LABELS = 64;
    /** Most live values in one update (64 nodes with up to 3 value outputs each, with room). */
    public static final int MAX_LIVE = 512;
    private static final int MAX_KEY = 256;
    private static final int MAX_PORT = 32;

    private EditorPayloads() {
    }

    /** Client to server: open graph {@code index} (the graph count for a new one) of the open controller. */
    public record Open(int containerId, int index) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(MaxtechStorage.id("logic_editor_open"));
        public static final StreamCodec<ByteBuf, Open> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Open::containerId, ByteBufCodecs.VAR_INT, Open::index, Open::new);

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    /** Client to server: the editor closed; live values stop and the graph is free to edit. */
    public record Close(int containerId) implements CustomPacketPayload {
        public static final Type<Close> TYPE = new Type<>(MaxtechStorage.id("logic_editor_close"));
        public static final StreamCodec<ByteBuf, Close> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Close::containerId, Close::new);

        @Override
        public Type<Close> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: save {@code graph} (encoded with {@link LogicKey#GRAPH_CODEC}) as graph {@code index}, with the
     * ids of the nodes whose values it pins to the controller screen.
     */
    public record Save(int containerId, int index, String name, CompoundTag graph,
            List<Integer> pins) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(MaxtechStorage.id("logic_editor_save"));
        public static final StreamCodec<ByteBuf, Save> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Save::containerId, ByteBufCodecs.VAR_INT, Save::index, ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME),
                Save::name, ByteBufCodecs.COMPOUND_TAG, Save::graph,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(GraphSlot.MAX_PINS)), Save::pins, Save::new);

        public Save {
            pins = List.copyOf(pins);
        }

        /** A save that pins nothing. */
        public Save(int containerId, int index, String name, CompoundTag graph) {
            this(containerId, index, name, graph, List.of());
        }

        @Override
        public Type<Save> type() {
            return TYPE;
        }
    }

    /** A label on the controller's network, for the editor's label picker. */
    public record LabelInfo(DeviceLabel label, int devices, boolean switchable) {
        public static final StreamCodec<ByteBuf, LabelInfo> STREAM_CODEC = StreamCodec.composite(
                DeviceLabel.STREAM_CODEC, LabelInfo::label, ByteBufCodecs.VAR_INT, LabelInfo::devices,
                ByteBufCodecs.BOOL, LabelInfo::switchable, LabelInfo::new);
    }

    static final StreamCodec<ByteBuf, EditorNote> NOTE_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
            EditorNote::node, ByteBufCodecs.stringUtf8(MAX_PORT), EditorNote::port, ByteBufCodecs.stringUtf8(MAX_KEY),
            EditorNote::key, ByteBufCodecs.stringUtf8(SettingSpec.MAX_TEXT), EditorNote::arg, ByteBufCodecs.BOOL,
            EditorNote::error, EditorNote::new);

    /**
     * Server to client: graph {@code index} as saved, with its notes, the network's labels and whether someone else
     * edits it ({@code editor} names them; empty when the receiver edits), its pinned nodes and how many may be pinned.
     */
    public record Graph(int containerId, int index, String name, CompoundTag graph, List<EditorNote> notes,
            boolean readOnly, String editor, List<LabelInfo> labels, boolean networkKnown, int maxNodes,
            List<Integer> pins, int maxPins) implements CustomPacketPayload {
        public static final Type<Graph> TYPE = new Type<>(MaxtechStorage.id("logic_editor_graph"));
        public static final StreamCodec<ByteBuf, Graph> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Graph::containerId, ByteBufCodecs.VAR_INT, Graph::index, ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME),
                Graph::name, ByteBufCodecs.COMPOUND_TAG, Graph::graph,
                NOTE_CODEC.apply(ByteBufCodecs.list(EditorNote.MAX)), Graph::notes, ByteBufCodecs.BOOL, Graph::readOnly,
                ByteBufCodecs.stringUtf8(MAX_PORT), Graph::editor,
                LabelInfo.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LABELS)), Graph::labels, ByteBufCodecs.BOOL,
                Graph::networkKnown, ByteBufCodecs.VAR_INT, Graph::maxNodes,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(GraphSlot.MAX_PINS)), Graph::pins, ByteBufCodecs.VAR_INT,
                Graph::maxPins, Graph::new);

        public Graph {
            pins = List.copyOf(pins);
        }

        @Override
        public Type<Graph> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the answer to a {@link Save}. Saved with its notes (errors mean it is saved but paused), or
     * refused with {@code refusal} (a lang key) and nothing changed.
     */
    public record Saved(int containerId, int index, boolean saved, String refusal,
            List<EditorNote> notes) implements CustomPacketPayload {
        public static final Type<Saved> TYPE = new Type<>(MaxtechStorage.id("logic_editor_saved"));
        public static final StreamCodec<ByteBuf, Saved> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Saved::containerId, ByteBufCodecs.VAR_INT, Saved::index, ByteBufCodecs.BOOL, Saved::saved,
                ByteBufCodecs.stringUtf8(MAX_KEY), Saved::refusal, NOTE_CODEC.apply(ByteBufCodecs.list(EditorNote.MAX)),
                Saved::notes, Saved::new);

        @Override
        public Type<Saved> type() {
            return TYPE;
        }
    }

    /** One live value: node {@code node}'s output {@code output}; for events, the run it last fired in. */
    public record LiveValue(int node, String output, long value) {
        public static final StreamCodec<ByteBuf, LiveValue> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                LiveValue::node, ByteBufCodecs.stringUtf8(MAX_PORT), LiveValue::output, ByteBufCodecs.VAR_LONG,
                LiveValue::value, LiveValue::new);
    }

    /**
     * One line of the last run's why trace ({@code RunTrace}): the node, the reason's lang key and its values; a
     * non-empty {@code text} fills the first {@code %s} instead of {@code first}.
     */
    public record Why(int node, String key, long first, long second, String text) {
        public static final StreamCodec<ByteBuf, Why> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Why::node, ByteBufCodecs.stringUtf8(MAX_KEY), Why::key, ByteBufCodecs.VAR_LONG, Why::first,
                ByteBufCodecs.VAR_LONG, Why::second, ByteBufCodecs.stringUtf8(MAX_KEY), Why::text, Why::new);
    }

    /** {@link Live#flags} bit: the graph only simulates its actions. */
    public static final int LIVE_SIMULATE = 1;
    /** {@link Live#flags} bit: the graph has a last working version to revert to. */
    public static final int LIVE_PREVIOUS = 2;
    /**
     * {@link Live#flags} bit, only for a read-only viewer: nobody edits the graph any more, so opening it again makes
     * the viewer its editor ("Edit now").
     */
    public static final int LIVE_LOCK_FREE = 4;

    /**
     * Server to client, only while an editor session is open: the graph's status ({@code LogicWorker.Status#id}), the
     * last run's cost in checks and action points, the run count, the values on its wires, the last run's why trace and
     * the {@code LIVE_*} flags. Sent when something changed, at most every {@link LogicEditing#LIVE_INTERVAL} ticks.
     */
    public record Live(int containerId, int status, int lastCost, int lastPoints, long runs, List<LiveValue> values,
            List<Why> why, int flags) implements CustomPacketPayload {
        public static final Type<Live> TYPE = new Type<>(MaxtechStorage.id("logic_editor_live"));
        public static final StreamCodec<ByteBuf, Live> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Live::containerId, ByteBufCodecs.VAR_INT, Live::status, ByteBufCodecs.VAR_INT, Live::lastCost,
                ByteBufCodecs.VAR_INT, Live::lastPoints, ByteBufCodecs.VAR_LONG, Live::runs,
                LiveValue.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LIVE)), Live::values,
                Why.STREAM_CODEC.apply(ByteBufCodecs.list(16)), Live::why, ByteBufCodecs.VAR_INT, Live::flags,
                Live::new);

        @Override
        public Type<Live> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Open.TYPE, Open.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        Graph graph = LogicEditing.open(server, payload.containerId(), payload.index());
                        if (graph != null) {
                            Platform.get().network().sendToPlayer(server, graph);
                        }
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Close.TYPE, Close.STREAM_CODEC,
                (payload, player) -> LogicEditing.close(player, payload.containerId()));
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Save.TYPE, Save.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        Saved saved = LogicEditing.save(server, payload);
                        if (saved != null) {
                            Platform.get().network().sendToPlayer(server, saved);
                        }
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Graph.TYPE, Graph.STREAM_CODEC,
                (payload, player) -> LogicEditorClient.graph(payload));
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Saved.TYPE, Saved.STREAM_CODEC,
                (payload, player) -> LogicEditorClient.saved(payload));
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Live.TYPE, Live.STREAM_CODEC,
                (payload, player) -> LogicEditorClient.live(payload));
    }
}
