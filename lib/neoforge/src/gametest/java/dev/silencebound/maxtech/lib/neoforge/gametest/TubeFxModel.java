package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitColours;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import dev.silencebound.maxtech.lib.client.conduit.ConduitUvs;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import dev.silencebound.maxtech.lib.neoforge.gametest.TubeFx.Effect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import org.jspecify.annotations.Nullable;

/**
 * PROTOTYPE (proto/tube-fx only): the tubes' model hook. Lib's shell body with its border strips and rims drawn in the
 * rail texture, optionally a moulded fitting at junctions, then the contents from the fluid's own baked fluid model
 * (still and flowing sprites, tint) and the effects the render value names. Pure geometry per key; Lib caches the
 * parts.
 */
final class TubeFxModel implements ConduitModelHook {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPS = 1.0E-4;
    private static final int WHITE = 0xFFFFFFFF;
    /** Contents sit this far inside the glass, in pixels. */
    private static final double INSET = 0.5;
    /** A moulded fitting is this much wider than the tube on each side (1 px wider in all). */
    private static final double FITTING = 0.5;
    private static final double STRIP_OFFSET = 0.02;
    private static final double RAIL_WIDTH = 1.0;
    private static final float RAIL_ROWS = 4.0F;

    static final Identifier RAIL = sprite("rail");
    static final Identifier BUBBLES = sprite("bubbles");
    static final Identifier CAUSTICS = sprite("caustics");
    static final Identifier MENISCUS = sprite("meniscus");
    static final Identifier MOTES = sprite("motes");
    static final Identifier PULSE = sprite("pulse");
    static final Identifier GAUGE_LIT = sprite("gauge_lit");
    static final Identifier GAUGE_DIM = sprite("gauge_dim");
    static final Identifier GLOW_ON = sprite("glow_on");
    static final Identifier GLOW_OFF = sprite("glow_off");
    static final Identifier EMBERS = sprite("embers");
    static final Identifier FROST = sprite("frost");
    static final Identifier DROPLETS = sprite("droplets");
    static final Identifier HAZE = sprite("haze");
    static final Set<Identifier> EMISSIVE = Set.of(PULSE, GAUGE_LIT, GLOW_ON, EMBERS);

    private static final Identifier LAVA = Identifier.withDefaultNamespace("lava");
    private static final Identifier GEL = Identifier.fromNamespaceAndPath(TubeFx.MOD_ID, "test_nutrient_gel");
    private static final Identifier CRYO = Identifier.fromNamespaceAndPath(TubeFx.MOD_ID, "test_cryo_fluid");
    /** Effect 8: the one per-fluid table; every other fluid gets none. */
    private static final Map<Identifier, Identifier> FLUID_OVERLAY = Map.of(GEL, BUBBLES, LAVA, EMBERS, CRYO, FROST);

    private final boolean fitted;

    TubeFxModel(boolean fitted) {
        this.fitted = fitted;
    }

    private static Identifier sprite(String name) {
        return Identifier.fromNamespaceAndPath(TubeFx.MOD_ID, "block/proto_fx/" + name);
    }

    @Override
    public Set<Identifier> sprites() {
        return Set.of(RAIL, BUBBLES, CAUSTICS, MENISCUS, MOTES, PULSE, GAUGE_LIT, GAUGE_DIM, GLOW_ON, GLOW_OFF, EMBERS,
                FROST, DROPLETS, HAZE);
    }

    @Override
    public List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
        List<ConduitQuad> lib = ConduitParts.body(look, key, labels);
        if (!(look instanceof ConduitLook.Shell shell)) {
            return lib;
        }
        float thickness = key.type().thickness();
        int arms = key.arms();
        int rings = key.rings();
        int borderArgb = ConduitColours.argb(ConduitLook.resolve(shell.borderColour(), key.type()), key.colour());
        AABB core = ConduitShapes.core(thickness);
        AABB fitting = fitted && isJunction(arms) ? core.inflate(FITTING) : null;
        List<ConduitQuad> out = new ArrayList<>();
        for (ConduitQuad quad : lib) {
            AABB box = fitting == null ? quad.box() : clip(quad.box(), fitting);
            if (box == null) {
                continue;
            }
            boolean border = quad.sprite() == null && quad.texture() == ConduitTexture.FLAT
                    && quad.argb() == borderArgb;
            if (border) {
                out.add(rail(box, quad.facing()));
            } else if (!box.equals(quad.box())) {
                ConduitUv uv = quad.uv();
                out.add(new ConduitQuad(box, quad.facing(), quad.texture(), quad.argb(), quad.culled(), quad.layer(),
                        quad.sprite(),
                        uv == null ? null : ConduitUvs.projected(box, quad.facing(), uv.quarterTurns())));
            } else {
                out.add(quad);
            }
        }
        if (fitting != null) {
            addFitting(out, fitting, core, arms, shell.shell());
        }
        TubeFx.Value value = TubeFx.Value.decode(key.renderValue());
        addContents(out, value, core, arms, rings, shell);
        return out;
    }

    /** A bend or a junction of three or more arms; straight runs and ends have no fitting. */
    private static boolean isJunction(int arms) {
        int count = Integer.bitCount(arms);
        if (count >= 3) {
            return true;
        }
        if (count != 2) {
            return false;
        }
        for (Direction side : DIRECTIONS) {
            if (ConduitConnections.isConnected(arms, side)
                    && ConduitConnections.isConnected(arms, side.getOpposite())) {
                return false;
            }
        }
        return true;
    }

    /** {@code box} with the part inside {@code fitting} cut off; null if nothing is left. */
    private static @Nullable AABB clip(AABB box, AABB fitting) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (!(box.max(axis) > fitting.min(axis) + EPS && box.min(axis) < fitting.max(axis) - EPS)
                    && !(flat(box, axis) && box.min(axis) > fitting.min(axis) - EPS
                            && box.min(axis) < fitting.max(axis) + EPS)) {
                return box;
            }
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (!within(box, fitting, axis)) {
                continue;
            }
            boolean inside = box.min(axis) >= fitting.min(axis) - EPS && box.max(axis) <= fitting.max(axis) + EPS;
            if (inside) {
                return null;
            }
            if (box.min(axis) < fitting.min(axis) - EPS) {
                return with(box, axis, box.min(axis), fitting.min(axis));
            }
            return with(box, axis, fitting.max(axis), box.max(axis));
        }
        return box;
    }

    private static boolean flat(AABB box, Direction.Axis axis) {
        return box.max(axis) - box.min(axis) < EPS;
    }

    /** Whether {@code box} lies inside {@code fitting} on both axes other than {@code axis}. */
    private static boolean within(AABB box, AABB fitting, Direction.Axis axis) {
        for (Direction.Axis other : Direction.Axis.values()) {
            if (other != axis
                    && (box.min(other) < fitting.min(other) - EPS || box.max(other) > fitting.max(other) + EPS)) {
                return false;
            }
        }
        return true;
    }

    /** A rail-textured strip: the sprite's length along the strip's long side, its four rows across it. */
    private static ConduitQuad rail(AABB box, Direction face) {
        Direction.Axis length = null;
        double longest = -1;
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != face.getAxis() && box.max(axis) - box.min(axis) > longest) {
                longest = box.max(axis) - box.min(axis);
                length = axis;
            }
        }
        Direction along = Direction.fromAxisAndDirection(length == null ? Direction.Axis.X : length,
                Direction.AxisDirection.POSITIVE);
        int turns = (ConduitUvs.flowTurns(face, along).orElse(3) + 1) % 4;
        ConduitUv p = ConduitUvs.projected(box, face, turns);
        boolean down = p.v0() <= p.v1();
        // The sprite holds the four rail rows four times over; one copy spans the strip's width.
        ConduitUv uv = new ConduitUv(p.u0(), down ? 0 : RAIL_ROWS, p.u1(), down ? RAIL_ROWS : 0, turns);
        return new ConduitQuad(box, face, ConduitTexture.FLAT, WHITE, false, ConduitLayer.AUTO, RAIL, uv);
    }

    /** The fitting's glass, flanges round the arms leaving it and rails on its twelve edges. */
    private static void addFitting(List<ConduitQuad> out, AABB fitting, AABB core, int arms, Identifier glass) {
        for (Direction face : DIRECTIONS) {
            AABB plane = face(fitting, face, 0);
            if (!ConduitConnections.isConnected(arms, face)) {
                out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, WHITE, false, ConduitLayer.TRANSLUCENT, glass,
                        ConduitUvs.projected(plane, face)));
                continue;
            }
            // A frame between the fitting's face and the arm's glass.
            Direction.Axis[] inPlane = others(face.getAxis());
            for (int i = 0; i < 2; i++) {
                Direction.Axis a = inPlane[i];
                Direction.Axis b = inPlane[1 - i];
                AABB low = with(with(plane, a, fitting.min(a), core.min(a)), b, fitting.min(b), fitting.max(b));
                AABB high = with(with(plane, a, core.max(a), fitting.max(a)), b, fitting.min(b), fitting.max(b));
                if (i == 1) {
                    low = with(low, b, core.min(b), core.max(b));
                    high = with(high, b, core.min(b), core.max(b));
                }
                out.add(rail(low, face));
                out.add(rail(high, face));
            }
        }
        for (Direction on : DIRECTIONS) {
            for (Direction beside : DIRECTIONS) {
                if (beside.getAxis() != on.getAxis()) {
                    out.add(rail(edgeStrip(fitting, on, beside), on));
                }
            }
        }
    }

    /** The strip on face {@code on} of {@code box} beside its edge with {@code beside}, just outside it. */
    private static AABB edgeStrip(AABB box, Direction on, Direction beside) {
        Direction.Axis across = beside.getAxis();
        boolean positive = beside.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double edge = positive ? box.max(across) : box.min(across);
        double inner = positive ? edge - RAIL_WIDTH : edge + RAIL_WIDTH;
        double outer = positive ? edge + STRIP_OFFSET : edge - STRIP_OFFSET;
        AABB pane = face(box.inflate(STRIP_OFFSET), on, 0);
        return with(pane, across, Math.min(inner, outer), Math.max(inner, outer));
    }

    // ---- contents ----

    /** One box of contents and the flow through it (null when it stands still). */
    private record Piece(AABB box, @Nullable Direction flow, int skip, boolean partial) {
    }

    private static void addContents(List<ConduitQuad> out, TubeFx.Value value, AABB core, int arms, int rings,
            ConduitLook.Shell shell) {
        Fluid fluid = BuiltInRegistries.FLUID.byId(value.fluidId());
        float thickness = (float) ((core.maxX - core.minX) / 16.0);
        if (value.has(Effect.GAUGE)) {
            addGauge(out, value, core, arms);
        }
        if (value.has(Effect.GLOW)) {
            addGlow(out, value, core, arms, rings, shell, thickness);
        }
        if (fluid == null || fluid.isSame(Fluids.EMPTY) || value.fill() == 0) {
            return;
        }
        Identifier fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet()
                .get(fluid.defaultFluidState());
        Identifier still = remember(model.stillMaterial(), fluid);
        Identifier flowing = remember(model.flowingMaterial(), fluid);
        FluidTintSource tintSource = model.fluidTintSource();
        int tint = tintSource == null ? WHITE : ARGB.opaque(tintSource.color(fluid.defaultFluidState()));
        boolean moving = value.flowing() && value.upstream() != null;
        List<Piece> pieces = pieces(core, arms, rings, value, thickness);
        List<AABB> glassBoxes = glassBoxes(core, arms, rings, thickness);
        for (Piece piece : pieces) {
            for (Direction face : DIRECTIONS) {
                if (ConduitConnections.isConnected(piece.skip(), face)) {
                    continue;
                }
                Direction flow = piece.flow();
                AABB plane = face(piece.box(), face, 0);
                boolean along = moving && flow != null && ConduitUvs.flowTurns(face, flow).isPresent();
                if (along && flow != null) {
                    ConduitUv uv = half(ConduitUvs.projected(plane, face, ConduitUvs.flowTurns(face, flow).getAsInt()));
                    out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, tint, false, ConduitLayer.CUTOUT, flowing,
                            uv));
                } else {
                    out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, tint, false, ConduitLayer.CUTOUT, still,
                            ConduitUvs.projected(plane, face)));
                }
                int slot = 0;
                for (int effectId : new int[]{value.effectA(), value.effectB()}) {
                    Effect effect = Effect.byId(effectId);
                    if (effect == null) {
                        continue;
                    }
                    double offset = 0.02 + 0.02 * slot++;
                    Direction drift = flow != null ? flow : Direction.UP;
                    switch (effect) {
                        case BUBBLES -> overlay(out, piece.box(), face, offset, BUBBLES, drift, WHITE);
                        case CAUSTICS -> overlay(out, piece.box(), face, offset, CAUSTICS, null, WHITE);
                        case MOTES -> overlay(out, piece.box(), face, offset, MOTES, drift, WHITE);
                        case PULSE -> overlay(out, piece.box(), face, offset, PULSE, flow, WHITE);
                        case MENISCUS -> meniscus(out, piece, face, offset, tint);
                        case FLUID_OVERLAY -> {
                            Identifier extra = FLUID_OVERLAY.get(fluidId);
                            if (extra != null && (extra.equals(BUBBLES) || extra.equals(EMBERS))) {
                                overlay(out, piece.box(), face, offset, extra, drift, WHITE);
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        // Effects on the inside of the glass.
        if (value.has(Effect.FLUID_OVERLAY) && fluidId.equals(CRYO)) {
            glassOverlay(out, glassBoxes, arms, FROST, false);
        }
        if (value.has(Effect.CONDENSATION)) {
            if (fluidId.equals(CRYO)) {
                glassOverlay(out, glassBoxes, arms, DROPLETS, true);
            } else if (fluidId.equals(LAVA)) {
                glassOverlay(out, glassBoxes, arms, HAZE, false);
            }
        }
    }

    /** The fluid's sprite id, handed to Lib's prototype sprite hook with the fluid's light level. */
    private static Identifier remember(Material.Baked material, Fluid fluid) {
        Identifier id = material.sprite().contents().name();
        TubeFxClient.FLUID_SPRITES.put(id, material);
        int light = fluid.getFluidType().getLightLevel();
        if (light > 0) {
            TubeFxClient.EMISSION.put(id, light);
        }
        return id;
    }

    /**
     * The contents boxes: a straight run is one box, else the core and one box per arm. Horizontal pieces fill from the
     * bottom; vertical ones (and a core with a vertical arm) are full columns.
     */
    private static List<Piece> pieces(AABB core, int arms, int rings, TubeFx.Value value, float thickness) {
        AABB inner = core.deflate(INSET);
        double top = inner.minY + (inner.maxY - inner.minY) * value.fill() / 4.0;
        Direction up = value.upstream();
        List<Piece> pieces = new ArrayList<>();
        Direction.Axis straight = null;
        for (Direction.Axis axis : Direction.Axis.values()) {
            Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            if (arms == (ConduitConnections.bit(negative) | ConduitConnections.bit(negative.getOpposite()))) {
                straight = axis;
            }
        }
        if (straight != null) {
            Direction negative = Direction.fromAxisAndDirection(straight, Direction.AxisDirection.NEGATIVE);
            AABB box = armContents(inner, negative, rings, thickness)
                    .minmax(armContents(inner, negative.getOpposite(), rings, thickness));
            boolean partial = straight != Direction.Axis.Y;
            Direction flow = up != null && up.getAxis() == straight ? up.getOpposite() : null;
            pieces.add(new Piece(partial ? with(box, Direction.Axis.Y, box.minY, top) : box, flow, 0, partial));
            return pieces;
        }
        boolean vertical = ConduitConnections.isConnected(arms, Direction.UP)
                || ConduitConnections.isConnected(arms, Direction.DOWN);
        int coreSkip = 0;
        for (Direction side : DIRECTIONS) {
            // A full core shows its side above a partly filled horizontal arm, so only equal heights skip it.
            boolean armPartial = side.getAxis() != Direction.Axis.Y;
            if (ConduitConnections.isConnected(arms, side) && (!vertical || !armPartial)) {
                coreSkip |= ConduitConnections.bit(side);
            }
        }
        pieces.add(new Piece(vertical ? inner : with(inner, Direction.Axis.Y, inner.minY, top),
                up != null ? up.getOpposite() : null, coreSkip, !vertical));
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, side)) {
                continue;
            }
            AABB box = armContents(inner, side, rings, thickness);
            boolean partial = side.getAxis() != Direction.Axis.Y;
            Direction flow = up == null ? null : side == up ? side.getOpposite() : side;
            pieces.add(new Piece(partial ? with(box, Direction.Axis.Y, box.minY, top) : box, flow,
                    ConduitConnections.bit(side.getOpposite()), partial));
        }
        return pieces;
    }

    /** An arm's contents: the core's inner cross-section, from the core's inner face out to the arm's end. */
    private static AABB armContents(AABB inner, Direction side, int rings, float thickness) {
        AABB arm = ConduitConnections.isConnected(rings, side)
                ? ConduitShapes.armBeforeRing(thickness, side)
                : ConduitShapes.arm(thickness, side);
        Direction.Axis axis = side.getAxis();
        boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        AABB box = inner;
        return positive
                ? with(box, axis, inner.max(axis), arm.max(axis))
                : with(box, axis, arm.min(axis), inner.min(axis));
    }

    /** The glass boxes as Lib lays them out: one per straight run, else the core and each arm. */
    private static List<AABB> glassBoxes(AABB core, int arms, int rings, float thickness) {
        List<AABB> boxes = new ArrayList<>();
        boxes.add(core);
        for (Direction side : DIRECTIONS) {
            if (ConduitConnections.isConnected(arms, side)) {
                boxes.add(ConduitConnections.isConnected(rings, side)
                        ? ConduitShapes.armBeforeRing(thickness, side)
                        : ConduitShapes.arm(thickness, side));
            }
        }
        return boxes;
    }

    /** A sprite on the inside of the glass, on every face that is glass (not where an arm leaves the core). */
    private static void glassOverlay(List<ConduitQuad> out, List<AABB> boxes, int arms, Identifier sprite,
            boolean sidesOnly) {
        for (int i = 0; i < boxes.size(); i++) {
            AABB box = boxes.get(i);
            for (Direction face : DIRECTIONS) {
                if (sidesOnly && face.getAxis() == Direction.Axis.Y) {
                    continue;
                }
                if (i == 0
                        ? ConduitConnections.isConnected(arms, face)
                        : armAxis(box, boxes.get(0)) == face.getAxis()) {
                    continue;
                }
                AABB plane = face(box, face, -0.06);
                out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, WHITE, false, ConduitLayer.TRANSLUCENT,
                        sprite, ConduitUvs.projected(plane, face)));
            }
        }
    }

    /** The axis an arm box runs along: the one where it reaches past the core. */
    private static Direction.Axis armAxis(AABB arm, AABB core) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (arm.min(axis) < core.min(axis) - EPS || arm.max(axis) > core.max(axis) + EPS) {
                return axis;
            }
        }
        return Direction.Axis.Y;
    }

    /** An effect sprite on a face of the contents, {@code offset} px outside it, drifting along {@code drift}. */
    private static void overlay(List<ConduitQuad> out, AABB box, Direction face, double offset, Identifier sprite,
            @Nullable Direction drift, int argb) {
        AABB plane = face(box, face, offset);
        int turns = drift == null ? 0 : ConduitUvs.flowTurns(face, drift).orElse(0);
        out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, argb, false, ConduitLayer.TRANSLUCENT, sprite,
                ConduitUvs.projected(plane, face, turns)));
    }

    /** Effect 3: a bright line round a partly filled piece at its fill level. */
    private static void meniscus(List<ConduitQuad> out, Piece piece, Direction face, double offset, int tint) {
        if (!piece.partial() || face == Direction.DOWN) {
            return;
        }
        AABB box = piece.box();
        int light = ARGB.srgbLerp(0.55F, tint, WHITE);
        if (face == Direction.UP) {
            // The surface itself, a little brighter, so the level reads from above too.
            AABB surface = face(box, Direction.UP, offset);
            out.add(new ConduitQuad(surface, Direction.UP, ConduitTexture.FLAT, ARGB.color(70, light), false,
                    ConduitLayer.TRANSLUCENT, MENISCUS, ConduitUvs.projected(surface, Direction.UP)));
            return;
        }
        AABB band = with(box, Direction.Axis.Y, Math.max(box.minY, box.maxY - 0.35), box.maxY);
        AABB plane = face(band, face, offset);
        out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, light, false, ConduitLayer.TRANSLUCENT, MENISCUS,
                ConduitUvs.projected(plane, face)));
    }

    /** Effect 6: four ticks on both sides of a straight horizontal run, lit up to the fill level. */
    private static void addGauge(List<ConduitQuad> out, TubeFx.Value value, AABB core, int arms) {
        Direction.Axis run = null;
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            if (arms == (ConduitConnections.bit(negative) | ConduitConnections.bit(negative.getOpposite()))) {
                run = axis;
            }
        }
        if (run == null) {
            return;
        }
        Direction.Axis across = run == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        double height = core.maxY - core.minY - 2 * RAIL_WIDTH;
        for (Direction.AxisDirection sign : Direction.AxisDirection.values()) {
            Direction face = Direction.fromAxisAndDirection(across, sign);
            for (int i = 1; i <= 4; i++) {
                double y = core.minY + RAIL_WIDTH + (i - 0.5) * height / 4;
                double length = i % 2 == 0 ? 2.0 : 1.25;
                AABB tick = with(with(core, Direction.Axis.Y, y - 0.25, y + 0.25), run, 8 - length / 2, 8 + length / 2);
                AABB plane = face(tick, face, 0.05);
                Identifier sprite = i <= value.fill() ? GAUGE_LIT : GAUGE_DIM;
                out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, WHITE, false, ConduitLayer.AUTO, sprite,
                        ConduitUvs.projected(plane, face)));
            }
        }
    }

    /**
     * Effect 7: a light ring just inside the rim where an arm meets a machine, and round an open end; bright and
     * full-bright while flowing, dim when idle.
     */
    private static void addGlow(List<ConduitQuad> out, TubeFx.Value value, AABB core, int arms, int rings,
            ConduitLook.Shell shell, float thickness) {
        Identifier sprite = value.flowing() ? GLOW_ON : GLOW_OFF;
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(rings, side) || !ConduitConnections.isConnected(arms, side)) {
                continue;
            }
            AABB arm = ConduitShapes.armBeforeRing(thickness, side);
            Direction.Axis axis = side.getAxis();
            boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            double end = positive ? arm.max(axis) : arm.min(axis);
            double from = positive ? end - shell.rim() - 1.0 : end + shell.rim();
            band(out, arm, axis, from, from + 1.0, sprite);
        }
        if (Integer.bitCount(arms) == 1) {
            for (Direction side : DIRECTIONS) {
                if (ConduitConnections.isConnected(arms, side)) {
                    Direction cap = side.getOpposite();
                    Direction.Axis axis = cap.getAxis();
                    boolean positive = cap.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                    double end = positive ? core.max(axis) : core.min(axis);
                    double from = positive ? end - shell.rim() - 1.0 : end + shell.rim();
                    band(out, core, axis, from, from + 1.0, sprite);
                }
            }
        }
    }

    private static void band(List<ConduitQuad> out, AABB box, Direction.Axis axis, double from, double to,
            Identifier sprite) {
        for (Direction face : DIRECTIONS) {
            if (face.getAxis() == axis) {
                continue;
            }
            AABB plane = with(face(box.inflate(0.04), face, 0), axis, from, to);
            int turns = ConduitUvs
                    .flowTurns(face, Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)).orElse(0);
            out.add(new ConduitQuad(plane, face, ConduitTexture.FLAT, WHITE, false, ConduitLayer.AUTO, sprite,
                    ConduitUvs.projected(plane, face, turns)));
        }
    }

    // ---- box helpers ----

    /** The flat box on {@code face} of {@code box}, moved {@code offset} px outward (inward when negative). */
    private static AABB face(AABB box, Direction face, double offset) {
        Direction.Axis axis = face.getAxis();
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? box.max(axis) + offset
                : box.min(axis) - offset;
        return with(box, axis, plane, plane);
    }

    private static Direction.Axis[] others(Direction.Axis axis) {
        return switch (axis) {
            case X -> new Direction.Axis[]{Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Y};
        };
    }

    /** The flowing sprite at vanilla's scale: it is drawn at twice the still sprite's density. */
    private static ConduitUv half(ConduitUv uv) {
        return new ConduitUv(uv.u0() / 2, uv.v0() / 2, uv.u1() / 2, uv.v1() / 2, uv.quarterTurns());
    }

    private static AABB with(AABB box, Direction.Axis axis, double min, double max) {
        return switch (axis) {
            case X -> new AABB(min, box.minY, box.minZ, max, box.maxY, box.maxZ);
            case Y -> new AABB(box.minX, min, box.minZ, box.maxX, max, box.maxZ);
            case Z -> new AABB(box.minX, box.minY, min, box.maxX, box.maxY, max);
        };
    }
}
