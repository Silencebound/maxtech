package dev.silencebound.maxtech.storage.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HistoryClaimsTest {
    /** A claimant whose "still here" answer the test sets. */
    private static final class Recorder implements HistoryClaims.Claimant {
        boolean here = true;

        @Override
        public boolean stillClaims(HistoryClaims claims) {
            return here;
        }
    }

    /** The first to claim keeps its series; a later claimant gets what is left; claiming again is not counted twice. */
    @Test
    void firstComeFirstServed() {
        HistoryClaims claims = new HistoryClaims();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        assertEquals(3, claims.claim(a, 3, 4));
        assertEquals(1, claims.claim(b, 2, 4));
        assertEquals(3, claims.claim(a, 3, 4));
        assertEquals(4, claims.total());
        claims.release(a);
        assertEquals(2, claims.claim(b, 2, 4));
        assertEquals(2, claims.total());
    }

    /** A claimant that left without releasing is dropped once a claim does not fit; one that is still here stays. */
    @Test
    void staleClaimsArePruned() {
        HistoryClaims claims = new HistoryClaims();
        Recorder gone = new Recorder();
        Recorder staying = new Recorder();
        Recorder late = new Recorder();
        claims.claim(gone, 2, 3);
        claims.claim(staying, 1, 3);
        assertEquals(0, claims.claim(late, 1, 3));
        gone.here = false;
        assertEquals(1, claims.claim(late, 1, 3));
        assertEquals(2, claims.total());
    }

    /** A smaller limit (a config change) cuts a claimant down at its next claim. */
    @Test
    void limitShrinks() {
        HistoryClaims claims = new HistoryClaims();
        Recorder a = new Recorder();
        assertEquals(8, claims.claim(a, 8, 32));
        assertEquals(2, claims.claim(a, 8, 2));
        assertEquals(0, claims.claim(a, 8, 0));
        assertEquals(0, claims.total());
    }
}
