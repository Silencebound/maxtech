package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * The placeholder values of one screen showing {@link DisplaySourceKind#TEXT}, server to client, in the order of the
 * text's placeholders ({@link RichText#values()}), at most {@link RichText#MAX_VALUES}. Sent by {@link ScreenWorker}
 * only to the players who can see the screen, when a value changed or a player started seeing it.
 */
public record DisplayValuesUpdate(BlockPos pos, int side, LongList values) implements CustomPacketPayload {
    public static final Type<DisplayValuesUpdate> TYPE = new Type<>(MaxtechStorage.id("display_values"));
    private static final StreamCodec<ByteBuf, LongList> VALUES = new StreamCodec<>() {
        @Override
        public LongList decode(ByteBuf buf) {
            int size = VarInt.read(buf);
            if (size < 0 || size > RichText.MAX_VALUES) {
                throw new DecoderException("Display text with " + size + " values");
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
    public static final StreamCodec<ByteBuf, DisplayValuesUpdate> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DisplayValuesUpdate::pos, ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue),
            DisplayValuesUpdate::side, VALUES, DisplayValuesUpdate::values, DisplayValuesUpdate::new);

    @Override
    public Type<DisplayValuesUpdate> type() {
        return TYPE;
    }

    static void handle(DisplayValuesUpdate update, Player player) {
        DisplayView view = DisplayTargets.view(player.level(), update.pos, update.side);
        if (view != null) {
            view.values(update.values);
        }
    }
}
