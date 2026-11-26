package dev.silencebound.maxtech.storage.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import org.junit.jupiter.api.Test;

class ClusterStatsTest {
    /** The M6c2 starting numbers of the further modules. */
    private static final ClusterStats.Modules MODULES = new ClusterStats.Modules(10, 40, 10);
    /** The default card slots and limits (M7R item 2). */
    private static final ClusterStats.Cards CARDS = new ClusterStats.Cards(4, 1, 12, 4, 4, 1);
    /** One block holds what the old core alone held (M7R item 1). */
    private static final ClusterStats.Numbers NUMBERS = new ClusterStats.Numbers(10_000, 1_000, 25, 100, MODULES,
            CARDS);
    /** The default size steps. */
    private static final ClusterStats.Size SIZE = new ClusterStats.Size(8, 16, 32, 64, 64);

    private static int[] counts(ModuleKind kind, int count) {
        int[] counts = new int[ModuleKind.count()];
        counts[kind.index()] = count;
        return counts;
    }

    /** Stats of {@code blocks} blocks without modules. */
    private static ClusterStats plain(int blocks, long bufferMu, long inputPerTick, int batchPercent) {
        return new ClusterStats(blocks, bufferMu, inputPerTick, batchPercent, 100, 0);
    }

    @Test
    void oneBlockIsTheOldCore() {
        assertEquals(plain(1, 10_000, 1_000, 100), ClusterStats.single(NUMBERS));
    }

    @Test
    void blocksAddUp() {
        assertEquals(plain(3, 30_000, 3_000, 100), ClusterStats.of(3, new int[0], NUMBERS));
        assertEquals(plain(64, 640_000, 64_000, 100), ClusterStats.of(64, new int[0], NUMBERS));
        assertEquals(ClusterStats.single(NUMBERS), ClusterStats.of(0, new int[0], NUMBERS), "at least one block");
    }

    @Test
    void retiredModulesAddNothing() {
        for (ModuleKind kind : ModuleKind.values()) {
            if (kind.retired() && !kind.card()) {
                assertEquals(ClusterStats.single(NUMBERS), ClusterStats.of(1, counts(kind, 5), NUMBERS),
                        kind.getSerializedName());
            }
        }
        assertTrue(ModuleKind.CHANNEL_ARRAY.retired() && ModuleKind.CAPACITOR_BANK.retired()
                && ModuleKind.DEVICE_BUS.retired(), "the three retired kinds");
        assertFalse(ModuleKind.SECURITY_MODULE.retired(), "the security module is no retired block");
        assertTrue(ModuleKind.BATCH_AMPLIFIER.card() && ModuleKind.EFFICIENCY_CORE.card()
                && ModuleKind.SURGE_REGULATOR.card(), "the three card kinds");
        assertTrue(ModuleKind.BATCH_AMPLIFIER.retired(), "card kinds no longer attach as blocks");
        assertFalse(ModuleKind.CAPACITOR_BANK.card(), "a bank is no card");
    }

    @Test
    void amplifiersCapAtTheirMaximum() {
        assertEquals(125, ClusterStats.of(2, counts(ModuleKind.BATCH_AMPLIFIER, 1), NUMBERS).batchPercent());
        assertEquals(200, ClusterStats.of(2, counts(ModuleKind.BATCH_AMPLIFIER, 4), NUMBERS).batchPercent());
        assertEquals(200, ClusterStats.of(2, counts(ModuleKind.BATCH_AMPLIFIER, 9), NUMBERS).batchPercent(),
                "capped at +100 %");
    }

    @Test
    void deletedKindIndicesStayFree() {
        // The priority router (4) and burst buffer (5) are deleted; their indices are never reused.
        assertNull(ModuleKind.byIndex(4));
        assertNull(ModuleKind.byIndex(5));
        // The status crown (12) and light strip (13) were removed on 2026-10-18.
        assertNull(ModuleKind.byIndex(12));
        assertNull(ModuleKind.byIndex(13));
        assertEquals(ModuleKind.ANTENNA_MOUNT, ModuleKind.byIndex(14));
        assertEquals(18, ModuleKind.count(), "count arrays keep their length");
        assertEquals(ModuleKind.EFFICIENCY_CORE, ModuleKind.byIndex(6));
    }

    @Test
    void efficiencyCoresSaveUpToTheirCap() {
        assertEquals(90, ClusterStats.of(1, counts(ModuleKind.EFFICIENCY_CORE, 1), NUMBERS).energyPercent());
        assertEquals(60, ClusterStats.of(1, counts(ModuleKind.EFFICIENCY_CORE, 4), NUMBERS).energyPercent());
        assertEquals(60, ClusterStats.of(1, counts(ModuleKind.EFFICIENCY_CORE, 9), NUMBERS).energyPercent(),
                "capped at -40 %");
    }

    @Test
    void surgeRegulatorSetsTheReserve() {
        assertEquals(0, ClusterStats.single(NUMBERS).surgeReservePercent());
        assertEquals(10, ClusterStats.of(1, counts(ModuleKind.SURGE_REGULATOR, 1), NUMBERS).surgeReservePercent());
        assertEquals(10, ClusterStats.of(1, counts(ModuleKind.SURGE_REGULATOR, 3), NUMBERS).surgeReservePercent());
    }

    @Test
    void networkDevicesAddNothing() {
        // M7R item 4: the security module, anchor, mounts and bridge are network devices, read from the network.
        int devices = 0;
        for (ModuleKind kind : ModuleKind.values()) {
            if (kind.device()) {
                devices++;
                assertFalse(kind.retired() || kind.card() || kind.builtIn(), kind.getSerializedName());
                assertEquals(ClusterStats.single(NUMBERS), ClusterStats.of(1, counts(kind, 3), NUMBERS),
                        kind.getSerializedName());
            }
        }
        assertEquals(5, devices, "five network devices");
    }

    @Test
    void negativeCountsCountAsNone() {
        assertEquals(ClusterStats.single(NUMBERS), ClusterStats.of(1, counts(ModuleKind.BATCH_AMPLIFIER, -4), NUMBERS));
    }

    @Test
    void sumsSaturate() {
        ClusterStats.Numbers huge = new ClusterStats.Numbers(Long.MAX_VALUE - 1, Long.MAX_VALUE / 2, Integer.MAX_VALUE,
                Integer.MAX_VALUE, new ClusterStats.Modules(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
                new ClusterStats.Cards(12, 12, 12, 12, 12, 12));
        ClusterStats stats = ClusterStats.of(3, counts(ModuleKind.BATCH_AMPLIFIER, 3), huge);
        assertEquals(Long.MAX_VALUE, stats.bufferMu());
        assertEquals(Long.MAX_VALUE, stats.inputPerTick());
        assertEquals(Integer.MAX_VALUE, stats.batchPercent());
    }

    @Test
    void cardSlotsGrowWithTheBlocks() {
        assertEquals(4, CARDS.slots(1), "one block");
        assertEquals(5, CARDS.slots(2), "one more per block");
        assertEquals(11, CARDS.slots(8));
        assertEquals(12, CARDS.slots(9), "at most 12");
        assertEquals(12, CARDS.slots(64));
        assertEquals(4, CARDS.slots(0), "no block counts as one");
        assertEquals(12, new ClusterStats.Cards(4, 4, 99, 4, 4, 1).slots(10), "never past the slot list");
        assertEquals(6, new ClusterStats.Cards(4, 1, 6, 4, 4, 1).slots(10), "the config's maximum");
        assertEquals(0, new ClusterStats.Cards(0, 0, 12, 4, 4, 1).slots(5), "no slots");
    }

    @Test
    void cardLimitsPerKind() {
        ClusterStats.Numbers two = new ClusterStats.Numbers(10_000, 1_000, 25, 100, MODULES,
                new ClusterStats.Cards(4, 1, 12, 2, 1, 0));
        assertEquals(150, ClusterStats.of(1, counts(ModuleKind.BATCH_AMPLIFIER, 4), two).batchPercent(),
                "two amplifiers count");
        assertEquals(90, ClusterStats.of(1, counts(ModuleKind.EFFICIENCY_CORE, 4), two).energyPercent(),
                "one core counts");
        assertEquals(0, ClusterStats.of(1, counts(ModuleKind.SURGE_REGULATOR, 1), two).surgeReservePercent(),
                "no regulator counts");
        assertEquals(4, CARDS.limit(CardKind.EFFICIENCY_CORE));
        assertEquals(1, CARDS.limit(CardKind.SURGE_REGULATOR));
    }

    @Test
    void sizeFollowsResearchSteps() {
        assertEquals(8, SIZE.cap(false, false, false), "no research");
        assertEquals(16, SIZE.cap(true, false, false));
        assertEquals(32, SIZE.cap(true, true, false));
        assertEquals(64, SIZE.cap(true, true, true));
        assertEquals(32, SIZE.cap(false, true, false), "a pack that reorders the nodes");
    }

    @Test
    void sizeNeverExceedsTheMaximum() {
        ClusterStats.Size capped = new ClusterStats.Size(8, 16, 32, 128, 20);
        assertEquals(8, capped.cap(false, false, false));
        assertEquals(20, capped.cap(true, true, false));
        assertEquals(20, capped.cap(true, true, true));
        assertEquals(1, new ClusterStats.Size(0, 0, 0, 0, 0).cap(false, false, false), "at least one block");
    }
}
