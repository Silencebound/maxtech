package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlock;
import dev.silencebound.maxtech.storage.energy.EnergyCellItem;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import java.util.List;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;

/**
 * The energy cells' models: a column with a gauge on its four sides per {@link EnergyCellBlock#CHARGE} (shared by the
 * tiers) and the tier's mark on top and bottom. The block state picks the model by charge, so the gauge needs no block
 * entity renderer. The item shows the empty model, which sits at the block's plain model location.
 */
final class EnergyCellModels {
    private EnergyCellModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        List<BlockEntry<EnergyCellBlock, EnergyCellItem>> cells = List.of(EnergyContent.TIER_1, EnergyContent.TIER_2,
                EnergyContent.TIER_3);
        for (BlockEntry<EnergyCellBlock, EnergyCellItem> entry : cells) {
            EnergyCellBlock block = entry.get();
            Material end = new Material(MaxtechStorage.id("block/energy_cell/end_tier_" + block.tier().level()));
            MultiVariant[] variants = new MultiVariant[EnergyCellBlock.FULL + 1];
            for (int charge = 0; charge <= EnergyCellBlock.FULL; charge++) {
                Identifier id = charge == 0
                        ? ModelLocationUtils.getModelLocation(block)
                        : ModelLocationUtils.getModelLocation(block, "_charge_" + charge);
                Material side = new Material(MaxtechStorage.id("block/energy_cell/side_" + charge));
                variants[charge] = BlockModelGenerators.plainVariant(ModelTemplates.CUBE_COLUMN.create(id,
                        TextureMapping.column(side, end), blockModels.modelOutput));
            }
            blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block)
                    .with(PropertyDispatch.initial(EnergyCellBlock.CHARGE).generate(charge -> variants[charge])));
        }
    }
}
