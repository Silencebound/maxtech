package dev.silencebound.maxtech.storage.p2p;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** What a P2P link carries; each kind is its own block and part. */
public enum P2PKind implements StringRepresentable {
    ITEM("Item"), FLUID("Fluid"), ENERGY("Energy"), REDSTONE("Redstone");

    private final String englishName;

    P2PKind(String englishName) {
        this.englishName = englishName;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The registry name of this kind's block: {@code p2p_<kind>_link}. */
    public String blockName() {
        return "p2p_" + getSerializedName() + "_link";
    }

    /** The English name used in datagen lang lines. */
    public String englishName() {
        return englishName;
    }

    /** Lang key of the kind's name in messages. */
    public String translationKey() {
        return "p2p.maxtech_storage.kind." + getSerializedName();
    }
}
