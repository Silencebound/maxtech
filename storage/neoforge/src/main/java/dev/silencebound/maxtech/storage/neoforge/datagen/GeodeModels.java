package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.world.level.block.Block;

/**
 * The data and void crystal buds and clusters: vanilla's amethyst cluster look (a cross model turned to the block's
 * facing) and a flat item drawn from the block texture, which the content declarations cannot express.
 */
final class GeodeModels {
    private GeodeModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        for (Block stage : DataCrystalContent.growthStages()) {
            blockModels.createAmethystCluster(stage);
            blockModels.registerSimpleFlatItemModel(stage);
        }
        for (Block stage : VoidCrystalContent.growthStages()) {
            blockModels.createAmethystCluster(stage);
            blockModels.registerSimpleFlatItemModel(stage);
        }
    }
}
