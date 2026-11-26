package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Sends screen payloads; GameTests swap the sender to count what would go to whom. Server thread. */
public final class DisplaySync {
    /** Where a payload goes. */
    @FunctionalInterface
    public interface Sender {
        void send(ServerPlayer player, CustomPacketPayload payload);
    }

    /** Players without a connection (fake players) are skipped. */
    private static final Sender NETWORK = (player, payload) -> {
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, payload);
        }
    };
    private static Sender sender = NETWORK;

    private DisplaySync() {
    }

    /** Registers the screen payloads; during mod construction. */
    static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, DisplayUpdate.TYPE, DisplayUpdate.STREAM_CODEC,
                DisplayUpdate::handle);
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, DisplayHistoryUpdate.TYPE,
                DisplayHistoryUpdate.STREAM_CODEC, DisplayHistoryUpdate::handle);
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, DisplayNoticesUpdate.TYPE,
                DisplayNoticesUpdate.STREAM_CODEC, DisplayNoticesUpdate::handle);
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, DisplayValuesUpdate.TYPE,
                DisplayValuesUpdate.STREAM_CODEC, DisplayValuesUpdate::handle);
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, DisplayRowsUpdate.TYPE,
                DisplayRowsUpdate.STREAM_CODEC, DisplayRowsUpdate::handle);
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, DisplayTextSave.TYPE,
                DisplayTextSave.STREAM_CODEC, DisplayTextSave::handle);
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, DisplayChartSave.TYPE,
                DisplayChartSave.STREAM_CODEC, DisplayChartSave::handle);
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, DisplayListSave.TYPE,
                DisplayListSave.STREAM_CODEC, DisplayListSave::handle);
    }

    /** Sends through {@code replacement} instead of the network, or through the network again with null (tests). */
    public static void setSender(@Nullable Sender replacement) {
        sender = replacement == null ? NETWORK : replacement;
    }

    static void send(ServerPlayer player, CustomPacketPayload payload) {
        sender.send(player, payload);
    }
}
