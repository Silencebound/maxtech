// port-override: base=857a87a6c4b68f4b
package dev.silencebound.maxtech.lib.neoforge.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.client.conduit.ConduitContentsGeometry;
import dev.silencebound.maxtech.lib.client.conduit.ConduitGeometry;
import dev.silencebound.maxtech.lib.client.conduit.ConduitLooks;
import dev.silencebound.maxtech.lib.client.conduit.PartCache;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.facade.FacadeView;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import dev.silencebound.maxtech.lib.shim.client.render.Transparency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.ExtraFaceData;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.apache.commons.lang3.mutable.MutableObject;
import org.joml.Vector3f;
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
 * 1.21.1 port: meshing asks a model for its render types and then for the quads of one side in one render type, so a
 * {@link Part} keeps its quads by chunk layer and side. A quad's layer is the one its sprite's pixels call for (solid,
 * or cutout for a cable's wire core overlay; {@link Transparency#of}), or the one the quad asks for (a shell's glass is
 * translucent). Colours and light are baked into the quads ({@link ExtraFaceData}) rather than supplied by a block
 * colour: the cache key already includes the dye colour.
 *
 * <p>
 * A facaded conduit draws the facade block's baked model instead, or itself while {@link FacadeView#conduitsShown()};
 * the facade's tint comes from {@link FacadeClient}.
 *
 * <p>
 * A shell conduit's {@link ConduitContents} are a part of their own ({@link ConduitContentsGeometry}), drawn with the
 * paint resolved from the resource kind's look ({@link ConduitLooks}: still and flowing sprites, tint, light), cached
 * per {@link ConduitContentsGeometry#key}.
 *
 * <p>
 * An addon conduit marked {@code customModel} gets a copy with its {@link ConduitModelHook} ({@link #withHook}), which
 * then supplies the quads of each body and face part instead of {@link ConduitGeometry}; caching and facades stay the
 * same.
 */
public final class ConduitModel implements IDynamicBakedModel {
    /** What {@link #materialFlags} sets for a model with translucent quads. */
    static final int FLAG_TRANSLUCENT = 1;

    // Holds no state; each bake gets its own BlockFaceUV, which bakeQuad changes in place.
    private static final FaceBakery FACE_BAKERY = new FaceBakery();
    /** Solid, cutout and translucent: the chunk layers a quad can land in, by index. */
    private static final int LAYER_COUNT = 3;

    /** The render types of the layers, held apart so unit tests can load this class without render state. */
    private static final class Layers {
        static final RenderType[] TYPES = {RenderType.solid(), RenderType.cutout(), RenderType.translucent()};
        // Every combination of TYPES, by bit mask, so getRenderTypes allocates nothing.
        static final ChunkRenderTypeSet[] SETS = new ChunkRenderTypeSet[1 << LAYER_COUNT];

        static {
            for (int mask = 0; mask < SETS.length; mask++) {
                List<RenderType> types = new ArrayList<>();
                for (int i = 0; i < LAYER_COUNT; i++) {
                    if ((mask & (1 << i)) != 0) {
                        types.add(TYPES[i]);
                    }
                }
                SETS[mask] = ChunkRenderTypeSet.of(types);
            }
        }
    }

    private final ConduitLook look;
    private final Sprites sprites;
    private final @Nullable Custom custom;
    // Bounded by 64 arm masks x 64 ring masks x 17 colours of this block's one type x the render values its addon
    // sets, and in practice by what the world holds: a long straight run shares one part.
    private final PartCache<ConduitBodyKey, Part> bodies = new PartCache<>(this::bakeBody);
    // Rings and stubs: 6 sides x 4 modes.
    private final PartCache<ConduitFaceKey, Part> faces = new PartCache<>(this::bakeFace);
    // The same with the colour labels on (ClientConfig#conduitColorLabels), so switching it never mixes the two.
    private final PartCache<ConduitBodyKey, Part> labelledBodies = new PartCache<>(this::bakeLabelledBody);
    private final PartCache<ConduitFaceKey, Part> labelledFaces = new PartCache<>(this::bakeLabelledFace);
    // A shell's contents: arm and ring masks x the contents the world shows; labels and dyes do not change them.
    // Replaced whenever the contents paints change (ConduitLooks.INDEX#generation), so a part baked from an old paint
    // never outlives it; a bake racing the swap lands in the dropped cache.
    private volatile PartCache<ConduitBodyKey, Part> contents = new PartCache<>(this::bakeContents);
    private volatile int contentsGeneration = ConduitLooks.INDEX.generation();

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
    record Custom(ResourceLocation block, ConduitModelHook hook, Map<ResourceLocation, TextureAtlasSprite> sprites,
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
    record Sprites(TextureAtlasSprite flat, TextureAtlasSprite end, @Nullable TextureAtlasSprite overlay,
            Map<ResourceLocation, TextureAtlasSprite> named) {
    }

    /**
     * One baked part: its quads by chunk layer (solid, cutout, translucent) and side ({@code Direction} ordinal, then the unculled
     * ones), and the mask of layers it has quads in.
     */
    static final class Part {
        private static final int SIDES = 7;
        private final List<BakedQuad>[] quads;
        final int layers;

        @SuppressWarnings("unchecked")
        private Part(List<BakedQuad>[] quads, int layers) {
            this.quads = quads;
            this.layers = layers;
        }

        void addTo(List<BakedQuad> output, int layer, @Nullable Direction side) {
            output.addAll(quads[layer * SIDES + (side == null ? 6 : side.ordinal())]);
        }
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random,
            ModelData extraData, @Nullable RenderType renderType) {
        ConduitRenderData data = extraData.get(ConduitBlockEntity.RENDER_DATA);
        if (data == null) {
            return List.of();
        }
        BlockState facade = data.facade();
        if (facade != null && !FacadeView.conduitsShown()) {
            // The facade block's own baked model; it hides the conduit inside, so that is not meshed at all. Its culled
            // faces are culled against our neighbours as usual.
            return facadeModel(facade).getQuads(facade, side, random, ModelData.EMPTY, renderType);
        }
        List<BakedQuad> output = new ArrayList<>();
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            if (renderType == null || renderType == Layers.TYPES[layer]) {
                collect(data, output, layer, side);
            }
        }
        return output;
    }

    private void collect(ConduitRenderData data, List<BakedQuad> output, int layer, @Nullable Direction side) {
        boolean labels = ClientConfig.conduitColorLabels();
        ConduitBodyKey body = data.bodyKey();
        (labels ? labelledBodies : bodies).get(custom == null ? ConduitGeometry.cacheKey(body) : body).addTo(output,
                layer, side);
        if (custom == null && look instanceof ConduitLook.Shell && ConduitContentsGeometry.drawn(body.contents())) {
            contentsParts().get(ConduitContentsGeometry.key(body)).addTo(output, layer, side);
        }
        PartCache<ConduitFaceKey, Part> faceParts = labels ? labelledFaces : faces;
        List<ConduitFaceKey> keys = data.faces();
        for (int i = 0; i < keys.size(); i++) {
            faceParts.get(keys.get(i)).addTo(output, layer, side);
        }
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData extraData) {
        ConduitRenderData data = extraData.get(ConduitBlockEntity.RENDER_DATA);
        if (data == null) {
            return ChunkRenderTypeSet.none();
        }
        BlockState facade = data.facade();
        if (facade != null && !FacadeView.conduitsShown()) {
            return facadeModel(facade).getRenderTypes(facade, random, ModelData.EMPTY);
        }
        boolean labels = ClientConfig.conduitColorLabels();
        ConduitBodyKey body = data.bodyKey();
        int mask = (labels ? labelledBodies : bodies).get(custom == null ? ConduitGeometry.cacheKey(body) : body).layers;
        if (custom == null && look instanceof ConduitLook.Shell && ConduitContentsGeometry.drawn(body.contents())) {
            mask |= contentsParts().get(ConduitContentsGeometry.key(body)).layers;
        }
        PartCache<ConduitFaceKey, Part> faceParts = labels ? labelledFaces : faces;
        List<ConduitFaceKey> keys = data.faces();
        for (int i = 0; i < keys.size(); i++) {
            mask |= faceParts.get(keys.get(i)).layers;
        }
        return Layers.SETS[mask];
    }

    private PartCache<ConduitBodyKey, Part> contentsParts() {
        int generation = ConduitLooks.INDEX.generation();
        if (generation == contentsGeneration) {
            return contents;
        }
        PartCache<ConduitBodyKey, Part> fresh = new PartCache<>(this::bakeContents);
        contents = fresh;
        contentsGeneration = generation;
        return fresh;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return sprites.end();
    }

    /** Breaking and landing particles show the facade, if there is one. */
    @Override
    public TextureAtlasSprite getParticleIcon(ModelData extraData) {
        ConduitRenderData data = extraData.get(ConduitBlockEntity.RENDER_DATA);
        BlockState facade = data == null ? null : data.facade();
        return facade == null ? getParticleIcon() : facadeModel(facade).getParticleIcon(ModelData.EMPTY);
    }

    /** Read on meshing threads; the shaper's map is replaced as a whole on a resource reload, never changed in place. */
    private static BakedModel facadeModel(BlockState facade) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(facade);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    /**
     * Whether the model has translucent quads: a shell's glass is translucent, the solid look has none, and a custom
     * model has them when its builder says so. 26.1 hands this to block outlines and moving (piston) blocks; 1.21.1 asks
     * {@link #getRenderTypes} instead, so here it only backs the declared-translucency check.
     */
    int materialFlags() {
        return materialFlags(custom != null ? custom.translucent() : translucent(look));
    }

    /** Whether Lib draws {@code look} with translucent quads. */
    static boolean translucent(ConduitLook look) {
        return look instanceof ConduitLook.Shell;
    }

    /** {@link #materialFlags()} of a model that is or is not translucent. */
    static int materialFlags(boolean translucent) {
        return translucent ? FLAG_TRANSLUCENT : 0;
    }

    private Part bakeBody(ConduitBodyKey key) {
        return bake(custom == null ? ConduitGeometry.body(look, key) : custom.hook().body(look, key, false));
    }

    private Part bakeFace(ConduitFaceKey key) {
        return bake(custom == null ? ConduitGeometry.face(look, key) : custom.hook().face(look, key, false));
    }

    private Part bakeLabelledBody(ConduitBodyKey key) {
        return bake(custom == null ? ConduitGeometry.body(look, key, true) : custom.hook().body(look, key, true));
    }

    private Part bakeLabelledFace(ConduitFaceKey key) {
        return bake(custom == null ? ConduitGeometry.face(look, key, true) : custom.hook().face(look, key, true));
    }

    /**
     * The contents of a shell conduit, drawn with the paint {@link ConduitLooks} resolved for its look index on the
     * main thread; nothing when the index has no paint (its kind has no look, or the index was full). Reads only the
     * paint, never registries or kind code, so it is safe on meshing threads.
     */
    private Part bakeContents(ConduitBodyKey key) {
        if (!(look instanceof ConduitLook.Shell shell)) {
            return bake(List.of());
        }
        ConduitLooks.ContentsPaint paint = ConduitLooks.INDEX.paint(ConduitContents.packedLookIndex(key.contents()));
        if (paint == null) {
            return bake(List.of());
        }
        ConduitContentsGeometry.Paint ids = paint.paint();
        Map<ResourceLocation, TextureAtlasSprite> extra = ids.still().equals(ids.flowing())
                ? Map.of(ids.still(), paint.still())
                : Map.of(ids.still(), paint.still(), ids.flowing(), paint.flowing());
        return bake(ConduitContentsGeometry.quads(shell, key.type().thickness(), key.arms(), key.rings(),
                key.contents(), ids), extra);
    }

    private Part bake(List<ConduitQuad> faces) {
        return bake(faces, Map.of());
    }

    /** Bakes {@code faces}; {@code extra} holds sprites by atlas id beyond the look's and the hook's. */
    @SuppressWarnings("unchecked")
    private Part bake(List<ConduitQuad> faces, Map<ResourceLocation, TextureAtlasSprite> extra) {
        List<BakedQuad>[] quads = new List[LAYER_COUNT * Part.SIDES];
        int layers = 0;
        for (ConduitQuad face : faces) {
            TextureAtlasSprite sprite = material(face, extra);
            Transparency transparency = ConduitLayers.transparency(face.layer(), Transparency.of(sprite));
            if (custom != null) {
                ConduitLayers.requireDeclared(custom.translucent(), transparency, custom.block(),
                        face.sprite() != null ? face.sprite() : face.texture());
            }
            int layer = transparency.hasTranslucent() ? 2 : transparency.hasTransparent() ? 1 : 0;
            // Sits on the block face, so a solid neighbour hides it.
            int slot = layer * Part.SIDES + (face.culled() ? face.facing().ordinal() : 6);
            if (quads[slot] == null) {
                quads[slot] = new ArrayList<>();
            }
            quads[slot].add(quad(face, sprite));
            layers |= 1 << layer;
        }
        for (int i = 0; i < quads.length; i++) {
            quads[i] = quads[i] == null ? List.of() : List.copyOf(quads[i]);
        }
        return new Part(quads, layers);
    }

    private static BakedQuad quad(ConduitQuad face, TextureAtlasSprite sprite) {
        int light = face.lightEmission();
        // A glowing quad drops ambient occlusion, as vanilla renders light-emitting blocks without it.
        boolean ao = light == 0;
        AABB box = face.box();
        Vector3f from = new Vector3f((float) box.minX, (float) box.minY, (float) box.minZ);
        Vector3f to = new Vector3f((float) box.maxX, (float) box.maxY, (float) box.maxZ);
        BlockElementFace element = new BlockElementFace(null, -1, "",
                ConduitFaceUvs.uvs(face.uv()).toBlockFaceUV(ConduitFaceUvs.rotation(face.uv())),
                new ExtraFaceData(face.argb(), light, light, ao), new MutableObject<>());
        return FACE_BAKERY.bakeQuad(from, to, element, sprite, face.facing(), BlockModelRotation.X0_Y0, null, true);
    }

    private TextureAtlasSprite material(ConduitQuad face, Map<ResourceLocation, TextureAtlasSprite> extra) {
        ResourceLocation sprite = face.sprite();
        if (sprite != null) {
            TextureAtlasSprite own = custom == null ? null : custom.sprites().get(sprite);
            if (own == null) {
                own = sprites.named().get(sprite);
            }
            if (own == null) {
                own = extra.get(sprite);
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
                TextureAtlasSprite overlay = sprites.overlay();
                yield overlay == null ? sprites.end() : overlay;
            }
        };
    }

    /**
     * The {@code maxtech_lib:conduit} model loader every conduit block's model names, with the block's
     * {@link ConduitLook} inline: {@code {"loader": "maxtech_lib:conduit", "look": "solid", "end": ...}} or
     * {@code {"loader": "maxtech_lib:conduit", "look": "shell", "shell": ...}}. 26.1 names it in the block state file
     * instead; 1.21.1 block states can only name model files.
     */
    public record Unbaked(ConduitLook look) implements IUnbakedGeometry<Unbaked> {
        public static final ResourceLocation ID = MaxtechLib.id("conduit");
        public static final IGeometryLoader<Unbaked> LOADER = Unbaked::read;

        private static Unbaked read(JsonObject json, JsonDeserializationContext context) {
            JsonObject fields = json.deepCopy();
            fields.remove("loader");
            return new Unbaked(ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, fields)
                    .getOrThrow(message -> new JsonParseException(ID + ": " + message)));
        }

        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
            TextureAtlasSprite flat = sprite(spriteGetter, ConduitLook.FLAT);
            TextureAtlasSprite end = flat;
            TextureAtlasSprite overlay = null;
            Map<ResourceLocation, TextureAtlasSprite> named = Map.of();
            switch (look) {
                case ConduitLook.Solid solid -> {
                    end = sprite(spriteGetter, solid.end());
                    ResourceLocation overlayId = solid.overlay();
                    overlay = overlayId == null ? null : sprite(spriteGetter, overlayId);
                }
                case ConduitLook.Shell shell -> {
                    end = sprite(spriteGetter, shell.shell());
                    Optional<ResourceLocation> barTexture = shell.barTexture();
                    named = barTexture.isEmpty() || barTexture.get().equals(shell.shell())
                            ? Map.of(shell.shell(), end)
                            : Map.of(shell.shell(), end, barTexture.get(), sprite(spriteGetter, barTexture.get()));
                }
            }
            return new ConduitModel(look, new Sprites(flat, end, overlay, named));
        }

        private static TextureAtlasSprite sprite(Function<Material, TextureAtlasSprite> spriteGetter,
                ResourceLocation texture) {
            return spriteGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, texture));
        }
    }
}
