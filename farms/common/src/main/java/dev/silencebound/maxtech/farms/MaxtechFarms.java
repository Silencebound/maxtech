package dev.silencebound.maxtech.farms;

import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.hub.HubMenu;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.registry.FarmsCreativeTab;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import net.minecraft.core.registries.Registries;
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
        FarmsCreativeTab.init();
        HubMenu.MENU.register(Registrars.create(MOD_ID, Registries.MENU));
    }
}
