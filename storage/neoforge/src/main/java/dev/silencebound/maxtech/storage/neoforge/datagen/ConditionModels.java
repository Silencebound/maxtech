package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlock;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.world.level.block.Block;

/**
 * The condition switch block's two models: its front on every face, dark while off and lit while
 * {@link ConditionSwitchBlock#POWERED} (it gives its signal on every side). The item shows the off model.
 */
final class ConditionModels {
    private ConditionModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        Block block = ConditionContent.SWITCH.get();
        MultiVariant off = BlockModelGenerators
                .plainVariant(ModelTemplates.CUBE_ALL.create(ModelLocationUtils.getModelLocation(block),
                        TextureMapping.cube(new Material(MaxtechStorage.id("block/condition_switch/front_off"))),
                        blockModels.modelOutput));
        MultiVariant on = BlockModelGenerators
                .plainVariant(ModelTemplates.CUBE_ALL.create(ModelLocationUtils.getModelLocation(block, "_on"),
                        TextureMapping.cube(new Material(MaxtechStorage.id("block/condition_switch/front_on"))),
                        blockModels.modelOutput));
        blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block)
                .with(BlockModelGenerators.createBooleanModelDispatch(ConditionSwitchBlock.POWERED, on, off)));
    }
}
