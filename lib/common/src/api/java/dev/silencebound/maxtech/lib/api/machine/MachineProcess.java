package dev.silencebound.maxtech.lib.api.machine;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyRole;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipe;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeShape;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeType;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * The working loop of one machine (ADR-0019), composed into the machine's own block entity: it finds the recipe its
 * inputs match, runs it for the recipe's duration while paying its energy, and when the run is over takes the inputs
 * and puts out every output in one transaction, all or nothing. There is no ticker: the process is one
 * {@code ScheduledTask} in a {@link TaskGroup} (the level's machine group unless {@link Builder#tasks} gives one), and
 * it sleeps without a recipe, with full outputs, without energy and while redstone keeps it off, until an event wakes
 * it.
 * <p>
 * The block entity wires it up: build the process in its constructor, call {@link #start} from {@code onLoad} (after
 * its {@link RedstoneControl#start}), {@link #stop} from {@code setRemoved} and {@code onChunkUnloaded}, and
 * {@link #save}/{@link #load} from its own save methods. Its inventories' change listeners call {@link #slotChanged}
 * and {@link #tankChanged}, and the redstone control's wake callback and the block's neighbour updates call
 * {@link #wake}. The process sets the energy endpoint's {@link EnergyEndpoint#setListener listener} itself.
 * <p>
 * A run starts only with room for every output (each chance output counted as made, so none is ever voided) and only
 * when the redstone mode allows it; in {@link RedstoneMode#PULSE} a started run uses one pulse and keeps going, in the
 * other modes a run pauses while the mode does not allow it. Inputs stay in their slots until the run ends, so taking
 * one out mid-run only resets the run. A recipe with energy declares its {@code energy_per_tick} as the endpoint's
 * demand while it runs and moves on by what it receives (half the energy, half the speed); a process without an
 * endpoint ignores recipe energy. When the run is over and the outputs no longer fit, it holds at the end, draws
 * nothing, and finishes once room is made.
 * <p>
 * Saves keep the progress, the run's recipe id and the mode. After a load, or a datapack reload, the run keeps its
 * progress only if the inputs still match the same recipe; otherwise the progress resets and the items stay where they
 * are. Server thread only. The running step allocates nothing; looking a recipe up again (after an input change) does.
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface MachineProcess {
    /** A builder for a process over {@code type}'s recipes. */
    static Builder builder(MachineRecipeType type) {
        return new Builder(type);
    }

    /** The recipe type it runs. */
    MachineRecipeType type();

    /**
     * Joins its task group in {@code level} and looks at its inputs on the next pass; does nothing while started. Call
     * from the block entity's {@code onLoad} on the server.
     */
    void start(ServerLevel level, BlockPos pos);

    /** Leaves its task group and stops drawing energy; does nothing while stopped. */
    void stop();

    /** Whether it is started. */
    boolean started();

    /**
     * Wakes it for a change it cannot see itself: a neighbour update, the redstone control's callback, an upgrade.
     * Cheap; does nothing while stopped or during its own step.
     */
    void wake();

    /**
     * Slot {@code slot} of its {@link ItemSlots} changed: an input change looks the recipe up again, an output change
     * may make room. Call from the slots' change listener; cheap.
     */
    void slotChanged(int slot);

    /** One of its tanks changed; call from the tanks' change listeners. Cheap. */
    void tankChanged();

    /** What it is doing; {@link MachineStatus#IDLE} until its first step. */
    MachineStatus status();

    /** The recipe of the run in progress or about to start; empty without one. */
    Optional<RecipeHolder<MachineRecipe>> recipe();

    /**
     * Work done in the current run, from 0 to {@link #progressTotal()}: energy received for a recipe with energy, ticks
     * for one without.
     */
    long progress();

    /** Work a run of the current recipe needs; 0 without a recipe. With {@link #progress()}, a menu's progress bar. */
    long progressTotal();

    /** The selected mode id; an empty string for a type without modes. */
    String mode();

    /**
     * Selects mode {@code id}, one of the type's {@link MachineRecipeShape#modes()}; a change looks the recipe up again
     * and so resets a run of another mode's recipe. Throws {@link IllegalArgumentException} for another id.
     */
    void setMode(String id);

    /** Writes the progress, the run's recipe id and the mode, leaving out defaults. */
    void save(ValueOutput output);

    /** Reads what {@link #save} wrote, without saving or waking; an unknown mode loads as the first. */
    void load(ValueInput input);

    /**
     * Collects a process's parts; {@link #build()} checks them against the recipe type's shape. Not thread-safe; use on
     * one thread, then build.
     */
    final class Builder {
        private final MachineRecipeType type;
        private @Nullable ItemSlots slots;
        private int firstInput;
        private int firstOutput;
        private final List<ResourceTank<?>> inputTanks = new ArrayList<>();
        private final List<ResourceTank<?>> outputTanks = new ArrayList<>();
        private @Nullable EnergyEndpoint energy;
        private @Nullable RedstoneControl redstone;
        private @Nullable Function<ServerLevel, TaskGroup> tasks;
        private Consumer<MachineStatus> onStatus = status -> {
        };
        private Runnable onChange = () -> {
        };

        private Builder(MachineRecipeType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        /**
         * The machine's slots: the type's input slots from {@code firstInput}, its catalyst slots right after them, and
         * its output slots from {@code firstOutput}; counts come from the type's shape. Needed when the shape has any
         * slots.
         */
        public Builder items(ItemSlots slots, int firstInput, int firstOutput) {
            this.slots = Objects.requireNonNull(slots, "slots");
            this.firstInput = firstInput;
            this.firstOutput = firstOutput;
            return this;
        }

        /** Adds the next input tank; the shape's {@code tankInputs} of them, in recipe order. */
        public Builder inputTank(ResourceTank<?> tank) {
            inputTanks.add(Objects.requireNonNull(tank, "tank"));
            return this;
        }

        /**
         * Adds the next output tank; the shape's {@code tankOutputs} of them. A tank output goes into the first output
         * tank already holding it, else the first empty one that takes it.
         */
        public Builder outputTank(ResourceTank<?> tank) {
            outputTanks.add(Objects.requireNonNull(tank, "tank"));
            return this;
        }

        /**
         * The machine's {@link EnergyRole#CONSUMER consumer} endpoint, whose demand the process declares while a recipe
         * with energy runs; the process takes over its listener. Without one, recipe energy is ignored. Throws
         * {@link IllegalArgumentException} for another role.
         */
        public Builder energy(EnergyEndpoint consumer) {
            if (consumer.role() != EnergyRole.CONSUMER) {
                throw new IllegalArgumentException("Machine energy must be a consumer, got " + consumer.role());
            }
            this.energy = consumer;
            return this;
        }

        /** The machine's redstone control; without one, the machine always runs. */
        public Builder redstone(RedstoneControl control) {
            this.redstone = Objects.requireNonNull(control, "control");
            return this;
        }

        /**
         * Runs the process in the group {@code groups} gives for the level, asked on every {@link #start}, instead of
         * the level's machine group; a closed group falls back to the machine group.
         */
        public Builder tasks(Function<ServerLevel, TaskGroup> groups) {
            this.tasks = Objects.requireNonNull(groups, "groups");
            return this;
        }

        /** Told on the server thread when {@link MachineProcess#status()} changes, after the change. */
        public Builder onStatus(Consumer<MachineStatus> listener) {
            this.onStatus = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Runs when something {@link MachineProcess#save} writes changed (pass the block entity's {@code setChanged});
         * during a run that is every step.
         */
        public Builder onChange(Runnable changed) {
            this.onChange = Objects.requireNonNull(changed, "changed");
            return this;
        }

        /**
         * The process. Throws {@link IllegalArgumentException} when the slots or tanks do not cover the type's shape:
         * missing slots for a shape with slots, a slot range outside the slots or overlapping inputs and outputs, or
         * tank counts other than the shape's.
         */
        public MachineProcess build() {
            MachineRecipeShape shape = type.shape();
            int inputs = shape.itemInputs() + shape.catalysts();
            int outputs = shape.itemOutputs();
            ItemSlots items = slots;
            if (items == null) {
                if (inputs + outputs > 0) {
                    throw new IllegalArgumentException(type.id() + " needs item slots");
                }
            } else {
                checkRange("input", firstInput, inputs, items.size());
                checkRange("output", firstOutput, outputs, items.size());
                if (inputs > 0 && outputs > 0 && firstInput < firstOutput + outputs
                        && firstOutput < firstInput + inputs) {
                    throw new IllegalArgumentException("Input and output slots overlap");
                }
            }
            if (inputTanks.size() != shape.tankInputs() || outputTanks.size() != shape.tankOutputs()) {
                throw new IllegalArgumentException(
                        type.id() + " needs " + shape.tankInputs() + " input and " + shape.tankOutputs()
                                + " output tanks, got " + inputTanks.size() + " and " + outputTanks.size());
            }
            return MachineProcessFactory.get()
                    .create(new MachineProcessFactory.Spec(type, items, firstInput, firstOutput,
                            List.copyOf(inputTanks), List.copyOf(outputTanks), energy, redstone, tasks, onStatus,
                            onChange));
        }

        private static void checkRange(String what, int from, int count, int size) {
            if (count > 0 && (from < 0 || from + count > size)) {
                throw new IllegalArgumentException(
                        "The " + count + " " + what + " slot(s) from " + from + " do not fit " + size + " slots");
            }
        }
    }
}
