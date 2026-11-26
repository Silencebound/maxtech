package dev.silencebound.maxtech.storage.material;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;

/**
 * Budding void crystal, the heart of a void geode on the outer End islands: grows void crystal buds into clusters (see
 * {@link BuddingCrystalBlock}) on one random tick in {@code materials.voidCrystal.growthChance}, while the
 * {@code voidGeodes} module is on.
 */
public final class BuddingVoidCrystalBlock extends BuddingCrystalBlock {
    public static final MapCodec<BuddingVoidCrystalBlock> CODEC = simpleCodec(BuddingVoidCrystalBlock::new);

    public BuddingVoidCrystalBlock(Properties properties) {
        super(properties, VoidCrystalContent::growthStages,
                () -> StorageServerConfig.VOID_CRYSTAL_GROWTH_CHANCE.getAsInt(),
                () -> StorageModules.VOID_GEODES.isEnabled());
    }

    @Override
    public MapCodec<BuddingVoidCrystalBlock> codec() {
        return CODEC;
    }
}
