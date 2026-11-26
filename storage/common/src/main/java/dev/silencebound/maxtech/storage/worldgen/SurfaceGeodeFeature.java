package dev.silencebound.maxtech.storage.worldgen;

import com.mojang.serialization.Codec;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * A geode of Storage's that sits a configured depth under the surface instead of in a height band (PRD 7.2: void geodes
 * on the outer End islands, whose height varies from island to island and which float over the void). Once per chunk,
 * one chunk in {@code rarity} picks a random column; the geode goes there only if the column has ground, and its origin
 * sits {@code minDepth} to {@code maxDepth} blocks under the top block, in solid ground. Vanilla's geode feature then
 * refuses a spot where too much of the geode would be air, so geodes stay inside islands. Rarity and depths are read
 * from config at generation time; the dimension must be in {@code dimensions}.
 */
public final class SurfaceGeodeFeature extends Feature<NoneFeatureConfiguration> {
    private static final int CHUNK = 16;

    private final IntSupplier rarity;
    private final IntSupplier minDepth;
    private final IntSupplier maxDepth;
    private final TagKey<DimensionType> dimensions;
    private final Supplier<GeodeConfiguration> configuration;
    // Built on first use, after the geode's blocks exist.
    private @Nullable GeodeConfiguration geode;

    /**
     * A geode feature reading its rarity (one chunk in so many, 0 for none) and its depth under the surface from
     * config, placing {@code configuration}'s geode only in dimensions of {@code dimensions}.
     */
    public SurfaceGeodeFeature(Codec<NoneFeatureConfiguration> codec, IntSupplier rarity, IntSupplier minDepth,
            IntSupplier maxDepth, TagKey<DimensionType> dimensions, Supplier<GeodeConfiguration> configuration) {
        super(codec);
        this.rarity = rarity;
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
        this.dimensions = dimensions;
        this.configuration = configuration;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        int chance = rarity.getAsInt();
        if (chance <= 0 || !level.getLevel().dimensionTypeRegistration().is(dimensions)) {
            return false;
        }
        RandomSource random = context.random();
        if (random.nextInt(chance) != 0) {
            return false;
        }
        BlockPos origin = context.origin();
        int x = origin.getX() + random.nextInt(CHUNK);
        int z = origin.getZ() + random.nextInt(CHUNK);
        // The generation heightmap is the one kept up to date while features run.
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        return placeUnder(level, context.chunkGenerator(), random, x, surface, z);
    }

    /**
     * Places one geode under the top block {@code surface} of column {@code x, z}: at a random configured depth, only
     * if the column has ground there (the surface above the world's bottom and a solid, dry block at the origin).
     * Vanilla's geode feature may still refuse it. Worldgen or server thread.
     */
    public boolean placeUnder(WorldGenLevel level, ChunkGenerator generator, RandomSource random, int x, int surface,
            int z) {
        if (surface <= level.getMinY()) {
            return false;
        }
        int least = minDepth.getAsInt();
        int most = Math.max(least, maxDepth.getAsInt());
        int y = surface - least - random.nextInt(most - least + 1);
        if (y <= level.getMinY()) {
            return false;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState ground = level.getBlockState(pos);
        if (ground.isAir() || !ground.getFluidState().isEmpty()) {
            return false;
        }
        return placeGeode(level, generator, random, pos);
    }

    /**
     * Places one geode with its origin at {@code pos}, without the rarity, depth or dimension checks; vanilla's geode
     * feature may still refuse a spot open to air. Worldgen or server thread.
     */
    public boolean placeGeode(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos) {
        GeodeConfiguration current = geode;
        if (current == null) {
            current = configuration.get();
            geode = current;
        }
        return Feature.GEODE.place(current, level, generator, random, pos);
    }
}
