package dev.silencebound.maxtech.lib.api.machine;

import dev.silencebound.maxtech.lib.api.menu.MenuStatus;

/**
 * What a {@link MachineProcess} is doing, for a menu's {@code Layout.status} line, an overlay or a working-state look.
 * Every state but {@link #RUNNING} is one the process sleeps in until an event. Immutable; any thread.
 */
public enum MachineStatus implements MenuStatus {
    /** The inputs are empty: nothing to do. */
    IDLE("idle", Tone.OFF),
    /** A run is in progress. */
    RUNNING("running", Tone.OK),
    /** The inputs (or the mode) match no recipe. */
    NO_RECIPE("no_recipe", Tone.WARN),
    /** The run needs energy and gets none. */
    NO_ENERGY("no_energy", Tone.WARN),
    /** The outputs have no room for every output of the run, chance outputs counted as made. */
    OUTPUT_FULL("output_full", Tone.WARN),
    /** The machine's {@link RedstoneControl} does not let it run. */
    REDSTONE_OFF("redstone_off", Tone.OFF);

    /** The prefix of every state's lang key: {@code maxtech_lib.gui.machine_status.<name>}. */
    public static final String KEY_PREFIX = "maxtech_lib.gui.machine_status";

    private final String name;
    private final Tone tone;

    MachineStatus(String name, Tone tone) {
        this.name = name;
        this.tone = tone;
    }

    /** The state's fixed lower-case name, as in its lang key. */
    public String serializedName() {
        return name;
    }

    /** {@code maxtech_lib.gui.machine_status.<name>}; Lib ships the English text. */
    @Override
    public String translationKey() {
        return KEY_PREFIX + "." + name;
    }

    @Override
    public Tone tone() {
        return tone;
    }
}
