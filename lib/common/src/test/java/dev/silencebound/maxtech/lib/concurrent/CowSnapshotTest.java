package dev.silencebound.maxtech.lib.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CowSnapshotTest {
    @Test
    void snapshotNeverSeesLaterWrites() {
        CowSnapshot<Int2LongOpenHashMap> totals = CowSnapshot.int2Long();
        totals.write().put(1, 10);
        CowSnapshot.Snapshot<Int2LongOpenHashMap> before = totals.snapshot();
        totals.write().put(1, 20);
        totals.write().put(2, 5);
        assertEquals(10, before.value().get(1), "the snapshot kept its value");
        assertEquals(1, before.value().size());
        assertEquals(20, totals.read().get(1));
        CowSnapshot.Snapshot<Int2LongOpenHashMap> after = totals.snapshot();
        assertEquals(2, after.value().size());
        assertEquals(10, before.value().get(1), "a second snapshot does not disturb the first");
    }

    @Test
    void copiesOnlyOnFirstWriteAfterSnapshot() {
        AtomicInteger copies = new AtomicInteger();
        CowSnapshot<Int2LongOpenHashMap> totals = new CowSnapshot<>(new Int2LongOpenHashMap(), map -> {
            copies.incrementAndGet();
            return new Int2LongOpenHashMap(map);
        });
        totals.write().put(1, 1);
        totals.write().put(2, 2);
        assertEquals(0, copies.get(), "no snapshot, no copy");
        CowSnapshot.Snapshot<Int2LongOpenHashMap> first = totals.snapshot();
        assertSame(first, totals.snapshot(), "unchanged data gives the same snapshot");
        assertEquals(0, copies.get(), "snapshots themselves never copy");
        totals.write().put(3, 3);
        totals.write().put(4, 4);
        assertEquals(1, copies.get(), "one copy per write burst after a snapshot");
        assertNotSame(first.value(), totals.read());
    }

    @Test
    void generationsTrackWrites() {
        CowSnapshot<Int2LongOpenHashMap> totals = CowSnapshot.int2Long();
        assertNull(totals.latest());
        assertEquals(0, totals.generation());
        totals.write();
        totals.write();
        CowSnapshot.Snapshot<Int2LongOpenHashMap> snapshot = totals.snapshot();
        assertEquals(2, snapshot.generation());
        assertSame(snapshot, totals.latest());
        totals.write();
        assertEquals(3, totals.generation());
        assertSame(snapshot, totals.latest(), "latest is the last snapshot taken, not the live value");
    }
}
