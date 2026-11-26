package dev.silencebound.maxtech.storage.crafting.job;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** Where a crafting job is in its life (M7 C16, C19, PRD 7.2 partial results). */
public enum JobState implements StringRepresentable {
    /** Planned and waiting for a CPU. */
    PLANNED("planned"),
    /** A CPU runs it. */
    RUNNING("running"),
    /** Stopped on a missing leaf with every satisfiable sub-tree done; may resume. */
    PARTIAL("partial"),
    /** Everything came out. */
    DONE("done"),
    /** Cancelled; intermediates went back to storage. */
    CANCELLED("cancelled");

    public static final Codec<JobState> CODEC = StringRepresentable.fromEnum(JobState::values);

    private final String name;

    JobState(String name) {
        this.name = name;
    }

    /** Whether the job is over and holds nothing any more. */
    public boolean finished() {
        return this == DONE || this == CANCELLED;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
