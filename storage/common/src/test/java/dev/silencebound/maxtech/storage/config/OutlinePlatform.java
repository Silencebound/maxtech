package dev.silencebound.maxtech.storage.config;

import dev.silencebound.maxtech.lib.config.ConfigOutline;
import dev.silencebound.maxtech.lib.platform.CapabilityHelper;
import dev.silencebound.maxtech.lib.platform.ConfigHelper;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.MenuHelper;
import dev.silencebound.maxtech.lib.platform.NetworkHelper;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.platform.TransferHelper;

/**
 * The {@link Platform} of Storage's unit tests (META-INF/services in the test resources): config classes record into
 * {@link #OUTLINE}; every other service fails, since unit tests run without the game.
 */
public final class OutlinePlatform implements Platform {
    /** What the config classes loaded in this test JVM declared. */
    public static final ConfigOutline OUTLINE = new ConfigOutline();

    @Override
    public ConfigHelper config() {
        return OUTLINE;
    }

    @Override
    public RegistryHelper registry() {
        throw unavailable();
    }

    @Override
    public CapabilityHelper capabilities() {
        throw unavailable();
    }

    @Override
    public NetworkHelper network() {
        throw unavailable();
    }

    @Override
    public LifecycleEvents lifecycle() {
        throw unavailable();
    }

    @Override
    public TransferHelper transfer() {
        throw unavailable();
    }

    @Override
    public MenuHelper menus() {
        throw unavailable();
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Unit tests have only the config outline platform");
    }
}
