package dev.silencebound.maxtech.farms.neoforge.datagen;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import java.util.stream.Stream;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Farms' block states and models: placeholder flat items and a plain cube hub until the FM8 textures pass. Only the
 * mod's own content; GameTest entries never ship.
 */
final class FarmsModelProvider extends ModelProvider {
    FarmsModelProvider(PackOutput output) {
        super(output, MaxtechFarms.MOD_ID);
    }

    @Override
    protected Stream<? extends Holder<Block>> getKnownBlocks() {
        return Stream.of(BuiltInRegistries.BLOCK.wrapAsHolder(FarmsContent.LAB_HUB.get()));
    }

    @Override
    protected Stream<? extends Holder<Item>> getKnownItems() {
        return Stream.of(FarmsContent.SAMPLER.get(), FarmsContent.SPECIMEN.get(), FarmsContent.LAB_HUB_ITEM.get())
                .map(BuiltInRegistries.ITEM::wrapAsHolder);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        blockModels.createTrivialCube(FarmsContent.LAB_HUB.get());
        itemModels.generateFlatItem(FarmsContent.SAMPLER.get(), ModelTemplates.FLAT_ITEM);
        itemModels.generateFlatItem(FarmsContent.SPECIMEN.get(), ModelTemplates.FLAT_ITEM);
    }
}
