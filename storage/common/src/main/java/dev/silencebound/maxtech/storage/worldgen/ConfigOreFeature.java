package dev.silencebound.maxtech.storage.worldgen;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.dimension.DimensionType;
import com.mojang.serialization.Codec;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * One of Storage's ores (PRD 7.2): plain vanilla ore blobs, a normal NeoForge ore feature rather than MaxTech's mixed
 * veins (CLAUDE.md, Storage exception). The placed feature has no placement modifiers, so it runs once per chunk; vein
 * count, size and height come from Storage's server config at generation time, so a server owner tunes them without a
 * datapack. Which host rock takes which ore block is the feature's targets.
 */
public final class ConfigOreFeature extends Feature<NoneFeatureConfiguration> {
    private static final int CHUNK = 16;

    private final TagKey<DimensionType> dimensions;
    private final IntSupplier veins;
    private final IntSupplier size;
    private final IntSupplier minY;
    private final IntSupplier maxY;
    private final IntFunction<OreConfiguration> configuration;
    // Built on first use, after the ore blocks exist, and again only when the configured size changes.
    private @Nullable OreConfiguration ore;

    /**
     * An ore feature that runs only in dimension types tagged {@code dimensions}, reading its vein count, vein size and
     * lowest and highest vein centre from config; {@code configuration} builds the vanilla ore configuration (targets)
     * for a vein size.
     */
    public ConfigOreFeature(Codec<NoneFeatureConfiguration> codec, TagKey<DimensionType> dimensions, IntSupplier veins,
            IntSupplier size, IntSupplier minY, IntSupplier maxY, IntFunction<OreConfiguration> configuration) {
        super(codec);
        this.dimensions = dimensions;
        this.veins = veins;
        this.size = size;
        this.minY = minY;
        this.maxY = maxY;
        this.configuration = configuration;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        return level.getLevel().dimensionTypeRegistration().is(dimensions)
                && placeVeins(level, context.chunkGenerator(), context.random(), context.origin());
    }

    /**
     * The feature's veins in the chunk at {@code origin}, without the dimension check; for GameTests, whose level is
     * always the Overworld.
     */
    public boolean placeVeins(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        int bottom = Math.max(minY.getAsInt(), level.getMinY());
        int top = Math.min(maxY.getAsInt(), level.getMaxY());
        int count = veins.getAsInt();
        if (count <= 0 || top < bottom) {
            return false;
        }
        OreConfiguration current = configuration(size.getAsInt());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean placed = false;
        for (int i = 0; i < count; i++) {
            pos.set(origin.getX() + random.nextInt(CHUNK), bottom + random.nextInt(top - bottom + 1),
                    origin.getZ() + random.nextInt(CHUNK));
            placed |= Feature.ORE.place(current, level, generator, random, pos);
        }
        return placed;
    }

    private OreConfiguration configuration(int veinSize) {
        OreConfiguration current = ore;
        if (current == null || current.size != veinSize) {
            current = configuration.apply(veinSize);
            ore = current;
        }
        return current;
    }

}
