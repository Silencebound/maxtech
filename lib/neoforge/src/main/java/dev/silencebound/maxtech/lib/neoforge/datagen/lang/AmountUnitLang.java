package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import java.util.function.BiConsumer;

/** Resource amounts by unit. */
final class AmountUnitLang {
    private AmountUnitLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(AmountUnit.ITEMS.langKey(), "%s");
        lang.accept(AmountUnit.MILLIBUCKETS.langKey(), "%s mB");
        lang.accept(AmountUnit.MU.langKey(), "%s MU");
    }
}
