package dev.silencebound.maxtech.storage.dev;

import java.util.List;
import java.util.Optional;

/**
 * The camera angles of the scene shots in {@code docs/release/screenshots.md}, one per station, and the math that turns
 * an angle into a camera pose framing a pad. The dev client's {@code /maxtech_storage shoot} uses them; pure math, no
 * game state, so any thread.
 *
 * <p>
 * Angles use Minecraft's convention: yaw 0 looks south (+z), 90 west, 180 north, -90 east; pitch is the angle below the
 * horizon. A pad's front is its north side, where the fence gap and the step wall are, so a front shot has yaw 0.
 */
public final class ShowcaseShots {
    /** How high above the pad's floor the camera aims; the stations' blocks sit in the lowest two or three layers. */
    static final double AIM_HEIGHT = 1.0;

    /**
     * One shot: the view direction ({@code yaw}, {@code pitch}) and how far the camera stands back, horizontally, from
     * the pad's edge nearest to it.
     */
    public record Shot(ShowcaseStation station, float yaw, float pitch, double back) {
    }

    /** Where the camera's eye goes and where it looks. */
    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    /** The shot list's scene shots, in its order. */
    public static final List<Shot> SHOTS = List.of(new Shot(ShowcaseStation.CORE, 0, 25, 6), // front, slightly above
            new Shot(ShowcaseStation.CABLES, -90, 12, 3), // low, along the rows (they run along x)
            new Shot(ShowcaseStation.CHANNELS, 0, 45, 4), // from above, 45 degrees
            new Shot(ShowcaseStation.MACHINES, 0, 8, 5), // front, eye level
            new Shot(ShowcaseStation.ENERGY, 0, 25, 5), // front, slightly above
            new Shot(ShowcaseStation.CRAFTING, 135, 30, 4), // from the back (south-east) corner
            new Shot(ShowcaseStation.BRIDGES, 0, 30, 3), // centre, onto the two bridge spots
            new Shot(ShowcaseStation.PORTS, 0, 20, 5), // front
            new Shot(ShowcaseStation.LOGIC, 0, 20, 8), // front, wide
            new Shot(ShowcaseStation.SUBNET, 90, 20, 5)); // side (east), looking west

    private ShowcaseShots() {
    }

    /** The scene shot of {@code station}, or empty for a station the shot list has no scene shot of. */
    public static Optional<Shot> shot(ShowcaseStation station) {
        for (Shot shot : SHOTS) {
            if (shot.station() == station) {
                return Optional.of(shot);
            }
        }
        return Optional.empty();
    }

    /**
     * The eye pose for {@code shot} of a pad whose north-west corner (on top of the floor) is at ({@code originX},
     * {@code originY}, {@code originZ}) and which is {@code size} blocks wide along x and z: the camera looks at the
     * pad's centre, {@link #AIM_HEIGHT} above the floor, from {@code back} blocks outside the pad's extent along the
     * view direction, raised so the line of sight drops at the shot's pitch.
     */
    public static Pose pose(Shot shot, int originX, int originY, int originZ, int size) {
        double half = size / 2.0;
        double centreX = originX + half;
        double centreZ = originZ + half;
        double yaw = Math.toRadians(shot.yaw());
        // Horizontal view direction for Minecraft's yaw.
        double dirX = -Math.sin(yaw);
        double dirZ = Math.cos(yaw);
        // How far the square pad reaches from its centre along the view direction (half its width for a side view,
        // half its diagonal for a corner view), so the camera stands back from the near edge, not from the centre.
        double reach = half * Math.abs(dirX) + half * Math.abs(dirZ);
        double distance = reach + shot.back();
        double rise = distance * Math.tan(Math.toRadians(shot.pitch()));
        return new Pose(centreX - dirX * distance, originY + AIM_HEIGHT + rise, centreZ - dirZ * distance, shot.yaw(),
                shot.pitch());
    }
}
