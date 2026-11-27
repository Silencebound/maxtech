package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Lib's client-only entrypoint. FML constructs it after the common one, during mod construction; client-only
 * registration that must happen then (screens, before client setup) goes here.
 */
@Mod(value = MaxtechLib.MOD_ID, dist = Dist.CLIENT)
public final class MaxtechLibClient {
    /** Top sections of Lib's config files, most often changed first. */
    private static final List<String> CONFIG_ORDER = List.of("overlay", "text", "accessibility", "highlights",
            "facades", "energy", "networks", "security", "scheduler", "sync", "workers");

    public MaxtechLibClient(ModContainer container) {
        ConfigScreens.register(container, CONFIG_ORDER);
        TextScale.setOptionSource(ClientConfig::textScale);
    }
}
