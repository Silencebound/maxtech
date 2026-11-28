package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

/**
 * Whether the game's registries can be used in this test run. NeoForge 21.1's bootstrap needs FML (moddev's unit test
 * support); in plain JUnit it fails, and tests that need items or fluids skip themselves.
 */
final class GameBootstrap {
    private static final boolean AVAILABLE = tryBootstrap();

    private GameBootstrap() {
    }

    static boolean available() {
        return AVAILABLE;
    }

    private static boolean tryBootstrap() {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            // A failed bootstrap can leave the flag set; touching the registries tells for sure.
            return Items.STONE != null && Fluids.WATER != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
