package dev.silencebound.maxtech.storage.logic.remote;

import dev.silencebound.maxtech.lib.api.carried.CarriedItemMatcher;
import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Pushes a network's new notices to its logic remotes (M7L2 chunk 12). When a line is posted to a network's log, each
 * online player's inventory and accessory slots ({@link CarriedItems}) are looked through once for a logic remote bound
 * to that network that reaches it now (bound, loaded and charged, {@link LogicRemoteItem#reach}) while the player may
 * see the network; the first such remote takes the network's newest notices and the player gets a
 * {@link RemotePayloads.Ping}, which the client shows as a toast unless switched off. A player who got a ping is
 * skipped for {@link #INTERVAL_TICKS}, so a rule that notifies on every run sends at most one ping and one item rewrite
 * per player per interval; the notices posted meanwhile reach the item with the next ping or when the remote's screen
 * opens. Nothing runs between notices. GameTests swap the sender, since a mock player's connection cannot take
 * Storage's payloads. Server thread.
 */
public final class RemoteNotices {
    /** Where a ping goes. */
    public interface Sender {
        void ping(ServerPlayer player, RemotePayloads.Ping ping);
    }

    /** Players without a connection (fake players) are skipped. */
    private static final Sender NETWORK = (player, ping) -> {
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, ping);
        }
    };
    private static Sender sender = NETWORK;
    /** Fewest game ticks between two pings, and two rewrites of the kept notices, for one player. */
    public static final int INTERVAL_TICKS = 40;
    /** Game time of each player's last ping; only players who got one, cleared when the server stops. */
    private static final Object2LongOpenHashMap<UUID> LAST_PING = new Object2LongOpenHashMap<>();

    static {
        LAST_PING.defaultReturnValue(Long.MIN_VALUE / 2);
    }

    private static final CarriedItemMatcher<NetworkState> REACHES = RemoteNotices::reaches;

    private RemoteNotices() {
    }

    /** Sends through {@code replacement}, or through the network again with null (tests). */
    public static void setSender(@Nullable Sender replacement) {
        sender = replacement == null ? NETWORK : replacement;
    }

    /** {@code notice} was posted to {@code network}'s log: the remotes in range keep it and their holders hear it. */
    static void posted(NetworkState network, NetworkNotices.Notice notice) {
        long now = network.level().getGameTime();
        List<ServerPlayer> players = network.level().getServer().getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            UUID id = player.getUUID();
            if (now - LAST_PING.getLong(id) < INTERVAL_TICKS) {
                continue;
            }
            ItemStack remote = remoteFor(player, network);
            if (!remote.isEmpty()) {
                LAST_PING.put(id, now);
                LogicRemoteItem.remember(remote, network.notices().newest(ModDataComponents.REMOTE_NOTICE_LIMIT));
                sender.ping(player, new RemotePayloads.Ping(notice));
            }
        }
    }

    /** Forgets every player's last ping; called when the server stops. */
    static void clear() {
        LAST_PING.clear();
    }

    /**
     * The first logic remote {@code player} carries (inventory, then accessory slots of other mods,
     * {@link CarriedItems}) that is bound to {@code network} and reaches it now, while the player may see the network;
     * empty for none. Looks at each slot once and asks for the controller only for remotes.
     */
    static ItemStack remoteFor(ServerPlayer player, NetworkState network) {
        return CarriedItems.find(player, REACHES, network);
    }

    /** A remote bound to the context network that reaches it now, held by a player who may see the network. */
    @SuppressWarnings("ReferenceEquality") // the network is compared by identity
    private static boolean reaches(Player holder, ItemStack stack, NetworkState network) {
        if (!(stack.getItem() instanceof LogicRemoteItem) || !(holder instanceof ServerPlayer player)) {
            return false;
        }
        GlobalPos link = stack.get(ModDataComponents.WIRELESS_LINK.get());
        ControllerBlockEntity core = link == null ? null : WirelessTerminalItem.core(player, link);
        return core != null && core.network() == network && LogicRemoteItem.reach(player, stack) == Reach.OK
                && network.canAccess(player);
    }
}
