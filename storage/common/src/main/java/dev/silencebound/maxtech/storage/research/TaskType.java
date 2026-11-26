package dev.silencebound.maxtech.storage.research;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/**
 * What a research task counts. Every task uses Storage's own mechanics (PRD 7.2), counted per team from events: a
 * {@link #highest} type keeps the highest value any of the team's networks reached, the others add up.
 */
public enum TaskType implements StringRepresentable {
    /** Kinds stored in one network at once; the target can scale with the pack's item count. */
    STORE_KINDS(true),
    /** Items stored in one network at once, optionally of one item or tag. */
    STORE_ITEMS(true),
    /** How full one cell got, in percent of its bytes. */
    CELL_FILL(true),
    /** Finished machine runs, optionally of one machine. */
    MACHINE_RUNS(false),
    /** Items made in the fabricator's refine mode. */
    REFINE_AMOUNT(false),
    /** Processors (items tagged {@code maxtech_storage:processors}) made in machines. */
    PROCESSORS_MADE(false),
    /** Drives in one working network at once. */
    DRIVES_RUNNING(true),
    /** Items ports moved between networks and inventories. */
    PORTS_THROUGHPUT(false);

    public static final Codec<TaskType> CODEC = StringRepresentable.fromEnum(TaskType::values);

    private final boolean highest;
    private final String name;

    TaskType(boolean highest) {
        this.highest = highest;
        this.name = name().toLowerCase(Locale.ROOT);
    }

    /** Whether the team keeps the highest value reached instead of a sum. */
    public boolean highest() {
        return highest;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
