package dev.silencebound.maxtech.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.MaxtechLib;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every value and section of Lib's config files has a name in {@link LibConfigNames} and every value a comment, and
 * every name belongs to a value or section, checked in the normal build instead of only by datagen and the
 * {@code config_lang_complete} GameTest. A new config class must be loaded here too.
 */
class LibConfigNamesTest {
    @Test
    void everyValueAndSectionHasANameAndEveryNameAValue() {
        ClientConfig.register();
        ServerConfig.register();
        List<ConfigOutline.Entry> entries = OutlinePlatform.OUTLINE.entries(MaxtechLib.MOD_ID);
        assertTrue(entries.size() > 10, "config classes recorded nothing: " + entries);
        assertEquals(List.of(), ConfigOutline.problems(entries, LibConfigNames.NAMES));
    }
}
