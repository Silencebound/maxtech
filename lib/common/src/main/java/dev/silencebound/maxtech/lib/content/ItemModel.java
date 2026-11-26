package dev.silencebound.maxtech.lib.content;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * How datagen draws an item that is not a block item, as plain data for the reason given on {@link BlockModel}. Block
 * items need none; they use their block's model.
 */
public sealed interface ItemModel {
    /** A flat item with one texture. */
    static ItemModel flat(Identifier texture) {
        return new Flat(texture);
    }

    /** A flat item with one texture, tinted with the constant colour {@code rgb}. */
    static ItemModel tintedFlat(Identifier texture, int rgb) {
        return new TintedFlat(texture, rgb);
    }

    /**
     * A flat item drawn with the model {@code model}, which datagen writes once from {@code texture} and every item
     * naming it shares, tinted per item with the constant colour {@code rgb} (material forms: one base texture per
     * form, PRD 5.5). Items sharing a model must name the same texture.
     */
    static ItemModel tintedShared(Identifier model, Identifier texture, int rgb) {
        return new Layered(model, List.of(ModelLayer.tinted(texture, rgb)));
    }

    /**
     * A flat item drawn from one to five {@code layers}, bottom first, each tinted with its own constant colour or not
     * at all (e.g. a tinted base under an untinted overlay). With a {@code sharedModel}, datagen writes that model once
     * and every item naming it shares it, so such items must name the same textures in the same order and differ only
     * in their tints; without one, the item gets a model of its own.
     */
    static ItemModel layered(@Nullable Identifier sharedModel, List<ModelLayer> layers) {
        return new Layered(sharedModel, layers);
    }

    /**
     * A container that draws the fluid it holds (NeoForge's {@code fluid_container} item model): {@code base} with the
     * fluid's sprite, tinted like the fluid, drawn through the {@code fluidMask} texture's shape. {@code fluid} is what
     * it shows while its fluid item capability reports nothing, e.g. a bucket's own fluid, or empty for a cell whose
     * contents vary; with {@code flipGas} a gas is drawn upside down.
     */
    static ItemModel fluidContainer(Identifier base, Identifier fluidMask, Supplier<? extends Fluid> fluid,
            boolean flipGas) {
        return new FluidContainer(base, fluidMask, fluid, flipGas);
    }

    /**
     * An item drawn with the model {@code model}, which the mod's own datagen writes (e.g. a 3D model built from
     * elements); Lib's datagen only writes the item definition naming it. For items whose look the other kinds cannot
     * express, such as a conduit drawn as a piece of its block.
     */
    static ItemModel existing(Identifier model) {
        return new Existing(model);
    }

    record Flat(Identifier texture) implements ItemModel {
    }

    record TintedFlat(Identifier texture, int rgb) implements ItemModel {
    }

    record Existing(Identifier model) implements ItemModel {
    }

    record FluidContainer(Identifier base, Identifier fluidMask, Supplier<? extends Fluid> fluid,
            boolean flipGas) implements ItemModel {
    }

    record Layered(@Nullable Identifier sharedModel, List<ModelLayer> layers) implements ItemModel {
        public Layered {
            layers = List.copyOf(layers);
            if (layers.isEmpty() || layers.size() > 5) {
                throw new IllegalArgumentException("An item model has one to five layers, not " + layers.size());
            }
        }
    }
}
