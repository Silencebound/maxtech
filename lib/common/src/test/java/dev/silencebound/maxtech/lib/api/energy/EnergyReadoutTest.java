package dev.silencebound.maxtech.lib.api.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Diagnostics (MLE item 3): brownout and time-to-full/empty math, and readouts that never wake a sleeping network. */
class EnergyReadoutTest {
    private final long[] time = {100};
    private final EnergyScheduler scheduler = new EnergyScheduler(() -> time[0]);
    private final EnergyNetwork network = scheduler.createNetwork();

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            scheduler.tick();
            time[0]++;
        }
    }

    @Test
    void suppliedFractionEdges() {
        assertEquals(1.0, EnergyReadout.suppliedFraction(0, 0), "no demand is fully served");
        assertEquals(1.0, EnergyReadout.suppliedFraction(5, 0));
        assertEquals(0.0, EnergyReadout.suppliedFraction(0, 100), "no supply");
        assertEquals(0.25, EnergyReadout.suppliedFraction(25, 100));
        assertEquals(1.0, EnergyReadout.suppliedFraction(100, 100));
        assertEquals(1.0, EnergyReadout.suppliedFraction(150, 100), "clamped");
        assertEquals(0.5, EnergyReadout.suppliedFraction(Long.MAX_VALUE / 2, Long.MAX_VALUE - 1), 1e-12);
    }

    @Test
    void ticksToFullEdges() {
        assertEquals(OptionalLong.of(10), EnergyReadout.ticksToFull(0, 100, 10));
        assertEquals(OptionalLong.of(4), EnergyReadout.ticksToFull(65, 100, 10), "rounded up");
        assertEquals(OptionalLong.of(0), EnergyReadout.ticksToFull(100, 100, 10), "full");
        assertEquals(OptionalLong.of(0), EnergyReadout.ticksToFull(100, 100, -10), "full and draining");
        assertEquals(OptionalLong.empty(), EnergyReadout.ticksToFull(50, 100, 0), "no flow");
        assertEquals(OptionalLong.empty(), EnergyReadout.ticksToFull(50, 100, -1), "losing");
        assertEquals(OptionalLong.empty(), EnergyReadout.ticksToFull(0, 0, 10), "no capacity");
        assertEquals(OptionalLong.of(Long.MAX_VALUE), EnergyReadout.ticksToFull(0, Long.MAX_VALUE, 1), "no overflow");
        assertEquals(OptionalLong.of(1), EnergyReadout.ticksToFull(0, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(OptionalLong.of(2), EnergyReadout.ticksToFull(1, Long.MAX_VALUE, Long.MAX_VALUE / 2));
    }

    @Test
    void ticksToEmptyEdges() {
        assertEquals(OptionalLong.of(10), EnergyReadout.ticksToEmpty(100, -10));
        assertEquals(OptionalLong.of(4), EnergyReadout.ticksToEmpty(35, -10), "rounded up");
        assertEquals(OptionalLong.of(0), EnergyReadout.ticksToEmpty(0, -10), "empty");
        assertEquals(OptionalLong.of(0), EnergyReadout.ticksToEmpty(0, 10), "empty and charging");
        assertEquals(OptionalLong.empty(), EnergyReadout.ticksToEmpty(50, 0), "no flow");
        assertEquals(OptionalLong.empty(), EnergyReadout.ticksToEmpty(50, 3), "gaining");
        assertEquals(OptionalLong.of(1), EnergyReadout.ticksToEmpty(Long.MAX_VALUE, Long.MIN_VALUE), "no overflow");
        assertEquals(OptionalLong.of(Long.MAX_VALUE), EnergyReadout.ticksToEmpty(Long.MAX_VALUE, -1));
    }

    @Test
    void readoutBeforeFirstPassIsNone() {
        assertSame(EnergyReadout.NONE, network.readout());
        assertEquals(1.0, EnergyReadout.NONE.suppliedFraction());
        assertFalse(EnergyReadout.NONE.brownout());
    }

    @Test
    void readoutMatchesAKnownSetup() {
        EnergyEndpoint producer = EnergyEndpoint.producer();
        producer.setSupply(100);
        network.add(producer);
        EnergyEndpoint consumer = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        consumer.setDemand(60);
        network.add(consumer);
        EnergyEndpoint battery = EnergyEndpoint.buffer(10_000, 25, 25, EnergyPriority.LEFTOVERS);
        battery.setStored(1_000);
        network.add(battery);
        ticks(1);
        EnergyReadout readout = network.readout();
        assertEquals(100, readout.supply());
        assertEquals(60, readout.demand());
        assertEquals(60, readout.received());
        assertEquals(25, readout.bufferCharge());
        assertEquals(0, readout.bufferDischarge());
        assertEquals(1.0, readout.suppliedFraction());
        assertFalse(readout.brownout());
        assertEquals(10_000, readout.capacity());
        assertEquals(1_025, readout.stored(), "one tick of charge since the pass");
        assertEquals(OptionalLong.of(359), readout.ticksToFull(), "8,975 MU at 25 MU/t");
        assertEquals(OptionalLong.empty(), readout.ticksToEmpty());
        assertEquals(1, readout.passCount());
        assertTrue(readout.asleep());

        // Demand above supply: the battery covers 25 of the 40 short, the rest is a brownout.
        consumer.setDemand(140);
        ticks(1);
        readout = network.readout();
        assertEquals(125, readout.received());
        assertEquals(25, readout.bufferDischarge());
        assertTrue(readout.brownout());
        assertEquals(125.0 / 140, readout.suppliedFraction(), 1e-12);
        assertEquals(OptionalLong.empty(), readout.ticksToFull());
        assertTrue(readout.ticksToEmpty().isPresent());
    }

    @Test
    void readoutNeverWakesAndFollowsTheFlows() {
        EnergyEndpoint producer = EnergyEndpoint.producer();
        producer.setSupply(40);
        network.add(producer);
        EnergyEndpoint battery = EnergyEndpoint.buffer(1_000_000, 10, 10, EnergyPriority.DEFAULT);
        network.add(battery);
        ticks(1);
        long passes = network.passCount();
        for (int i = 0; i < 500; i++) {
            EnergyReadout readout = network.readout();
            assertEquals(passes, readout.passCount());
            ticks(1);
        }
        assertEquals(passes, network.passCount(), "reading ran a pass");
        assertTrue(network.isAsleep());
        assertEquals(battery.stored(), network.readout().stored(), "projected stored equals the settled buffer");
        assertEquals(network.bufferStored(), network.readout().stored());
        assertEquals(passes, network.passCount(), "settling the buffer ran a pass");
    }

    @Test
    void projectedStoredMatchesWalkingTheBuffers() {
        Random random = new Random(7);
        for (int round = 0; round < 40; round++) {
            EnergyScheduler own = new EnergyScheduler(() -> time[0]);
            EnergyNetwork net = own.createNetwork();
            for (int i = 0; i < 6; i++) {
                int kind = random.nextInt(3);
                EnergyEndpoint endpoint;
                if (kind == 0) {
                    endpoint = EnergyEndpoint.producer();
                    endpoint.setSupply(random.nextInt(200));
                } else if (kind == 1) {
                    endpoint = EnergyEndpoint.consumer(random.nextInt(3));
                    endpoint.setDemand(random.nextInt(200));
                } else {
                    endpoint = EnergyEndpoint.buffer(1 + random.nextInt(5_000), random.nextInt(50), random.nextInt(50),
                            random.nextInt(3));
                    endpoint.setStored(random.nextInt(5_000));
                }
                net.add(endpoint);
            }
            for (int t = 0; t < 300; t++) {
                own.tick();
                time[0]++;
                if (t % 7 == 0) {
                    assertEquals(net.bufferStored(), net.readout().stored(), "round " + round + " tick " + t);
                    assertEquals(net.bufferCapacity(), net.readout().capacity());
                }
            }
        }
    }

    @Test
    void disposedNetworkReadsNone() {
        EnergyEndpoint producer = EnergyEndpoint.producer();
        producer.setSupply(10);
        network.add(producer);
        ticks(1);
        network.dispose();
        assertSame(EnergyReadout.NONE, network.readout());
    }
}
