package dev.silencebound.maxtech.storage.smartlabel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

/**
 * A smart label's settings, saved with it and kept in its item ({@code maxtech_storage:smart_label}) when they differ
 * from a new label's. Immutable.
 *
 * @param mode
 *            what it shows
 * @param item
 *            the item whose count {@link LabelMode#ITEM} shows; empty until a player picks one
 */
public record LabelSettings(LabelMode mode, Optional<Item> item) {
    public static final LabelSettings DEFAULT = new LabelSettings(LabelMode.ITEM, Optional.empty());
    public static final Codec<LabelSettings> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(LabelMode.CODEC.optionalFieldOf("mode", LabelMode.ITEM).forGetter(LabelSettings::mode),
                    BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item").forGetter(LabelSettings::item))
            .apply(instance, LabelSettings::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, LabelSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(LabelMode::byId, LabelMode::id), LabelSettings::mode,
            ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.ITEM)), LabelSettings::item, LabelSettings::new);

    public LabelSettings withMode(LabelMode mode) {
        return new LabelSettings(mode, item);
    }

    /** Shows {@code item}'s count. */
    public LabelSettings withItem(Item item) {
        return new LabelSettings(LabelMode.ITEM, Optional.of(item));
    }
}
