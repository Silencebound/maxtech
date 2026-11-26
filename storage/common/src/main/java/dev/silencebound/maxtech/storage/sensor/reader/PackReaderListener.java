package dev.silencebound.maxtech.storage.sensor.reader;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Loads the pack readers from {@code data/<namespace>/maxtech_storage/readers/**.json} on server start and on
 * {@code /reload}. A file in a later pack replaces the same file id in an earlier one, as for any data file; a file
 * whose {@code neoforge:conditions} fail is skipped; a file that does not parse is logged with its id and the problem
 * and left out, so one broken file never stops the others. The loader adds it as a server reload listener.
 */
public final class PackReaderListener extends SimpleJsonResourceReloadListener<PackReader> {
    /** The folder inside a namespace's data that holds reader files. */
    public static final String DIRECTORY = MaxtechStorage.MOD_ID + "/readers";
    /** The listener's key for the loader. */
    public static final Identifier ID = MaxtechStorage.id("pack_readers");
    private static final Logger LOGGER = LogUtils.getLogger();

    public PackReaderListener() {
        super(PackReader.CODEC, FileToIdConverter.json(DIRECTORY));
    }

    @Override
    protected void apply(Map<Identifier, PackReader> loaded, ResourceManager manager, ProfilerFiller profiler) {
        PackReaders.apply(loaded);
        LOGGER.info("Loaded {} sensor pack readers", loaded.size());
    }
}
