package dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.neoforged.fml.ModList;

/**
 * Entry point of the Refined Storage compat. No RS classes here, so it is safe to call without Refined Storage: it
 * always declares the lang key (datagen runs without RS) and installs the bridge finder only when RS is loaded.
 */
public final class RefinedStorageCompat {
    /** Refined Storage's mod id (its {@code neoforge.mods.toml}, 3.2.1). */
    public static final String MOD_ID = "refinedstorage";
    /** The foreign network's name in the bridge's info lines. */
    public static final String NAME = "info." + MaxtechStorage.MOD_ID + ".bridge.refinedstorage";

    private RefinedStorageCompat() {
    }

    /** Call once during mod construction. */
    public static void init() {
        ModContent.CONTENT.translation(NAME, "Refined Storage network");
        if (ModList.get().isLoaded(MOD_ID)) {
            RsFinder.install();
            // Built only by the dev-only showcase command.
            RsShowcase.register();
        }
    }

    /**
     * For tests, and only with Refined Storage loaded: how many bridge exports are mounted in {@code root} (an RS
     * network's {@code StorageNetworkComponent}).
     */
    public static int mountedExports(Object root) {
        return RsExport.mountedIn(root)[0];
    }

    /**
     * For tests, and only with Refined Storage loaded: how many RS resources the exports mounted in {@code root} keep
     * for their keys, summed (at most the kinds they list in RS).
     */
    public static int exportResources(Object root) {
        return RsExport.mountedIn(root)[1];
    }
}
