package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplate;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplateListener;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.common.data.JsonCodecProvider;

/**
 * The rule templates in Storage's jar ({@code data/maxtech_storage/maxtech_storage/logic_templates/}), from
 * {@link BuiltInTemplates}.
 */
final class LogicTemplateDatagen extends JsonCodecProvider<LogicTemplate<LogicKey>> {
    LogicTemplateDatagen(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, PackOutput.Target.DATA_PACK, LogicTemplateListener.DIRECTORY, LogicTemplateListener.CODEC,
                registries, MaxtechStorage.MOD_ID);
    }

    @Override
    protected void gather() {
        // "#namespace:path" names an item tag (the For each template's ores), anything else an item.
        Map<String, LogicTemplate<LogicKey>> all = BuiltInTemplates.all(id -> id.startsWith("#")
                ? LogicKey.tag(TagKey.create(Registries.ITEM, Identifier.parse(id.substring(1))))
                : LogicKey.of(ItemKey.of(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)))));
        all.forEach((path, template) -> unconditional(MaxtechStorage.id(path), template));
    }

    @Override
    public String getName() {
        return "Logic rule templates";
    }
}
