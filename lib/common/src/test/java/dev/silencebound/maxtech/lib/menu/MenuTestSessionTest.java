package dev.silencebound.maxtech.lib.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

class MenuTestSessionTest {
    @Test
    void realPlayerIsRefused() {
        assertFalse(MenuTestSession.permitted(new LocalChannel()), "a real connection, dev client included");
        assertFalse(MenuTestSession.permitted(null), "no connection");
    }

    @Test
    void mockPlayerIsAccepted() {
        assertTrue(MenuTestSession.permitted(new EmbeddedChannel()), "a GameTest mock player");
    }
}
