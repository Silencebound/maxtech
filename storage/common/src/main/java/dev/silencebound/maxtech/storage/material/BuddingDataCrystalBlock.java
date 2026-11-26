package dev.silencebound.maxtech.storage.material;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Budding data crystal, the heart of a deep data geode: grows data crystal buds into clusters (see
 * {@link BuddingCrystalBlock}) on one random tick in {@code materials.dataCrystal.growthChance}, while the
 * {@code dataGeodes} module is on.
 */
public final class BuddingDataCrystalBlock extends BuddingCrystalBlock {
    public static final MapCodec<BuddingDataCrystalBlock> CODEC = simpleCodec(BuddingDataCrystalBlock::new);

    public BuddingDataCrystalBlock(Properties properties) {
        super(properties, DataCrystalContent::growthStages,
                () -> StorageServerConfig.DATA_CRYSTAL_GROWTH_CHANCE.getAsInt(),
                () -> StorageModules.DATA_GEODES.isEnabled());
    }

    @Override
    public MapCodec<BuddingDataCrystalBlock> codec() {
        return CODEC;
    }

    /** Grows the data crystal bud on {@code direction}'s face of the budding block at {@code pos}. Server thread. */
    public static void grow(ServerLevel level, BlockPos pos, Direction direction) {
        DataCrystalContent.BUDDING_DATA_CRYSTAL.get().growOn(level, pos, direction);
    }
}
