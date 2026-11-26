package dev.silencebound.maxtech.storage.machine;

import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * Storage's four machines (PRD 7.2): their slot counts and modes. One block, block entity type and menu layout each;
 * the three processing machines also have a recipe type of their own, {@code maxtech_storage:<id>}, and two upgrade
 * module slots (one module plus the void module, see {@link MachineModule}).
 */
public enum MachineKind {
    /** Burns furnace fuel for FE. */
    FUEL_GENERATOR("fuel_generator", 1, 0, 1, 0, List.of()),
    /** Slices, dopes and prints wafers; prints with a mask in its catalyst slot. */
    LITHOGRAPH("lithograph", 2, 1, 2, 2, List.of("slice", "dope", "print")),
    /** Draws wire and fibre, presses plates, boards and capacitors, refines ore. */
    FABRICATOR("fabricator", 2, 0, 2, 2, List.of("draw", "press", "refine")),
    /** Assembles processors from up to six inputs. */
    ASSEMBLER("assembler", 6, 0, 1, 2, List.of());

    private final String id;
    private final int inputs;
    private final int catalysts;
    private final int outputs;
    private final int modules;
    private final ImmutableList<String> modes;

    MachineKind(String id, int inputs, int catalysts, int outputs, int modules, List<String> modes) {
        this.id = id;
        this.inputs = inputs;
        this.catalysts = catalysts;
        this.outputs = outputs;
        this.modules = modules;
        this.modes = ImmutableList.copyOf(modes);
    }

    /** The registry path of the block, item, block entity type and, for processing machines, recipe type. */
    public String id() {
        return id;
    }

    /** Input slots; the generator's one is its fuel slot. */
    public int inputs() {
        return inputs;
    }

    /** Slots for reusable catalysts (the lithograph's mask), matched only against a recipe's unconsumed inputs. */
    public int catalysts() {
        return catalysts;
    }

    /** Output slots; the generator's one takes a fuel's remainder. */
    public int outputs() {
        return outputs;
    }

    /** Upgrade module slots; hoppers and pipes neither fill nor empty them. */
    public int modules() {
        return modules;
    }

    /** The named modes in button order, the first being the default; empty for a machine without modes. */
    public List<String> modes() {
        return modes;
    }

    /** The kind with {@link #id()} {@code id}; the assembler for an unknown one (client data). */
    public static MachineKind byId(String id) {
        for (MachineKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return ASSEMBLER;
    }

    /** Whether it runs recipes; the generator burns fuel instead. */
    public boolean processes() {
        return this != FUEL_GENERATOR;
    }

    /** Slots in total: inputs, then catalysts, then outputs, then modules. */
    public int slots() {
        return inputs + catalysts + outputs + modules;
    }

    /** Lang key of a mode's name: {@code machine.maxtech_storage.<id>.mode.<mode>}. */
    public String modeKey(String mode) {
        return "machine.maxtech_storage." + id + ".mode." + mode;
    }
}
