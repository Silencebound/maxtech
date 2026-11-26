package dev.silencebound.maxtech.lib.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.player.Player;

/**
 * Play-phase custom payloads. Registration must happen during mod construction; the channel version is the mod's
 * version, so client and server with different mod versions refuse to connect instead of misreading packets. Handlers
 * run on the main thread of the receiving side.
 */
public interface NetworkHelper {
    <T extends CustomPacketPayload> void registerToClient(String modId, CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, PayloadHandler<T> handler);

    <T extends CustomPacketPayload> void registerToServer(String modId, CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, PayloadHandler<T> handler);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Client side only. */
    void sendToServer(CustomPacketPayload payload);

    /**
     * Sends {@code payload} to every player whose client has the chunk at {@code chunkPos} loaded (for traffic that is
     * batched per chunk section, PRD 7.1). Server thread.
     */
    void sendToTrackingChunk(ServerLevel level, ChunkPos chunkPos, CustomPacketPayload payload);

    @FunctionalInterface
    interface PayloadHandler<T> {
        /** {@code player} is the sender on the server, and the local player on the client. */
        void handle(T payload, Player player);
    }
}
