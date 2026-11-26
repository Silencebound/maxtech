package dev.silencebound.maxtech.lib.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.energy.EnergyScheduler;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
// PLATFORM-LEAK: the handler is a NeoForge transfer handler; tests drive it through its transactions.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

/** A MaxTech buffer as an FE handler: conversion never gains, rollbacks move nothing, export switch and limits. */
class FeEndpointHandlerTest {
    private final long[] clock = {10};
    private final AtomicInteger changes = new AtomicInteger();
    private EdgeSettings settings = EdgeSettings.DEFAULTS;

    private FeEndpointHandler handler(EnergyEndpoint buffer) {
        return new FeEndpointHandler(buffer, () -> clock[0], () -> settings, changes::incrementAndGet);
    }

    private static int insert(FeEndpointHandler handler, int amount) {
        try (Transaction tx = Transaction.openRoot()) {
            int moved = handler.insert(amount, tx);
            tx.commit();
            return moved;
        }
    }

    private static int extract(FeEndpointHandler handler, int amount) {
        try (Transaction tx = Transaction.openRoot()) {
            int moved = handler.extract(amount, tx);
            tx.commit();
            return moved;
        }
    }

    @Test
    void convertsAtTheRateAndKeepsFractions() {
        settings = new EdgeSettings(new FeRate(3, 7), true, 0, 0, 20); // 3 FE are 7 MU
        EnergyEndpoint buffer = EnergyEndpoint.buffer(1_000, 1_000, 1_000, EnergyPriority.DEFAULT);
        FeEndpointHandler handler = handler(buffer);
        assertEquals(1, insert(handler, 1));
        assertEquals(2, buffer.stored(), "1 FE is 2.33 MU: 2 MU, a third held");
        assertEquals(2, insert(handler, 2));
        assertEquals(7, buffer.stored(), "3 FE in total are exactly 7 MU");
        assertEquals(3, handler.getAmountAsLong());
        assertEquals(428, handler.getCapacityAsLong(), "1,000 MU are 428.57 FE");
        assertEquals(2, changes.get(), "one change per committed insert");
        assertEquals(3, extract(handler, 10));
        assertEquals(0, buffer.stored());
    }

    /** Random inserts and extracts at random rates: FE out never exceeds FE in, at most one MU short. */
    @Test
    void roundTripsNeverGain() {
        Random random = new Random(26_10_16L);
        for (int run = 0; run < 500; run++) {
            FeRate rate = run == 0 ? new FeRate(3, 7) : new FeRate(1 + random.nextInt(12), 1 + random.nextInt(12));
            settings = new EdgeSettings(rate, true, 0, 0, 20);
            EnergyEndpoint buffer = EnergyEndpoint.buffer(random.nextInt(3_000), 1 + random.nextInt(400),
                    1 + random.nextInt(400), EnergyPriority.DEFAULT);
            FeEndpointHandler handler = handler(buffer);
            long feIn = 0;
            long feOut = 0;
            for (int step = 0; step < 300; step++) {
                int amount = random.nextInt(60);
                boolean commit = random.nextInt(4) > 0;
                try (Transaction tx = Transaction.openRoot()) {
                    int moved = random.nextBoolean() ? handler.insert(amount, tx) : -handler.extract(amount, tx);
                    if (commit) {
                        tx.commit();
                        if (moved > 0) {
                            feIn += moved;
                        } else {
                            feOut -= moved;
                        }
                    }
                }
                long inUnits = rate.unitsOfFe(feIn);
                long nowUnits = rate.unitsOfFe(feOut) + rate.unitsOfMu(buffer.stored());
                assertTrue(nowUnits <= inUnits, rate + " run " + run + ": gained " + (nowUnits - inUnits));
                assertTrue(inUnits - nowUnits < rate.fe(), rate + " run " + run + ": lost a whole MU");
            }
        }
    }

    @Test
    void abortedTransactionsMoveNothing() {
        settings = new EdgeSettings(new FeRate(7, 3), true, 0, 0, 20);
        EnergyEndpoint buffer = EnergyEndpoint.buffer(10_000, 1_000, 1_000, EnergyPriority.DEFAULT);
        buffer.setStored(500);
        FeEndpointHandler handler = handler(buffer);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(1_000, handler.insert(1_000, tx));
            try (Transaction inner = Transaction.open(tx)) {
                assertTrue(handler.extract(400, inner) > 0);
                inner.commit();
            }
            try (Transaction inner = Transaction.open(tx)) {
                handler.extract(5, inner);
            }
        }
        assertEquals(500, buffer.stored(), "a rolled-back root changes nothing");
        assertEquals(0, changes.get(), "and fires no change");
        // The held fraction was rolled back as well: 7 FE now are exactly 3 MU.
        assertEquals(7, insert(handler, 7));
        assertEquals(503, buffer.stored());
    }

    @Test
    void exportSwitchAndLimits() {
        long[] networkClock = {10};
        EnergyScheduler scheduler = new EnergyScheduler(() -> networkClock[0]);
        EnergyNetwork network = scheduler.createNetwork();
        EnergyEndpoint a = EnergyEndpoint.buffer(100_000, 10_000, 10_000, EnergyPriority.DEFAULT);
        EnergyEndpoint b = EnergyEndpoint.buffer(100_000, 10_000, 10_000, EnergyPriority.DEFAULT);
        a.setStored(50_000);
        b.setStored(50_000);
        network.add(a);
        network.add(b);
        FeEndpointHandler handlerA = handler(a);
        FeEndpointHandler handlerB = handler(b);

        settings = new EdgeSettings(FeRate.ONE_TO_ONE, false, 0, 0, 20);
        assertEquals(0, extract(handlerA, 100), "export off: nothing comes out");
        assertEquals(100, insert(handlerA, 100), "input is always allowed");

        settings = new EdgeSettings(FeRate.ONE_TO_ONE, true, 300, 500, 20);
        assertEquals(300, extract(handlerA, 1_000), "per-handler limit");
        assertEquals(0, extract(handlerA, 1_000), "used up this tick");
        assertEquals(200, extract(handlerB, 1_000), "the network's limit is shared");
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(0, handlerB.extract(1, tx));
        }
        clock[0]++;
        networkClock[0]++;
        assertEquals(300, extract(handlerB, 1_000), "a new tick, new budgets");
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(200, handlerA.extract(1_000, tx), "what is left of the network's limit");
        }
        assertEquals(200, extract(handlerA, 1_000), "a rolled-back extract gave its count back");
    }

    @Test
    void needsABuffer() {
        assertThrows(IllegalArgumentException.class, () -> handler(EnergyEndpoint.consumer(0)));
    }
}
