package dev.silencebound.maxtech.lib.content;

import java.util.List;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * An item tag declared on its own rather than with an item: it holds the tags {@code includes}, or, with none, it only
 * exists, so recipes naming a tag that only other mods fill still load.
 */
public record ItemTagDeclaration(TagKey<Item> tag, List<TagKey<Item>> includes) {
}
