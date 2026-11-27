package dev.silencebound.maxtech.farms.specimen;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/** What a specimen was sampled from. FM1 samples mobs only; the other kinds arrive with their areas. */
public enum SpecimenKind implements StringRepresentable {
    MOB, CROP, SAPLING, ANIMAL, MINERAL;

    public static final Codec<SpecimenKind> CODEC = StringRepresentable.fromEnum(SpecimenKind::values);
    public static final StreamCodec<ByteBuf, SpecimenKind> STREAM_CODEC = ByteBufCodecs.idMapper(
            ByIdMap.continuous(SpecimenKind::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO),
            SpecimenKind::ordinal);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The lang key of the kind's name. */
    public String translationKey() {
        return "specimen.maxtech_farms.kind." + getSerializedName();
    }
}
