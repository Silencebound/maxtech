package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.api.machine.MachineStatus;
import java.util.function.BiConsumer;

/** The machine status line. */
final class MachineLang {
    private MachineLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(MachineStatus.IDLE.translationKey(), "Idle");
        lang.accept(MachineStatus.RUNNING.translationKey(), "Running");
        lang.accept(MachineStatus.NO_RECIPE.translationKey(), "No matching recipe");
        lang.accept(MachineStatus.NO_ENERGY.translationKey(), "No energy");
        lang.accept(MachineStatus.OUTPUT_FULL.translationKey(), "Output full");
        lang.accept(MachineStatus.REDSTONE_OFF.translationKey(), "Off by redstone");
    }
}
