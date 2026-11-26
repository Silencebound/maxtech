package dev.silencebound.maxtech.storage.crafting.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The badge and toast mapping of crafting job notices (B09 item 21d): which live state and which ending gives which
 * notice, which badge each notice puts on the jobs tab, and how one player's jobs sum up to the tab's single badge.
 */
class JobNoticeTest {
    @Test
    void liveStates() {
        assertEquals(JobNotice.WAITING_CPU, JobNotice.live(JobState.PLANNED, false, false));
        assertEquals(JobNotice.RUNNING, JobNotice.live(JobState.RUNNING, false, false));
        assertEquals(JobNotice.WAITING_POWER, JobNotice.live(JobState.RUNNING, true, true), "power comes first");
        assertEquals(JobNotice.PROVIDER_LOCKED, JobNotice.live(JobState.RUNNING, false, true), "held by a lock");
        for (JobState ended : new JobState[]{JobState.PARTIAL, JobState.DONE, JobState.CANCELLED}) {
            assertNull(JobNotice.live(ended, false, true), ended + " is told by ended()");
        }
    }

    @Test
    void endings() {
        assertEquals(JobNotice.DONE, JobNotice.ended(JobState.DONE, false, false, false, false));
        assertEquals(JobNotice.MISSING, JobNotice.ended(JobState.PARTIAL, true, true, true, true),
                "missing items come first");
        assertEquals(JobNotice.PROVIDER_LOCKED, JobNotice.ended(JobState.PARTIAL, true, false, true, true));
        assertEquals(JobNotice.RECIPE_CHANGED, JobNotice.ended(JobState.PARTIAL, true, false, false, true));
        assertEquals(JobNotice.STOPPED, JobNotice.ended(JobState.PARTIAL, true, false, false, false));
        // Classic mode could not start it for want of materials: cancelled, but it reads as missing items.
        assertEquals(JobNotice.MISSING, JobNotice.ended(JobState.CANCELLED, true, true, false, false));
        assertEquals(JobNotice.CANCELLED, JobNotice.ended(JobState.CANCELLED, false, false, true, true),
                "a plain cancel ignores what the providers say");
    }

    @Test
    void badges() {
        for (JobNotice notice : JobNotice.values()) {
            JobNotice.Alert expected;
            if (notice == JobNotice.WAITING_CPU || notice == JobNotice.WAITING_POWER) {
                expected = JobNotice.Alert.WARN;
            } else if (notice.ending() && !notice.gone()) {
                expected = JobNotice.Alert.ERROR;
            } else {
                expected = JobNotice.Alert.NONE;
            }
            assertEquals(expected, notice.alert(), notice.name());
        }
        assertTrue(JobNotice.DONE.ending() && JobNotice.CANCELLED.ending() && JobNotice.MISSING.ending());
        assertFalse(JobNotice.RUNNING.ending() || JobNotice.WAITING_CPU.ending());
        assertEquals(JobNotice.Alert.NONE, JobNotice.stuck(false, false));
        assertEquals(JobNotice.Alert.WARN, JobNotice.stuck(true, true), "no power is a warning");
        assertEquals(JobNotice.Alert.ERROR, JobNotice.stuck(true, false));
        assertTrue(JobNotice.Alert.ERROR.rank() > JobNotice.Alert.WARN.rank()
                && JobNotice.Alert.WARN.rank() > JobNotice.Alert.NONE.rank());
    }

    @Test
    void stateKeepsTheWorstJob() {
        JobAlertState state = new JobAlertState();
        UUID waiting = UUID.randomUUID();
        UUID stopped = UUID.randomUUID();
        assertNull(state.worst());
        assertTrue(state.apply(waiting, JobNotice.WAITING_CPU));
        assertEquals(JobNotice.WAITING_CPU, state.worst());
        assertTrue(state.apply(stopped, JobNotice.MISSING), "an error beats a warning");
        assertEquals(JobNotice.MISSING, state.worst());
        assertEquals(stopped, state.worstJob());
        int version = state.version();
        assertFalse(state.apply(waiting, JobNotice.WAITING_POWER), "the worst did not change");
        assertEquals(version, state.version());
        assertTrue(state.apply(stopped, JobNotice.WAITING_CPU), "resumed: back to a warning");
        assertEquals(JobNotice.WAITING_POWER, state.worst(), "the first among equals");
        state.apply(waiting, JobNotice.RUNNING);
        assertEquals(1, state.size());
        state.apply(stopped, JobNotice.CANCELLED);
        assertNull(state.worst(), "running and ended jobs leave no badge");
        assertEquals(0, state.size());
        state.apply(stopped, JobNotice.STOPPED);
        state.clear();
        assertNull(state.worst());
    }
}
