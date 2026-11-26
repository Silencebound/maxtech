package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * A screen's past samples from the server's {@link DisplayHistory}, sent once when a player starts seeing the screen.
 * At most {@link DisplayView#SAMPLES} values.
 */
public record DisplayHistoryUpdate(BlockPos pos, int side, int ticksPerSample,
        LongList samples) implements CustomPacketPayload {
    public static final Type<DisplayHistoryUpdate> TYPE = new Type<>(MaxtechStorage.id("display_history"));
    private static final StreamCodec<ByteBuf, LongList> SAMPLES = new StreamCodec<>() {
        @Override
        public LongList decode(ByteBuf buf) {
            int size = VarInt.read(buf);
            if (size < 0 || size > DisplayView.SAMPLES) {
                throw new DecoderException("Display history of " + size + " samples");
            }
            long[] values = new long[size];
            for (int i = 0; i < size; i++) {
                values[i] = VarLong.read(buf);
            }
            return LongArrayList.wrap(values);
        }

        @Override
        public void encode(ByteBuf buf, LongList values) {
            VarInt.write(buf, values.size());
            for (int i = 0; i < values.size(); i++) {
                VarLong.write(buf, values.getLong(i));
            }
        }
    };
    public static final StreamCodec<ByteBuf, DisplayHistoryUpdate> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DisplayHistoryUpdate::pos,
            ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue), DisplayHistoryUpdate::side,
            ByteBufCodecs.VAR_INT, DisplayHistoryUpdate::ticksPerSample, SAMPLES, DisplayHistoryUpdate::samples,
            DisplayHistoryUpdate::new);

    @Override
    public Type<DisplayHistoryUpdate> type() {
        return TYPE;
    }

    static void handle(DisplayHistoryUpdate update, Player player) {
        DisplayView view = DisplayTargets.view(player.level(), update.pos, update.side);
        if (view != null) {
            view.history(update.samples, update.ticksPerSample);
        }
    }
}
