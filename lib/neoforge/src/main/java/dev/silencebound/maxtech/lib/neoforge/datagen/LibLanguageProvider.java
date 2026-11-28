package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.neoforge.datagen.lang.LibLangSections;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

/**
 * Lib's {@code en_us}: the frameworks' GUI, tooltip, info and recipe viewer lines. The lines live in one section class
 * per feature in {@code datagen.lang}; a new feature adds its own section there, not lines here.
 */
final class LibLanguageProvider extends LanguageProvider {
    LibLanguageProvider(PackOutput output) {
        super(output, MaxtechLib.MOD_ID, "en_us");
    }

    @Override
    protected void addTranslations() {
        for (LangSection section : LibLangSections.all()) {
            section.addTo(this::add);
        }
    }
}
