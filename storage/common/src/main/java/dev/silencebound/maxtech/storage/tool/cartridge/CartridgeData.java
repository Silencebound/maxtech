package dev.silencebound.maxtech.storage.tool.cartridge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * The puzzle a written cartridge carries: which puzzle, at which difficulty, from which seed. The seed fixes the board,
 * so the same cartridge always poses the same puzzle until it is solved.
 */
public record CartridgeData(Identifier puzzle, int difficulty, long seed) {
    public static final Codec<CartridgeData> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Identifier.CODEC.fieldOf("puzzle").forGetter(CartridgeData::puzzle),
                    Codec.intRange(PuzzleType.EASY, PuzzleType.HARD).fieldOf("difficulty").forGetter(
                            CartridgeData::difficulty),
                    Codec.LONG.fieldOf("seed").forGetter(CartridgeData::seed))
            .apply(instance, CartridgeData::new));
    public static final StreamCodec<FriendlyByteBuf, CartridgeData> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, CartridgeData::puzzle, ByteBufCodecs.VAR_INT, CartridgeData::difficulty,
            ByteBufCodecs.VAR_LONG, CartridgeData::seed, CartridgeData::new);
}
