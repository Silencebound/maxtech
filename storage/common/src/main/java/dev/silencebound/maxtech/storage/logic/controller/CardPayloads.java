package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.controller.client.LogicShareClient;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The controller screen's card view packets (M7L2 chunk 11). The screen asks with {@link Select} for the rule it shows
 * as a card; the open menu answers once with the rule's {@link Card} (the graph as saved, encoded like the editor's, so
 * the same codec caps and NBT quota bound it, and its live values), at most every {@link #INTERVAL} ticks however often
 * the screen asks. After that only {@link Values} follow, and only when the controller's log is rebuilt because a graph
 * ran or was saved; a save of the shown rule sends its card again. Nothing is sent per tick. The select request is
 * checked against the sender's open {@link LogicControllerMenu} (container id, reach and the logic access level).
 *
 * <p>
 * Edits on the card ({@link Edit}) name one edit, which the server applies to the rule as saved
 * ({@code LogicEditing#cardEdit}, through {@code CardEdits}) and saves like an editor save; {@link Edited} answers
 * each, and the card follows. A value travels as a compound tag the server decodes in its handler, as the editor's
 * graph does.
 */
public final class CardPayloads {
    /** Ticks between two cards sent to one open screen. */
    public static final int INTERVAL = 4;
    /** {@link Edit#op}: set setting {@code setting} of node {@code node} to {@code value}. */
    public static final int OP_SET = 0;
    /**
     * {@link Edit#op}: remove the line of section {@code part} (a {@code RuleCard.Section} name) about node
     * {@code node}.
     */
    public static final int OP_REMOVE = 1;
    /**
     * {@link Edit#op}: add a node of type {@code nodeType} with the "+" button {@code part} (a {@code CardEdits.Add}
     * name).
     */
    public static final int OP_ADD = 2;
    /** {@link Edit#op}: start a new rule, switched off, as an empty card at row {@code index} (the rule count). */
    public static final int OP_NEW = 3;
    private static final int MAX_KEY = 256;
    private static final int MAX_ID = 64;
    private static final int MAX_NAME = 32;

    private CardPayloads() {
    }

    /** Client to server: show rule {@code index} of the open controller as a card. */
    public record Select(int containerId, int index) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(MaxtechStorage.id("logic_card_select"));
        public static final StreamCodec<ByteBuf, Select> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Select::containerId, ByteBufCodecs.VAR_INT, Select::index, Select::new);

        @Override
        public Type<Select> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: rule {@code index}'s graph as saved (encoded with {@link LogicKey#GRAPH_CODEC}), its live
     * values now, and for editing on the card the network's labels and the node limit.
     */
    public record Card(int containerId, int index, CompoundTag graph, List<EditorPayloads.LiveValue> values,
            List<EditorPayloads.LabelInfo> labels, int maxNodes) implements CustomPacketPayload {
        public static final Type<Card> TYPE = new Type<>(MaxtechStorage.id("logic_card"));
        public static final StreamCodec<ByteBuf, Card> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Card::containerId, ByteBufCodecs.VAR_INT, Card::index, ByteBufCodecs.COMPOUND_TAG, Card::graph,
                EditorPayloads.LiveValue.STREAM_CODEC.apply(ByteBufCodecs.list(EditorPayloads.MAX_LIVE)), Card::values,
                EditorPayloads.LabelInfo.STREAM_CODEC.apply(ByteBufCodecs.list(EditorPayloads.MAX_LABELS)),
                Card::labels, ByteBufCodecs.VAR_INT, Card::maxNodes, Card::new);

        public Card {
            values = List.copyOf(values);
            labels = List.copyOf(labels);
        }

        @Override
        public Type<Card> type() {
            return TYPE;
        }
    }

    /** Server to client: the live values of the card's rule {@code index} after a run. */
    public record Values(int containerId, int index,
            List<EditorPayloads.LiveValue> values) implements CustomPacketPayload {
        public static final Type<Values> TYPE = new Type<>(MaxtechStorage.id("logic_card_values"));
        public static final StreamCodec<ByteBuf, Values> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Values::containerId, ByteBufCodecs.VAR_INT, Values::index,
                EditorPayloads.LiveValue.STREAM_CODEC.apply(ByteBufCodecs.list(EditorPayloads.MAX_LIVE)),
                Values::values, Values::new);

        public Values {
            values = List.copyOf(values);
        }

        @Override
        public Type<Values> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: one edit on rule {@code index}'s card. {@code op} is one of the {@code OP_*} constants;
     * {@code part} is the section of a removed line or the "+" button of an add; {@code value} is a set value encoded
     * with {@code SettingValue.codec(LogicKey.CODEC)} (empty otherwise); {@code nodeType} is an added node's type id.
     */
    public record Edit(int containerId, int index, int op, int node, String part, String setting, CompoundTag value,
            String nodeType) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(MaxtechStorage.id("logic_card_edit"));
        public static final StreamCodec<ByteBuf, Edit> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Edit::containerId, ByteBufCodecs.VAR_INT, Edit::index, ByteBufCodecs.VAR_INT, Edit::op,
                ByteBufCodecs.VAR_INT, Edit::node, ByteBufCodecs.stringUtf8(MAX_NAME), Edit::part,
                ByteBufCodecs.stringUtf8(MAX_NAME), Edit::setting, ByteBufCodecs.COMPOUND_TAG, Edit::value,
                ByteBufCodecs.stringUtf8(MAX_ID), Edit::nodeType, Edit::new);

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the answer to an {@link Edit} on rule {@code index}: done, or refused with {@code refusal} (a
     * lang key) and its arguments (who edits the rule, the node limit) and nothing changed.
     */
    public record Edited(int containerId, int index, boolean done, String refusal,
            List<String> args) implements CustomPacketPayload {
        public static final Type<Edited> TYPE = new Type<>(MaxtechStorage.id("logic_card_edited"));
        public static final StreamCodec<ByteBuf, Edited> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Edited::containerId, ByteBufCodecs.VAR_INT, Edited::index, ByteBufCodecs.BOOL, Edited::done,
                ByteBufCodecs.stringUtf8(MAX_KEY), Edited::refusal,
                ByteBufCodecs.stringUtf8(MAX_KEY).apply(ByteBufCodecs.list(4)), Edited::args, Edited::new);

        public Edited {
            args = List.copyOf(args);
        }

        @Override
        public Type<Edited> type() {
            return TYPE;
        }
    }

    /** Registers the packets; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Edit.TYPE, Edit.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        Edited answer = LogicEditing.cardEdit(server, payload);
                        if (answer != null) {
                            Platform.get().network().sendToPlayer(server, answer);
                        }
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Edited.TYPE, Edited.STREAM_CODEC,
                (payload, player) -> LogicShareClient.cardEdited(payload));
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Select.TYPE, Select.STREAM_CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof LogicControllerMenu menu
                            && menu.containerId == payload.containerId() && menu.controller() != null
                            && menu.stillValid(player)) {
                        menu.selectCard(payload.index());
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Card.TYPE, Card.STREAM_CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof LogicControllerMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.acceptCard(payload, player.registryAccess());
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Values.TYPE, Values.STREAM_CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof LogicControllerMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.acceptValues(payload);
                    }
                });
    }
}
