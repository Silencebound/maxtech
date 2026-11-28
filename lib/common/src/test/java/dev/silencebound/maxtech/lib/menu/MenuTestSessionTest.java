package dev.silencebound.maxtech.lib.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

class MenuTestSessionTest {
    @Test
    void realPlayerIsRefusedInProduction() {
        assertFalse(MenuTestSession.permitted(false, new LocalChannel()), "a real connection in production");
        assertFalse(MenuTestSession.permitted(false, null), "no connection in production");
    }

    @Test
    void mockPlayerAndDevRunsAreAccepted() {
        assertTrue(MenuTestSession.permitted(false, new EmbeddedChannel()), "a GameTest mock player");
        assertTrue(MenuTestSession.permitted(true, new LocalChannel()), "any player in a dev run");
    }
}
