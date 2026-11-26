package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.List;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the sparklines of the pinned values that have recorded history ({@link PinHistory}), for the open
 * controller screen {@code containerId}. Sent when the screen opens and after each sample while it stays open; a pinned
 * value without history has no entry.
 */
public record PinSparks(int containerId, List<Spark> sparks) implements CustomPacketPayload {
    public static final Type<PinSparks> TYPE = new Type<>(MaxtechStorage.id("logic_pin_sparks"));

    /** One sparkline: graph {@code graph}'s pinned value {@code pin}, oldest point first, gaps as Long.MIN_VALUE. */
    public record Spark(int graph, int pin, LongList points) {
        private static final StreamCodec<ByteBuf, LongList> POINTS = new StreamCodec<>() {
            @Override
            public LongList decode(ByteBuf buf) {
                int size = VarInt.read(buf);
                if (size < 0 || size > PinHistory.SPARK_POINTS) {
                    throw new DecoderException("Sparkline of " + size + " points");
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
        static final StreamCodec<ByteBuf, Spark> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Spark::graph, ByteBufCodecs.VAR_INT, Spark::pin, POINTS, Spark::points, Spark::new);
    }

    public static final StreamCodec<ByteBuf, PinSparks> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
            PinSparks::containerId,
            Spark.STREAM_CODEC.apply(ByteBufCodecs.list(LogicControllerMenu.ROWS * GraphSlot.MAX_PINS)),
            PinSparks::sparks, PinSparks::new);

    public PinSparks {
        sparks = List.copyOf(sparks);
    }

    @Override
    public Type<PinSparks> type() {
        return TYPE;
    }

    /** Registers the packet; during mod construction. */
    static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, TYPE, STREAM_CODEC, (payload, player) -> {
            if (player.containerMenu instanceof LogicControllerMenu menu && menu.containerId == payload.containerId()) {
                menu.acceptSparks(payload);
            }
        });
    }
}
