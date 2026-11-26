package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.platform.Transparency;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.drive.DriveBays;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jspecify.annotations.Nullable;

/**
 * The drive's model for one block state: its datagen model (casing with eight empty bays on the front, turned to the
 * drive's facing) plus one cell per occupied slot, flush in its bay with its light at the inner end by fill band
 * ({@link DriveBlockEntity#CELL_BANDS}: the Storage accent below 75 %, amber from 75 %, red when full, grey while
 * inactive: the MTX state colours). A cell is a copy of the front quad with the same corners, normals, light and AO,
 * drawn with a cell sprite that is transparent but for the cell ({@link DriveBays} maps it onto the slot's bay). The
 * copy lands in the cutout layer, which draws after the solid one with a less-or-equal depth test, so it covers the bay
 * without z-fighting, as {@link LinkLineModel}'s line does. Every (slot, band) part is made once when models bake;
 * meshing only adds cached parts, and the drive re-meshes only when a band changes. No block entity renderer.
 */
final class StorageDriveModel implements DynamicBlockStateModel {
    /** The front texture whose quads the cells are copied from. */
    static final Identifier FRONT = MaxtechStorage.id("block/drive/front");
    private static final Direction[] SIDES = Direction.values();
    private static final int BANDS = 5;

    private final BlockStateModel base;
    /** Per slot, per band: the cell's part, or null for an empty slot or a front without a drive front texture. */
    private final @Nullable BlockStateModelPart[][] cells;

    private record GeometryKey(BlockState state, int bands) {
    }

    /**
     * {@code state}'s drive model around {@code base}. {@code sprites} holds the cell sprite per band
     * ({@link DriveBays#cellTexture}), null where a band draws no cell.
     */
    StorageDriveModel(BlockStateModel base, BlockState state, Material.@Nullable Baked[] sprites) {
        if (sprites.length != BANDS) {
            throw new IllegalArgumentException("One cell sprite per band: " + sprites.length);
        }
        this.base = base;
        List<BlockStateModelPart> parts = new ArrayList<>();
        base.collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, state, RandomSource.create(42L), parts);
        this.cells = new BlockStateModelPart[DriveBlockEntity.SLOTS][BANDS];
        for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
            for (int band = 0; band < BANDS; band++) {
                Material.Baked sprite = sprites[band];
                if (sprite != null) {
                    cells[slot][band] = cell(parts, slot, sprite);
                }
            }
        }
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            List<BlockStateModelPart> output) {
        base.collectParts(level, pos, state, random, output);
        Integer bands = level.getModelData(pos).get(DriveBlockEntity.CELL_BANDS);
        if (bands == null) {
            return;
        }
        for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
            int band = DriveBlockEntity.band(bands, slot);
            if (band < BANDS) {
                BlockStateModelPart cell = cells[slot][band];
                if (cell != null) {
                    output.add(cell);
                }
            }
        }
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        Integer bands = level.getModelData(pos).get(DriveBlockEntity.CELL_BANDS);
        return new GeometryKey(state, bands == null ? 0 : bands);
    }

    @Override
    public Material.Baked particleMaterial() {
        return base.particleMaterial();
    }

    @Override
    public int materialFlags() {
        return base.materialFlags();
    }

    /** Slot {@code slot}'s cell drawn with {@code sprite}: a copy of every drive front quad in {@code parts}. */
    static @Nullable BlockStateModelPart cell(List<BlockStateModelPart> parts, int slot, Material.Baked sprite) {
        QuadCollection.Builder quads = new QuadCollection.Builder();
        boolean any = false;
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) {
                for (BakedQuad quad : part.getQuads(side)) {
                    if (front(quad)) {
                        quads.addCulledFace(side, copy(quad, slot, sprite));
                        any = true;
                    }
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                if (front(quad)) {
                    quads.addUnculledFace(copy(quad, slot, sprite));
                    any = true;
                }
            }
        }
        return any ? new SimpleModelWrapper(quads.build(), true, sprite) : null;
    }

    private static boolean front(BakedQuad quad) {
        return quad.materialInfo().sprite().contents().name().equals(FRONT);
    }

    /**
     * {@code quad} drawn with the cell sprite: the same corners, normals, colours, light and shading, each corner's UV
     * moved from the front texture to the slot's window of the cell sprite, in the layer the sprite's pixels ask for.
     */
    static BakedQuad copy(BakedQuad quad, int slot, Material.Baked cell) {
        BakedQuad.MaterialInfo from = quad.materialInfo();
        TextureAtlasSprite source = from.sprite();
        TextureAtlasSprite target = cell.sprite();
        Transparency transparency = target.contents().transparency();
        BakedQuad.MaterialInfo info = BakedQuad.MaterialInfo.of(cell, transparency, from.tintIndex(), from.shade(),
                from.lightEmission(), from.ambientOcclusion());
        return new BakedQuad(quad.position0(), quad.position1(), quad.position2(), quad.position3(),
                move(quad.packedUV0(), slot, source, target), move(quad.packedUV1(), slot, source, target),
                move(quad.packedUV2(), slot, source, target), move(quad.packedUV3(), slot, source, target),
                quad.direction(), info, quad.bakedNormals(), quad.bakedColors());
    }

    /** {@code packed}, a UV on the front sprite in the atlas, moved to slot {@code slot}'s spot of the cell sprite. */
    private static long move(long packed, int slot, TextureAtlasSprite source, TextureAtlasSprite target) {
        float u = (UVPair.unpackU(packed) - source.getU0()) / (source.getU1() - source.getU0());
        float v = (UVPair.unpackV(packed) - source.getV0()) / (source.getV1() - source.getV0());
        return UVPair.pack(target.getU0() + DriveBays.cellU(slot, u) * (target.getU1() - target.getU0()),
                target.getV0() + DriveBays.cellV(slot, v) * (target.getV1() - target.getV0()));
    }
}
