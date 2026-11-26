package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.Content;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.BlockTagsProvider;

/** The block tags a mod's content declared. A subclass adding more calls {@code super.addTags} first. */
public class ContentBlockTagsProvider extends BlockTagsProvider {
    private final Content content;

    public ContentBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
            Content content) {
        super(output, registries, content.modId());
        this.content = content;
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        ContentDatagen.blockTags(content, (tag, block) -> tag(tag).add(block));
    }
}
