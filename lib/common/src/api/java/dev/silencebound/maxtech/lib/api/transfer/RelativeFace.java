package dev.silencebound.maxtech.lib.api.transfer;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/**
 * A block face named relative to the block's facing, as a player sees it standing in front of the block and looking at
 * its front (owner, 2026-11-28): left and right are that player's left and right. A block facing up or down is named
 * the same way, with {@link #TOP} the side towards north (looking at an up-facing front from above, north at the top of
 * the view). Side config screens show these names; ports and settings stay absolute ({@link Direction}). Immutable; any
 * thread.
 */
@ApiStatus.Experimental
public enum RelativeFace {
    /** The side the block faces. */
    FRONT,
    /** The side opposite the front. */
    BACK,
    /** The viewer's left when looking at the front. */
    LEFT,
    /** The viewer's right when looking at the front. */
    RIGHT,
    /** Up for a block facing sideways; north for one facing up or down. */
    TOP,
    /** Down for a block facing sideways; south for one facing up or down. */
    BOTTOM;

    /** The absolute face this relative face is on a block facing {@code facing}. */
    public Direction toAbsolute(Direction facing) {
        return switch (this) {
            case FRONT -> facing;
            case BACK -> facing.getOpposite();
            case TOP -> facing.getAxis().isVertical() ? Direction.NORTH : Direction.UP;
            case BOTTOM -> facing.getAxis().isVertical() ? Direction.SOUTH : Direction.DOWN;
            case LEFT -> switch (facing) {
                case UP -> Direction.WEST;
                case DOWN -> Direction.EAST;
                default -> facing.getClockWise();
            };
            case RIGHT -> switch (facing) {
                case UP -> Direction.EAST;
                case DOWN -> Direction.WEST;
                default -> facing.getCounterClockWise();
            };
        };
    }

    /** The relative face {@code absolute} is on a block facing {@code facing}; the inverse of {@link #toAbsolute}. */
    public static RelativeFace of(Direction facing, Direction absolute) {
        for (RelativeFace face : values()) {
            if (face.toAbsolute(facing) == absolute) {
                return face;
            }
        }
        // toAbsolute maps the six faces onto the six directions for every facing.
        throw new IllegalStateException("No relative face for " + absolute + " facing " + facing);
    }
}
