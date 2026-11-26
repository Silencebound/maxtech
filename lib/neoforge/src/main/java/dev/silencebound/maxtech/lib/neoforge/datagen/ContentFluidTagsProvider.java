package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.Content;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.FluidTagsProvider;

/**
 * The fluid tags a mod's content declared (material fluids' {@code c:} tags). A subclass adding more calls
 * {@code super.addTags} first.
 */
public class ContentFluidTagsProvider extends FluidTagsProvider {
    private final Content content;

    public ContentFluidTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
            Content content) {
        super(output, registries, content.modId());
        this.content = content;
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        ContentDatagen.fluidTags(content, (tag, fluid) -> tag(tag).add(fluid));
    }
}
