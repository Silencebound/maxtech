package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.lib.client.conduit.ConduitGeometry;
import dev.silencebound.maxtech.lib.client.conduit.ConduitLabels;
import dev.silencebound.maxtech.lib.client.conduit.ConduitQuad;
import dev.silencebound.maxtech.lib.client.conduit.PartCache;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.facade.FacadeView;
import dev.silencebound.maxtech.storage.cable.CableGeometry;
import dev.silencebound.maxtech.storage.cable.CableLane;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cable.CableModeMarks;
import dev.silencebound.maxtech.storage.cable.CableTierMarks;
import dev.silencebound.maxtech.storage.cable.SmartLoad;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.part.PartLook;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jspecify.annotations.Nullable;

/**
 * A storage cable's model (look "J", owner decision 2026-10-15): the body from {@link CableGeometry} (a clear glass
 * sleeve in the translucent layer with one lane inside it, drawn solid and lit; on a covered cable an opaque sleeve
 * without a lane), Lib's disabled-face stubs (no connector rings; {@link CableModeClient} shows the modes while a
 * wrench is held) from {@link ConduitGeometry}, the parts on its faces (each with the cable's arm up to its back, drawn
 * by the part type's {@link PartBaker}), and, while a facade covers the cable, Lib's own conduit model, which draws the
 * facade. It replaces Lib's model for storage cable block states after baking ({@link StorageModels}), so the conduit
 * framework's block state files stay as they are. Parts are baked the first time their key is seen and cached for the
 * life of the baked model, as Lib's conduit model does; no block entity renderer.
 *
 * <p>
 * Every tier is the same width; the tier shows as the lane's brightness: the cable's colour (its tier's accent, pulled
 * towards a dye) scaled per tier by {@link CableTierMarks} and drawn lit per tier while the client's
 * {@code visuals.cableTierGlow} is on; colour and light are part of every cache key. The glass sleeve is untinted, so
 * it looks the same whatever the colour. A smart cable shows its load bucket instead of its tier: the bucket's colour
 * while its segment is busy, and the bucket's brightness ({@link SmartLoad}, from the cable's synced
 * {@link PartLook#load()}). A covered cable draws its sleeve in the {@code covered} sprites tinted with the cable's
 * colour, its core faces with one to four light pips for the tier in the texture (look "K1", owner decision
 * 2026-10-15), so the pips need no quads of their own. A glass, fiber or smart cable shows the same pips on the faces
 * of its lane's core, in the lane's sprite, so they take the lane's colour and light (a smart cable's load) while their
 * count stays the tier. A fiber cable's lane has a brighter dot every 8 px; a glass cable is drawn as a plain one. Only
 * the sleeve is translucent, and nothing is animated. With Lib's {@code conduitColorLabels} on, a dyed cable also shows
 * the dye's letter ({@link ConduitLabels}) on its arms, so it never covers the tier pips on its core.
 */
final class StorageCableModel implements DynamicBlockStateModel {
    private static final CuboidFace.UVs WHOLE = new CuboidFace.UVs(0, 0, 16, 16);

    private final BlockStateModel lib;
    private final StorageCableConduitType type;
    private final Sprites sprites;
    // Bounded by 64 arm masks x 64 part masks x 17 colours (x 4 load buckets on a smart cable), in practice by what the
    // world holds.
    private final PartCache<BodyKey, BlockStateModelPart> bodies = new PartCache<>(this::bakeBody);
    private final PartCache<ConduitFaceKey, BlockStateModelPart> faces = new PartCache<>(this::bakeFace);
    // Bounded by part types x looks x 6 faces x 17 colours.
    private final PartCache<PartKey, BlockStateModelPart> partPieces = new PartCache<>(this::bakePart);
    private final Map<Identifier, PartBaker> partBakers;
    /** The front line overlay per {@link LinkLook} but online ({@link LinkLineModel}). */
    private final Map<LinkLook, Material.Baked> lines;

    /**
     * The sprites a cable draws with: the glass sleeve along arms and its core cells; the covered sleeve along arms,
     * its plain cells for closed ends and its core cells with 1 to 4 tier pips ({@code coveredCores}, tier 1 first);
     * the plain and the fiber lane; the lane with 1 to 4 tier pips on its core faces ({@code laneCores}, tier 1 first);
     * and {@code flat}, Lib's white conduit sprite for stubs.
     */
    record Sprites(Material.Baked sleeve, Material.Baked sleeveCore, Material.Baked covered, Material.Baked coveredEnd,
            List<Material.Baked> coveredCores, Material.Baked lane, Material.Baked fiberLane,
            List<Material.Baked> laneCores, Material.Baked flat) {
        /** The covered core cells with {@code tier}'s pips; a tier outside 1 to 4 is clamped. */
        Material.Baked coveredCore(int tier) {
            return coveredCores.get(Math.clamp(tier, 1, coveredCores.size()) - 1);
        }

        /** The lane's core faces with {@code tier}'s pips; a tier outside 1 to 4 is clamped. */
        Material.Baked laneCore(int tier) {
            return laneCores.get(Math.clamp(tier, 1, laneCores.size()) - 1);
        }
    }

    /**
     * {@code lineArgb} is the lane's colour, or the sleeve's on a covered cable; {@code emission} the lane's light;
     * {@code label} the dye whose letter the core shows while Lib's {@code conduitColorLabels} is on, else null.
     */
    private record BodyKey(int lineArgb, int emission, int arms, int parts, @Nullable DyeColor label) {
    }

    /** One part on one face; the colour and light are the lane's on the arm behind it. */
    private record PartKey(PartLook.Face face, int lineArgb, int emission) {
    }

    /** {@link #createGeometryKey}'s answer while the cable carries parts or the glow is off. */
    private record GeometryKey(@Nullable Object cable, @Nullable PartLook parts, boolean glow) {
    }

    StorageCableModel(BlockStateModel lib, StorageCableConduitType type, Sprites sprites,
            Map<Identifier, PartBaker> partBakers, Map<LinkLook, Material.Baked> lines) {
        this.lib = lib;
        this.type = type;
        this.sprites = sprites;
        this.partBakers = partBakers;
        this.lines = lines;
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            List<BlockStateModelPart> output) {
        ConduitRenderData data = level.getModelData(pos).get(ConduitBlockEntity.RENDER_DATA);
        if (data == null) {
            return;
        }
        if (data.facade() != null && !FacadeView.conduitsShown()) {
            lib.collectParts(level, pos, state, random, output);
            return;
        }
        PartLook look = level.getModelData(pos).get(ConduitBlockEntity.STATE_RENDER_DATA) instanceof PartLook parts
                ? parts
                : null;
        // A face with a part draws neither an arm to a neighbour nor Lib's stub: the part and its own arm stand there.
        int partMask = look == null ? 0 : look.mask();
        int load = look == null ? SmartLoad.IDLE : look.load();
        int lineArgb = lineArgb(data.colour(), load);
        int emission = CableTierMarks.lineEmission(brightness(load), StorageClientConfig.cableTierGlow());
        // Lib's connector rings are not drawn on cables: the arm runs to the block face (the hit shape keeps the ring).
        DyeColor label = ClientConfig.conduitColorLabels() ? data.colour() : null;
        output.add(bodies.get(new BodyKey(lineArgb, emission, data.arms() & ~partMask, partMask, label)));
        List<ConduitFaceKey> keys = data.faces();
        for (int i = 0; i < keys.size(); i++) {
            ConduitFaceKey key = keys.get(i);
            if (key.isStub() && (partMask & 1 << key.side().get3DDataValue()) == 0) {
                output.add(faces.get(key));
            }
        }
        if (look != null) {
            List<PartLook.Face> parts = look.faces();
            for (int i = 0; i < parts.size(); i++) {
                output.add(partPieces.get(new PartKey(parts.get(i), lineArgb, emission)));
            }
        }
    }

    /** Lib's key: it is made from the same render data, and value-equal data draws the same geometry here too. */
    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
            RandomSource random) {
        Object cable = lib.createGeometryKey(level, pos, state, random);
        PartLook parts = level.getModelData(pos).get(ConduitBlockEntity.STATE_RENDER_DATA) instanceof PartLook look
                ? look
                : null;
        // The lane's glow is baked into the mesh; Lib's key stands alone only in the default case.
        boolean glow = StorageClientConfig.cableTierGlow();
        return parts == null && glow ? cable : new GeometryKey(cable, parts, glow);
    }

    @Override
    public Material.Baked particleMaterial() {
        return sprites.covered();
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        ConduitRenderData data = level.getModelData(pos).get(ConduitBlockEntity.RENDER_DATA);
        return data != null && data.facade() != null ? lib.particleMaterial(level, pos, state) : particleMaterial();
    }

    /** The glass sleeve's quads are in the translucent layer; a covered cable has none. */
    @Override
    public int materialFlags() {
        return covered() ? 0 : BakedQuad.FLAG_TRANSLUCENT;
    }

    private boolean covered() {
        return type.look() == CableLook.COVERED;
    }

    /** The lane's brightness level: the tier, or a smart cable's load bucket. */
    private int brightness(int load) {
        return CableLane.brightness(type, load);
    }

    /** The lane's colour, or a covered cable's sleeve colour ({@link CableLane#argb}). */
    private int lineArgb(@Nullable DyeColor colour, int load) {
        return CableLane.argb(type, colour, load);
    }

    private BlockStateModelPart bakeBody(BodyKey key) {
        List<QuadBaking.Face> out = new ArrayList<>();
        for (CableGeometry.Face face : CableGeometry.body(type.thickness(), key.arms(), key.parts(), !covered())) {
            addCableFace(out, face, key.lineArgb(), key.emission());
        }
        DyeColor label = key.label();
        if (label != null) {
            List<ConduitQuad> letter = new ArrayList<>();
            int ink = ConduitLabels.ink(key.lineArgb());
            // Every look's core faces carry the tier pips (on a glass cable seen through the sleeve), so the letter
            // goes on the arms, clear of the mode glyph.
            ConduitLabels.addDyeLetterOnArms(letter, type.thickness(), key.arms(), label, ink,
                    CableModeMarks.letterSpan(type.thickness()));
            addFlat(out, letter);
        }
        return QuadBaking.part(out, sprites.covered());
    }

    /** Adds {@code quads} drawn with Lib's flat white sprite in their own colours. */
    private void addFlat(List<QuadBaking.Face> out, List<ConduitQuad> quads) {
        for (ConduitQuad quad : quads) {
            out.add(new QuadBaking.Face(quad.box(), quad.facing(), sprites.flat(), WHOLE, Quadrant.R0, quad.argb(),
                    quad.culled()));
        }
    }

    /** Adds {@code face} in this cable's look: the glass sleeve untinted, a covered sleeve and the lane tinted. */
    private void addCableFace(List<QuadBaking.Face> out, CableGeometry.Face face, int lineArgb, int emission) {
        boolean covered = covered();
        Material.Baked sprite = switch (face.texture()) {
            case SLEEVE -> covered ? sprites.covered() : sprites.sleeve();
            case SLEEVE_CORE -> covered ? sprites.coveredCore(type.level()) : sprites.sleeveCore();
            case SLEEVE_END -> covered ? sprites.coveredEnd() : sprites.sleeveCore();
            case LANE -> type.look() == CableLook.FIBER ? sprites.fiberLane() : sprites.lane();
            case LANE_CORE -> sprites.laneCore(type.level());
        };
        boolean lane = face.texture() == CableGeometry.Texture.LANE
                || face.texture() == CableGeometry.Texture.LANE_CORE;
        out.add(new QuadBaking.Face(face.box(), face.facing(), sprite,
                new CuboidFace.UVs(face.u0(), face.v0(), face.u1(), face.v1()),
                face.rotated() ? Quadrant.R90 : Quadrant.R0, lane || covered ? lineArgb : -1, face.culled(),
                lane ? emission : 0));
    }

    /** A part and the cable's arm from the core to the part's back; an unknown part draws only the arm. */
    private BlockStateModelPart bakePart(PartKey key) {
        List<QuadBaking.Face> out = new ArrayList<>();
        PartType part = PartTypes.byId(key.face().type());
        Direction side = key.face().side();
        if (part != null) {
            for (CableGeometry.Face face : CableGeometry.connector(type.thickness(), side, part.depth(), !covered())) {
                addCableFace(out, face, key.lineArgb(), key.emission());
            }
            PartBaker baker = partBakers.get(part.id());
            if (baker != null) {
                out.addAll(baker.bake(part, side, key.face().variant()));
            }
            Material.Baked line = lines.get(key.face().link());
            if (line != null) {
                // The part's front line in its look: a copy of each front face on top, as on device blocks.
                int faces = out.size();
                for (int i = 0; i < faces; i++) {
                    QuadBaking.Face face = out.get(i);
                    if (LinkLook.isFront(face.sprite().sprite().contents().name())) {
                        out.add(new QuadBaking.Face(face.box(), face.facing(), line, face.uvs(), face.rotation(),
                                face.argb(), face.culled(), face.emission()));
                    }
                }
            }
        }
        return QuadBaking.part(out, sprites.covered());
    }

    private BlockStateModelPart bakeFace(ConduitFaceKey key) {
        List<QuadBaking.Face> out = new ArrayList<>();
        addFlat(out, ConduitGeometry.face(ConduitLook.wire(), key));
        return QuadBaking.part(out, sprites.covered());
    }
}
