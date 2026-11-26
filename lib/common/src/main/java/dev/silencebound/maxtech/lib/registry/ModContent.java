package dev.silencebound.maxtech.lib.registry;

import dev.silencebound.maxtech.lib.facade.FacadeContent;

/**
 * Lib's framework registrations: the conduit block-entity type and data components, and no player-facing items or
 * worldgen except the facade ({@code FacadeContent}, the one exception ADR-0014 names). Lib's custom registries are
 * created in {@code LibRegistrySetup}.
 */
public final class ModContent {
    private ModContent() {
    }

    /** Loads every registration class during mod construction so their entries are queued before registration runs. */
    public static void init() {
        ModBlockEntities.init();
        ModDataComponents.init();
        FacadeContent.init();
    }
}
