package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.GraphDiff;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * Writes a rule's change history (M7L2 chunk 9): every save from the editor, revert, switch on or off, simulate toggle
 * and add (import, paste, logic card, template) records who did it, when, and in a few words what changed. A save's
 * words come from {@link GraphDiff} plus a rename and changed pins. Runs once per player action, never per tick. Server
 * thread.
 */
public final class RuleHistory {
    private RuleHistory() {
    }

    /**
     * Server to client: rule {@code index}'s history, sent by the open controller menu when it changed (at most every
     * {@link ControllerLog#INTERVAL} ticks); the controller screen and the node editor read it from the client's menu.
     */
    public record Update(int containerId, int index, List<HistoryEntry> entries) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(MaxtechStorage.id("logic_history"));
        public static final StreamCodec<ByteBuf, Update> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Update::containerId, ByteBufCodecs.VAR_INT, Update::index,
                HistoryEntry.STREAM_CODEC.apply(ByteBufCodecs.list(HistoryEntry.HARD_MAX)), Update::entries,
                Update::new);

        public Update {
            entries = List.copyOf(entries);
        }

        @Override
        public Type<Update> type() {
            return TYPE;
        }
    }

    /** Registers the update packet: the client keeps it in its open controller menu. */
    static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Update.TYPE, Update.STREAM_CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof LogicControllerMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.acceptHistory(payload);
                    }
                });
    }

    /** Records {@code changes} as graph {@code index}'s newest history entry, made by {@code player} now. */
    public static void record(LogicWorker worker, int index, Player player, List<GraphChange> changes) {
        worker.record(index, entry(player, changes));
    }

    /** Records a change with one part ({@code LogicLang.HISTORY_*}) and plain-text arguments. */
    public static void record(LogicWorker worker, int index, Player player, String key, String... args) {
        record(worker, index, player, List.of(GraphChange.of(key, args)));
    }

    /** An entry by {@code player} at the current game time and server clock. */
    static HistoryEntry entry(Player player, List<GraphChange> changes) {
        return new HistoryEntry(player.level().getGameTime(), System.currentTimeMillis(), Optional.of(player.getUUID()),
                player.getGameProfile().name(), changes);
    }

    /**
     * What a save from the editor changed: a new rule says how many nodes it was created with; otherwise a rename, the
     * graph's differences ({@link GraphDiff}) and changed pins. "Saved without changes" only when none of these
     * changed.
     */
    static List<GraphChange> saved(boolean created, String oldName, LogicGraph<LogicKey> before, List<Integer> oldPins,
            String newName, LogicGraph<LogicKey> after, List<Integer> newPins) {
        List<GraphChange> out = new ArrayList<>();
        if (created) {
            out.add(GraphChange.of(LogicLang.HISTORY_CREATED, String.valueOf(after.nodes().size())));
            return out;
        }
        if (!oldName.equals(newName)) {
            out.add(GraphChange.of(LogicLang.HISTORY_RENAMED, shown(oldName), shown(newName)));
        }
        List<GraphChange> diff = GraphDiff.of(before, after, RuleHistory::item);
        boolean graphSame = diff.size() == 1 && diff.get(0).key().equals(LogicLang.HISTORY_NO_CHANGE);
        boolean pins = !oldPins.equals(newPins);
        if (!graphSame || (out.isEmpty() && !pins)) {
            out.addAll(diff);
        }
        if (pins) {
            out.add(GraphChange.of(LogicLang.HISTORY_PINS));
        }
        return out;
    }

    private static String shown(String name) {
        return "\"" + name + "\"";
    }

    /** An item setting as the history shows it: an item by its lang key, anything else as its id or tag. */
    static GraphChange.Arg item(LogicKey key) {
        return switch (key) {
            case LogicKey.Stack(StackKey stack) -> stack instanceof ItemKey item
                    ? GraphChange.Arg.key(item.item().getDescriptionId())
                    : GraphChange.Arg.text(stack.toString());
            case LogicKey.Tag(var tag) -> GraphChange.Arg.text("#" + tag.location());
            case LogicKey.FluidTag(var tag) -> GraphChange.Arg.text("#" + tag.location());
        };
    }
}
