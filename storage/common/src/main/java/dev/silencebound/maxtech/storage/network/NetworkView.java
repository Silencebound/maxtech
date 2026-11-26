package dev.silencebound.maxtech.storage.network;

import org.jspecify.annotations.Nullable;

/**
 * A provider that shows another network's storage (a subnet controller's mount, PRD 7.2, Subnet controller). A
 * network's own storage ({@link NetworkState#nativeAmount} and the other native reads) leaves views out, so two
 * networks that mount each other never count or reach each other's contents twice, and an operation through a view
 * never comes back through the other side's view. Server thread only.
 */
public interface NetworkView extends StorageProvider {
    /**
     * The network this view shows now, or null when it shows none. Its patterns and crafting CPUs are shared with the
     * network holding the view (M7 C30); null by default.
     */
    default @Nullable NetworkState viewed() {
        return null;
    }

    /**
     * The Storage network this view's stores and takes reach into, for loop checks ({@link NetworkState#reaches}):
     * {@link #viewed()} by default; a storage bus facing another network's interface or chest reaches that network
     * without sharing its patterns. Null when it reaches none.
     */
    default @Nullable NetworkState reaches() {
        return viewed();
    }
}
