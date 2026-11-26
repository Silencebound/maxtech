package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.platform.Transparency;
import dev.silencebound.maxtech.storage.network.LinkLook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jspecify.annotations.Nullable;

/**
 * A device block's model with the line on its front in the device's {@link LinkLook} (MTX, owner 2026-10-17). The front
 * textures ({@link LinkLook#FRONT_TEXTURES}) carry the online line, so online draws the block's own model unchanged and
 * items show it. For no channel and offline, every front quad gets a copy on top: the same four corners, so the same
 * depth, light and ambient occlusion, drawn with the look's line overlay (transparent but for the line, so in the
 * cutout layer, which draws after the solid one and wins at equal depth, as vanilla's grass overlay does). The copies
 * are made once per block state when models bake; meshing only adds a cached part. No block entity renderer.
 */
final class LinkLineModel implements DynamicBlockStateModel {
    private static final Direction[] SIDES = Direction.values();

    private final BlockStateModel base;
    private final BlockStateModelPart overlay;
    private final LinkLook look;

    /** The geometry key while the base model gives one: the overlay changes the geometry. */
    private record Key(Object base, LinkLook look) {
    }

    private LinkLineModel(BlockStateModel base, BlockStateModelPart overlay, LinkLook look) {
        this.base = base;
        this.overlay = overlay;
        this.look = look;
    }

    /**
     * {@code state}'s model: {@code base} with the line of the state's {@link LinkLook#PROPERTY} on its fronts, drawn
     * from {@code lines} (one overlay per look but online). Returns {@code base} itself for online and for a model that
     * draws no front texture.
     */
    static BlockStateModel wrap(BlockStateModel base, BlockState state, Map<LinkLook, Material.Baked> lines) {
        LinkLook look = state.getValue(LinkLook.PROPERTY);
        Material.Baked line = lines.get(look);
        if (line == null) {
            return base;
        }
        List<BlockStateModelPart> parts = new ArrayList<>();
        base.collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, state, RandomSource.create(42L), parts);
        BlockStateModelPart overlay = overlay(parts, line);
        return overlay == null ? base : new LinkLineModel(base, overlay, look);
    }

    /** The line copies of every front quad in {@code parts}, drawn with {@code line}; null when there is none. */
    static @Nullable BlockStateModelPart overlay(List<BlockStateModelPart> parts, Material.Baked line) {
        QuadCollection.Builder quads = new QuadCollection.Builder();
        boolean any = false;
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) {
                for (BakedQuad quad : part.getQuads(side)) {
                    if (front(quad)) {
                        quads.addCulledFace(side, copy(quad, line));
                        any = true;
                    }
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                if (front(quad)) {
                    quads.addUnculledFace(copy(quad, line));
                    any = true;
                }
            }
        }
        return any ? new SimpleModelWrapper(quads.build(), true, line) : null;
    }

    private static boolean front(BakedQuad quad) {
        return LinkLook.isFront(quad.materialInfo().sprite().contents().name());
    }

    /**
     * {@code quad} drawn with {@code line} instead of its own sprite: the same corners, normals, colours, light and
     * shading, each corner's UV moved to the same spot of the line's sprite, in the layer the line's pixels ask for.
     */
    static BakedQuad copy(BakedQuad quad, Material.Baked line) {
        BakedQuad.MaterialInfo from = quad.materialInfo();
        TextureAtlasSprite source = from.sprite();
        TextureAtlasSprite target = line.sprite();
        Transparency transparency = target.contents().transparency();
        BakedQuad.MaterialInfo info = BakedQuad.MaterialInfo.of(line, transparency, from.tintIndex(), from.shade(),
                from.lightEmission(), from.ambientOcclusion());
        return new BakedQuad(quad.position0(), quad.position1(), quad.position2(), quad.position3(),
                move(quad.packedUV0(), source, target), move(quad.packedUV1(), source, target),
                move(quad.packedUV2(), source, target), move(quad.packedUV3(), source, target), quad.direction(), info,
                quad.bakedNormals(), quad.bakedColors());
    }

    /** {@code packed}, a UV on {@code source} in the atlas, moved to the same spot of {@code target}. */
    private static long move(long packed, TextureAtlasSprite source, TextureAtlasSprite target) {
        float u = (UVPair.unpackU(packed) - source.getU0()) / (source.getU1() - source.getU0());
        float v = (UVPair.unpackV(packed) - source.getV0()) / (source.getV1() - source.getV0());
        return UVPair.pack(target.getU0() + u * (target.getU1() - target.getU0()),
                target.getV0() + v * (target.getV1() - target.getV0()));
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            List<BlockStateModelPart> output) {
        base.collectParts(level, pos, state, random, output);
        output.add(overlay);
    }

    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
            RandomSource random) {
        Object key = base.createGeometryKey(level, pos, state, random);
        return key == null ? null : new Key(key, look);
    }

    @Override
    public Material.Baked particleMaterial() {
        return base.particleMaterial();
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return base.particleMaterial(level, pos, state);
    }

    @Override
    public int materialFlags() {
        return base.materialFlags();
    }

    @Override
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return base.materialFlags(level, pos, state);
    }
}
