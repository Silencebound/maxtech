package dev.silencebound.maxtech.storage.api.crafting;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a pattern input accepts: one exact key, or any item or fluid in a tag. Tag inputs let the planner pick whatever
 * matching key the network holds most of; a pattern that must use one item locks it with {@link Exact}.
 *
 * <p>
 * Immutable; safe on any thread.
 * <p>
 * Experimental (Storage 0.5.0): may change in any Storage minor version, also after 1.0.
 */
@ApiStatus.Experimental
public sealed interface PatternIngredient {
    /** Whether {@code key} satisfies this ingredient. Tag checks read the registries' bound tags. */
    boolean matches(StackKey key);

    /**
     * Exactly {@code key}, components included.
     *
     * @param key
     *            the one key accepted
     */
    record Exact(StackKey key) implements PatternIngredient {
        @Override
        public boolean matches(StackKey other) {
            return key.equals(other);
        }
    }

    /**
     * Any item in {@code tag}, with any components.
     *
     * @param tag
     *            the accepted items
     */
    record ItemTag(TagKey<Item> tag) implements PatternIngredient {
        @Override
        public boolean matches(StackKey key) {
            return key instanceof ItemKey item && item.item().builtInRegistryHolder().is(tag);
        }
    }

    /**
     * Any fluid in {@code tag}, with any components.
     *
     * @param tag
     *            the accepted fluids
     */
    record FluidTag(TagKey<Fluid> tag) implements PatternIngredient {
        @Override
        public boolean matches(StackKey key) {
            return key instanceof FluidKey fluid && fluid.fluid().builtInRegistryHolder().is(tag);
        }
    }
}
