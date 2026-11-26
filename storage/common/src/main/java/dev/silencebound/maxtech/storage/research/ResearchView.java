package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * What the research screen shows of one team, sent to the player whose research menu is open (server to client): the
 * team's points and tokens, and per node of the tree (by id, in the tree's order) its state and each option's progress.
 * The client draws names, icons and branches from its own synced copy of the tree. Small: one entry per node with a few
 * longs each, sent only when the team's progress changed. Immutable.
 *
 * @param containerId
 *            the menu it is for; the client drops it for any other
 */
public record ResearchView(int containerId, long points, int tokens, long tokenProgress, long pointsPerToken,
        boolean off, List<Node> nodes) implements CustomPacketPayload {
    /** Most nodes a view carries; a tree beyond that is cut off rather than sent whole. */
    public static final int MAX_NODES = 1024;
    public static final Type<ResearchView> TYPE = new Type<>(MaxtechStorage.id("research_view"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ResearchView::containerId, ByteBufCodecs.VAR_LONG, ResearchView::points,
            ByteBufCodecs.VAR_INT, ResearchView::tokens, ByteBufCodecs.VAR_LONG, ResearchView::tokenProgress,
            ByteBufCodecs.VAR_LONG, ResearchView::pointsPerToken, ByteBufCodecs.BOOL, ResearchView::off,
            Node.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_NODES)), ResearchView::nodes, ResearchView::new);

    /** A node's state for the team. */
    public enum State {
        LOCKED, AVAILABLE, COMPLETE;

        static State of(int id) {
            return id == 2 ? COMPLETE : id == 1 ? AVAILABLE : LOCKED;
        }

        int id() {
            return this == COMPLETE ? 2 : this == AVAILABLE ? 1 : 0;
        }
    }

    /**
     * One node: its state and, per option in the node's order, the progress and the target (a task's counter and
     * target, a points option's team points and cost, a puzzle's 0 and 1) and whether it is offered (a task's item
     * exists, a puzzle is registered).
     */
    public record Node(Identifier id, State state, List<Option> options) {
        static final StreamCodec<RegistryFriendlyByteBuf, Node> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Node::id, ByteBufCodecs.VAR_INT.map(State::of, State::id), Node::state,
                Option.STREAM_CODEC.apply(ByteBufCodecs.list(ResearchNode.MAX_ENTRIES)), Node::options, Node::new);
    }

    /** One option's progress. */
    public record Option(boolean offered, long progress, long target) {
        static final StreamCodec<RegistryFriendlyByteBuf, Option> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Option::offered, ByteBufCodecs.VAR_LONG, Option::progress, ByteBufCodecs.VAR_LONG,
                Option::target, Option::new);

        /** How far along, from 0 to 1. */
        public float fraction() {
            return target <= 0 ? 0 : (float) Math.min(1.0, (double) progress / target);
        }
    }

    /** Builds the view of {@code team} for menu {@code containerId}. */
    public static ResearchView of(MinecraftServer server, ResearchTeam team, int containerId) {
        ResearchTree tree = Research.tree(server);
        List<Node> nodes = new ArrayList<>();
        for (ResearchTree.Entry entry : tree.entries()) {
            if (nodes.size() == MAX_NODES) {
                break;
            }
            State state = Research.isComplete(team, entry)
                    ? State.COMPLETE
                    : Research.isAvailable(server, team, entry) ? State.AVAILABLE : State.LOCKED;
            List<Option> options = new ArrayList<>();
            for (ResearchOption option : entry.node().anyOf()) {
                options.add(switch (option) {
                    case ResearchOption.Task task ->
                        new Option(Research.offered(task), team.counter(task.counter()), Research.target(task));
                    case ResearchOption.Puzzle puzzle -> new Option(Research.offered(puzzle), 0, 1);
                    case ResearchOption.Points points -> new Option(true, team.points(), Research.cost(points));
                });
            }
            nodes.add(new Node(entry.id(), state, options));
        }
        return new ResearchView(containerId, team.points(), team.tokens(), team.tokenProgress(),
                Research.pointsPerToken(), !Research.active(), nodes);
    }

    @Override
    public Type<ResearchView> type() {
        return TYPE;
    }
}
