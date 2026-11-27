package dev.silencebound.maxtech.farms.neoforge.datagen;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import java.util.stream.Stream;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Farms' block states and models: placeholder flat items and plain cubes until the FM8 textures pass. Only the mod's
 * own content; GameTest entries never ship.
 */
final class FarmsModelProvider extends ModelProvider {
    FarmsModelProvider(PackOutput output) {
        super(output, MaxtechFarms.MOD_ID);
    }

    @Override
    protected Stream<? extends Holder<Block>> getKnownBlocks() {
        // The lab tube's block state comes from Lib, which draws every conduit.
        return Stream.of(FarmsContent.LAB_HUB.get(), FarmsContent.FLUID_PORT.get())
                .map(BuiltInRegistries.BLOCK::wrapAsHolder);
    }

    @Override
    protected Stream<? extends Holder<Item>> getKnownItems() {
        return Stream
                .of(FarmsContent.SAMPLER.get(), FarmsContent.SPECIMEN.get(), FarmsContent.LAB_HUB_ITEM.get(),
                        FarmsContent.FLUID_PORT_ITEM.get(), LabTubes.TUBE.item().get())
                .map(BuiltInRegistries.ITEM::wrapAsHolder);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        blockModels.createTrivialCube(FarmsContent.LAB_HUB.get());
        blockModels.createTrivialCube(FarmsContent.FLUID_PORT.get());
        // A flat item showing the tube's glass; Lib's item model definition names this model.
        itemModels.itemModelOutput.accept(LabTubes.TUBE.item().get(),
                ItemModelUtils.plainModel(ModelTemplates.FLAT_ITEM.create(LabTubes.TUBE.item().get(),
                        new TextureMapping().put(TextureSlot.LAYER0, new Material(MaxtechFarms.id("block/lab_tube"))),
                        itemModels.modelOutput)));
        itemModels.generateFlatItem(FarmsContent.SAMPLER.get(), ModelTemplates.FLAT_ITEM);
        itemModels.generateFlatItem(FarmsContent.SPECIMEN.get(), ModelTemplates.FLAT_ITEM);
    }
}
