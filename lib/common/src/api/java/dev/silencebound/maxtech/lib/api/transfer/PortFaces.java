package dev.silencebound.maxtech.lib.api.transfer;

import java.util.Set;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/**
 * Packs one resource kind's six {@link PortFaceMode}s into an {@code int}, two bits per face at
 * {@code 2 * get3DDataValue()} (down, up, north, south, west, east) holding {@link PortFaceMode#id()}; 0 is every face
 * {@link PortFaceMode#OFF}. This is also the saved form ({@link PortSettings#CODEC}). Pure functions; any thread.
 */
@ApiStatus.Experimental
public final class PortFaces {
    private static final int BITS = 2;
    private static final int MASK = (1 << BITS) - 1;
    private static final PortFaceMode[] BY_ID = PortFaceMode.values();
    /** Every packed bit that belongs to a face. */
    public static final int ALL_BITS = (1 << (BITS * 6)) - 1;
    /** Every face off. */
    public static final int NONE = 0;

    private PortFaces() {
    }

    /** {@code mode} on every face. */
    public static int all(PortFaceMode mode) {
        int packed = 0;
        for (int face = 0; face < 6; face++) {
            packed |= mode.id() << (BITS * face);
        }
        return packed;
    }

    /** The mode of {@code face} in {@code packed}; an id no mode has (3) reads as {@link PortFaceMode#OFF}. */
    public static PortFaceMode get(int packed, Direction face) {
        int id = (packed >>> (BITS * face.get3DDataValue())) & MASK;
        return id < BY_ID.length ? BY_ID[id] : PortFaceMode.OFF;
    }

    /** {@code packed} with {@code face} set to {@code mode}. */
    public static int with(int packed, Direction face, PortFaceMode mode) {
        int shift = BITS * face.get3DDataValue();
        return (packed & ~(MASK << shift)) | (mode.id() << shift);
    }

    /** Side mask (bit {@code get3DDataValue()} per face) of the faces set to {@code mode}. */
    public static int facesWith(int packed, PortFaceMode mode) {
        int mask = 0;
        for (int face = 0; face < 6; face++) {
            if (((packed >>> (BITS * face)) & MASK) == mode.id()) {
                mask |= 1 << face;
            }
        }
        return mask;
    }

    /**
     * {@code packed} with every face whose mode is not allowed, or whose bits name no mode, set to
     * {@link PortFaceMode#OFF}, and the bits above the six faces cleared; for data saved under other rules.
     */
    public static int sanitise(int packed, Set<PortFaceMode> allowed) {
        int result = 0;
        for (Direction face : Direction.values()) {
            PortFaceMode mode = get(packed, face);
            if (allowed.contains(mode)) {
                result = with(result, face, mode);
            }
        }
        return result;
    }
}
