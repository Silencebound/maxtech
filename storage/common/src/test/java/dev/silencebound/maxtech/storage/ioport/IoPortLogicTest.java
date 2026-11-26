package dev.silencebound.maxtech.storage.ioport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IoPortLogicTest {
    @Test
    void eachCardMultipliesTheOperations() {
        assertEquals(4, IoPortLogic.operations(4, 0, 2.0), "no cards");
        assertEquals(8, IoPortLogic.operations(4, 1, 2.0), "one card");
        assertEquals(32, IoPortLogic.operations(4, 3, 2.0), "three cards: x8");
        assertEquals(6, IoPortLogic.operations(4, 1, 1.5), "another multiplier");
        assertEquals(13, IoPortLogic.operations(3, 2, 2.1), "rounded down from 13.23");
    }

    @Test
    void operationsStayWithinBounds() {
        assertEquals(1, IoPortLogic.operations(0, 0, 2.0), "at least one");
        assertEquals(4, IoPortLogic.operations(4, 2, 0.5), "a multiplier below 1 never slows the port");
        assertEquals(IoPortLogic.MAX_OPERATIONS, IoPortLogic.operations(1024, 3, 16.0), "capped");
    }

    @Test
    void emptyAndFullLeaveBeforeTheWorkIsDone() {
        assertTrue(IoPortLogic.leaves(IoPortOutput.EMPTY, true, false, false), "empty cell, setting empty");
        assertFalse(IoPortLogic.leaves(IoPortOutput.EMPTY, false, false, true), "done but not empty");
        assertTrue(IoPortLogic.leaves(IoPortOutput.FULL, false, true, false), "full cell, setting full");
        assertFalse(IoPortLogic.leaves(IoPortOutput.FULL, true, false, true), "done but not full");
    }

    @Test
    void workDoneLeavesOnlyWhenDone() {
        assertFalse(IoPortLogic.leaves(IoPortOutput.WORK_DONE, true, false, false), "empty but not done");
        assertFalse(IoPortLogic.leaves(IoPortOutput.WORK_DONE, false, true, false), "full but not done");
        assertTrue(IoPortLogic.leaves(IoPortOutput.WORK_DONE, false, false, true), "done");
    }

    @Test
    void unloadFinishesEmptyAndLoadFull() {
        assertTrue(IoPortLogic.finished(IoPortMode.UNLOAD, true, false), "unloaded empty cell");
        assertFalse(IoPortLogic.finished(IoPortMode.UNLOAD, false, true), "unloading a full cell");
        assertTrue(IoPortLogic.finished(IoPortMode.LOAD, false, true), "loaded full cell");
        assertFalse(IoPortLogic.finished(IoPortMode.LOAD, true, false), "loading an empty cell");
    }

    @Test
    void settingsKeepTheirIds() {
        for (IoPortMode mode : IoPortMode.values()) {
            assertEquals(mode, IoPortMode.byId(mode.id()), mode + " by its id");
        }
        for (IoPortOutput output : IoPortOutput.values()) {
            assertEquals(output, IoPortOutput.byId(output.id()), output + " by its id");
        }
        assertEquals(IoPortMode.UNLOAD, IoPortMode.byId(99), "unknown mode");
        assertEquals(IoPortOutput.WORK_DONE, IoPortOutput.byId(-1), "unknown output setting");
        assertEquals(IoPortMode.UNLOAD, IoPortMode.LOAD.next(), "mode wraps");
        assertEquals(IoPortOutput.EMPTY, IoPortOutput.WORK_DONE.next(), "output setting wraps");
    }
}
