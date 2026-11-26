package dev.silencebound.maxtech.storage.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShowcaseShotsTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void frontShotStandsBackFromTheNorthEdgeAndLooksAtTheCentre() {
        ShowcaseShots.Shot shot = new ShowcaseShots.Shot(ShowcaseStation.CORE, 0, 45, 6);
        ShowcaseShots.Pose pose = ShowcaseShots.pose(shot, 100, 64, 200, 9);
        // Centre x, 4.5 to the north edge plus 6 back, and as high above the aim point as it is far from it.
        assertEquals(104.5, pose.x(), EPSILON);
        assertEquals(200 + 4.5 - 10.5, pose.z(), EPSILON);
        assertEquals(64 + ShowcaseShots.AIM_HEIGHT + 10.5, pose.y(), EPSILON);
        assertEquals(0, pose.yaw());
        assertEquals(45, pose.pitch());
    }

    @Test
    void sideShotLookingWestStandsEastOfThePad() {
        ShowcaseShots.Shot shot = new ShowcaseShots.Shot(ShowcaseStation.SUBNET, 90, 0, 5);
        ShowcaseShots.Pose pose = ShowcaseShots.pose(shot, 0, 10, 0, 9);
        assertEquals(4.5 + 4.5 + 5, pose.x(), EPSILON);
        assertEquals(4.5, pose.z(), EPSILON);
        assertEquals(10 + ShowcaseShots.AIM_HEIGHT, pose.y(), EPSILON);
    }

    @Test
    void cornerShotStandsBackFromTheCorner() {
        ShowcaseShots.Shot shot = new ShowcaseShots.Shot(ShowcaseStation.CRAFTING, 135, 0, 4);
        ShowcaseShots.Pose pose = ShowcaseShots.pose(shot, 0, 0, 0, 9);
        // The pad reaches half its diagonal towards the south-east corner.
        double distance = 4.5 * Math.sqrt(2) + 4;
        assertEquals(4.5 + distance / Math.sqrt(2), pose.x(), 1.0E-6);
        assertEquals(4.5 + distance / Math.sqrt(2), pose.z(), 1.0E-6);
    }

    @Test
    void everyPoseLooksAtThePadCentre() {
        for (ShowcaseShots.Shot shot : ShowcaseShots.SHOTS) {
            ShowcaseShots.Pose pose = ShowcaseShots.pose(shot, -40, 70, 13, 9);
            double yaw = Math.toRadians(pose.yaw());
            double pitch = Math.toRadians(pose.pitch());
            // Minecraft's look vector for the pose, followed until it is above the pad's centre.
            double lookX = -Math.sin(yaw) * Math.cos(pitch);
            double lookY = -Math.sin(pitch);
            double lookZ = Math.cos(yaw) * Math.cos(pitch);
            double horizontal = Math.hypot(-40 + 4.5 - pose.x(), 13 + 4.5 - pose.z());
            double steps = horizontal / Math.hypot(lookX, lookZ);
            String id = shot.station().id();
            assertEquals(-35.5, pose.x() + lookX * steps, 1.0E-6, id);
            assertEquals(17.5, pose.z() + lookZ * steps, 1.0E-6, id);
            assertEquals(70 + ShowcaseShots.AIM_HEIGHT, pose.y() + lookY * steps, 1.0E-6, id);
            assertTrue(horizontal >= 4.5 + shot.back() - EPSILON, id);
        }
    }

    @Test
    void shotListHasOneShotPerSceneStation() {
        Set<ShowcaseStation> seen = EnumSet.noneOf(ShowcaseStation.class);
        for (ShowcaseShots.Shot shot : ShowcaseShots.SHOTS) {
            assertTrue(seen.add(shot.station()), shot.station().id());
        }
        assertEquals(10, seen.size());
        assertTrue(ShowcaseShots.shot(ShowcaseStation.TOOLS).isEmpty());
    }
}
