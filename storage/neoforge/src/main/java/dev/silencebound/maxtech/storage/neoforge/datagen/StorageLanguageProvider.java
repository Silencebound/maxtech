package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.lib.neoforge.datagen.ContentLanguageProvider;
import dev.silencebound.maxtech.lib.neoforge.platform.ConfigTranslations;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageConfigNames;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplate;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.neoforge.compat.ae2.Ae2Lang;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.StorageRecipeViews;
import net.minecraft.data.PackOutput;

/**
 * Storage's {@code en_us}. Lines besides the names {@link ModContent} declares go in an {@code addTranslations()}
 * override that calls {@code super} first.
 */
final class StorageLanguageProvider extends ContentLanguageProvider {
    StorageLanguageProvider(PackOutput output) {
        super(output, ModContent.CONTENT);
    }

    @Override
    protected void addTranslations() {
        super.addTranslations();
        // The advancement tree, one per guide progress step.
        for (StorageAdvancements.Entry entry : StorageAdvancements.STEPS) {
            add(entry.titleKey(), entry.title());
            add(entry.descriptionKey(), entry.description());
        }
        // Recipe viewer pages; StorageRecipeViews holds no JEI classes, so datagen runs without JEI.
        for (StorageRecipeViews.Category category : StorageRecipeViews.categories()) {
            add(category.titleKey(), StorageRecipeViews.englishTitle(category.kind()));
        }
        add(StorageRecipeViews.FE_PER_TICK_KEY, "%s MU/t");
        add(StorageRecipeViews.TOTAL_FE_KEY, "Total: %s MU");
        add(StorageRecipeViews.RESEARCH_KEY, "Needs research: %s");
        // The built-in logic templates' names and descriptions, so a translation can replace the English in their
        // files.
        BuiltInTemplates.all(item -> item).forEach((path, template) -> {
            add(LogicTemplate.nameKey(MaxtechStorage.id(path)), template.name());
            add(LogicTemplate.descriptionKey(MaxtechStorage.id(path)), template.description());
        });
        // The dev showcase command (dev runs only); its texts live beside the builder.
        Showcase.translations().forEach(this::add);
        // The network bridge's AE2 side; Ae2Lang holds no AE2 classes.
        Ae2Lang.translations().forEach(this::add);
        // Names and tooltips of every config value and section for NeoForge's config screen.
        ConfigTranslations.english(MaxtechStorage.MOD_ID, StorageConfigNames.NAMES).forEach(this::add);
    }
}
