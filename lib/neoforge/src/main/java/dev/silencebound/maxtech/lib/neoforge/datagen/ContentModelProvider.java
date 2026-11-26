package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.Content;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.core.Holder;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * Block states and models for a mod's declared content. A subclass adding hand-written models calls
 * {@code super.registerModels} first.
 */
public class ContentModelProvider extends ModelProvider {
    private final Content content;
    private Predicate<Identifier> textureExists = texture -> {
        throw new IllegalStateException("No resources to look up optional model layer " + texture + " in");
    };

    public ContentModelProvider(PackOutput output, Content content) {
        super(output, content.modId());
        this.content = content;
    }

    /**
     * Where optional model layers ({@link dev.silencebound.maxtech.lib.content.ModelLayer#optional()}) are looked up:
     * the datagen run's client resources, which hold the generating mod's own textures ({@code --existing}), and the
     * jar of the loaded mod whose id is the texture's namespace. The run's resources leave out other mods, so without
     * the second an addon's models of another mod's materials would lose the overlays that mod ships.
     */
    void textures(ResourceManager resources) {
        this.textureExists = texture -> {
            Identifier file = texture.withPath(path -> "textures/" + path + ".png");
            return resources.getResource(file).isPresent() || shippedBy(texture.getNamespace(), file);
        };
    }

    private static boolean shippedBy(String modId, Identifier file) {
        IModFileInfo mod = ModList.get().getModFileById(modId);
        return mod != null && mod.getFile().getContents().containsFile("assets/" + modId + "/" + file.getPath());
    }

    /**
     * Only the declared blocks. The GameTest source set registers test blocks in the same namespace and is loaded in
     * {@code runData}, but those never ship and have no models.
     */
    @Override
    protected Stream<? extends Holder<Block>> getKnownBlocks() {
        return ContentDatagen.knownBlocks(content).stream();
    }

    @Override
    protected Stream<? extends Holder<Item>> getKnownItems() {
        return ContentDatagen.knownItems(content).stream();
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        ContentDatagen.models(content, blockModels, itemModels, textureExists);
    }
}
