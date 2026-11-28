package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.viewer.RecipeViewText;
import java.util.function.BiConsumer;

/** Recipe viewer pages' shared lines. */
final class RecipeViewerLang {
    private RecipeViewerLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(RecipeViewText.DURATION_KEY, "%s s (%s ticks)");
        lang.accept(RecipeViewText.MODE_KEY, "Mode: %s");
        lang.accept(RecipeViewText.CHANCE_KEY, "Chance: %s%%");
        lang.accept(RecipeViewText.NOT_CONSUMED_KEY, "Not consumed");
    }
}
