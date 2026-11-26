package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.conduit.ConduitLook;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * How datagen draws a block, as plain data. Common can't hold model callbacks: the model generators
 * ({@code net.minecraft.client.data.models}) are absent from the dedicated server jar, and a lambda typed with them
 * would fail to link when the declaring class loads there. lib/neoforge's datagen turns each kind into files.
 */
public sealed interface BlockModel {
    /** A cube with one texture on every face. */
    static BlockModel cubeAll(Identifier texture) {
        return new CubeAll(texture);
    }

    /** A cube with one texture and the colour {@code rgb} baked into its quads, so no colour handler is needed. */
    static BlockModel tintedCubeAll(Identifier texture, int rgb) {
        return new TintedCubeAll(texture, rgb);
    }

    /**
     * A cube drawn from {@code layers}, bottom first: each is a full cube with the layer's texture on every face and
     * its tint baked into the quads (a host stone under a tinted ore, a tinted storage block under its overlay). The
     * first layer is the particle and may not be optional.
     */
    static BlockModel layeredCube(List<ModelLayer> layers) {
        return new LayeredCube(layers);
    }

    /**
     * A cube with its own layers on each face, starting from {@code all} on every face; see {@link FacesBuilder}. For
     * machines: a tinted casing everywhere and a front overlay on one face.
     */
    static FacesBuilder faces(List<ModelLayer> all) {
        return new FacesBuilder(all);
    }

    /** A column whose end faces the block's {@code facing} property, up by default. */
    static BlockModel facingColumn(Identifier side, Identifier end) {
        return new FacingColumn(side, end);
    }

    /**
     * A column with {@code side} on the four sides and {@code end} on top and bottom (and as particle), with the colour
     * {@code rgb} baked into its quads.
     */
    static BlockModel tintedColumn(Identifier side, Identifier end, int rgb) {
        return new TintedColumn(side, end, end, rgb);
    }

    /** Like {@link #tintedColumn(Identifier, Identifier, int)}, with its own particle texture. */
    static BlockModel tintedColumn(Identifier side, Identifier end, Identifier particle, int rgb) {
        return new TintedColumn(side, end, particle, rgb);
    }

    /**
     * Vanilla's orientable cube ({@code front}, {@code side}, {@code top}; the bottom is the top texture), turned to
     * the block's horizontal {@code facing} property.
     */
    static BlockModel orientable(Identifier front, Identifier side, Identifier top) {
        return new Orientable(front, side, top);
    }

    /**
     * A model with only a particle texture and no faces, for blocks drawn some other way, like vanilla's water: a
     * liquid block's fluid model draws it.
     */
    static BlockModel particleOnly(Identifier particle) {
        return new ParticleOnly(particle);
    }

    /** A block state pointing at a model that is generated elsewhere. */
    static BlockModel existing(Identifier model) {
        return new Existing(model);
    }

    /**
     * A model of the block's own that only inherits {@code parent}, so blocks sharing a look still get distinct model
     * files (machines on their tier's casing).
     */
    static BlockModel child(Identifier parent) {
        return new Child(parent);
    }

    record CubeAll(Identifier texture) implements BlockModel {
    }

    record TintedCubeAll(Identifier texture, int rgb) implements BlockModel {
    }

    record LayeredCube(List<ModelLayer> layers) implements BlockModel {
        public LayeredCube {
            layers = List.copyOf(layers);
            if (layers.isEmpty() || layers.getFirst().optional()) {
                throw new IllegalArgumentException("A layered cube needs a first layer that is not optional");
            }
        }
    }

    /**
     * A cube with layers of its own on each face, drawn bottom first like {@link LayeredCube}; the east face's first
     * layer is the particle. With {@code horizontalFacing}, the north face is the front and the block state turns it to
     * the block's horizontal {@code facing} property.
     */
    record Faces(Map<Direction, List<ModelLayer>> faces, boolean horizontalFacing) implements BlockModel {
        public Faces {
            EnumMap<Direction, List<ModelLayer>> copy = new EnumMap<>(Direction.class);
            for (Direction face : Direction.values()) {
                List<ModelLayer> layers = faces.get(face);
                if (layers == null || layers.isEmpty() || layers.getFirst().optional()) {
                    throw new IllegalArgumentException("Face " + face + " needs a first layer that is not optional");
                }
                copy.put(face, List.copyOf(layers));
            }
            faces = Collections.unmodifiableMap(copy);
        }

        /** The layers of {@code face}, bottom first; every face has at least one. */
        public List<ModelLayer> face(Direction face) {
            return Objects.requireNonNull(faces.get(face));
        }
    }

    /** Builds a {@link Faces} model from the layers every face starts with. */
    final class FacesBuilder {
        private final EnumMap<Direction, List<ModelLayer>> faces = new EnumMap<>(Direction.class);
        private boolean horizontalFacing;

        FacesBuilder(List<ModelLayer> all) {
            for (Direction face : Direction.values()) {
                faces.put(face, List.copyOf(all));
            }
        }

        /** Replaces the layers of {@code face}. */
        public FacesBuilder face(Direction face, List<ModelLayer> layers) {
            faces.put(face, List.copyOf(layers));
            return this;
        }

        /** Replaces the layers of the four horizontal faces. */
        public FacesBuilder sides(List<ModelLayer> layers) {
            for (Direction face : Direction.Plane.HORIZONTAL) {
                face(face, layers);
            }
            return this;
        }

        /** Draws {@code layers} over what {@code face} already has, e.g. a machine's front overlay. */
        public FacesBuilder over(Direction face, List<ModelLayer> layers) {
            List<ModelLayer> combined = new ArrayList<>(faces.get(face));
            combined.addAll(layers);
            faces.put(face, List.copyOf(combined));
            return this;
        }

        /** Turns the model to the block's horizontal {@code facing} property, the north face being the front. */
        public FacesBuilder horizontalFacing() {
            horizontalFacing = true;
            return this;
        }

        public BlockModel build() {
            return new Faces(faces, horizontalFacing);
        }
    }

    record FacingColumn(Identifier side, Identifier end) implements BlockModel {
    }

    record TintedColumn(Identifier side, Identifier end, Identifier particle, int rgb) implements BlockModel {
    }

    record Orientable(Identifier front, Identifier side, Identifier top) implements BlockModel {
    }

    record ParticleOnly(Identifier particle) implements BlockModel {
    }

    record Existing(Identifier model) implements BlockModel {
    }

    record Child(Identifier parent) implements BlockModel {
    }

    /**
     * Lib's conduit model (ADR-0012), which draws the block in {@code look} from its block entity's render data at the
     * conduit type's thickness; declared by {@link ConduitBuilder} for every conduit block.
     */
    record Conduit(ConduitLook look) implements BlockModel {
    }
}
