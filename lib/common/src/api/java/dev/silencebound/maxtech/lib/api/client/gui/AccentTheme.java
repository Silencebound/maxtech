package dev.silencebound.maxtech.lib.api.client.gui;

import org.jetbrains.annotations.ApiStatus;

/**
 * A mod's colours for the Accent look (ADR-0016): the accent line and lit faces, the gauge gradient and the text. The
 * frame itself (outline, graphite, slots) and the status colours stay the same for every mod, so all MaxTech screens
 * read as one set. {@link #LIB} is Lib's cyan, which Storage uses; an addon passes its own theme to the widgets and to
 * {@link Accent}'s theme overloads (Farms uses a mint accent). Colours are ARGB. Immutable; any thread.
 *
 * @param accent
 *            the accent line under a panel's tab and the edge of hovered and lit faces
 * @param accentDim
 *            the fill of hovered and lit faces and the line in a title tab
 * @param barTop
 *            the bright end of the gauge gradient
 * @param barBottom
 *            the dark end of the gauge gradient
 * @param text
 *            body text and button labels
 * @param heading
 *            headings, links and live values on a dark panel
 */
@ApiStatus.Experimental
public record AccentTheme(int accent, int accentDim, int barTop, int barBottom, int text, int heading) {
    /** Lib's own theme: the cyan palette every MaxTech screen used before themes existed. */
    public static final AccentTheme LIB = new AccentTheme(Accent.ACCENT, Accent.ACCENT_DIM, Accent.BAR_TOP,
            Accent.BAR_BOTTOM, Accent.TEXT, Accent.CYAN);
}
