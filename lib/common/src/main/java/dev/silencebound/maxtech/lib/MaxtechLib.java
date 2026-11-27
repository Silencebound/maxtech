package dev.silencebound.maxtech.lib;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceModes;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import dev.silencebound.maxtech.lib.client.conduit.LibConduitParts;
import dev.silencebound.maxtech.lib.concurrent.WorkerPool;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.config.LibConfigFactory;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.conduit.ConduitAttachments;
import dev.silencebound.maxtech.lib.content.LibConduits;
import dev.silencebound.maxtech.lib.energy.LevelEnergy;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.lib.network.NetworkBootstrap;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.registry.ModContent;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/** MaxTech Lib: the shared code every MaxTech mod builds on, with no player-facing content of its own (ADR-0014). */
public final class MaxtechLib {
    public static final String MOD_ID = "maxtech_lib";

    private MaxtechLib() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Called once from the loader entrypoint during mod construction, before any other MaxTech mod's. */
    public static void init() {
        // Resolve the platform now so a missing or duplicate implementation fails at startup, not on first use.
        Platform.get();
        LibConfigFactory.install();
        Registrars.setFactory(new Registrars.Factory() {
            @Override
            public <R> Registrar<R> create(String modId, ResourceKey<? extends Registry<R>> registry) {
                return Platform.get().registry().registrar(modId, registry);
            }
        });
        Conduits.setFactory(new LibConduits());
        ConduitParts.setFactory(new LibConduitParts());
        ServerConfig.register();
        OwnerSecurity.setOpsBypassSource(() -> ServerConfig.loadedOr(ServerConfig.OPS_BYPASS_OWNERSHIP, true));
        EnergyNetworks.setFeRateSource(ServerConfig::feRate);
        ClientConfig.register();
        ModContent.init();
        Highlights.register();
        CraftingGridMenus.register();
        NetworkBootstrap.bootstrap();
        ConduitAttachments.bootstrap();
        LevelTasks.bootstrap();
        LevelEnergy.bootstrap();
        WorkerPool.bootstrap();
        GuideConfigValues.register(MOD_ID, ServerConfig.guideSource());
        // Other mods register conduit types too, so check them all once registration is over.
        Platform.get().lifecycle().onCommonSetup(MOD_ID, ConduitFaceModes::validateRegistered);
    }
}
