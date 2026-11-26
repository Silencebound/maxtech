package dev.silencebound.maxtech.lib.api.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The energy pass: priorities, linear brownout, buffers, sleep and wake, catch-up equal to ticking every tick. */
class EnergyNetworkTest {
    private final long[] time = {100};
    private final EnergyScheduler scheduler = new EnergyScheduler(() -> time[0]);
    private final EnergyNetwork network = scheduler.createNetwork();

    /** Ends the current tick (the scheduler runs) and starts the next. */
    private void tick() {
        scheduler.tick();
        time[0]++;
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            tick();
        }
    }

    private EnergyEndpoint producer(long supply) {
        EnergyEndpoint producer = EnergyEndpoint.producer();
        producer.setSupply(supply);
        network.add(producer);
        return producer;
    }

    private EnergyEndpoint consumer(long demand, int priority) {
        EnergyEndpoint consumer = EnergyEndpoint.consumer(priority);
        consumer.setDemand(demand);
        network.add(consumer);
        return consumer;
    }

    private EnergyEndpoint buffer(long capacity, long stored, long rate, int priority) {
        EnergyEndpoint buffer = EnergyEndpoint.buffer(capacity, rate, rate, priority);
        buffer.setStored(stored);
        network.add(buffer);
        return buffer;
    }

    @Test
    void producerFeedsConsumer() {
        EnergyEndpoint producer = producer(100);
        EnergyEndpoint consumer = consumer(60, EnergyPriority.DEFAULT);
        tick();
        assertEquals(60, consumer.received());
        assertEquals(60, producer.drawn());
        assertEquals(1.0, consumer.suppliedFraction());
        assertEquals(1.0, network.satisfaction());
        assertEquals(100, network.producerSupply());
    }

    @Test
    void brownoutIsLinearWithinAPriority() {
        producer(50);
        EnergyEndpoint small = consumer(40, EnergyPriority.DEFAULT);
        EnergyEndpoint large = consumer(60, EnergyPriority.DEFAULT);
        tick();
        assertEquals(20, small.received());
        assertEquals(30, large.received());
        assertEquals(0.5, small.suppliedFraction());
        assertEquals(0.5, large.suppliedFraction());
        assertEquals(0.5, network.satisfaction());
    }

    @Test
    void unevenSharesStillAddUp() {
        EnergyEndpoint producer = producer(10);
        EnergyEndpoint[] consumers = new EnergyEndpoint[3];
        for (int i = 0; i < consumers.length; i++) {
            consumers[i] = consumer(7, EnergyPriority.DEFAULT);
        }
        tick();
        long received = 0;
        for (EnergyEndpoint consumer : consumers) {
            assertTrue(consumer.received() == 3 || consumer.received() == 4, "within one MU of an even share");
            received += consumer.received();
        }
        assertEquals(10, received, "every MU drawn is handed out");
        assertEquals(10, producer.drawn());
    }

    @Test
    void higherPrioritiesAreServedFirst() {
        producer(100);
        EnergyEndpoint low = consumer(80, -1);
        EnergyEndpoint high = consumer(70, 5);
        EnergyEndpoint middle = consumer(20, EnergyPriority.DEFAULT);
        tick();
        assertEquals(70, high.received());
        assertEquals(20, middle.received());
        assertEquals(10, low.received());
        low.setPriority(10);
        tick();
        assertEquals(80, low.received(), "re-sorted after a priority change");
        assertEquals(20, high.received());
        assertEquals(0, middle.received());
    }

    @Test
    void leftoversBufferChargesOnlyFromWhatMachinesLeave() {
        producer(100);
        EnergyEndpoint battery = buffer(10_000, 0, 1_000, EnergyPriority.LEFTOVERS);
        EnergyEndpoint machine = consumer(70, -100);
        tick();
        assertEquals(70, machine.received(), "a machine at any priority goes first");
        assertEquals(30, battery.received());
        machine.setDemand(100);
        tick();
        assertEquals(0, battery.flow(), "nothing left over");
    }

    @Test
    void bufferAtAPriorityChargesBeforeLowerMachines() {
        producer(100);
        EnergyEndpoint battery = buffer(10_000, 0, 80, 1);
        EnergyEndpoint machine = consumer(100, EnergyPriority.DEFAULT);
        tick();
        assertEquals(80, battery.received());
        assertEquals(20, machine.received());
    }

    @Test
    void buffersCoverShortfallAndDrainWhileAsleep() {
        EnergyEndpoint battery = buffer(1_000, 1_000, 100, EnergyPriority.DEFAULT);
        EnergyEndpoint machine = consumer(150, EnergyPriority.DEFAULT);
        tick();
        assertEquals(100, machine.received());
        assertEquals(-100, battery.flow());
        assertEquals(2.0 / 3.0, machine.suppliedFraction(), 1e-9);
        long passes = network.passCount();
        ticks(5);
        assertEquals(passes, network.passCount(), "constant flows: the network sleeps");
        assertEquals(400, battery.stored(), "reading settles the six ticks since the pass");
        ticks(5);
        assertEquals(0, battery.stored());
        assertEquals(0, machine.received(), "woke when the battery ran low");
        assertTrue(network.passCount() - passes <= 2, "only the predicted wake ran");
    }

    @Test
    void producersChargeBuffersUntilFullWithoutOvershoot() {
        EnergyEndpoint producer = producer(70);
        EnergyEndpoint battery = buffer(1_000, 0, 100, EnergyPriority.DEFAULT);
        tick();
        assertEquals(70, battery.received());
        ticks(30);
        assertEquals(1_000, battery.stored(), "filled to exactly its capacity");
        assertEquals(0, producer.drawn(), "a full battery takes nothing");
        assertTrue(network.passCount() <= 5, network.passCount() + " passes for one fill");
    }

    @Test
    void sleepsUntilADeclarationChanges() {
        EnergyEndpoint producer = producer(100);
        EnergyEndpoint machine = consumer(50, EnergyPriority.DEFAULT);
        tick();
        long passes = network.passCount();
        ticks(100);
        assertEquals(passes, network.passCount(), "nothing changed, nothing ran");
        assertTrue(network.isAsleep());
        machine.setDemand(50);
        tick();
        assertEquals(passes, network.passCount(), "the same declaration is no change");
        machine.setDemand(80);
        assertFalse(network.isAsleep());
        tick();
        assertEquals(passes + 1, network.passCount(), "a change wakes it for one pass");
        assertEquals(80, producer.drawn());
        ticks(10);
        assertEquals(passes + 1, network.passCount());
    }

    @Test
    void addRemovePauseAndDispose() {
        EnergyEndpoint producer = producer(100);
        EnergyEndpoint machine = consumer(50, EnergyPriority.DEFAULT);
        tick();
        assertTrue(network.remove(machine));
        assertEquals(0, machine.flow(), "a removed endpoint gets nothing");
        assertTrue(machine.network().isEmpty());
        tick();
        assertEquals(0, producer.drawn());
        network.add(machine);
        network.setPaused(true);
        tick();
        assertEquals(0, machine.flow(), "a paused network hands out nothing");
        network.setPaused(false);
        tick();
        assertEquals(50, machine.received());
        EnergyNetwork other = scheduler.createNetwork();
        assertThrows(IllegalStateException.class, () -> other.add(machine), "one network at a time");
        other.dispose();
        network.dispose();
        assertEquals(0, machine.flow());
        assertEquals(0, scheduler.networkCount());
        tick();
    }

    @Test
    void listenerHearsChangesAndMayRedeclare() {
        producer(100);
        EnergyEndpoint machine = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        List<Long> heard = new ArrayList<>();
        machine.setListener(endpoint -> {
            heard.add(endpoint.flow());
            if (endpoint.flow() == 40) {
                endpoint.setDemand(60);
            }
        });
        machine.setDemand(40);
        network.add(machine);
        tick();
        tick();
        assertEquals(List.of(40L, 60L), heard, "the redeclared demand ran in the next tick");
        machine.setListener(endpoint -> network.add(EnergyEndpoint.producer()));
        machine.setDemand(10);
        assertThrows(IllegalStateException.class, this::tick, "no adding from a listener");
    }

    @Test
    void ownerExtractAndInsertWakeTheNetwork() {
        EnergyEndpoint battery = buffer(1_000, 500, 100, EnergyPriority.DEFAULT);
        tick();
        long passes = network.passCount();
        assertEquals(200, battery.extract(200));
        assertEquals(300, battery.stored());
        assertEquals(700, battery.insert(10_000), "not limited by the network rate");
        assertEquals(0, battery.extract(0));
        tick();
        assertEquals(passes + 1, network.passCount());
        assertEquals(EnergyEndpoint.MAX_RATE, EnergyEndpoint.buffer(1, Long.MAX_VALUE, -1, 0).maxInput(), "clamped");
        assertThrows(IllegalStateException.class, () -> battery.setDemand(1));
    }

    @Test
    void passesAndSleepingTicksAllocateNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        producer(1_000);
        for (int i = 0; i < 50; i++) {
            consumer(30, i % 5);
        }
        buffer(1_000_000, 500_000, 100, EnergyPriority.LEFTOVERS);
        for (int i = 0; i < 20_000; i++) {
            network.wake();
            tick();
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 10_000; i++) {
            network.wake();
            tick();
            tick();
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        // Tolerates the measuring call itself; one allocation per pass would be at least 160 KB.
        assertTrue(allocated < 4_096, allocated + " bytes allocated by 10,000 passes and sleeping ticks");
    }

    /**
     * Property: a network that sleeps gives, every tick, the same flows and buffer levels as the same network forced to
     * run its pass every tick, and every pass hands out exactly what it draws.
     */
    @Test
    void catchUpEqualsTickingEveryTick() {
        Random random = new Random(26_10_15L);
        for (int run = 0; run < 60; run++) {
            long seed = random.nextLong();
            Scenario sleeping = new Scenario(seed);
            Scenario ticking = new Scenario(seed);
            Random changes = new Random(seed ^ 0x5DEECE66DL);
            for (int t = 0; t < 400; t++) {
                if (changes.nextInt(25) == 0) {
                    int index = changes.nextInt(sleeping.endpoints.size());
                    long value = changes.nextInt(300);
                    int priority = changes.nextInt(3) - 1;
                    sleeping.change(index, value, priority);
                    ticking.change(index, value, priority);
                }
                ticking.network.wake();
                sleeping.tick();
                ticking.tick();
                ticking.assertConserved(run, t);
                for (int i = 0; i < sleeping.endpoints.size(); i++) {
                    EnergyEndpoint a = sleeping.endpoints.get(i);
                    EnergyEndpoint b = ticking.endpoints.get(i);
                    String where = "run " + run + " tick " + t + " endpoint " + i + " (" + a.role() + ")";
                    assertEquals(b.flow(), a.flow(), where + " flow");
                    assertEquals(b.stored(), a.stored(), where + " stored");
                }
            }
            assertTrue(sleeping.network.passCount() < ticking.network.passCount(), "the sleeping one slept");
        }
    }

    /** A random network with its own clock. */
    private static final class Scenario {
        final long[] clock = {0};
        final EnergyScheduler scheduler = new EnergyScheduler(() -> clock[0]);
        final EnergyNetwork network = scheduler.createNetwork();
        final List<EnergyEndpoint> endpoints = new ArrayList<>();

        Scenario(long seed) {
            Random random = new Random(seed);
            int count = 2 + random.nextInt(10);
            for (int i = 0; i < count; i++) {
                int priority = random.nextInt(3) - 1;
                EnergyEndpoint endpoint = switch (random.nextInt(3)) {
                    case 0 -> {
                        EnergyEndpoint producer = EnergyEndpoint.producer();
                        producer.setSupply(random.nextInt(200));
                        yield producer;
                    }
                    case 1 -> {
                        EnergyEndpoint consumer = EnergyEndpoint.consumer(priority);
                        consumer.setDemand(random.nextInt(200));
                        yield consumer;
                    }
                    default -> {
                        long capacity = 1L + random.nextInt(5_000);
                        EnergyEndpoint buffer = EnergyEndpoint.buffer(capacity, 1 + random.nextInt(150),
                                1 + random.nextInt(150), random.nextInt(4) == 0 ? EnergyPriority.LEFTOVERS : priority);
                        buffer.setStored(random.nextLong(capacity + 1));
                        yield buffer;
                    }
                };
                endpoints.add(endpoint);
                network.add(endpoint);
            }
        }

        void change(int index, long value, int priority) {
            EnergyEndpoint endpoint = endpoints.get(index);
            switch (endpoint.role()) {
                case PRODUCER -> endpoint.setSupply(value);
                case CONSUMER -> {
                    endpoint.setDemand(value);
                    endpoint.setPriority(priority);
                }
                case BUFFER -> endpoint.extract(value);
            }
        }

        void tick() {
            scheduler.tick();
            clock[0]++;
        }

        void assertConserved(int run, int t) {
            long sum = 0;
            for (EnergyEndpoint endpoint : endpoints) {
                sum += endpoint.flow();
            }
            assertEquals(0, sum, "run " + run + " tick " + t + ": a pass hands out exactly what it draws");
        }
    }
}
