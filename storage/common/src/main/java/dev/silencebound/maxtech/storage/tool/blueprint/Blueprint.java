package dev.silencebound.maxtech.storage.tool.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * What a blueprint card holds ({@code maxtech_storage:blueprint}): the kind of device it was copied from and that
 * device's settings as item components.
 */
public record Blueprint(Identifier kind, DataComponentPatch settings) {
    public static final Codec<Blueprint> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Identifier.CODEC.fieldOf("kind").forGetter(Blueprint::kind), DataComponentPatch.CODEC
                    .optionalFieldOf("settings", DataComponentPatch.EMPTY).forGetter(Blueprint::settings))
            .apply(instance, Blueprint::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, Blueprint> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, Blueprint::kind, DataComponentPatch.STREAM_CODEC, Blueprint::settings,
            Blueprint::new);

    /** The settings for {@link DeviceSettings#paste()}. */
    public DataComponentMap components() {
        return settings.split().added();
    }
}
