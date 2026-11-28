package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.neoforge.compat.jade.BlockInfoLines;
import java.util.function.BiConsumer;

/** Jade's block info lines. */
final class BlockInfoLang {
    private BlockInfoLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(BlockInfoLines.CONFIG_KEY, "Block Info");
        lang.accept(BlockInfoLines.MORE_KEY, "...and %s more; use a scanner for the rest");
    }
}
