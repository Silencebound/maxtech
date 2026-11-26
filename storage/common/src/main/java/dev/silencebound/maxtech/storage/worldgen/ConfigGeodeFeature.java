package dev.silencebound.maxtech.storage.worldgen;

import com.mojang.serialization.Codec;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * A geode of Storage's (PRD 7.2): vanilla's geode feature with its rarity and heights read from Storage's server config
 * at generation time, as {@link ConfigOreFeature} does for ores. The placed feature has no placement modifiers, so it
 * runs once per chunk: one chunk in {@code rarity} gets a geode, at a random spot of the chunk and a random height in
 * range. Biomes come from its biome modifier's biome tag; the dimension must be in {@code dimensions}, a dimension type
 * tag, so a pack can keep geodes out of a dimension that reuses Overworld biomes.
 */
public final class ConfigGeodeFeature extends Feature<NoneFeatureConfiguration> {
    private static final int CHUNK = 16;

    private final IntSupplier rarity;
    private final IntSupplier minY;
    private final IntSupplier maxY;
    private final TagKey<DimensionType> dimensions;
    private final Supplier<GeodeConfiguration> configuration;
    // Built on first use, after the geode's blocks exist.
    private @Nullable GeodeConfiguration geode;

    /**
     * A geode feature reading its rarity (one chunk in so many, 0 for none) and lowest and highest origin from config,
     * placing {@code configuration}'s geode only in dimensions of {@code dimensions}.
     */
    public ConfigGeodeFeature(Codec<NoneFeatureConfiguration> codec, IntSupplier rarity, IntSupplier minY,
            IntSupplier maxY, TagKey<DimensionType> dimensions, Supplier<GeodeConfiguration> configuration) {
        super(codec);
        this.rarity = rarity;
        this.minY = minY;
        this.maxY = maxY;
        this.dimensions = dimensions;
        this.configuration = configuration;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        int chance = rarity.getAsInt();
        int bottom = Math.max(minY.getAsInt(), level.getMinY());
        int top = Math.min(maxY.getAsInt(), level.getMaxY());
        if (chance <= 0 || top < bottom || !level.getLevel().dimensionTypeRegistration().is(dimensions)) {
            return false;
        }
        RandomSource random = context.random();
        if (random.nextInt(chance) != 0) {
            return false;
        }
        BlockPos origin = context.origin();
        return placeGeode(level, context.chunkGenerator(), random, new BlockPos(origin.getX() + random.nextInt(CHUNK),
                bottom + random.nextInt(top - bottom + 1), origin.getZ() + random.nextInt(CHUNK)));
    }

    /**
     * Places one geode with its origin at {@code pos}, without the rarity, height or dimension checks; vanilla's geode
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
