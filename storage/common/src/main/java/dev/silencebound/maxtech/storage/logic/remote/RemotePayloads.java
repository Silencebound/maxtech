package dev.silencebound.maxtech.storage.logic.remote;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.remote.client.RemoteClient;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The logic remote's packets (M7L2 chunk 12). The server sends an open remote screen what it shows ({@link View}) when
 * it changed, and the answer to a pause or resume ({@link Answer}); the client asks for a pause or resume
 * ({@link SetRule}), naming the open menu's container id, the controller's position and the rule's index. The server
 * acts only for the sender's open remote menu with that id, one request per player per tick, and checks reach, power
 * and the logic access level itself ({@link LogicRemoteMenu#setRule}). A {@link Ping} carries a new notice to a holder
 * in range ({@link RemoteNotices}).
 */
public final class RemotePayloads {
    /** Most rules one view lists, over all the network's logic controllers. */
    public static final int MAX_RULES = 64;
    /** A reach by name, so the wire does not depend on the enum's order; an unknown name reads as no network. */
    static final StreamCodec<ByteBuf, Reach> REACH_CODEC = ByteBufCodecs.stringUtf8(32).map(RemotePayloads::reachOf,
            Reach::name);
    /** A result by name; an unknown name reads as no such rule. */
    static final StreamCodec<ByteBuf, RemoteResult> RESULT_CODEC = ByteBufCodecs.stringUtf8(32)
            .map(RemoteResult::byName, RemoteResult::name);
    /** Game tick of each player's last request; only players who used a remote, cleared when the server stops. */
    private static final Object2LongOpenHashMap<UUID> LAST_REQUEST = new Object2LongOpenHashMap<>();

    static {
        LAST_REQUEST.defaultReturnValue(Long.MIN_VALUE);
    }

    private RemotePayloads() {
    }

    /**
     * One rule as the remote lists it.
     *
     * @param controller
     *            the logic controller's position
     * @param graph
     *            the rule's index in that controller
     * @param label
     *            the controller's label; empty without one
     * @param name
     *            the rule's name; empty without one
     * @param status
     *            its status ({@code LogicWorker.Status#id})
     * @param enabled
     *            whether it is switched on (a rule with errors is on but paused)
     */
    public record Rule(BlockPos controller, int graph, String label, String name, int status, boolean enabled) {
        public static final StreamCodec<ByteBuf, Rule> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
                Rule::controller, ByteBufCodecs.VAR_INT, Rule::graph, ByteBufCodecs.stringUtf8(DeviceLabel.MAX_LENGTH),
                Rule::label, ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME), Rule::name, ByteBufCodecs.VAR_INT,
                Rule::status, ByteBufCodecs.BOOL, Rule::enabled, Rule::new);
    }

    /**
     * Server to client: what open remote menu {@code containerId} shows.
     *
     * @param reach
     *            how the remote reaches its network
     * @param energy
     *            FE in the remote
     * @param logic
     *            whether the player may see and change the rules; false out of reach
     * @param notices
     *            the network's newest notices, oldest first; the remote's kept ones out of reach
     * @param rules
     *            the network's rules; empty out of reach or without logic access
     */
    public record View(int containerId, Reach reach, int energy, boolean logic, List<NetworkNotices.Notice> notices,
            List<Rule> rules) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(MaxtechStorage.id("logic_remote_view"));
        public static final StreamCodec<ByteBuf, View> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                View::containerId, REACH_CODEC, View::reach, ByteBufCodecs.VAR_INT, View::energy, ByteBufCodecs.BOOL,
                View::logic,
                NetworkNotices.Notice.STREAM_CODEC.apply(ByteBufCodecs.list(ModDataComponents.REMOTE_NOTICE_LIMIT)),
                View::notices, Rule.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_RULES)), View::rules, View::new);

        public View {
            notices = List.copyOf(notices);
            rules = List.copyOf(rules);
        }

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    /** Client to server: switch rule {@code graph} of the logic controller at {@code controller} on or off. */
    public record SetRule(int containerId, BlockPos controller, int graph,
            boolean enabled) implements CustomPacketPayload {
        public static final Type<SetRule> TYPE = new Type<>(MaxtechStorage.id("logic_remote_set_rule"));
        public static final StreamCodec<ByteBuf, SetRule> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                SetRule::containerId, BlockPos.STREAM_CODEC, SetRule::controller, ByteBufCodecs.VAR_INT, SetRule::graph,
                ByteBufCodecs.BOOL, SetRule::enabled, SetRule::new);

        @Override
        public Type<SetRule> type() {
            return TYPE;
        }
    }

    /** Server to client: what came of the last {@link SetRule} of open remote menu {@code containerId}. */
    public record Answer(int containerId, RemoteResult result) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(MaxtechStorage.id("logic_remote_answer"));
        public static final StreamCodec<ByteBuf, Answer> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Answer::containerId, RESULT_CODEC, Answer::result, Answer::new);

        @Override
        public Type<Answer> type() {
            return TYPE;
        }
    }

    /** Server to client: a new notice of the network a remote in the player's inventory is bound to. */
    public record Ping(NetworkNotices.Notice notice) implements CustomPacketPayload {
        public static final Type<Ping> TYPE = new Type<>(MaxtechStorage.id("logic_remote_ping"));
        public static final StreamCodec<ByteBuf, Ping> STREAM_CODEC = StreamCodec
                .composite(NetworkNotices.Notice.STREAM_CODEC, Ping::notice, Ping::new);

        @Override
        public Type<Ping> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; called once during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, SetRule.TYPE, SetRule.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        RemoteResult result = handle(payload, server);
                        if (result != null && server.connection != null) {
                            Platform.get().network().sendToPlayer(server, new Answer(payload.containerId(), result));
                        }
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, View.TYPE, View.STREAM_CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof LogicRemoteMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.accept(payload);
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Answer.TYPE, Answer.STREAM_CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof LogicRemoteMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.answered(payload.result());
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Ping.TYPE, Ping.STREAM_CODEC,
                (payload, player) -> RemoteClient.ping(payload));
        Platform.get().lifecycle().onServerStopped(server -> LAST_REQUEST.clear());
    }

    /** The reach named {@code name}; {@link Reach#NO_NETWORK} for an unknown one. */
    static Reach reachOf(String name) {
        for (Reach reach : Reach.values()) {
            if (reach.name().equals(name)) {
                return reach;
            }
        }
        return Reach.NO_NETWORK;
    }

    /**
     * Carries out {@code payload} from {@code player}: only for the player's open remote menu with the payload's
     * container id and at most once per player per tick. Returns what came of it, or null when it was ignored (no
     * answer is sent then). Server thread.
     */
    public static @Nullable RemoteResult handle(SetRule payload, ServerPlayer player) {
        if (!(player.containerMenu instanceof LogicRemoteMenu menu) || menu.containerId != payload.containerId()
                || !allowed(player)) {
            return null;
        }
        return menu.setRule(player, payload.controller(), payload.graph(), payload.enabled());
    }

    /** One request per player per tick. */
    private static boolean allowed(ServerPlayer player) {
        long tick = player.level().getGameTime();
        long last = LAST_REQUEST.put(player.getUUID(), tick);
        return last != tick;
    }
}
