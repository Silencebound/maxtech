package dev.silencebound.maxtech.lib.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * An item output of a machine recipe. Outputs are always our own items (PRD 5.5). Immutable and safe from any thread.
 *
 * @param item
 *            what is produced, with its count
 * @param chance
 *            chance in hundredths of a percent, 1 to {@value #ALWAYS}. Each copy of the output is rolled when a run
 *            finishes; a machine keeps room for every copy before it starts, so a chance output is never voided
 */
public record ItemOutput(ItemStackTemplate item, int chance) {
    /** A chance meaning "always". */
    public static final int ALWAYS = 10_000;

    /** JSON form: {@code {"item": <item stack template>, "chance": n}}, chance defaulting to {@value #ALWAYS}. */
    public static final Codec<ItemOutput> CODEC = RecordCodecBuilder.create(i -> i
            .group(ItemStackTemplate.CODEC.fieldOf("item").forGetter(ItemOutput::item),
                    ExtraCodecs.intRange(1, ALWAYS).optionalFieldOf("chance", ALWAYS).forGetter(ItemOutput::chance))
            .apply(i, ItemOutput::new));
    /** Network form, for syncing recipes to clients. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemOutput> STREAM_CODEC = StreamCodec.composite(
            ItemStackTemplate.STREAM_CODEC, ItemOutput::item, ByteBufCodecs.VAR_INT, ItemOutput::chance,
            ItemOutput::new);

    /** Throws {@link IllegalArgumentException} if {@code chance} is outside 1..{@value #ALWAYS}. */
    public ItemOutput {
        Chances.check(chance);
    }

    /** Whether this output is always produced. */
    public boolean guaranteed() {
        return chance == ALWAYS;
    }

    /**
     * Rolls this output for a finished run: how many of the template's copies come out, each copy rolled on its own, so
     * 0 to the template's count; always the full count for a guaranteed output.
     */
    public int roll(RandomSource random) {
        return Chances.copies(item.count(), chance, random);
    }
}
