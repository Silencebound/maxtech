package dev.silencebound.maxtech.storage.save;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Storage's migration steps (M7R items 1 to 6: the controller cluster becomes a multiblock). */
class StorageSaveFormatsTest {
    private static final Identifier CELL = Identifier.fromNamespaceAndPath("maxtech_storage", "energy_cell");
    private static final Identifier GENERATOR = Identifier.fromNamespaceAndPath("maxtech_storage", "fuel_generator");

    @BeforeEach
    void oneToOne() {
        // JUnit has no Lib config; the step asks this instead.
        StorageSaveFormats.useRate(() -> FeRate.ONE_TO_ONE);
    }

    @AfterEach
    void reset() {
        StorageSaveFormats.useRate(() -> FeRate.ONE_TO_ONE);
    }

    private static CompoundTag withEnergy(int version, long fe) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SaveFormat.VERSION_KEY, version);
        tag.putLong("energy", fe);
        return tag;
    }

    @Test
    void storedFeBecomesMuOnceAtOneToOne() {
        for (Identifier kind : new Identifier[]{StorageSaveFormats.CONTROLLER, CELL, GENERATOR}) {
            SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(kind, withEnergy(7, 12_345L));
            assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome(), kind.toString());
            assertEquals(12_345L, migration.tag().getLongOr("energy", 0L), "1 FE is 1 MU for " + kind);
            assertFalse(migration.tag().contains("fe_held"), "no remainder at 1:1");
        }
    }

    @Test
    void storedFeBecomesMuAtAnUnevenRateWithTheRemainderKept() {
        // 3 FE are worth 2 MU: 1,000 FE are 666 MU and two thirds of one.
        StorageSaveFormats.useRate(() -> new FeRate(3, 2));
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER,
                withEnergy(7, 1_000L));
        CompoundTag tag = migration.tag();
        assertEquals(666L, tag.getLongOr("energy", 0L), "whole MU, rounded down");
        assertEquals(2L, tag.getLongOr("fe_held", 0L), "two units of a third of an MU kept");
        assertEquals(3, tag.getIntOr("fe_held_rate_fe", 0));
        assertEquals(2, tag.getIntOr("fe_held_rate_mu", 0));
        assertEquals(StorageSaveFormats.BLOCK_ENTITIES.currentVersion(), tag.getIntOr(SaveFormat.VERSION_KEY, 0));
        // Stamped: loading the migrated data again converts nothing a second time, whatever the rate is now.
        StorageSaveFormats.useRate(() -> new FeRate(1, 7));
        SaveFormat.Migration again = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, tag);
        assertEquals(SaveFormat.Outcome.CURRENT, again.outcome());
        assertEquals(666L, again.tag().getLongOr("energy", 0L), "converted once");
    }

    @Test
    void conversionKeepsTheWholeValue() {
        Random random = new Random(8);
        for (int i = 0; i < 2_000; i++) {
            FeRate rate = new FeRate(1 + random.nextInt(50), 1 + random.nextInt(50));
            long fe = random.nextInt(1 << 30);
            long[] mu = StorageSaveFormats.feToMu(fe, rate);
            assertTrue(mu[1] >= 0 && mu[1] < rate.fe(), "the remainder is less than one MU");
            // In units of 1/mu FE = 1/fe MU, both sides are whole: nothing created, nothing lost.
            assertEquals(fe * rate.mu(), mu[0] * rate.fe() + mu[1], fe + " FE at " + rate);
        }
    }

    @Test
    void tooltipsReadSavedEnergyAsMuWithoutWriting() {
        StorageSaveFormats.useRate(() -> new FeRate(3, 2));
        CompoundTag old = withEnergy(7, 1_000L);
        assertEquals(666L, StorageSaveFormats.savedMu(old), "FE from before, shown as MU");
        assertEquals(1_000L, old.getLongOr("energy", 0L), "the item's data stays as it is");
        assertEquals(1_000L, StorageSaveFormats.savedMu(withEnergy(8, 1_000L)), "already MU");
        CompoundTag none = new CompoundTag();
        none.putLong("energy", 300L);
        assertEquals(200L, StorageSaveFormats.savedMu(none), "no version is version 1");
    }

    @Test
    void blocksWithoutFePassThroughTheMove() {
        CompoundTag drive = withEnergy(7, 500L);
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES
                .migrate(Identifier.fromNamespaceAndPath("maxtech_storage", "storage_drive"), drive);
        assertEquals(500L, migration.tag().getLongOr("energy", 0L), "not an FE holder");
        StorageSaveFormats.useRate(() -> new FeRate(2, 1));
        CompoundTag empty = withEnergy(7, 0L);
        assertFalse(StorageSaveFormats.BLOCK_ENTITIES.migrate(CELL, empty).tag().contains("fe_held"), "nothing held");
    }
    private static CompoundTag oldCore() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("energy", 30_000L);
        tag.putString("network_name", "Old Cluster");
        tag.putLongArray("members", new long[]{1L, 2L});
        tag.putIntArray("kinds", new int[]{1, 2});
        tag.putBoolean("idle", true);
        tag.putBoolean("over", true);
        tag.putInt("cap", 8);
        return tag;
    }

    @Test
    void blockEntitiesAreAtVersionEight() {
        assertEquals(8, StorageSaveFormats.BLOCK_ENTITIES.currentVersion());
    }

    @Test
    void aControllerWithStatusLightsAsksForAFill() {
        for (int kind : new int[]{12, 13}) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(SaveFormat.VERSION_KEY, 6);
            tag.putLong("energy", 30_000L);
            tag.putString("network_name", "Lit");
            tag.putLongArray("members", new long[]{11L});
            tag.putIntArray("kinds", new int[]{kind});
            SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER,
                    tag);
            assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
            assertTrue(migration.tag().getBooleanOr("redetect", false), "kind " + kind + " leaves the controller");
            assertEquals(30_000L, migration.tag().getLongOr("energy", 0L), "FE kept");
            assertEquals("Lit", migration.tag().getStringOr("network_name", ""), "name kept");
        }
        CompoundTag none = new CompoundTag();
        none.putInt(SaveFormat.VERSION_KEY, 6);
        none.putIntArray("kinds", new int[]{0, 3});
        assertFalse(StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, none).tag()
                .getBooleanOr("redetect", false), "no status light, no fill");
        CompoundTag current = new CompoundTag();
        current.putInt(SaveFormat.VERSION_KEY, 8);
        current.putIntArray("kinds", new int[]{12});
        assertEquals(SaveFormat.Outcome.CURRENT,
                StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, current).outcome(),
                "a current save is not migrated again");
    }

    @Test
    void anOldStatusLightDropsItsCore() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SaveFormat.VERSION_KEY, 6);
        tag.putLong("core", 123L);
        tag.putLong("join_seq", 7L);
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.STATUS_LIGHT,
                tag);
        assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
        assertFalse(migration.tag().contains("core"), "the old core's position goes");
        assertEquals(7L, migration.tag().getLongOr("join_seq", 0L), "the light's own data stays");
    }

    @Test
    void aControllerWithCapacitorBanksListsThemAndAsksForAFill() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SaveFormat.VERSION_KEY, 5);
        tag.putLong("energy", 90_000L);
        tag.putLongArray("members", new long[]{11L, 12L, 13L});
        tag.putIntArray("kinds", new int[]{1, 12, 1});
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, tag);
        assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
        assertTrue(migration.tag().getBooleanOr("redetect", false), "the fill drops the banks");
        assertArrayEquals(new long[]{11L, 13L},
                migration.tag().getLongArray(StorageSaveFormats.BANK_CELLS).orElseThrow(), "where the banks stood");
        assertEquals(90_000L, migration.tag().getLongOr("energy", 0L), "FE kept until the fill moves it");
        CompoundTag lights = new CompoundTag();
        lights.putInt(SaveFormat.VERSION_KEY, 5);
        lights.putLongArray("members", new long[]{11L});
        lights.putIntArray("kinds", new int[]{12});
        CompoundTag out = StorageSaveFormats.CAPACITOR_BANKS_TO_CELLS.apply(StorageSaveFormats.CONTROLLER, lights);
        assertFalse(out.getBooleanOr("redetect", false), "no bank, no fill");
        assertFalse(out.contains(StorageSaveFormats.BANK_CELLS), "no bank, no list");
    }

    @Test
    void aControllerWithDeviceModulesAsksForAFill() {
        for (int kind : new int[]{8, 9, 14, 15, 17}) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(SaveFormat.VERSION_KEY, 4);
            tag.putLong("energy", 30_000L);
            tag.putIntArray("kinds", new int[]{12, kind});
            SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER,
                    tag);
            assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
            assertTrue(migration.tag().getBooleanOr("redetect", false), "kind " + kind + " leaves the controller");
            assertEquals(30_000L, migration.tag().getLongOr("energy", 0L), "FE kept");
        }
        CompoundTag lights = new CompoundTag();
        lights.putInt(SaveFormat.VERSION_KEY, 4);
        lights.putIntArray("kinds", new int[]{12, 13});
        assertFalse(StorageSaveFormats.MODULES_TO_DEVICES.apply(StorageSaveFormats.CONTROLLER, lights)
                .getBooleanOr("redetect", false), "status lights only, no fill in this step");
    }

    @Test
    void aMalformedControllerTagMigratesWithoutBanks() {
        // Nothing saved at all: unchanged apart from the stamp.
        CompoundTag empty = new CompoundTag();
        empty.putInt(SaveFormat.VERSION_KEY, 5);
        CompoundTag out = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, empty).tag();
        assertFalse(out.contains(StorageSaveFormats.BANK_CELLS), "no list without modules");
        assertFalse(out.getBooleanOr("redetect", false), "no fill without modules");
        // Fields of the wrong type read as empty.
        CompoundTag wrong = new CompoundTag();
        wrong.putInt(SaveFormat.VERSION_KEY, 5);
        wrong.putString("kinds", "1");
        wrong.putString("members", "11");
        out = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, wrong).tag();
        assertFalse(out.contains(StorageSaveFormats.BANK_CELLS), "no list from wrong types");
        // A bank kind without a member position: nothing to point at, so no entry.
        CompoundTag missing = new CompoundTag();
        missing.putInt(SaveFormat.VERSION_KEY, 5);
        missing.putIntArray("kinds", new int[]{1});
        out = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, missing).tag();
        assertFalse(out.contains(StorageSaveFormats.BANK_CELLS), "no position, no entry");
        // More kinds than members: only the paired banks are listed.
        CompoundTag shorter = new CompoundTag();
        shorter.putInt(SaveFormat.VERSION_KEY, 5);
        shorter.putIntArray("kinds", new int[]{1, 1, 1});
        shorter.putLongArray("members", new long[]{11L, 12L});
        out = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, shorter).tag();
        assertArrayEquals(new long[]{11L, 12L}, out.getLongArray(StorageSaveFormats.BANK_CELLS).orElseThrow(),
                "paired banks only");
        assertTrue(out.getBooleanOr("redetect", false), "asks for a fill");
    }

    @Test
    void anOldModuleLoadedAsADeviceDropsItsCore() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SaveFormat.VERSION_KEY, 4);
        tag.putLong("core", 123L);
        tag.putLong("join_seq", 7L);
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.SERVICE_DEVICE,
                tag);
        assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
        assertFalse(migration.tag().contains("core"), "the old core's position goes");
        assertEquals(7L, migration.tag().getLongOr("join_seq", 0L), "the device's own data stays");
        CompoundTag expansion = new CompoundTag();
        expansion.putLong("core", 123L);
        CompoundTag out = StorageSaveFormats.MODULES_TO_DEVICES
                .apply(Identifier.fromNamespaceAndPath("maxtech_storage", "controller_expansion"), expansion.copy());
        assertEquals(expansion, out, "a status light keeps its core");
    }

    @Test
    void aControllerWithRecorderModulesAsksForAFill() {
        for (int kind : new int[]{10, 11, 16}) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(SaveFormat.VERSION_KEY, 3);
            tag.putIntArray("kinds", new int[]{8, kind});
            SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER,
                    tag);
            assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
            assertTrue(migration.tag().getBooleanOr("redetect", false), "kind " + kind + " moves into the controller");
        }
        CompoundTag other = new CompoundTag();
        other.putInt(SaveFormat.VERSION_KEY, 3);
        other.putIntArray("kinds", new int[]{0, 3});
        assertFalse(StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, other).tag()
                .getBooleanOr("redetect", false), "no recorder kinds, no fill");
        CompoundTag module = new CompoundTag();
        module.putInt(SaveFormat.VERSION_KEY, 3);
        module.putIntArray("kinds", new int[]{16});
        CompoundTag out = StorageSaveFormats.CONTROLLER_MODULES_TO_RECORDERS
                .apply(Identifier.fromNamespaceAndPath("maxtech_storage", "controller_expansion"), module.copy());
        assertEquals(module, out, "the modules themselves pass through");
    }

    @Test
    void aControllerWithCardModulesAsksForAFill() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SaveFormat.VERSION_KEY, 2);
        tag.putIntArray("kinds", new int[]{8, 6});
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, tag);
        assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
        assertTrue(migration.tag().getBooleanOr("redetect", false), "an efficiency core turns into a card");
        CompoundTag other = new CompoundTag();
        other.putInt(SaveFormat.VERSION_KEY, 2);
        other.putIntArray("kinds", new int[]{0, 3});
        assertFalse(StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, other).tag()
                .getBooleanOr("redetect", false), "no card kinds, no fill");
    }

    @Test
    void anOldCoreDropsItsClusterFieldsAndAsksForAFill() {
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER,
                oldCore());
        assertEquals(SaveFormat.Outcome.MIGRATED, migration.outcome());
        CompoundTag tag = migration.tag();
        assertTrue(tag.getBooleanOr("redetect", false), "asks for a fill");
        assertFalse(tag.contains("idle"), "idle");
        assertFalse(tag.contains("over"), "over");
        assertFalse(tag.contains("cap"), "cap");
        assertEquals(30_000L, tag.getLongOr("energy", 0L), "FE kept");
        assertEquals("Old Cluster", tag.getStringOr("network_name", ""), "name kept");
        assertEquals(2, tag.getIntArray("kinds").orElseThrow().length, "modules kept for the fill");
    }

    @Test
    void otherBlockEntitiesPassThrough() {
        CompoundTag tag = oldCore();
        CompoundTag out = StorageSaveFormats.CONTROLLER_CLUSTER_TO_MULTIBLOCK
                .apply(Identifier.fromNamespaceAndPath("maxtech_storage", "storage_drive"), tag.copy());
        assertEquals(tag, out);
    }

    @Test
    void newerDataIsLeftAlone() {
        CompoundTag tag = oldCore();
        tag.putInt(SaveFormat.VERSION_KEY, StorageSaveFormats.BLOCK_ENTITIES.currentVersion() + 1);
        SaveFormat.Migration migration = StorageSaveFormats.BLOCK_ENTITIES.migrate(StorageSaveFormats.CONTROLLER, tag);
        assertEquals(SaveFormat.Outcome.FUTURE, migration.outcome());
        assertTrue(migration.tag().getBooleanOr("idle", false), "unchanged");
        assertFalse(migration.tag().contains("redetect"), "no step ran");
    }
}
