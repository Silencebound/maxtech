package dev.silencebound.maxtech.lib.conduit;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * How a conduit's render value ({@link ConduitBlockEntity#renderValue()}) is saved and synced: under its own key, and
 * only when non-zero, so conduits that never set one (all of Storage's cables) save and sync exactly as before.
 */
final class ConduitRenderValue {
    static final String KEY = "render_value";

    private ConduitRenderValue() {
    }

    static void write(ValueOutput output, int renderValue) {
        if (renderValue != 0) {
            output.putInt(KEY, renderValue);
        }
    }

    /** The saved value, or 0 for data without one. */
    static int read(ValueInput input) {
        return input.getIntOr(KEY, 0);
    }
}
