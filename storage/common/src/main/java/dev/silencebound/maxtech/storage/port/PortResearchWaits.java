package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.research.Research;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * The ports whose owner's team lacks their tier's research: they sleep until a node completes for that team, instead of
 * polling for it (as machines do through {@code machine.ResearchWaits}). A port leaves the set when it finds its
 * research, and removed ports are dropped on the next wake pass and when the server stops. Server thread.
 */
public final class PortResearchWaits {
    private static final ReferenceOpenHashSet<PortWorker> WAITING = new ReferenceOpenHashSet<>();

    private PortResearchWaits() {
    }

    /** Hooks the wake-ups into research; once, during mod construction. */
    static void init() {
        Research.onCompleted((server, team, node) -> {
            if (WAITING.isEmpty()) {
                return;
            }
            // Copied: a woken port may leave the set before this loop ends.
            for (PortWorker worker : WAITING.toArray(new PortWorker[0])) {
                if (worker.isGone()) {
                    WAITING.remove(worker);
                } else if (Research.inTeam(server, worker.owner().ownerRecord(), team)) {
                    worker.researchMayHaveChanged();
                }
            }
        });
        Platform.get().lifecycle().onServerStopped(server -> WAITING.clear());
    }

    static void waiting(PortWorker worker, boolean waiting) {
        if (waiting) {
            WAITING.add(worker);
        } else {
            WAITING.remove(worker);
        }
    }

    /** How many ports wait for research; for tests. */
    public static int count() {
        return WAITING.size();
    }
}
