package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's network log tab (M7L): the client asks for the network's logic log (the lines the controller's Network
 * tab lists) and the server answers with all of them. Every payload names the terminal menu's container id; the server
 * answers only for the sender's open, valid terminal menu with that id on a network the player has view access to, as
 * for the terminal itself. One request per player per tick.
 */
public final class NetworkLogPayloads {
    /** The last answer the client got; client side only, null before the first. */
    private static volatile @Nullable Log received;
    /** Game tick of each player's last request; only players who used the tab, cleared when the server stops. */
    private static final Object2LongOpenHashMap<UUID> LAST_REQUEST = new Object2LongOpenHashMap<>();

    static {
        LAST_REQUEST.defaultReturnValue(Long.MIN_VALUE);
    }

    private NetworkLogPayloads() {
    }

    /** Client to server: send the log of terminal menu {@code containerId}'s network. */
    public record Ask(int containerId) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(MaxtechStorage.id("network_log_ask"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ask> CODEC = StreamCodec
                .composite(ByteBufCodecs.VAR_INT, Ask::containerId, Ask::new);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    /** Server to client: the network's log, oldest line first. */
    public record Log(int containerId, List<NetworkNotices.Notice> lines) implements CustomPacketPayload {
        public static final Type<Log> TYPE = new Type<>(MaxtechStorage.id("network_log"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Log> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Log::containerId,
                NetworkNotices.Notice.STREAM_CODEC.apply(ByteBufCodecs.list(NetworkNotices.CAPACITY)), Log::lines,
                Log::new);

        public Log {
            lines = List.copyOf(lines);
        }

        @Override
        public Type<Log> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; called once during mod construction. */
    static void init() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Ask.TYPE, Ask.CODEC, (payload, player) -> {
            CraftingSession session = RequestContent.session(player, payload.containerId());
            if (session != null && allowed(player)) {
                answer(session);
            }
        });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Log.TYPE, Log.CODEC, (payload, player) -> {
            if (player.containerMenu instanceof TerminalMenu menu && menu.containerId == payload.containerId()) {
                received = payload;
            }
        });
        Platform.get().lifecycle().onServerStopped(server -> LAST_REQUEST.clear());
    }

    /** The last log the client received for menu {@code containerId}; null when none came for it. */
    public static @Nullable Log received(int containerId) {
        Log last = received;
        return last != null && last.containerId() == containerId ? last : null;
    }

    private static boolean allowed(Player player) {
        long tick = player.level().getGameTime();
        long last = LAST_REQUEST.put(player.getUUID(), tick);
        return last != tick;
    }

    /**
     * Answers with {@code session}'s network's log; false (nothing sent) without a network or without view access.
     */
    public static boolean answer(CraftingSession session) {
        NetworkState network = session.network();
        if (network == null || !network.accessLevel(session.player()).atLeast(AccessLevel.VIEW)) {
            return false;
        }
        session.send(new Log(session.containerId(), network.notices().lines()));
        return true;
    }
}
