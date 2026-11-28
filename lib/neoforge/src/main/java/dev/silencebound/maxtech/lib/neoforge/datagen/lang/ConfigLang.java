package dev.silencebound.maxtech.lib.neoforge.datagen.lang;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.config.LibConfigNames;
import dev.silencebound.maxtech.lib.neoforge.client.ConfigScreens;
import dev.silencebound.maxtech.lib.neoforge.platform.ConfigTranslations;
import java.util.function.BiConsumer;

/** Names and tooltips of every Lib config value and section, and the config screen's own lines. */
final class ConfigLang {
    private ConfigLang() {
    }

    static void addTo(BiConsumer<String, String> lang) {
        ConfigTranslations.english(MaxtechLib.MOD_ID, LibConfigNames.NAMES).forEach(lang);
        lang.accept(ConfigScreens.DEFAULT_KEY, "Default: %s");
        lang.accept(ConfigScreens.WORLD_RESTART_KEY, "Applies after the world is reloaded");
        lang.accept(ConfigScreens.GAME_RESTART_KEY, "Applies after the game is restarted");
    }
}
