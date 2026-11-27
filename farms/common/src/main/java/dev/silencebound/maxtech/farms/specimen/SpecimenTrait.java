package dev.silencebound.maxtech.farms.specimen;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** A specimen's traits; a specimen carries at most {@link Specimen#MAX_TRAITS}. What they do comes with breeding. */
public enum SpecimenTrait implements StringRepresentable {
    LUCKY(1), FRUGAL(1 << 1), HARDY(1 << 2);

    public static final Codec<SpecimenTrait> CODEC = StringRepresentable.fromEnum(SpecimenTrait::values);

    private final int bit;

    SpecimenTrait(int bit) {
        this.bit = bit;
    }

    /** The trait's bit in the network form's mask; fixed per trait. */
    public int bit() {
        return bit;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The lang key of the trait's name. */
    public String translationKey() {
        return "specimen.maxtech_farms.trait." + getSerializedName();
    }
}
