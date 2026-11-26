package dev.silencebound.maxtech.storage.history;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

/**
 * The recorded series of one network, counted against {@code history.maxSeriesPerNetwork} so a network's history memory
 * stays bounded however many displays and controllers record on it. Recorders (a chart display, a logic controller's
 * pinned values) claim their series before each sample; the first to claim keeps its series, a later one gets what is
 * left. A recorder that is gone or moved to another network may not release its claim (a chunk unload, a split), so the
 * claims are checked and stale ones dropped whenever a claim does not fit. Server thread only.
 */
public final class HistoryClaims {
    /** Something that records series on a network. */
    public interface Claimant {
        /** Whether this claimant still records on the network {@code claims} belongs to. */
        boolean stillClaims(HistoryClaims claims);
    }

    private final Reference2IntOpenHashMap<Claimant> claims = new Reference2IntOpenHashMap<>();
    private int total;

    /**
     * Claims {@code wanted} series for {@code claimant} (replacing its earlier claim) under the limit {@code max};
     * returns how many it got, from 0 to {@code wanted}. Allocates nothing unless the claimant is new.
     */
    public int claim(Claimant claimant, int wanted, int max) {
        total -= claims.getInt(claimant);
        if (total + wanted > max) {
            prune(claimant);
        }
        int granted = Math.clamp(max - total, 0, Math.max(0, wanted));
        if (granted > 0) {
            claims.put(claimant, granted);
        } else {
            claims.removeInt(claimant);
        }
        total += granted;
        return granted;
    }

    /** Gives back {@code claimant}'s series. */
    public void release(Claimant claimant) {
        total -= claims.removeInt(claimant);
    }

    /** Series claimed now. */
    public int total() {
        return total;
    }

    /** Drops the claims of claimants other than {@code asking} that no longer record here. */
    // Claimants are keyed by identity, as the map is.
    @SuppressWarnings("ReferenceEquality")
    private void prune(Claimant asking) {
        ObjectIterator<Reference2IntMap.Entry<Claimant>> it = claims.reference2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            Reference2IntMap.Entry<Claimant> entry = it.next();
            if (entry.getKey() != asking && !entry.getKey().stillClaims(this)) {
                total -= entry.getIntValue();
                it.remove();
            }
        }
    }
}
