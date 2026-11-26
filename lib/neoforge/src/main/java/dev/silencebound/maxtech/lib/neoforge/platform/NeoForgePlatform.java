package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.CapabilityHelper;
import dev.silencebound.maxtech.lib.platform.ConfigHelper;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.MenuHelper;
import dev.silencebound.maxtech.lib.platform.NetworkHelper;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.platform.TransferHelper;

/** Registered in {@code META-INF/services}; instantiated by {@link Platform#get()}. */
public final class NeoForgePlatform implements Platform {
    private final RegistryHelper registry = new NeoForgeRegistryHelper();
    private final CapabilityHelper capabilities = new NeoForgeCapabilityHelper();
    private final NetworkHelper network = new NeoForgeNetworkHelper();
    private final ConfigHelper config = new NeoForgeConfigHelper();
    private final LifecycleEvents lifecycle = new NeoForgeLifecycleEvents();
    private final TransferHelper transfer = new NeoForgeTransferHelper();
    private final MenuHelper menus = new NeoForgeMenuHelper();

    @Override
    public RegistryHelper registry() {
        return registry;
    }

    @Override
    public CapabilityHelper capabilities() {
        return capabilities;
    }

    @Override
    public NetworkHelper network() {
        return network;
    }

    @Override
    public ConfigHelper config() {
        return config;
    }

    @Override
    public LifecycleEvents lifecycle() {
        return lifecycle;
    }

    @Override
    public TransferHelper transfer() {
        return transfer;
    }

    @Override
    public MenuHelper menus() {
        return menus;
    }
}
