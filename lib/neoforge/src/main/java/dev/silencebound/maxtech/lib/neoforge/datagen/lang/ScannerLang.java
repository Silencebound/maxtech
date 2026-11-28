package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.info.ScannerItem;
import java.util.function.BiConsumer;

/** The scanner item and its chat report. */
final class ScannerLang {
    private ScannerLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(ScannerItem.DESCRIPTION_KEY, "Use on a block to print its stats and network to chat.");
        lang.accept(ScannerItem.HEADER_KEY, "Scan: %s");
        lang.accept(ScannerItem.NOTHING_KEY, "Nothing to report.");
    }
}
