package dev.silencebound.maxtech.farms;

import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import net.minecraft.resources.Identifier;

public final class MaxtechFarms {
    public static final String MOD_ID = "maxtech_farms";

    private MaxtechFarms() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Called once from the loader entrypoint during mod construction. */
    public static void init() {
        FarmsServerConfig.register();
        FarmsContent.init();
        LabTubes.init();
    }
}
