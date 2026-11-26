package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.Content;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

/**
 * A mod's {@code en_us}: the declared block, item and machine names. A subclass adding the mod's other lines calls
 * {@code super.addTranslations} first; there is one language provider per mod and locale.
 */
public class ContentLanguageProvider extends LanguageProvider {
    private final Content content;

    public ContentLanguageProvider(PackOutput output, Content content) {
        super(output, content.modId(), "en_us");
        this.content = content;
    }

    @Override
    protected void addTranslations() {
        ContentDatagen.language(content, this);
    }
}
