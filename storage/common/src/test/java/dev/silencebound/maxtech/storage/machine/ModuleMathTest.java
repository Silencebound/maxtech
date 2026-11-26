package dev.silencebound.maxtech.storage.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModuleMathTest {
    @Test
    void speedHalvesTheRunAndRoundsUp() {
        assertEquals(60, ModuleMath.duration(120, 100));
        assertEquals(61, ModuleMath.duration(121, 100));
        assertEquals(120, ModuleMath.duration(120, 0));
        assertEquals(1, ModuleMath.duration(1, 1000));
    }

    @Test
    void energyFollowsThePercent() {
        // 10 FE a tick over 120 ticks: 1,200 FE plain, +150 % with speed, -40 % with efficiency.
        assertEquals(1200, ModuleMath.runEnergy(10, 120, 100));
        assertEquals(3000, ModuleMath.runEnergy(10, 120, 250));
        assertEquals(720, ModuleMath.runEnergy(10, 120, 60));
        assertEquals(1, ModuleMath.runEnergy(1, 1, 60));
    }

    @Test
    void ticksAddUpToTheRun() {
        for (long total : new long[]{0, 1, 7, 720, 3000, 3001}) {
            for (int duration : new int[]{1, 7, 60, 121}) {
                long sum = 0;
                long max = 0;
                long min = Long.MAX_VALUE;
                for (int tick = 0; tick < duration; tick++) {
                    long cost = ModuleMath.energyForTick(total, duration, tick);
                    sum += cost;
                    max = Math.max(max, cost);
                    min = Math.min(min, cost);
                }
                assertEquals(total, sum, total + " over " + duration);
                // Spread evenly: no tick pays more than one FE above another.
                assertEquals(true, max - min <= 1, total + " over " + duration);
            }
        }
        assertEquals(ModuleMath.energyForTick(100, 7, 6), ModuleMath.energyForTick(100, 7, 9));
    }
}
