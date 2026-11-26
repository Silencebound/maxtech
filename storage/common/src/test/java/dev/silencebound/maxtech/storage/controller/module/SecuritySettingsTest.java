package dev.silencebound.maxtech.storage.controller.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.network.AccessLevel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecuritySettingsTest {
    @Test
    void levelsCycleAndOrder() {
        assertEquals(AccessLevel.VIEW, AccessLevel.NONE.next());
        assertEquals(AccessLevel.NONE, AccessLevel.FULL.next(), "wraps");
        assertTrue(AccessLevel.DEPOSIT.atLeast(AccessLevel.VIEW));
        assertTrue(!AccessLevel.VIEW.atLeast(AccessLevel.DEPOSIT));
        for (AccessLevel level : AccessLevel.values()) {
            assertEquals(level, AccessLevel.byId(level.id()));
        }
        assertEquals(AccessLevel.NONE, AccessLevel.byId(99), "an unknown id is none");
    }

    @Test
    void playerRulesAreSetChangedAndRemoved() {
        UUID id = new UUID(1, 2);
        SecuritySettings rules = SecuritySettings.DEFAULT.withPlayer(id, "alex", AccessLevel.VIEW);
        assertEquals(AccessLevel.VIEW, rules.playerLevel(id));
        rules = rules.withPlayer(id, "alex", AccessLevel.FULL);
        assertEquals(1, rules.players().size(), "one rule per player");
        assertEquals(AccessLevel.FULL, rules.playerLevel(id));
        assertNull(rules.withPlayer(id, "alex", null).playerLevel(id), "removed");
    }

    @Test
    void teamRulesAreSetAndRemoved() {
        SecuritySettings rules = SecuritySettings.DEFAULT.withTeam("red", AccessLevel.DEPOSIT);
        assertEquals(AccessLevel.DEPOSIT, rules.teamLevel("red"));
        assertNull(rules.teamLevel("blue"));
        assertNull(rules.withTeam("red", null).teamLevel("red"));
    }

    @Test
    void rulesStopAtTheirCaps() {
        SecuritySettings rules = SecuritySettings.DEFAULT;
        for (int i = 0; i < SecuritySettings.MAX_PLAYERS + 5; i++) {
            rules = rules.withPlayer(new UUID(0, i), "p" + i, AccessLevel.VIEW);
        }
        assertEquals(SecuritySettings.MAX_PLAYERS, rules.players().size());
        for (int i = 0; i < SecuritySettings.MAX_TEAMS + 5; i++) {
            rules = rules.withTeam("t" + i, AccessLevel.VIEW);
        }
        assertEquals(SecuritySettings.MAX_TEAMS, rules.teams().size());
        // An existing rule still changes at the cap.
        rules = rules.withPlayer(new UUID(0, 0), "p0", AccessLevel.FULL);
        assertEquals(AccessLevel.FULL, rules.playerLevel(new UUID(0, 0)));
    }

    @Test
    void defaultsMatchOwnerSecurity() {
        assertEquals(AccessLevel.NONE, SecuritySettings.DEFAULT.everyone());
        assertEquals(AccessLevel.FULL, SecuritySettings.DEFAULT.ownerTeam());
    }
}
