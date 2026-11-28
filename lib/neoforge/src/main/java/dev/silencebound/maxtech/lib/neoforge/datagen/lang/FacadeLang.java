package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.facade.FacadeContent;
import dev.silencebound.maxtech.lib.facade.FacadeItem;
import java.util.function.BiConsumer;

/** The facade item. */
final class FacadeLang {
    private FacadeLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(FacadeContent.FACADE.get().getDescriptionId(), "Facade");
        lang.accept(FacadeItem.LOOKS_LIKE_KEY, "Looks like %s");
    }
}
