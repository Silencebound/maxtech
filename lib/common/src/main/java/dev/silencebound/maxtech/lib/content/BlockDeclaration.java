package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.util.List;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * What a block chain declared for datagen. A {@code null} model means the model provider writes it by hand; a
 * {@code null} lang or loot means none is generated.
 */
public record BlockDeclaration<B extends Block>(RegistryEntry<Block, B> entry, @Nullable BlockModel model,
        @Nullable String lang, @Nullable BlockLoot loot, List<TagKey<Block>> tags) {
}
