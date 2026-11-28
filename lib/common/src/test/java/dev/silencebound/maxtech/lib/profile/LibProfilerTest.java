package dev.silencebound.maxtech.lib.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

/** How the profiler names task classes and the mods they belong to. */
class LibProfilerTest {
    private static final class Nested extends ScheduledTask {
        Nested() {
            super(TaskPriority.BACKGROUND);
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            return DONE;
        }
    }

    @Test
    void nestedClassesKeepTheirOuterName() {
        assertEquals("LibProfilerTest.Nested", LibProfiler.taskName(Nested.class));
        assertEquals("String", LibProfiler.taskName(String.class));
    }

    @Test
    void ownerComesFromThePackage() {
        assertEquals("maxtech_lib", LibProfiler.ownerOf(Nested.class));
        assertEquals("java.lang", LibProfiler.ownerOf(String.class));
        assertEquals("it.unimi.dsi", LibProfiler.ownerOf(LongArrayList.class));
    }
}
