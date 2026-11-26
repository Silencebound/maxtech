package dev.silencebound.maxtech.storage.machine;

import java.util.Locale;

/** What a Storage machine is doing, for its GUI and the active block state. */
public enum MachineState {
    /** Nothing to do: no recipe matches, or no fuel. */
    IDLE,
    /** Working (a recipe run, or fuel burning). */
    RUNNING,
    /** A recipe is loaded but the buffer cannot pay this tick. */
    NO_ENERGY,
    /** A run cannot start or finish because the outputs are full; for the generator, its buffer is full. */
    OUTPUT_FULL,
    /** The recipe needs research the machine's owner's team has not done. */
    RESEARCH_NEEDED;

    private static final MachineState[] VALUES = values();

    /** Lang key: {@code machine.maxtech_storage.state.<name>}. */
    public String translationKey() {
        return "machine.maxtech_storage.state." + name().toLowerCase(Locale.ROOT);
    }

    /** Its position in declaration order, for a data slot. */
    public int index() {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == this) {
                return i;
            }
        }
        throw new IllegalStateException("Unknown state " + this);
    }

    /** The state at {@code index}, or {@link #IDLE} for an unknown one (client data). */
    public static MachineState byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : IDLE;
    }
}
