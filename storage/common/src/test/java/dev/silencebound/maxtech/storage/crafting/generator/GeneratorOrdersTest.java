package dev.silencebound.maxtech.storage.crafting.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GeneratorOrdersTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    @Test
    void arrivalsFillTheOldestOrderFirst() {
        GeneratorOrders orders = new GeneratorOrders();
        orders.add(FIRST, 10);
        orders.add(SECOND, 5);
        orders.fill(12);
        assertEquals(0, orders.owed(FIRST));
        assertEquals(3, orders.owed(SECOND));
        assertEquals(3, orders.remaining());
        orders.fill(50);
        assertTrue(orders.isEmpty(), "more than owed empties the orders");
        assertEquals(0, orders.remaining());
    }

    @Test
    void ordersAreCappedToWhatTheirJobStillWaitsFor() {
        GeneratorOrders orders = new GeneratorOrders();
        orders.add(FIRST, 10);
        orders.add(SECOND, 5);
        assertTrue(orders.capTo(job -> job.equals(FIRST) ? 4 : 0));
        assertEquals(4, orders.owed(FIRST));
        assertEquals(0, orders.owed(SECOND), "a job that waits for nothing loses its order");
        assertEquals(4, orders.remaining());
        assertFalse(orders.capTo(job -> 100), "a cap above the order changes nothing");
        assertEquals(4, orders.remaining());
    }

    @Test
    void cancelWithdrawsOnlyThatJob() {
        GeneratorOrders orders = new GeneratorOrders();
        orders.add(FIRST, 10);
        orders.add(FIRST, 2);
        orders.add(SECOND, 5);
        assertEquals(12, orders.owed(FIRST), "orders of one job add up");
        assertTrue(orders.cancel(FIRST));
        assertFalse(orders.cancel(FIRST), "a second cancel finds nothing");
        assertEquals(5, orders.remaining());
        assertTrue(orders.cancel(SECOND));
        assertTrue(orders.isEmpty());
    }

    @Test
    void hugeOrdersSaturate() {
        GeneratorOrders orders = new GeneratorOrders();
        orders.add(FIRST, Long.MAX_VALUE);
        orders.add(SECOND, 10);
        assertEquals(Long.MAX_VALUE, orders.remaining());
    }
}
