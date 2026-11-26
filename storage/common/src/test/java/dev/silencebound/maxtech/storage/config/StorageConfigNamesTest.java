package dev.silencebound.maxtech.storage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.config.ConfigOutline;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every value and section of Storage's config files has a name in {@link StorageConfigNames} and every value a comment,
 * and every name belongs to a value or section, checked in the normal build instead of only by datagen and the
 * {@code config_lang_complete} GameTest. A new config class must be loaded here too.
 */
class StorageConfigNamesTest {
    @Test
    void everyValueAndSectionHasANameAndEveryNameAValue() {
        StorageClientConfig.register();
        StorageCommonConfig.register();
        StorageServerConfig.register();
        List<ConfigOutline.Entry> entries = OutlinePlatform.OUTLINE.entries(MaxtechStorage.MOD_ID);
        assertTrue(entries.size() > 100, "config classes recorded nothing: " + entries.size());
        assertEquals(List.of(), ConfigOutline.problems(entries, StorageConfigNames.NAMES));
    }
}
