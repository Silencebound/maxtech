package dev.silencebound.maxtech.storage.bulk;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.save.KeptValue;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a bulk drive holds, as its item and its save carry it: one key and a count above 0. One entry whatever the
 * count, so a full drive saves as small as a nearly empty one.
 *
 * @param key
 *            the kind the drive is locked to
 * @param count
 *            items held, above 0
 */
public record BulkContents(ItemKey key, long count) {
    public static final Codec<BulkContents> CODEC = RecordCodecBuilder
            .create(i -> i.group(ItemKey.MAP_CODEC.fieldOf("key").forGetter(BulkContents::key),
                    Codec.LONG
                            .validate(count -> count > 0
                                    ? DataResult.success(count)
                                    : DataResult.error(() -> "Bulk drive count " + count + " not above 0"))
                            .fieldOf("count").forGetter(BulkContents::count))
                    .apply(i, BulkContents::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, BulkContents> STREAM_CODEC = StreamCodec.composite(
            ItemKey.STREAM_CODEC, BulkContents::key, ByteBufCodecs.VAR_LONG, BulkContents::count, BulkContents::new);
    /**
     * The drive item's component: contents whose item no longer loads (a removed mod) are kept as saved instead of
     * failing and losing the whole stack.
     */
    public static final Codec<KeptValue<BulkContents>> KEPT_CODEC = KeptValue.codec(CODEC);
    public static final StreamCodec<RegistryFriendlyByteBuf, KeptValue<BulkContents>> KEPT_STREAM_CODEC = KeptValue
            .streamCodec(STREAM_CODEC);
}
