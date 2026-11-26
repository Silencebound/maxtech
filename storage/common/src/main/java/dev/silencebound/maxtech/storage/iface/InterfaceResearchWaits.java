package dev.silencebound.maxtech.storage.iface;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.research.Research;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * Interfaces whose owner's team lacks the interface unlock, so completing research wakes exactly those instead of
 * interfaces polling for it. One wakes them all: each asks again and a still-locked one comes back. A removed interface
 * leaves on the next wake. Server thread.
 */
final class InterfaceResearchWaits {
    private static final ReferenceOpenHashSet<InterfaceWorker> WAITING = new ReferenceOpenHashSet<>();

    private InterfaceResearchWaits() {
    }

    /** Registers the research listener and the cleanup; once, during mod construction. */
    static void init() {
        Research.onCompleted((server, team, node) -> wakeAll());
        Platform.get().lifecycle().onServerStopped(server -> WAITING.clear());
    }

    static void waiting(InterfaceWorker worker) {
        WAITING.add(worker);
    }

    private static void wakeAll() {
        if (WAITING.isEmpty()) {
            return;
        }
        // Copied: a woken interface may register again before this loop ends.
        InterfaceWorker[] workers = WAITING.toArray(new InterfaceWorker[0]);
        WAITING.clear();
        for (InterfaceWorker worker : workers) {
            if (!worker.isRemoved()) {
                worker.researchChanged();
            }
        }
    }
}
