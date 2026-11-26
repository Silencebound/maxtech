package dev.silencebound.maxtech.storage.crafting.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class CraftLimitsTest {
    private static CraftLimits decode(String json) {
        return CraftLimits.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void outOfRangeValuesAreClampedNotRefused() {
        assertEquals(new CraftLimits(CraftLimits.MAX_PER_JOB, CraftLimits.MAX_KEPT_FREE),
                decode("{\"max_per_job\": 5000000, \"cpus_kept_free\": 999}"));
        assertEquals(CraftLimits.DEFAULT, decode("{\"max_per_job\": -40, \"cpus_kept_free\": -7}"));
        assertEquals(CraftLimits.DEFAULT, decode("{}"), "missing fields follow the config");
    }

    @Test
    void stepsMoveThroughTheLadder() {
        assertEquals(256, new CraftLimits(1024, 0).stepMaxPerJob(false).maxPerJob());
        assertEquals(4096, new CraftLimits(1024, 0).stepMaxPerJob(true).maxPerJob());
        assertEquals(0, new CraftLimits(CraftLimits.MAX_PER_JOB, 0).stepMaxPerJob(true).maxPerJob(), "then no limit");
        assertEquals(CraftLimits.MAX_PER_JOB, new CraftLimits(0, 0).stepMaxPerJob(false).maxPerJob());
        assertEquals(0, new CraftLimits(64, 0).stepKeptFree(false).cpusKeptFree(), "never below 0");
    }
}
