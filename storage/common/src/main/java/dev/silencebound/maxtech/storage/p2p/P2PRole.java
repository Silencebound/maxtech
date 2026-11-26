package dev.silencebound.maxtech.storage.p2p;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** An end of a P2P link: not linked yet, the one input of its frequency, or one of its outputs. */
public enum P2PRole implements StringRepresentable {
    NONE, INPUT, OUTPUT;

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Lang key of the role's name in info lines. */
    public String translationKey() {
        return "p2p.maxtech_storage.role." + getSerializedName();
    }
}
