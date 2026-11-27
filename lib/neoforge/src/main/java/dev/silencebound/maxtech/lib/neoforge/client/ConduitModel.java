package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.platform.Transparency;
import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.client.conduit.ConduitGeometry;
import dev.silencebound.maxtech.lib.client.conduit.PartCache;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.facade.FacadeView;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
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
 * for a cable's wire core overlay), or the one the quad asks for (a shell's glass is translucent).
 *
 * <p>
 * A facaded conduit draws the facade block's baked model instead, or itself while {@link FacadeView#conduitsShown()};
 * the facade's tint comes from {@link FacadeClient}.
 *
 * <p>
 * An addon conduit marked {@code customModel} gets a copy with its {@link ConduitModelHook} ({@link #withHook}), which
 * then supplies the quads of each body and face part instead of {@link ConduitGeometry}; caching and facades stay the
 * same.
 */
public final class ConduitModel implements DynamicBlockStateModel {

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
    private final @Nullable Custom custom;
    // Bounded by 64 arm masks x 64 ring masks x 17 colours of this block's one type x the render values its addon
    // sets, and in practice by what the world holds: a long straight run shares one part.
    private final PartCache<ConduitBodyKey, BlockStateModelPart> bodies = new PartCache<>(this::bakeBody);
    // Rings and stubs: 6 sides x 4 modes.
    private final PartCache<ConduitFaceKey, BlockStateModelPart> faces = new PartCache<>(this::bakeFace);
    // The same with the colour labels on (ClientConfig#conduitColorLabels), so switching it never mixes the two.
    private final PartCache<ConduitBodyKey, BlockStateModelPart> labelledBodies = new PartCache<>(
            this::bakeLabelledBody);
    private final PartCache<ConduitFaceKey, BlockStateModelPart> labelledFaces = new PartCache<>(
            this::bakeLabelledFace);

    ConduitModel(ConduitLook look, Sprites sprites) {
        this(look, sprites, null);
    }

    private ConduitModel(ConduitLook look, Sprites sprites, @Nullable Custom custom) {
        this.look = look;
        this.sprites = sprites;
        this.custom = custom;
    }

    /**
     * An addon's model hook for one conduit block, with the sprites it named resolved at bake time.
     *
     * @param block
     *            the conduit block, for error messages
     * @param hook
     *            supplies the parts' quads
     * @param sprites
     *            {@link ConduitModelHook#sprites()} by atlas id
     * @param translucent
     *            whether the builder marked the model translucent
     */
    record Custom(Identifier block, ConduitModelHook hook, Map<Identifier, Material.Baked> sprites,
            boolean translucent) {
    }

    /** This model drawn by {@code custom}, with empty part caches. */
    ConduitModel withHook(Custom custom) {
        return new ConduitModel(look, sprites, custom);
    }

    /**
     * The sprites a look draws with, resolved at bake time: the flat tint sprite, the solid look's end and overlay (a
     * shell's glass stands in as its end, so particles show it), and the sprites its quads name by id.
     */
    record Sprites(Material.Baked flat, Material.Baked end, Material.@Nullable Baked overlay,
            Map<Identifier, Material.Baked> named) {
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
        output.add((labels ? labelledBodies : bodies).get(bodyKey(data.bodyKey())));
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

    /**
     * {@code key} as this model caches it: Lib's shell ignores the render value, so its tubes share parts whatever
     * value their addon sets; only a key with a value set is copied.
     */
    private ConduitBodyKey bodyKey(ConduitBodyKey key) {
        if (custom == null && look instanceof ConduitLook.Shell && key.renderValue() != 0) {
            return new ConduitBodyKey(key.type(), key.colour(), key.arms(), key.rings());
        }
        return key;
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

    /**
     * Whether the model has translucent quads, which block outlines and moving (piston) blocks ask about: a shell's
     * glass is translucent, the solid look has none, and a custom model has them when its builder says so.
     */
    @Override
    public int materialFlags() {
        return materialFlags(custom != null ? custom.translucent() : translucent(look));
    }

    /** Whether Lib draws {@code look} with translucent quads. */
    static boolean translucent(ConduitLook look) {
        return look instanceof ConduitLook.Shell;
    }

    /** {@link #materialFlags()} of a model that is or is not translucent. */
    static int materialFlags(boolean translucent) {
        return translucent ? BakedQuad.FLAG_TRANSLUCENT : 0;
    }

    private BlockStateModelPart bakeBody(ConduitBodyKey key) {
        return bake(custom == null ? ConduitGeometry.body(look, key) : custom.hook().body(look, key, false));
    }

    private BlockStateModelPart bakeFace(ConduitFaceKey key) {
        return bake(custom == null ? ConduitGeometry.face(look, key) : custom.hook().face(look, key, false));
    }

    private BlockStateModelPart bakeLabelledBody(ConduitBodyKey key) {
        return bake(custom == null ? ConduitGeometry.body(look, key, true) : custom.hook().body(look, key, true));
    }

    private BlockStateModelPart bakeLabelledFace(ConduitFaceKey key) {
        return bake(custom == null ? ConduitGeometry.face(look, key, true) : custom.hook().face(look, key, true));
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
        Transparency transparency = ConduitLayers.transparency(face.layer(),
                material.sprite().contents().transparency());
        if (custom != null) {
            ConduitLayers.requireDeclared(custom.translucent(), transparency, custom.block(),
                    face.sprite() != null ? face.sprite() : face.texture());
        }
        BakedQuad.MaterialInfo info = BakedQuad.MaterialInfo.of(material, transparency, -1, true, 0, true);
        AABB box = face.box();
        Vector3f from = new Vector3f((float) box.minX, (float) box.minY, (float) box.minZ);
        Vector3f to = new Vector3f((float) box.maxX, (float) box.maxY, (float) box.maxZ);
        return FaceBakery.bakeQuad(NO_INTERNING, from, to, ConduitFaceUvs.uvs(face.uv()),
                ConduitFaceUvs.rotation(face.uv()), info, face.facing(), BlockModelRotation.IDENTITY, null,
                new ExtraFaceData(face.argb(), 0, true));
    }

    private Material.Baked material(ConduitQuad face) {
        Identifier sprite = face.sprite();
        if (sprite != null) {
            Material.Baked own = custom == null ? null : custom.sprites().get(sprite);
            if (own == null) {
                own = sprites.named().get(sprite);
            }
            if (own == null) {
                throw new IllegalStateException("A quad of conduit " + (custom == null ? look : custom.block())
                        + " names sprite " + sprite + ", which neither its look nor its model hook's sprites() lists");
            }
            return own;
        }
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
     * {@link ConduitLook} inline: {@code {"type": "maxtech_lib:conduit", "look": "solid", "end": ...}} or
     * {@code {"type": "maxtech_lib:conduit", "look": "shell", "shell": ...}}.
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
            Map<Identifier, Material.Baked> named = Map.of();
            switch (look) {
                case ConduitLook.Solid solid -> {
                    end = sprite(baker, solid.end());
                    Identifier overlayId = solid.overlay();
                    overlay = overlayId == null ? null : sprite(baker, overlayId);
                }
                case ConduitLook.Shell shell -> {
                    end = sprite(baker, shell.shell());
                    named = Map.of(shell.shell(), end);
                }
            }
            return new ConduitModel(look, new Sprites(flat, end, overlay, named));
        }

        private static Material.Baked sprite(ModelBaker baker, Identifier texture) {
            return baker.materials().get(new Material(texture), () -> ID + " " + texture);
        }
    }
}
