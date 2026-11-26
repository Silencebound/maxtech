package dev.silencebound.maxtech.lib.tool;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Tags for tools (PRD 5.5). Recipes name a wrench through NeoForge's {@code c:tools/wrench} convention tag.
 *
 * <p>
 * In-world wrench actions (on a conduit, a cable part or a port) check {@code maxtech_lib:acts_as/wrench} instead. It
 * includes {@code c:tools/wrench}, so every wrench acts in the world too, and an item that should act like a wrench
 * without being one in recipes joins only the {@code acts_as} tag.
 */
public final class ToolTags {
    public static final TagKey<Item> WRENCH = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "tools/wrench"));

    /** Items that do a wrench's in-world actions; includes {@link #WRENCH}. */
    public static final TagKey<Item> ACTS_AS_WRENCH = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("maxtech_lib", "acts_as/wrench"));

    private ToolTags() {
    }
}
