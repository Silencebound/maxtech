package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.api.machine.RedstoneMode;
import java.util.function.BiConsumer;

/** The redstone mode button. */
final class RedstoneLang {
    private RedstoneLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(RedstoneMode.BUTTON_KEY, "Redstone: %s");
        mode(lang, RedstoneMode.ALWAYS, "Always", "Runs whatever the redstone signal");
        mode(lang, RedstoneMode.HIGH, "High", "Runs only with a redstone signal");
        mode(lang, RedstoneMode.LOW, "Low", "Runs only without a redstone signal");
        mode(lang, RedstoneMode.PULSE, "Pulse", "Runs once for each redstone pulse");
        mode(lang, RedstoneMode.NEVER, "Never", "Never runs, whatever the redstone signal");
    }

    private static void mode(BiConsumer<String, String> lang, RedstoneMode mode, String name, String hint) {
        lang.accept(mode.translationKey(), name);
        lang.accept(mode.hintKey(), hint);
    }
}
