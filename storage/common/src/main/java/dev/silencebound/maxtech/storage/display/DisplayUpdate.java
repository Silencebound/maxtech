package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * One value for one screen, server to client: a few bytes, sent only to the players who can see the screen, only when
 * the value or status changed and at most every {@code display.updateTicks} ticks ({@link ScreenWorker}).
 *
 * @param side
 *            the cable face of a part screen, {@code -1} for a panel block (the wall's anchor)
 * @param status
 *            {@link #OFFLINE}, {@link #ONLINE} or {@link #STOPPED}
 */
public record DisplayUpdate(BlockPos pos, int side, int status, long value, long max) implements CustomPacketPayload {
    /** Nothing received yet (client only). */
    public static final int NO_DATA = -1;
    /** The screen is in no network. */
    public static final int OFFLINE = 0;
    /** The network works. */
    public static final int ONLINE = 1;
    /** The network is stopped or unpowered; the value is still its latest. */
    public static final int STOPPED = 2;

    public static final Type<DisplayUpdate> TYPE = new Type<>(MaxtechStorage.id("display_update"));
    public static final StreamCodec<ByteBuf, DisplayUpdate> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
            DisplayUpdate::pos, ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue), DisplayUpdate::side,
            ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue), DisplayUpdate::status, ByteBufCodecs.VAR_LONG,
            DisplayUpdate::value, ByteBufCodecs.VAR_LONG, DisplayUpdate::max, DisplayUpdate::new);

    @Override
    public Type<DisplayUpdate> type() {
        return TYPE;
    }

    /** Client: hands the value to the screen at the address, if the client has it. */
    static void handle(DisplayUpdate update, Player player) {
        DisplayView view = DisplayTargets.view(player.level(), update.pos, update.side);
        if (view != null) {
            view.update(update.status, update.value, update.max);
        }
    }
}
