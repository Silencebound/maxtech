package dev.silencebound.maxtech.storage.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SceneSitesTest {
    private static final double EPSILON = 1.0E-4;

    @Test
    void scenesSitInARowEastOfTheStartWithoutOverlapping() {
        Set<Integer> xs = new HashSet<>();
        for (ShowcaseScene scene : ShowcaseScene.values()) {
            int x = SceneSites.originX(scene, 100);
            assertEquals(100 + SceneSites.GAP + scene.index() * SceneSites.SPACING, x);
            assertTrue(xs.add(x), "two scenes share a site");
        }
        assertEquals(-48, SceneSites.originZ(-50));
        // Neighbouring scenes and their levelled aprons stay apart.
        assertTrue(SceneSites.SPACING > ShowcaseScene.WIDTH + 2 * SceneSites.APRON);
    }

    @Test
    void floorIsTheMostCommonSurfaceAndTheLowerOnATie() {
        assertEquals(64, SceneSites.floorY(new int[]{64, 64, 70, 64, 63}));
        assertEquals(63, SceneSites.floorY(new int[]{64, 63, 64, 63}));
        assertEquals(80, SceneSites.floorY(new int[]{80}));
    }

    @Test
    void yawAndPitchFollowMinecraftsAngles() {
        assertEquals(0, SceneSites.yaw(0, 1), EPSILON);
        assertEquals(90, SceneSites.yaw(-1, 0), EPSILON);
        assertEquals(180, Math.abs(SceneSites.yaw(0, -1)), EPSILON);
        assertEquals(-90, SceneSites.yaw(1, 0), EPSILON);
        assertEquals(135, SceneSites.yaw(-1, -1), EPSILON);
        assertEquals(45, SceneSites.pitch(1, -1, 0), EPSILON);
        assertEquals(-45, SceneSites.pitch(0, 1, 1), EPSILON);
        assertEquals(0, SceneSites.pitch(3, 0, 4), EPSILON);
    }

    @Test
    void poseStandsAtTheEyeAndLooksAtTheTarget() {
        for (ShowcaseScene scene : ShowcaseScene.values()) {
            ShowcaseShots.Pose pose = SceneSites.pose(scene, 1000, 70, -200);
            assertEquals(1000 + scene.eyeX(), pose.x(), EPSILON);
            assertEquals(70 + scene.eyeY(), pose.y(), EPSILON);
            assertEquals(-200 + scene.eyeZ(), pose.z(), EPSILON);
            // Walking along the view direction from the eye reaches the target.
            double yaw = Math.toRadians(pose.yaw());
            double pitch = Math.toRadians(pose.pitch());
            double dx = scene.targetX() - scene.eyeX();
            double dy = scene.targetY() - scene.eyeY();
            double dz = scene.targetZ() - scene.eyeZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(dx, -Math.sin(yaw) * Math.cos(pitch) * distance, EPSILON, scene.id() + " x");
            assertEquals(dy, -Math.sin(pitch) * distance, EPSILON, scene.id() + " y");
            assertEquals(dz, Math.cos(yaw) * Math.cos(pitch) * distance, EPSILON, scene.id() + " z");
            // The scenes are rooms seen from the open south-east, looking down into them.
            assertTrue(pose.pitch() > 0, scene.id() + " looks up");
        }
    }

    @Test
    void cableRunsStepDownFromTierFourToOne() {
        int[] tiers = new int[9];
        for (int i = 0; i < tiers.length; i++) {
            tiers[i] = SceneSites.runTier(i);
        }
        assertEquals(4, tiers[0]);
        assertEquals(1, tiers[8]);
        for (int i = 1; i < tiers.length; i++) {
            assertTrue(tiers[i] <= tiers[i - 1] && tiers[i] >= tiers[i - 1] - 1, "tiers " + i);
        }
        for (int tier = 1; tier <= 4; tier++) {
            boolean seen = false;
            for (int each : tiers) {
                seen |= each == tier;
            }
            assertTrue(seen, "tier " + tier + " missing");
        }
    }

    @Test
    void repoRootIsFoundAboveTheRunFolder(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("docs").resolve("release"));
        Files.writeString(repo.resolve("settings.gradle.kts"), "");
        Path run = Files.createDirectories(repo.resolve("runs").resolve("storage-neoforge").resolve("client"));
        assertEquals(Optional.of(repo.toAbsolutePath().normalize()), SceneSites.repoRoot(run));
        assertEquals(repo.resolve("docs/release/scenes/hall.nbt").toAbsolutePath().normalize(),
                SceneSites.file(SceneSites.repoRoot(run).orElseThrow(), ShowcaseScene.HALL));
    }

    @Test
    void noRepoRootOutsideACheckout(@TempDir Path temp) throws IOException {
        // A settings file alone is not enough: the docs folder must be there too.
        Files.writeString(temp.resolve("settings.gradle.kts"), "");
        Path run = Files.createDirectories(temp.resolve("client"));
        Optional<Path> root = SceneSites.repoRoot(run);
        assertTrue(root.isEmpty() || !root.get().startsWith(temp.toAbsolutePath().normalize()), "found " + root);
    }
}
