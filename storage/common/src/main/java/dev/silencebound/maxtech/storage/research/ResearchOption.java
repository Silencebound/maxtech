package dev.silencebound.maxtech.storage.research;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;

/**
 * One way to complete a research node; a node lists several and any one completes it (PRD 7.2, owner decision
 * 2026-10-03: a task, a puzzle or points). JSON: {@code {"type": "task", "task": "store_items", "count": 4096}},
 * {@code {"type": "puzzle", "puzzle": "maxtech_storage:byte_packing", "difficulty": 1}} or
 * {@code {"type": "points", "cost": 150}}. Immutable.
 */
public sealed interface ResearchOption {
    Codec<ResearchOption> CODEC = Kind.CODEC.dispatch("type", ResearchOption::kind, Kind::codec);

    Kind kind();

    /** The option kinds, as written in {@code type}. */
    enum Kind implements StringRepresentable {
        TASK, PUZZLE, POINTS;

        static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        MapCodec<? extends ResearchOption> codec() {
            return switch (this) {
                case TASK -> Task.CODEC;
                case PUZZLE -> Puzzle.CODEC;
                case POINTS -> Points.CODEC;
            };
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Reach a number with one of Storage's mechanics. {@code count} is the target before the preset scales it; a
     * {@link TaskType#STORE_KINDS} task may give {@code fraction} of the pack's item count instead, capped at
     * {@code max}. {@link TaskType#STORE_ITEMS} may count only one {@code item} or {@code tag}; such a task is skipped
     * where the item or the tag's items do not exist. {@link TaskType#MACHINE_RUNS} may count one {@code machine}.
     */
    record Task(TaskType task, long count, double fraction, long max, Optional<Identifier> item,
            Optional<TagKey<Item>> tag, Optional<String> machine) implements ResearchOption {
        static final MapCodec<Task> CODEC = RecordCodecBuilder
                .<Task>mapCodec(instance -> instance
                        .group(TaskType.CODEC.fieldOf("task").forGetter(Task::task),
                                Codec.LONG.optionalFieldOf("count", 0L).forGetter(Task::count),
                                Codec.DOUBLE.optionalFieldOf("fraction", 0.0).forGetter(Task::fraction),
                                Codec.LONG.optionalFieldOf("max", 0L).forGetter(Task::max),
                                Identifier.CODEC.optionalFieldOf("item").forGetter(Task::item),
                                TagKey.codec(Registries.ITEM).optionalFieldOf("tag").forGetter(Task::tag),
                                Codec.STRING.optionalFieldOf("machine").forGetter(Task::machine))
                        .apply(instance, Task::new))
                .validate(Task::check);

        /** A task counting {@code count} with no filter. */
        public static Task of(TaskType task, long count) {
            return new Task(task, count, 0, 0, Optional.empty(), Optional.empty(), Optional.empty());
        }

        /** A {@link TaskType#STORE_KINDS} task for {@code fraction} of the pack's items, at most {@code max}. */
        public static Task kinds(double fraction, long max) {
            return new Task(TaskType.STORE_KINDS, 0, fraction, max, Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        /** A {@link TaskType#STORE_ITEMS} task counting only the items in {@code tag}. */
        public static Task items(TagKey<Item> tag, long count) {
            return new Task(TaskType.STORE_ITEMS, count, 0, 0, Optional.empty(), Optional.of(tag), Optional.empty());
        }

        /** A {@link TaskType#MACHINE_RUNS} task counting only runs of {@code machine}. */
        public static Task runs(MachineKind machine, long count) {
            return new Task(TaskType.MACHINE_RUNS, count, 0, 0, Optional.empty(), Optional.empty(),
                    Optional.of(machine.id()));
        }

        private static DataResult<Task> check(Task task) {
            boolean byFraction = task.fraction > 0;
            if (byFraction && task.task != TaskType.STORE_KINDS) {
                return DataResult.error(() -> "Only store_kinds tasks take a fraction");
            }
            if (byFraction ? task.fraction > 1 : task.count <= 0) {
                return DataResult.error(() -> "A task needs a count above 0 or a fraction up to 1");
            }
            if ((task.item.isPresent() || task.tag.isPresent()) && task.task != TaskType.STORE_ITEMS) {
                return DataResult.error(() -> "Only store_items tasks take an item or a tag");
            }
            if (task.item.isPresent() && task.tag.isPresent()) {
                return DataResult.error(() -> "A task takes an item or a tag, not both");
            }
            if (task.machine.isPresent()) {
                if (task.task != TaskType.MACHINE_RUNS) {
                    return DataResult.error(() -> "Only machine_runs tasks take a machine");
                }
                String machine = task.machine.get();
                if (!MachineKind.byId(machine).id().equals(machine) || !MachineKind.byId(machine).processes()) {
                    return DataResult.error(() -> "Unknown machine " + machine);
                }
            }
            return DataResult.success(task);
        }

        @Override
        public Kind kind() {
            return Kind.TASK;
        }

        /**
         * What the team counter for this task is called; tasks with the same type and filter share one counter. Saved
         * with the team, so it must stay stable.
         */
        public String counter() {
            String name = task.getSerializedName();
            if (item.isPresent()) {
                return name + "/item/" + item.get();
            }
            if (tag.isPresent()) {
                return name + "/tag/" + tag.get().location();
            }
            return machine.map(m -> name + "/machine/" + m).orElse(name);
        }
    }

    /** Solve one of area D's puzzles at {@code difficulty} (0 to 2). */
    record Puzzle(Identifier puzzle, int difficulty) implements ResearchOption {
        static final MapCodec<Puzzle> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
                .group(Identifier.CODEC.fieldOf("puzzle").forGetter(Puzzle::puzzle),
                        Codec.intRange(0, 2).optionalFieldOf("difficulty", 0).forGetter(Puzzle::difficulty))
                .apply(instance, Puzzle::new));

        @Override
        public Kind kind() {
            return Kind.PUZZLE;
        }
    }

    /** Spend {@code cost} research points (before the preset scales it). */
    record Points(long cost) implements ResearchOption {
        static final MapCodec<Points> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(Codec.LONG
                .validate(cost -> cost > 0
                        ? DataResult.success(cost)
                        : DataResult.error(() -> "A point cost must be above 0"))
                .fieldOf("cost").forGetter(Points::cost)).apply(instance, Points::new));

        @Override
        public Kind kind() {
            return Kind.POINTS;
        }
    }
}
