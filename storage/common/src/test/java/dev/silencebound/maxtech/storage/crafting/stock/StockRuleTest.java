package dev.silencebound.maxtech.storage.crafting.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StockRuleTest {
    private static StockRule rule(long keep, long restart, long batch) {
        return StockRule.EMPTY.withKeep(keep).withRestart(restart).withBatch(batch);
    }

    @Test
    void startsBelowTheRestartThresholdOnly() {
        StockRule rule = rule(100, 40, 0);
        assertFalse(rule.shouldStart(40));
        assertFalse(rule.shouldStart(99));
        assertTrue(rule.shouldStart(39));
    }

    @Test
    void withoutThresholdTopsUpBelowKeep() {
        StockRule rule = rule(100, 0, 0);
        assertTrue(rule.shouldStart(99));
        assertFalse(rule.shouldStart(100));
    }

    @Test
    void requestIsTheShortfallCappedByTheBatch() {
        StockRule rule = rule(100, 50, 32);
        assertEquals(32, rule.request(10));
        assertEquals(5, rule.request(95));
        assertEquals(0, rule.request(100));
        assertEquals(0, rule.request(250));
        assertEquals(100, rule(100, 50, 0).request(-3));
    }

    @Test
    void restartNeverExceedsKeepAndFollowsItUp() {
        StockRule rule = rule(64, 64, 0);
        assertEquals(64, rule.restart());
        assertEquals(128, rule.withKeep(128).restart(), "a threshold at keep follows keep");
        assertEquals(20, rule.withRestart(20).withKeep(128).restart(), "a lower threshold stays");
        assertEquals(10, rule(10, 500, 0).restart());
    }

    @Test
    void valuesAreClamped() {
        StockRule rule = StockRule.EMPTY.withPriority(1000).withCpuTier(7).withKeep(-5);
        assertEquals(StockRule.MAX_PRIORITY, rule.priority());
        assertEquals(3, rule.cpuTier());
        assertEquals(0, rule.keep());
        assertFalse(rule.active());
    }

    @Test
    void redstoneModes() {
        StockRule rule = rule(1, 0, 0);
        assertTrue(rule.enabled(false));
        assertTrue(rule.withRedstone(StockRule.Redstone.HIGH).enabled(true));
        assertFalse(rule.withRedstone(StockRule.Redstone.HIGH).enabled(false));
        assertTrue(rule.withRedstone(StockRule.Redstone.LOW).enabled(false));
        assertFalse(rule.withRedstone(StockRule.Redstone.LOW).enabled(true));
        assertEquals(StockRule.Redstone.ALWAYS, StockRule.Redstone.LOW.next());
        for (StockRule.Redstone mode : StockRule.Redstone.values()) {
            assertEquals(mode, StockRule.Redstone.byId(mode.id()));
        }
    }

    @Test
    void aRuleSavedBeforeSwitchingExistedLoadsSwitchedOn() {
        com.google.gson.JsonObject old = new com.google.gson.JsonObject();
        old.addProperty("keep", 64);
        old.addProperty("restart", 16);
        StockRule loaded = StockRule.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, old).getOrThrow();
        assertFalse(loaded.switchedOff(), "no switched_off field: on");
        assertTrue(loaded.enabled(false));
        assertEquals(64, loaded.keep());
    }

    @Test
    void switchedOffSurvivesTheCodecAndStopsRequests() {
        StockRule off = rule(100, 40, 8).withSwitchedOff(true);
        com.google.gson.JsonElement saved = StockRule.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, off)
                .getOrThrow();
        assertEquals(off, StockRule.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow());
        assertFalse(off.enabled(false), "switched off asks for nothing");
        assertFalse(off.enabled(true));
    }
}
