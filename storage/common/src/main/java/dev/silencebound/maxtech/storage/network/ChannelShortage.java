package dev.silencebound.maxtech.storage.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Why a device is offline for channels (M7N, PRD 7.2 Channels with hubs): the full link on its route, how many channels
 * that link carries and how many are in use. Immutable; made when channels are counted, never per transfer.
 *
 * @param kind
 *            what is full
 * @param pos
 *            the full cable or hub, the device at the near end of a full link or the outside end of a full root link;
 *            {@code Long.MIN_VALUE} for {@link Kind#AD_HOC} and {@link Kind#NO_ROUTE}
 * @param used
 *            channels in use there
 * @param capacity
 *            channels it carries
 */
public record ChannelShortage(Kind kind, long pos, int used, int capacity) {
    /** A shortage with no place and no numbers: the device has no route at all. */
    public static final ChannelShortage NO_ROUTE = new ChannelShortage(Kind.NO_ROUTE, Long.MIN_VALUE, 0, 0);
    /** For a device screen's status tab; an unknown kind reads as {@link Kind#NO_ROUTE}. */
    public static final StreamCodec<ByteBuf, ChannelShortage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(ChannelShortage::kindOf, Kind::ordinal), ChannelShortage::kind,
            ByteBufCodecs.LONG, ChannelShortage::pos, ByteBufCodecs.VAR_INT, ChannelShortage::used,
            ByteBufCodecs.VAR_INT, ChannelShortage::capacity, ChannelShortage::new);

    private static Kind kindOf(int ordinal) {
        Kind[] kinds = Kind.values();
        return ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal] : Kind.NO_ROUTE;
    }

    /** What ran out of channels. */
    public enum Kind {
        /** A storage cable on the route; {@code pos} is the cable. */
        CABLE,
        /** A link between two devices (touching or an antenna pair); {@code pos} is the device nearer the device. */
        LINK,
        /** One of the controller's root links; {@code pos} is its outside end. */
        ROOT_LINK,
        /** The allowance of a network without a controller ({@code network.channels.adHoc}). */
        AD_HOC,
        /** No route to the root: past the team's zone cap, or behind a root link the controller does not allow. */
        NO_ROUTE,
        /** A hub on the route ({@link ChannelHub}); {@code pos} is the hub. Last, so older ordinals keep their kind. */
        HUB
    }
}
