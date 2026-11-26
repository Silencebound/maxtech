package dev.silencebound.maxtech.storage.cable;

/**
 * How a storage cable shows its tier now that every tier is {@link CableContent#THICKNESS} wide and carries one lane
 * (look "J", owner decision 2026-10-15): the lane's brightness. Its colour is the cable's own (the tier's colour in
 * {@link CableContent}, or the dye's by Lib's rule) scaled so its brightest channel reaches the tier's peak, dim at
 * tier 1 and brightest at tier 4, which is also pulled a little towards white (for cyan: #1d5f70, #2687a0, #33b2d0,
 * #6fe6f8 in the owner's mockup). The lane is also drawn lit, more per tier, unless the client turns that off. A smart
 * cable uses the same scale for its load ({@link SmartLoad#brightness}). Pure arithmetic, safe on any thread.
 */
public final class CableTierMarks {
    /** The brightest channel of each brightness level's line, level 1 first. */
    private static final int[] LINE_PEAK = {0x70, 0xA0, 0xD0, 0xF8};
    /** The light level the line is drawn lit at least per brightness level while the glow is on. */
    private static final int[] EMISSION = {0, 4, 8, 15};
    /** How far the top level is pulled towards white. */
    private static final float TOP_WHITEN = 0.3F;
    private static final int LEVELS = LINE_PEAK.length;

    private CableTierMarks() {
    }

    /**
     * The lane colour at brightness {@code level} (a cable's tier, 1 to 4) of a cable whose dyed or undyed colour is
     * {@code argb}: each channel scaled so the brightest reaches the level's peak, the top level then pulled towards
     * white; alpha kept. A black colour reads as grey; a level outside 1 to 4 is clamped.
     */
    public static int lineRgb(int level, int argb) {
        int index = Math.clamp(level, 1, LEVELS) - 1;
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        int peak = Math.max(r, Math.max(g, b));
        if (peak == 0) {
            r = 1;
            g = 1;
            b = 1;
            peak = 1;
        }
        float scale = (float) LINE_PEAK[index] / peak;
        float whiten = index == LEVELS - 1 ? TOP_WHITEN : 0;
        return (argb & 0xFF000000) | channel(r, scale, whiten) << 16 | channel(g, scale, whiten) << 8
                | channel(b, scale, whiten);
    }

    private static int channel(int value, float scale, float whiten) {
        float scaled = Math.min(255, value * scale);
        return Math.min(255, Math.round(scaled + (255 - scaled) * whiten));
    }

    /**
     * The light level (0 to 15) the lane at brightness {@code level} is drawn lit at least: 0, 4, 8 and 15 while
     * {@code glow} (the client's {@code visuals.cableTierGlow}) is on, 0 for every level while it is off. Drawing only;
     * the cable gives no block light.
     */
    public static int lineEmission(int level, boolean glow) {
        return glow ? EMISSION[Math.clamp(level, 1, LEVELS) - 1] : 0;
    }
}
