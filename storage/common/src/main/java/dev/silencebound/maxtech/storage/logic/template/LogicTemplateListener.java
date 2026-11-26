package dev.silencebound.maxtech.storage.logic.template;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Loads the rule templates from {@code data/<namespace>/maxtech_storage/logic_templates/**.json} on server start and on
 * {@code /reload}. Read with the server's registries (a graph's items may carry components), through the same scan as
 * vanilla's JSON listeners: a later pack's file replaces the same id, a file whose {@code neoforge:conditions} fail is
 * skipped, and a file that does not parse (an unknown node type or item, a bad field) is logged with its id and left
 * out, so one broken file never stops the others. The loader adds it as a server reload listener.
 */
public final class LogicTemplateListener
        extends
            SimplePreparableReloadListener<Map<Identifier, LogicTemplate<LogicKey>>> {
    /** The folder inside a namespace's data that holds template files. */
    public static final String DIRECTORY = MaxtechStorage.MOD_ID + "/logic_templates";
    /** The listener's key for the loader. */
    public static final Identifier ID = MaxtechStorage.id("logic_templates");
    /** The codec of a template file. */
    public static final com.mojang.serialization.Codec<LogicTemplate<LogicKey>> CODEC = LogicTemplate
            .codec(LogicKey.GRAPH_CODEC);
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    protected Map<Identifier, LogicTemplate<LogicKey>> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, LogicTemplate<LogicKey>> loaded = new HashMap<>();
        // makeConditionalOps() carries the server's registries and the condition context.
        SimpleJsonResourceReloadListener.scanDirectory(manager, FileToIdConverter.json(DIRECTORY), makeConditionalOps(),
                CODEC, loaded);
        return loaded;
    }

    @Override
    protected void apply(Map<Identifier, LogicTemplate<LogicKey>> loaded, ResourceManager manager,
            ProfilerFiller profiler) {
        LogicTemplates.apply(loaded);
        LOGGER.info("Loaded {} logic rule templates", loaded.size());
    }
}
