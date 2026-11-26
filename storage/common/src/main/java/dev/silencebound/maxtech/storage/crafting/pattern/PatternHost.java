package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.network.NetworkDevice;

/**
 * A network device that runs patterns: Storage's pattern provider (M7c area B), later routed crafting pipes. Its
 * network registers {@link #patternProvider()} in its {@link NetworkPatterns} when the device joins and drops it when
 * it leaves; a device whose patterns change calls {@code deviceLink().patternsChanged()}. Server thread only.
 */
public interface PatternHost extends NetworkDevice {
    /** The provider the network sends work to; the same object for the device's whole life. */
    PatternProvider patternProvider();
}
