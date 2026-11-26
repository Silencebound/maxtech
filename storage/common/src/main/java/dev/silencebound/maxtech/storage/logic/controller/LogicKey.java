package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * What an item setting of a logic graph names in game (the rule core's {@code K}): one item or fluid ({@link Stack}),
 * every item in an item tag ({@link Tag}) or every fluid in a fluid tag ({@link FluidTag}). A count of a tag adds up
 * the totals of every item (or fluid) in it, like the condition switch's tag count; a tag cannot be crafted, so a craft
 * request or a job event for one does nothing. Immutable.
 *
 * <p>
 * Saved as the stack key itself ({@code {"kind": "item", "id": ...}}), as {@code {"tag": "c:ingots/iron"}} or as
 * {@code {"fluid_tag": "c:water"}}.
 */
public sealed interface LogicKey {
    /** One item or fluid, components included. */
    record Stack(StackKey key) implements LogicKey {
    }

    /** Every item in an item tag. */
    record Tag(TagKey<Item> tag) implements LogicKey {
    }

    /** Every fluid in a fluid tag (M7L2 batch 2: the fluid amount node). */
    record FluidTag(TagKey<Fluid> tag) implements LogicKey {
    }

    Codec<Tag> TAG_CODEC = TagKey.codec(Registries.ITEM).fieldOf("tag").xmap(Tag::new, Tag::tag).codec();
    Codec<FluidTag> FLUID_TAG_CODEC = TagKey.codec(Registries.FLUID).fieldOf("fluid_tag")
            .xmap(FluidTag::new, FluidTag::tag).codec();
    /**
     * Tags first: a stack key's codec needs its {@code kind} field, which a tag does not have, and each tag has its own
     * field name.
     */
    Codec<LogicKey> CODEC = Codec.either(TAG_CODEC, Codec.either(FLUID_TAG_CODEC, StackKey.CODEC)).xmap(either -> either
            .map(tag -> (LogicKey) tag, inner -> inner.map(fluidTag -> (LogicKey) fluidTag, Stack::new)),
            key -> switch (key) {
                case Tag tag -> Either.left(tag);
                case FluidTag fluidTag -> Either.right(Either.left(fluidTag));
                case Stack stack -> Either.right(Either.right(stack.key()));
            });
    /** The codec of graphs as the controller saves them. */
    Codec<LogicGraph<LogicKey>> GRAPH_CODEC = LogicGraph.codec(CODEC);

    static LogicKey of(StackKey key) {
        return new Stack(key);
    }

    static LogicKey tag(TagKey<Item> tag) {
        return new Tag(tag);
    }

    static LogicKey fluidTag(TagKey<Fluid> tag) {
        return new FluidTag(tag);
    }
}
