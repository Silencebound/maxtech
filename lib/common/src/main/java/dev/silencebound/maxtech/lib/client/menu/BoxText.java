package dev.silencebound.maxtech.lib.client.menu;

/**
 * How large a {@link ToolkitScreen} draws a line of text in a box whose height never changes
 * ({@link dev.silencebound.maxtech.lib.api.menu.Layout#TEXT_HEIGHT}): at the client's text size while it fits, smaller
 * when it does not. Pure arithmetic; any thread.
 */
final class BoxText {
    /** Text sits this far down in its box, like Accent's other text rows. */
    static final int TOP = 1;

    private BoxText() {
    }

    /**
     * The scale for text of {@code lineHeight} (the font's, with its gap below) in a box {@code boxHeight} tall: the
     * client's {@code wanted} factor, or less so the glyphs stay inside the box.
     */
    static float factor(int lineHeight, int boxHeight, float wanted) {
        float fits = (float) (boxHeight - TOP) / (lineHeight - 1);
        return Math.min(wanted, fits);
    }
}
