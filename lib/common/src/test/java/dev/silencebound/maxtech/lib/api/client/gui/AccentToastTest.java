package dev.silencebound.maxtech.lib.api.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import org.junit.jupiter.api.Test;

/** A toast's strip follows its theme for news and keeps the shared status colours otherwise. */
class AccentToastTest {
    private static final AccentTheme MINT = new AccentTheme(0xFF1, 0xFF2, 0xFF5FE08A, 0xFF4, 0xFF5, 0xFF6);

    @Test
    void infoStripFollowsTheTheme() {
        assertEquals(Accent.BAR_TOP, AccentToast.stripColour(AccentToast.Kind.INFO, AccentTheme.LIB));
        assertEquals(0xFF5FE08A, AccentToast.stripColour(AccentToast.Kind.INFO, MINT));
        assertEquals(Accent.AMBER, AccentToast.stripColour(AccentToast.Kind.WARN, MINT));
        assertEquals(Accent.RED, AccentToast.stripColour(AccentToast.Kind.ERROR, MINT));
    }
}
