package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.lib.neoforge.datagen.ContentModelProvider;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.data.PackOutput;

/**
 * Storage's block states and models: the declared ones, plus those the declarations cannot express (the controller's
 * per-status models, the machines' active look, the directional devices' facing, see FacingModels).
 */
final class StorageModelProvider extends ContentModelProvider {
    StorageModelProvider(PackOutput output) {
        super(output, ModContent.CONTENT);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        super.registerModels(blockModels, itemModels);
        ControllerModels.generate(blockModels);
        ExpansionModels.generate(blockModels);
        MachineModels.register(blockModels);
        PortModels.generate(blockModels);
        TerminalModels.generate(blockModels);
        FacingModels.generate(blockModels);
        CrafterModels.generate(blockModels);
        PartModels.generate(itemModels);
        CableItemModels.generate(itemModels);
        ConditionModels.generate(blockModels);
        EnergyCellModels.generate(blockModels);
        DisplayModels.generate(blockModels);
        SmartLabelModels.generate(blockModels);
        GeodeModels.generate(blockModels);
    }
}
