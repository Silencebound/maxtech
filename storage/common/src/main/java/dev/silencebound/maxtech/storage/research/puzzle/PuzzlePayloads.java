package dev.silencebound.maxtech.storage.research.puzzle;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The three puzzle payloads: open (server to client), submit (client to server) and result (server to client). */
public final class PuzzlePayloads {
    /** The longest solution a client may send; the largest puzzle (a 7x7 routing grid) needs 49 values. */
    public static final int MAX_SOLUTION_LENGTH = 256;

    /** A capped VarInt array, so a forged length cannot make the server allocate a huge array. */
    public static final StreamCodec<ByteBuf, IntList> SOLUTION_CODEC = new StreamCodec<>() {
        @Override
        public IntList decode(ByteBuf buf) {
            int length = VarInt.read(buf);
            if (length < 0 || length > MAX_SOLUTION_LENGTH) {
                throw new DecoderException("Puzzle solution of length " + length);
            }
            int[] values = new int[length];
            for (int i = 0; i < length; i++) {
                values[i] = VarInt.read(buf);
            }
            return IntArrayList.wrap(values);
        }

        @Override
        public void encode(ByteBuf buf, IntList values) {
            VarInt.write(buf, values.size());
            for (int i = 0; i < values.size(); i++) {
                VarInt.write(buf, values.getInt(i));
            }
        }
    };

    private PuzzlePayloads() {
    }

    /** Asks the client to show puzzle {@code puzzleId} for research node {@code nodeId}. */
    public record Open(int session, Identifier nodeId, Identifier puzzleId, long seed,
            int difficulty) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(MaxtechStorage.id("puzzle_open"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Open::session, Identifier.STREAM_CODEC, Open::nodeId, Identifier.STREAM_CODEC, Open::puzzleId,
                ByteBufCodecs.VAR_LONG, Open::seed, ByteBufCodecs.VAR_INT, Open::difficulty, Open::new);

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    /** The player's final configuration for session {@code session}. */
    public record Submit(int session, IntList solution) implements CustomPacketPayload {
        public static final Type<Submit> TYPE = new Type<>(MaxtechStorage.id("puzzle_submit"));
        public static final StreamCodec<ByteBuf, Submit> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Submit::session, SOLUTION_CODEC, Submit::solution, Submit::new);

        @Override
        public Type<Submit> type() {
            return TYPE;
        }
    }

    /** Whether the server accepted the last submission of session {@code session}. */
    public record Result(int session, boolean accepted) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(MaxtechStorage.id("puzzle_result"));
        public static final StreamCodec<ByteBuf, Result> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Result::session, ByteBufCodecs.BOOL, Result::accepted, Result::new);

        @Override
        public Type<Result> type() {
            return TYPE;
        }
    }
}
