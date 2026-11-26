package dev.silencebound.maxtech.storage.dev;

import java.util.Optional;

/**
 * The showcase scenes for the mod-page screenshots ({@code docs/release/screenshots.md}): base corners that look lived
 * in, built by {@link ShowcaseScenes} from a saved structure file or the code draft. Each scene fills the same box,
 * {@link #WIDTH} by {@link #DEPTH} blocks with {@link #HEIGHT} layers of room over {@link #BELOW} layers of floor and
 * what hides under it; the room's walls stand on the north (z 0) and west (x 0) edges, so the camera looks in from the
 * south-east. The camera's eye and the point it looks at are block coordinates relative to the scene's origin, its
 * north-west corner on top of the floor; they are a first guess for the owner to tune.
 */
public enum ShowcaseScene {
    HALL("hall", 0, 12.5, 5.5, 13.5, 5.0, 1.5, 2.0, 100), // from the open corner onto the drive wall
    CRAFTING("crafting", 1, 12.0, 4.5, 12.5, 5.5, 1.0, 2.0, 200), // the CPU, furnaces and the counter in one frame
    PROCESSING("processing", 2, 7.5, 3.5, 10.5, 7.0, 1.0, 1.5, 100), // straight at the machine line
    MONITORING("monitoring", 3, 9.0, 3.5, 10.0, 6.0, 1.5, 1.5, 500), // the displays need samples to draw lines
    CABLES("cables", 4, 11.0, 4.5, 11.5, 5.0, 2.5, 2.0, 60); // both cable walls

    /** Blocks along x. */
    public static final int WIDTH = 13;
    /** Blocks along z. */
    public static final int DEPTH = 13;
    /** Layers from the floor's top up. */
    public static final int HEIGHT = 7;
    /** Layers below the room: the floor and one under it. */
    public static final int BELOW = 2;

    private final String id;
    private final int index;
    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;
    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final int warmupTicks;

    ShowcaseScene(String id, int index, double eyeX, double eyeY, double eyeZ, double targetX, double targetY,
            double targetZ, int warmupTicks) {
        this.id = id;
        this.index = index;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.warmupTicks = warmupTicks;
    }

    /** The command and file name. */
    public String id() {
        return id;
    }

    /** The camera's eye, relative to the origin. */
    public double eyeX() {
        return eyeX;
    }

    public double eyeY() {
        return eyeY;
    }

    public double eyeZ() {
        return eyeZ;
    }

    /** The point the camera looks at, relative to the origin. */
    public double targetX() {
        return targetX;
    }

    public double targetY() {
        return targetY;
    }

    public double targetZ() {
        return targetZ;
    }

    /**
     * Ticks the shot waits after placing: stock arrives and bays light up, the crafting job gets going, the display
     * graphs gather samples while in view.
     */
    public int warmupTicks() {
        return warmupTicks;
    }

    /** Where this scene goes in a row of all scenes, counted from the start spot eastwards. */
    public int index() {
        return index;
    }

    /** The scene called {@code id}, or empty. */
    public static Optional<ShowcaseScene> byId(String id) {
        for (ShowcaseScene scene : values()) {
            if (scene.id.equals(id)) {
                return Optional.of(scene);
            }
        }
        return Optional.empty();
    }
}
