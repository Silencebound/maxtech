package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.platform.Transparency;
import com.mojang.math.Quadrant;
import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.client.conduit.ConduitBodyKey;
import dev.silencebound.maxtech.lib.client.conduit.ConduitGeometry;
import dev.silencebound.maxtech.lib.client.conduit.ConduitQuad;
import dev.silencebound.maxtech.lib.client.conduit.PartCache;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.facade.FacadeView;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.client.model.ExtraFaceData;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * The model of one conduit block (ADR-0012): a body (core and arms at the type's thickness) plus one part per connector
 * ring or disabled-face stub, composed from the block entity's {@link ConduitRenderData} in the block's
 * {@link ConduitLook}. {@link ConduitGeometry} lays the faces out; this class bakes them into quads the first time a
 * {@link ConduitBodyKey} or {@link ConduitFaceKey} is seen and caches the part for the lifetime of the baked model (a
 * resource reload bakes a fresh model with empty caches). Chunk meshing only looks parts up, so a conduit never
 * rebuilds geometry per frame and needs no block entity renderer.
 *
 * <p>
 * Colours are baked into the quads ({@link ExtraFaceData}) rather than supplied by a block tint source: the cache key
 * already includes the dye colour, and a tint source would have to look the block entity up again per quad. A quad's
 * chunk layer comes from its {@link BakedQuad.MaterialInfo}: the layer its sprite's pixels call for (solid, or cutout
 * for a cable's wire core overlay).
 *
 * <p>
 * A facaded conduit draws the facade block's baked model instead, or itself while {@link FacadeView#conduitsShown()};
 * the facade's tint comes from {@link FacadeClient}.
 */
public final class ConduitModel implements DynamicBlockStateModel {
    private static final CuboidFace.UVs WHOLE = new CuboidFace.UVs(0.0F, 0.0F, 16.0F, 16.0F);

    // Parts are baked lazily on meshing threads, after the baker and its interner are gone; interning only saves
    // memory.
    private static final ModelBaker.Interner NO_INTERNING = new ModelBaker.Interner() {
        @Override
        public Vector3fc vector(Vector3fc vector) {
            return vector;
        }

        @Override
        public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
            return material;
        }
    };

    private final ConduitLook look;
    private final Sprites sprites;
    // Bounded by 64 arm masks x 64 ring masks x 17 colours of this block's one type, and in practice by what the
    // world holds: a long straight run shares one part.
    private final PartCache<ConduitBodyKey, BlockStateModelPart> bodies = new PartCache<>(this::bakeBody);
    // Rings and stubs: 6 sides x 4 modes.
    private final PartCache<ConduitFaceKey, BlockStateModelPart> faces = new PartCache<>(this::bakeFace);
    // The same with the colour labels on (ClientConfig#conduitColorLabels), so switching it never mixes the two.
    private final PartCache<ConduitBodyKey, BlockStateModelPart> labelledBodies = new PartCache<>(
            this::bakeLabelledBody);
    private final PartCache<ConduitFaceKey, BlockStateModelPart> labelledFaces = new PartCache<>(
            this::bakeLabelledFace);

    ConduitModel(ConduitLook look, Sprites sprites) {
        this.look = look;
        this.sprites = sprites;
    }

    /**
     * The sprites a look draws with, resolved at bake time: the flat tint sprite and the solid look's end and overlay.
     */
    record Sprites(Material.Baked flat, Material.Baked end, Material.@Nullable Baked overlay) {
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            List<BlockStateModelPart> output) {
        ConduitRenderData data = level.getModelData(pos).get(ConduitBlockEntity.RENDER_DATA);
        if (data == null) {
            return;
        }
        BlockState facade = data.facade();
        if (facade != null && !FacadeView.conduitsShown()) {
            // The facade block's own baked model, cached by vanilla like any block's; it hides the conduit inside, so
            // that is not meshed at all. Its culled faces are culled against our neighbours as usual.
            facadeModel(facade).collectParts(level, pos, facade, random, output);
            return;
        }
        boolean labels = ClientConfig.conduitColorLabels();
        output.add((labels ? labelledBodies : bodies).get(ConduitBodyKey.of(data)));
        PartCache<ConduitFaceKey, BlockStateModelPart> faceParts = labels ? labelledFaces : faces;
        List<ConduitFaceKey> keys = data.faces();
        for (int i = 0; i < keys.size(); i++) {
            output.add(faceParts.get(keys.get(i)));
        }
    }

    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
            RandomSource random) {
        ConduitRenderData data = level.getModelData(pos).get(ConduitBlockEntity.RENDER_DATA);
        if (data == null) {
            return NoData.INSTANCE;
        }
        BlockState facade = data.facade();
        boolean shown = FacadeView.conduitsShown();
        Object facadeGeometry = facade == null || shown
                ? null
                : facadeModel(facade).createGeometryKey(level, pos, facade, random);
        Object key = FacadeView.geometryKey(data, facade != null, shown, facadeGeometry);
        // Only a key that draws the conduit itself changes with the labels; a facade's does not.
        return key instanceof ConduitRenderData drawn && ClientConfig.conduitColorLabels() ? new Labelled(drawn) : key;
    }

    /** The key of a conduit drawn with its colour labels: the same data must not share a mesh without them. */
    private record Labelled(ConduitRenderData data) {
    }

    /** The key of a conduit whose block entity has not handed over its render data yet: it draws nothing. */
    private enum NoData {
        INSTANCE
    }

    @Override
    public Material.Baked particleMaterial() {
        return sprites.end();
    }

    /** Breaking and landing particles show the facade, if there is one. */
    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        ConduitRenderData data = level.getModelData(pos).get(ConduitBlockEntity.RENDER_DATA);
        BlockState facade = data == null ? null : data.facade();
        return facade == null ? particleMaterial() : facadeModel(facade).particleMaterial(level, pos, facade);
    }

    /** Read on meshing threads; the set is replaced as a whole on a resource reload, never changed in place. */
    private static BlockStateModel facadeModel(BlockState facade) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(facade);
    }

    /** No conduit look has translucent quads, which block outlines and moving (piston) blocks ask about. */
    @Override
    public int materialFlags() {
        return 0;
    }

    private BlockStateModelPart bakeBody(ConduitBodyKey key) {
        return bake(ConduitGeometry.body(look, key));
    }

    private BlockStateModelPart bakeFace(ConduitFaceKey key) {
        return bake(ConduitGeometry.face(look, key));
    }

    private BlockStateModelPart bakeLabelledBody(ConduitBodyKey key) {
        return bake(ConduitGeometry.body(look, key, true));
    }

    private BlockStateModelPart bakeLabelledFace(ConduitFaceKey key) {
        return bake(ConduitGeometry.face(look, key, true));
    }

    private BlockStateModelPart bake(List<ConduitQuad> faces) {
        QuadCollection.Builder quads = new QuadCollection.Builder();
        for (ConduitQuad face : faces) {
            BakedQuad quad = quad(face);
            if (face.culled()) {
                // Sits on the block face, so a solid neighbour hides it.
                quads.addCulledFace(face.facing(), quad);
            } else {
                quads.addUnculledFace(quad);
            }
        }
        return new SimpleModelWrapper(quads.build(), true, particleMaterial());
    }

    private BakedQuad quad(ConduitQuad face) {
        Material.Baked material = material(face);
        Transparency transparency = material.sprite().contents().transparency();
        BakedQuad.MaterialInfo info = BakedQuad.MaterialInfo.of(material, transparency, -1, true, 0, true);
        AABB box = face.box();
        Vector3f from = new Vector3f((float) box.minX, (float) box.minY, (float) box.minZ);
        Vector3f to = new Vector3f((float) box.maxX, (float) box.maxY, (float) box.maxZ);
        return FaceBakery.bakeQuad(NO_INTERNING, from, to, WHOLE, Quadrant.R0, info, face.facing(),
                BlockModelRotation.IDENTITY, null, new ExtraFaceData(face.argb(), 0, true));
    }

    private Material.Baked material(ConduitQuad face) {
        return switch (face.texture()) {
            case FLAT -> sprites.flat();
            case END -> sprites.end();
            case OVERLAY -> {
                Material.Baked overlay = sprites.overlay();
                yield overlay == null ? sprites.end() : overlay;
            }
        };
    }

    /**
     * The {@code maxtech_lib:conduit} block state model type every conduit block's block state names, with the block's
     * {@link ConduitLook} inline: {@code {"type": "maxtech_lib:conduit", "look": "solid", "end": ...}}.
     */
    public record Unbaked(ConduitLook look) implements CustomUnbakedBlockStateModel {
        public static final Identifier ID = MaxtechLib.id("conduit");
        public static final MapCodec<Unbaked> CODEC = ConduitLook.CODEC.xmap(Unbaked::new, Unbaked::look);

        @Override
        public MapCodec<Unbaked> codec() {
            return CODEC;
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            // Only sprites, no other models.
        }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            Material.Baked flat = sprite(baker, ConduitLook.FLAT);
            Material.Baked end = flat;
            Material.Baked overlay = null;
            switch (look) {
                case ConduitLook.Solid solid -> {
                    end = sprite(baker, solid.end());
                    Identifier overlayId = solid.overlay();
                    overlay = overlayId == null ? null : sprite(baker, overlayId);
                }
            }
            return new ConduitModel(look, new Sprites(flat, end, overlay));
        }

        private static Material.Baked sprite(ModelBaker baker, Identifier texture) {
            return baker.materials().get(new Material(texture), () -> ID + " " + texture);
        }
    }
}
