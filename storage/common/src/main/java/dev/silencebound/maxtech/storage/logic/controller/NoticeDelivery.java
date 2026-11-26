package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.logic.LogicContext;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Sends a notify node's toast and chat channels (M7L) to the online players of the controller owner's team
 * ({@link TeamBudget#team}). Unowned controllers send nothing. GameTests swap the sender to see who would get what,
 * since a mock player's connection cannot take Storage's payloads. Server thread.
 */
public final class NoticeDelivery {
    /** Where a toast or chat line goes. */
    public interface Sender {
        void toast(ServerPlayer player, LogicNoticePayload payload);

        void chat(ServerPlayer player, Component line);
    }

    /** Players without a connection (fake players) are skipped. */
    private static final Sender NETWORK = new Sender() {
        @Override
        public void toast(ServerPlayer player, LogicNoticePayload payload) {
            if (player.connection != null) {
                Platform.get().network().sendToPlayer(player, payload);
            }
        }

        @Override
        public void chat(ServerPlayer player, Component line) {
            if (player.connection != null) {
                player.sendSystemMessage(line);
            }
        }
    };
    private static Sender sender = NETWORK;

    private NoticeDelivery() {
    }

    /** Sends through {@code replacement}, or through the network again with null (tests). */
    public static void setSender(@Nullable Sender replacement) {
        sender = replacement == null ? NETWORK : replacement;
    }

    /**
     * Sends {@code text} as a toast and/or a chat line, as {@code channels} asks, to every online player on the team of
     * {@code device}'s owner. Returns the players reached.
     */
    static int deliver(ServerLevel level, NetworkDevice device, String text, int channels) {
        boolean toast = (channels & LogicContext.CHANNEL_TOAST) != 0;
        boolean chat = (channels & LogicContext.CHANNEL_CHAT) != 0;
        if (!toast && !chat) {
            return 0;
        }
        UUID team = TeamBudget.team(level, device);
        if (team.equals(TeamBudget.UNOWNED)) {
            return 0;
        }
        int reached = 0;
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            if (!team.equals(TeamBudget.team(level, OwnerRecord.of(player)))) {
                continue;
            }
            reached++;
            if (toast) {
                sender.toast(player, new LogicNoticePayload(text));
            }
            if (chat) {
                sender.chat(player, Component.translatable(ControllerTexts.CHAT, text));
            }
        }
        return reached;
    }
}
