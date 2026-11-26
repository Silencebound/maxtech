package dev.silencebound.maxtech.lib.info;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink.Tone;

/**
 * Fill colours of overlay bars, in the bands Storage's drive cells show: the accent below 75 %, amber from 75 %, red
 * once full. Energy reads the other way round, since an empty store is the problem. Pure arithmetic.
 */
public final class InfoBars {
    private InfoBars() {
    }

    /** The colour of a bar for something that fills up (bytes, channels): accent, amber from 75 %, red when full. */
    public static int fill(long value, long max) {
        if (max > 0 && value >= max) {
            return Tone.BAD.rgb();
        }
        if (max > 0 && value >= max - max / 4) {
            return Tone.WARN.rgb();
        }
        return Tone.GOOD.rgb();
    }

    /** The colour of an energy bar: accent, amber at 25 % or less, red when empty. */
    public static int energy(long stored, long capacity) {
        if (capacity <= 0 || stored <= 0) {
            return Tone.BAD.rgb();
        }
        if (stored <= capacity / 4) {
            return Tone.WARN.rgb();
        }
        return Tone.GOOD.rgb();
    }
}
