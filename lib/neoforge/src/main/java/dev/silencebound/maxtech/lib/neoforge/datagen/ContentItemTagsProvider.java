package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.Content;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.data.ItemTagsProvider;

/** The item tags a mod's content declared. A subclass adding more calls {@code super.addTags} first. */
public class ContentItemTagsProvider extends ItemTagsProvider {
    private final Content content;

    public ContentItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
            Content content) {
        super(output, registries, content.modId());
        this.content = content;
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        ContentDatagen.itemTags(content, (tag, item) -> tag(tag).add(item));
        ContentDatagen.standaloneItemTags(content, (tag, includes) -> {
            var builder = tag(tag);
            for (TagKey<Item> include : includes) {
                builder.addTag(include);
            }
        });
    }
}
