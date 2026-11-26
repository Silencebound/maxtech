package dev.silencebound.maxtech.lib.platform;

import java.util.ServiceLoader;

/**
 * Loader services for {@code common} code. The implementation is found through {@link ServiceLoader} the first time
 * {@link #get()} is called and is shared by every suite mod.
 */
public interface Platform {
    static Platform get() {
        return Holder.INSTANCE;
    }

    RegistryHelper registry();

    CapabilityHelper capabilities();

    NetworkHelper network();

    ConfigHelper config();

    LifecycleEvents lifecycle();

    /** Item and fluid transfer; the one place common code reaches NeoForge's transfer capabilities (ADR-0002). */
    TransferHelper transfer();

    /** Menus that carry server data to the client when they open, and screen registration (M2 task 5). */
    MenuHelper menus();

    /** Lazy holder idiom: the lookup runs once, on first access, with class-initialisation thread safety. */
    final class Holder {
        static final Platform INSTANCE = load();

        private Holder() {
        }

        private static Platform load() {
            var providers = ServiceLoader.load(Platform.class, Platform.class.getClassLoader()).stream().toList();
            if (providers.size() != 1) {
                throw new IllegalStateException("Expected exactly one " + Platform.class.getName()
                        + " implementation, found " + providers.size());
            }
            return providers.getFirst().get();
        }
    }
}
