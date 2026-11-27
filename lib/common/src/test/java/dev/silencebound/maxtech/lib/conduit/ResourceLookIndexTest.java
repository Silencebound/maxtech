package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** {@link ResourceLookIndex}: indices in first-seen order, paints per index, overflow, repaint and clear. */
class ResourceLookIndexTest {
    @Test
    void addsInFirstSeenOrderAndKeepsIndices() {
        List<String> painted = new ArrayList<>();
        ResourceLookIndex<String, String> index = new ResourceLookIndex<>(key -> {
            painted.add(key);
            return "paint of " + key;
        });
        assertEquals(0, index.indexOf("water"));
        assertEquals(1, index.indexOf("glow:40"));
        assertEquals(0, index.indexOf("water"), "a known key keeps its index");
        assertEquals(List.of("water", "glow:40"), painted, "painted once, when added");
        assertEquals("paint of glow:40", index.paint(1));
        assertEquals(Optional.of("water"), index.key(0));
        assertNull(index.paint(2), "not held");
        assertNull(index.paint(-1), "no resource");
        assertEquals(Optional.empty(), index.key(2));
        assertEquals(2, index.size());
    }

    @Test
    void keyWithoutPaintDrawsNothing() {
        ResourceLookIndex<String, String> index = new ResourceLookIndex<>(key -> key.startsWith("x") ? null : key);
        assertEquals(0, index.indexOf("xenon"));
        assertNull(index.paint(0));
        assertEquals(1, index.indexOf("water"));
        assertEquals("water", index.paint(1));
    }

    @Test
    void packsWithTheIndexAndRoundTrips() {
        ResourceLookIndex<String, String> index = new ResourceLookIndex<>(key -> key);
        index.indexOf("lava");
        int packed = index.pack(Optional.of("water"), 3, Optional.of(Direction.WEST), true);
        assertEquals(ConduitContents.pack(1, 3, Direction.WEST, true), packed);
        assertEquals(Optional.of("water"), index.key(ConduitContents.packedLookIndex(packed)));
        assertEquals(3, ConduitContents.packedFill(packed));
        assertEquals(Optional.of(Direction.WEST), ConduitContents.packedUpstream(packed));
        assertTrue(ConduitContents.packedLane(packed));
        assertEquals(0, index.pack(Optional.empty(), 0, Optional.empty(), false), "empty packs to 0");
        assertEquals(2, index.size(), "nothing added for no resource");
    }

    @Test
    void overflowGetsNoIndexAndKeepsTheRest() {
        ResourceLookIndex<String, String> index = new ResourceLookIndex<>(key -> key, 2);
        assertEquals(0, index.indexOf("a"));
        assertEquals(1, index.indexOf("b"));
        assertEquals(-1, index.indexOf("c"), "full");
        assertEquals(-1, index.indexOf("d"));
        assertEquals(1, index.indexOf("b"), "held keys still found");
        assertEquals(ConduitContents.pack(-1, 2, Direction.UP, false),
                index.pack(Optional.of("c"), 2, Optional.of(Direction.UP), false), "drawn as nothing");
        assertEquals(2, index.size());
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceLookIndex<String, String>(key -> key, ConduitContents.MAX_LOOK_INDEX + 2));
    }

    @Test
    void fullSizeIndexHoldsEveryPackableIndex() {
        ResourceLookIndex<Integer, Integer> index = new ResourceLookIndex<>(key -> key);
        for (int i = 0; i <= ConduitContents.MAX_LOOK_INDEX; i++) {
            assertEquals(i, index.indexOf(i));
        }
        assertEquals(-1, index.indexOf(-5), "past MAX_LOOK_INDEX");
        assertEquals(ConduitContents.MAX_LOOK_INDEX, ConduitContents
                .packedLookIndex(index.pack(Optional.of(ConduitContents.MAX_LOOK_INDEX), 1, Optional.empty(), false)));
    }

    @Test
    void repaintKeepsIndicesAndClearDropsThem() {
        AtomicReference<String> suffix = new AtomicReference<>("old");
        ResourceLookIndex<String, String> index = new ResourceLookIndex<>(key -> key + " " + suffix.get());
        index.indexOf("water");
        index.indexOf("lava");
        int before = index.generation();
        suffix.set("new");
        index.repaint();
        assertEquals("lava new", index.paint(1));
        assertEquals(1, index.indexOf("lava"), "repaint keeps indices");
        assertNotEquals(before, index.generation());
        int repainted = index.generation();
        index.clear();
        assertEquals(0, index.size());
        assertNull(index.paint(0));
        assertNotEquals(repainted, index.generation());
        assertEquals(0, index.indexOf("lava"), "counts from 0 again");
    }

    @Test
    void clearResetsTheFullWarningState() {
        ResourceLookIndex<String, String> index = new ResourceLookIndex<>(key -> key, 1);
        index.indexOf("a");
        assertEquals(-1, index.indexOf("b"));
        index.clear();
        assertEquals(0, index.indexOf("b"));
    }

    /**
     * Readers never lock and never see a half-built array: while one thread adds, others read every index below the
     * size they saw and always find its paint.
     */
    @Test
    void readersSeeCompleteEntriesWhileAdding() throws InterruptedException {
        ResourceLookIndex<Integer, String> index = new ResourceLookIndex<>(key -> "p" + key);
        int count = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>("");
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (index.size() < count && failure.get().isEmpty()) {
                    int size = index.size();
                    for (int i = Math.max(0, size - 50); i < size; i++) {
                        if (!("p" + i).equals(index.paint(i))) {
                            failure.compareAndSet("", "index " + i + " of " + size + ": " + index.paint(i));
                        }
                    }
                }
            });
            reader.start();
            readers.add(reader);
        }
        start.countDown();
        for (int i = 0; i < count; i++) {
            index.indexOf(i);
        }
        for (Thread reader : readers) {
            reader.join();
        }
        assertEquals("", failure.get());
    }
}
