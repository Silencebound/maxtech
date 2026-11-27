package dev.silencebound.maxtech.lib.api.conduit;

import java.util.Set;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/**
 * Packs a conduit's six face modes into one {@code int}, two bits per side at {@code 2 * get3DDataValue()}, using
 * {@link ConduitFaceMode#id()} (ADR-0005). Pure functions, any thread.
 * <p>
 * Experimental (Lib 0.14.0, moved from {@code lib.conduit} for Farms, FM1) until Farms, its first addon user, settles
 * it.
 */
@ApiStatus.Experimental
public final class ConduitFaceModes {
    private static final int BITS = 2;
    private static final int MODE_MASK = (1 << BITS) - 1;
    /** Every packed bit that belongs to a side. */
    public static final int ALL_BITS = (1 << (BITS * 6)) - 1;
    private static final ConduitFaceMode[] BY_ID = new ConduitFaceMode[MODE_MASK + 1];

    static {
        for (ConduitFaceMode mode : ConduitFaceMode.values()) {
            BY_ID[mode.id()] = mode;
        }
    }

    private ConduitFaceModes() {
    }

    /** {@code mode} on every side. */
    public static int all(ConduitFaceMode mode) {
        int packed = 0;
        for (int side = 0; side < 6; side++) {
            packed |= mode.id() << (BITS * side);
        }
        return packed;
    }

    /** The mode of {@code side} in {@code packed}. */
    public static ConduitFaceMode get(int packed, Direction side) {
        return BY_ID[(packed >>> (BITS * side.get3DDataValue())) & MODE_MASK];
    }

    /** {@code packed} with {@code side} set to {@code mode}. */
    public static int with(int packed, Direction side, ConduitFaceMode mode) {
        int shift = BITS * side.get3DDataValue();
        return (packed & ~(MODE_MASK << shift)) | (mode.id() << shift);
    }

    /** Side mask ({@link ConduitConnections#bit}) of the faces set to {@code mode}. */
    public static int sidesWith(int packed, ConduitFaceMode mode) {
        int mask = 0;
        for (int side = 0; side < 6; side++) {
            if (((packed >>> (BITS * side)) & MODE_MASK) == mode.id()) {
                mask |= 1 << side;
            }
        }
        return mask;
    }

    /**
     * {@code packed} with every side holding a mode it may not hold put back to a valid one, e.g. after loading data
     * saved while the type allowed more modes. Sides in {@code conduitFaces} face another conduit and take {@code BOTH}
     * or {@code DISABLED} whatever the type supports ({@link #allowed}); anything else there becomes {@code BOTH}.
     * Other sides fall back to the type's default.
     */
    public static int sanitise(ConduitType type, int packed, int conduitFaces) {
        int result = packed & ALL_BITS;
        for (Direction side : Direction.values()) {
            boolean towardsConduit = ConduitConnections.isConnected(conduitFaces, side);
            if (!allowed(type, get(result, side), towardsConduit)) {
                result = with(result, side, towardsConduit ? ConduitFaceMode.BOTH : type.defaultFaceMode());
            }
        }
        return result;
    }

    /**
     * Whether a face of {@code type} may hold {@code mode}. Towards a conduit it could link with a face is only linked
     * ({@code BOTH}) or cut ({@code DISABLED}), regardless of the type's supported modes (ADR-0005); towards anything
     * else it takes the modes the type supports.
     */
    public static boolean allowed(ConduitType type, ConduitFaceMode mode, boolean towardsConduit) {
        return towardsConduit
                ? mode == ConduitFaceMode.BOTH || mode == ConduitFaceMode.DISABLED
                : type.supportedFaceModes().contains(mode);
    }

    /**
     * Checks the contract {@link ConduitType#supportedFaceModes()} documents: the set holds the type's default and
     * {@link ConduitFaceMode#DISABLED}. Throws {@link IllegalStateException} naming the type otherwise.
     */
    public static void validate(ConduitType type, String name) {
        Set<ConduitFaceMode> supported = type.supportedFaceModes();
        if (!supported.contains(type.defaultFaceMode())) {
            throw new IllegalStateException("Conduit type " + name + " does not support its own default face mode "
                    + type.defaultFaceMode() + " (supports " + supported + ")");
        }
        if (!supported.contains(ConduitFaceMode.DISABLED)) {
            throw new IllegalStateException(
                    "Conduit type " + name + " must support face mode DISABLED (supports " + supported + ")");
        }
    }

    /** {@link #validate} for every registered conduit type; Lib runs it once at common setup. */
    @ApiStatus.Internal
    public static void validateRegistered() {
        for (ConduitType type : ConduitTypes.registry()) {
            validate(type, ConduitTypes.idOf(type).toString());
        }
    }
}
