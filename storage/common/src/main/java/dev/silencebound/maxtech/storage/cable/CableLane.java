package dev.silencebound.maxtech.storage.cable;

import dev.silencebound.maxtech.lib.conduit.ConduitColours;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * The colour and brightness a storage cable's lane is drawn with, shared by the block model and the cable items' 3D
 * models (datagen) so they agree. Pure functions, any thread.
 */
public final class CableLane {
    private CableLane() {
    }

    /** The lane's brightness level (1 to 4): the cable's tier, or on a smart cable its {@code load} bucket's. */
    public static int brightness(StorageCableConduitType type, int load) {
        return type.look() == CableLook.SMART ? SmartLoad.brightness(load) : type.level();
    }

    /**
     * The lane's ARGB: the cable's own colour ({@code colour} dye or none; on a smart cable its load colour while
     * {@code load} is not idle) at the lane's {@link #brightness}. A covered cable has no lane; its sleeve takes the
     * cable's own colour as it is.
     */
    public static int argb(StorageCableConduitType type, @Nullable DyeColor colour, int load) {
        int own = ConduitColours.argb(type, colour);
        if (type.look() == CableLook.COVERED) {
            return own;
        }
        int argb = type.look() == CableLook.SMART ? 0xFF000000 | SmartLoad.colour(load, own & 0xFFFFFF) : own;
        return CableTierMarks.lineRgb(brightness(type, load), argb);
    }
}
