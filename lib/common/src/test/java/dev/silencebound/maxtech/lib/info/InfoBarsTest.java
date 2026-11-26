package dev.silencebound.maxtech.lib.info;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink.Tone;
import org.junit.jupiter.api.Test;

class InfoBarsTest {
    @Test
    void fillingBarsFollowTheDriveCellBands() {
        assertEquals(Tone.GOOD.rgb(), InfoBars.fill(0, 0));
        assertEquals(Tone.GOOD.rgb(), InfoBars.fill(74, 100));
        assertEquals(Tone.WARN.rgb(), InfoBars.fill(75, 100));
        assertEquals(Tone.WARN.rgb(), InfoBars.fill(99, 100));
        assertEquals(Tone.BAD.rgb(), InfoBars.fill(100, 100));
    }

    @Test
    void energyBarsWarnWhenLow() {
        assertEquals(Tone.BAD.rgb(), InfoBars.energy(0, 100));
        assertEquals(Tone.WARN.rgb(), InfoBars.energy(25, 100));
        assertEquals(Tone.GOOD.rgb(), InfoBars.energy(26, 100));
        assertEquals(Tone.BAD.rgb(), InfoBars.energy(5, 0));
    }
}
