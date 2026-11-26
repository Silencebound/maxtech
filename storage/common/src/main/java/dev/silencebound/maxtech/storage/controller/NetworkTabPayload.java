package dev.silencebound.maxtech.storage.controller;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The controller screen's Network tab content (server to client), sent to the one player whose controller menu is open
 * when it opens and then only when the overview changed, at most once per {@link ControllerMenu#REFRESH_TICKS}.
 *
 * @param containerId
 *            the menu it is for; the client drops it for any other
 */
public record NetworkTabPayload(int containerId, NetworkOverview overview) implements CustomPacketPayload {
    public static final Type<NetworkTabPayload> TYPE = new Type<>(MaxtechStorage.id("network_tab"));
    public static final StreamCodec<FriendlyByteBuf, NetworkTabPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NetworkTabPayload::containerId, NetworkOverview.STREAM_CODEC,
            NetworkTabPayload::overview, NetworkTabPayload::new);

    @Override
    public Type<NetworkTabPayload> type() {
        return TYPE;
    }
}
