package dev.silencebound.maxtech.storage.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

class DisplayViewTest {
    /** Nothing is sampled before the first update; then one point per spacing, never more. */
    @Test
    void samplesOncePerSpacing() {
        DisplayView view = new DisplayView();
        view.sample(0, 20);
        assertEquals(0, view.samples());
        view.update(DisplayUpdate.ONLINE, 5, 0);
        for (long tick = 0; tick < 100; tick++) {
            view.sample(tick, 20);
        }
        assertEquals(5, view.samples());
        assertEquals(5, view.sampleAt(4));
    }

    /** The window keeps the newest {@link DisplayView#SAMPLES} points, oldest first. */
    @Test
    void windowRollsOver() {
        DisplayView view = new DisplayView();
        for (int i = 0; i < DisplayView.SAMPLES + 10; i++) {
            view.update(DisplayUpdate.ONLINE, i, 0);
            view.sample(i * 20L, 20);
        }
        assertEquals(DisplayView.SAMPLES, view.samples());
        assertEquals(10, view.sampleAt(0));
        assertEquals(DisplayView.SAMPLES + 9, view.sampleAt(DisplayView.SAMPLES - 1));
        assertEquals(DisplayView.SAMPLES + 9, view.peak());
    }

    /** After the screen was out of view, the next point is one spacing after it came back: gaps are not filled. */
    @Test
    void gapIsNotFilled() {
        DisplayView view = new DisplayView();
        view.update(DisplayUpdate.ONLINE, 1, 0);
        view.sample(0, 20);
        view.sample(1000, 20);
        view.sample(1001, 20);
        view.sample(1019, 20);
        assertEquals(2, view.samples());
        view.sample(1020, 20);
        assertEquals(3, view.samples());
    }

    /** A new spacing (another window) starts over; the server's history seeds the window. */
    @Test
    void historySeedsAndSpacingResets() {
        DisplayView view = new DisplayView();
        view.history(LongArrayList.of(7, 8, 9), 40);
        assertEquals(3, view.samples());
        assertEquals(7, view.sampleAt(0));
        view.update(DisplayUpdate.ONLINE, 10, 0);
        view.sample(0, 40);
        assertEquals(4, view.samples());
        view.sample(100, 20);
        assertEquals(1, view.samples());
        assertEquals(10, view.sampleAt(0));
    }
}
