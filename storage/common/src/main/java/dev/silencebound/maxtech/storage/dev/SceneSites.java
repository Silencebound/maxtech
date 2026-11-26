package dev.silencebound.maxtech.storage.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the showcase scenes go and where their files live; pure math and path checks, no game state, so any thread.
 * Scenes are placed relative to a start spot: scene {@code i} has its north-west corner {@link #SPACING}{@code * i}
 * blocks east of the spot and {@link #GAP} blocks south-east of it, on the surface there, so a fixed-seed world and a
 * fixed start give the same sites every time.
 */
public final class SceneSites {
    /** Blocks between two scenes' corners along x; far more than a shot's view reaches sideways. */
    public static final int SPACING = 64;
    /** Blocks from the start spot to scene 0's corner along x and z, so the caller does not stand inside it. */
    public static final int GAP = 2;
    /** Blocks of terrain levelled around a scene, so it sits on flat ground rather than at a cliff. */
    public static final int APRON = 3;
    /** Where scene files live, relative to the repository root. */
    public static final String FOLDER = "docs/release/scenes";

    private SceneSites() {
    }

    /** The x of {@code scene}'s north-west corner for a start spot at {@code startX}. */
    public static int originX(ShowcaseScene scene, int startX) {
        return startX + GAP + scene.index() * SPACING;
    }

    /** The z of {@code scene}'s north-west corner for a start spot at {@code startZ}. */
    public static int originZ(int startZ) {
        return startZ + GAP;
    }

    /**
     * The floor's top for a site whose columns' surfaces (the first air above the ground, as the heightmap gives it)
     * are {@code surfaces}: the most common one, the lower on a tie, so a tree or a dip in a few columns does not lift
     * or sink the scene. {@code surfaces} must not be empty.
     */
    public static int floorY(int[] surfaces) {
        if (surfaces.length == 0) {
            throw new IllegalArgumentException("no surfaces");
        }
        int best = surfaces[0];
        int bestCount = 0;
        for (int candidate : surfaces) {
            int count = 0;
            for (int other : surfaces) {
                if (other == candidate) {
                    count++;
                }
            }
            if (count > bestCount || (count == bestCount && candidate < best)) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    /** The camera pose of {@code scene} placed with its origin at ({@code x}, {@code y}, {@code z}). */
    public static ShowcaseShots.Pose pose(ShowcaseScene scene, int x, int y, int z) {
        double dx = scene.targetX() - scene.eyeX();
        double dy = scene.targetY() - scene.eyeY();
        double dz = scene.targetZ() - scene.eyeZ();
        return new ShowcaseShots.Pose(x + scene.eyeX(), y + scene.eyeY(), z + scene.eyeZ(), yaw(dx, dz),
                pitch(dx, dy, dz));
    }

    /** Minecraft's yaw for a view along ({@code dx}, {@code dz}): 0 looks south (+z), 90 west, 180 north, -90 east. */
    public static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Minecraft's pitch for a view along ({@code dx}, {@code dy}, {@code dz}): positive looks down. */
    public static float pitch(double dx, double dy, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /**
     * The cable tier of the {@code i}th block of a nine-block run leaving a tier-4 trunk, stepping down towards the
     * device at its end: 4, 4, 4, 3, 3, 2, 2, 1, 1.
     */
    public static int runTier(int i) {
        return 4 - i * 4 / 9;
    }

    /**
     * The repository root above {@code from} (a dev run's game directory such as {@code runs/storage-neoforge/client}):
     * the nearest directory, {@code from} included, holding {@code settings.gradle.kts} and a {@code docs/release}
     * folder. Empty outside a checkout.
     */
    public static Optional<Path> repoRoot(Path from) {
        for (Path dir = from.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isDirectory(dir.resolve("docs").resolve("release"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /** {@code scene}'s structure file under the repository root {@code root}. */
    public static Path file(Path root, ShowcaseScene scene) {
        return root.resolve(FOLDER).resolve(scene.id() + ".nbt");
    }
}
