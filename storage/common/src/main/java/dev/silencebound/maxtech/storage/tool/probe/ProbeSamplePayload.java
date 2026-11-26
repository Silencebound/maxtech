package dev.silencebound.maxtech.storage.tool.probe;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One probe sample (server to client), sent to the one player whose probe screen is open, once per sample interval.
 *
 * @param containerId
 *            the menu it is for; the client drops it for any other
 * @param load
 *            0 to 100, or {@link ProbeSeries#NO_SIGNAL}
 * @param rate
 *            operations per second of the probed link
 * @param energy
 *            whether the block has a MaxTech energy network to show
 * @param supply
 *            that network's supply, MU per tick
 * @param demand
 *            its demand, MU per tick
 * @param received
 *            what its consumers receive, MU per tick
 */
public record ProbeSamplePayload(int containerId, int load, long rate, boolean energy, long supply, long demand,
        long received) implements CustomPacketPayload {
    public static final Type<ProbeSamplePayload> TYPE = new Type<>(MaxtechStorage.id("probe_sample"));
    public static final StreamCodec<FriendlyByteBuf, ProbeSamplePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ProbeSamplePayload::containerId, ByteBufCodecs.VAR_INT, ProbeSamplePayload::load,
            ByteBufCodecs.VAR_LONG, ProbeSamplePayload::rate, ByteBufCodecs.BOOL, ProbeSamplePayload::energy,
            ByteBufCodecs.VAR_LONG, ProbeSamplePayload::supply, ByteBufCodecs.VAR_LONG, ProbeSamplePayload::demand,
            ByteBufCodecs.VAR_LONG, ProbeSamplePayload::received, ProbeSamplePayload::new);

    @Override
    public Type<ProbeSamplePayload> type() {
        return TYPE;
    }
}
