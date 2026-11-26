package dev.silencebound.maxtech.storage.neoforge.compat.kubejs;

import dev.latvian.mods.kubejs.client.LangKubeEvent;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchema;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaRegistry;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.silencebound.maxtech.lib.neoforge.compat.kubejs.MachineRecipeKeys;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.ScriptNodes;
import dev.silencebound.maxtech.storage.logic.controller.LogicEvents;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Storage's KubeJS plugin. Recipes: every Storage machine recipe type gets a schema, so a script writes
 * {@code event.recipes.maxtech_storage.lithograph(outputs, inputs, energyPerTick, duration).mode('print')}; the
 * optional {@code mode} and {@code research} are set with their builder methods, and the recipe serializer validates
 * the result like a datapack recipe. Logic: the {@code MaxtechLogicEvents} group ({@link LogicKubeEvents}) for script
 * node types, notices and rule runs. Listed in Storage's {@code kubejs.plugins.txt}; KubeJS instantiates it, nothing
 * else does.
 */
public final class StorageKubeJSPlugin implements KubeJSPlugin {
    /** Hands logic notices and rule runs to {@code MaxtechLogicEvents}; runs on both sides as KubeJS loads. */
    @Override
    public void init() {
        LogicEvents.setListener(new LogicKubeEvents.Listener());
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(LogicKubeEvents.GROUP);
    }

    /** Once the startup scripts ran, their logic node types are registered ({@code registerNodeTypes}). */
    @Override
    public void afterScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType == ScriptType.STARTUP) {
            LogicKubeEvents.registerNodeTypes();
        }
    }

    /** The English texts of script node types, so their names and sentences show without a lang file. */
    @Override
    public void generateLang(LangKubeEvent event) {
        for (Map.Entry<String, String> text : ScriptNodes.english().entrySet()) {
            event.add(MaxtechStorage.MOD_ID, text.getKey(), text.getValue());
        }
    }

    @Override
    public void registerRecipeSchemas(RecipeSchemaRegistry registry) {
        for (Identifier id : StorageRecipeSchemas.MACHINE_RECIPES) {
            registry.register(id, machineSchema());
        }
    }

    /** The {@code StorageMachineRecipe} fields; a schema per type, since KubeJS keys are mutable. */
    private static RecipeSchema machineSchema() {
        return new RecipeSchema(MachineRecipeKeys.itemOutputs("outputs"), MachineRecipeKeys.itemInputs("inputs"),
                MachineRecipeKeys.energyPerTick("energy_per_tick"), MachineRecipeKeys.duration("duration"),
                MachineRecipeKeys.optionalString("mode"), MachineRecipeKeys.optionalId("research"));
    }
}
