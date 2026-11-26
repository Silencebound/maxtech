package dev.silencebound.maxtech.storage.logic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShareLimitsTest {
    @Test
    void eachKindHasItsOwnInterval() {
        ShareLimits limits = new ShareLimits(20);
        UUID player = UUID.randomUUID();
        assertTrue(limits.allow(player, ShareLimits.IMPORT, 20, 100));
        assertFalse(limits.allow(player, ShareLimits.IMPORT, 20, 119), "a second import within a second");
        assertTrue(limits.allow(player, ShareLimits.EXPORT, 5, 119), "exports count apart");
        assertTrue(limits.allow(player, ShareLimits.IMPORT, 20, 139), "refused requests count: 119 + 20");
        assertTrue(limits.allow(UUID.randomUUID(), ShareLimits.IMPORT, 20, 139), "another player");
    }

    @Test
    void aClockThatWentBackAllows() {
        ShareLimits limits = new ShareLimits(20);
        UUID player = UUID.randomUUID();
        assertTrue(limits.allow(player, ShareLimits.IMPORT, 20, 5000));
        assertTrue(limits.allow(player, ShareLimits.IMPORT, 20, 3), "a new server starts its ticks at 0");
    }

    @Test
    void oldEntriesAreDroppedSoTheMapStaysSmall() {
        ShareLimits limits = new ShareLimits(20);
        for (int i = 0; i < 10_000; i++) {
            // One new player every 5 ticks: at most 4 of them are recent at any time.
            limits.allow(UUID.randomUUID(), ShareLimits.IMPORT, 20, i * 5L);
        }
        assertTrue(limits.size() <= ShareLimits.PRUNE_AT, "size " + limits.size());
        limits.prune(10_000 * 5L + 100);
        assertEquals(0, limits.size());
    }
}
