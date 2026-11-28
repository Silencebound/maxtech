package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.neoforge.guide.GuideConfigText;
import java.util.function.BiConsumer;

/** A guide page's {@code <Config>} tag, in any mod's guide. */
final class GuideConfigLang {
    private GuideConfigLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        lang.accept(GuideConfigText.DEFAULT_KEY, "Default: %s");
        lang.accept(GuideConfigText.CHANGED_KEY, "Changed by this server");
        lang.accept(GuideConfigText.ON_KEY, "on");
        lang.accept(GuideConfigText.OFF_KEY, "off");
    }
}
