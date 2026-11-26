package dev.silencebound.maxtech.storage.logic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PluralTest {
    @Test
    void exactlyOneUsesTheSingularKey() {
        assertEquals("detail.actions_one", Plural.key("detail.actions", 1));
    }

    @Test
    void everyOtherCountUsesThePluralKey() {
        assertEquals("detail.actions", Plural.key("detail.actions", 0));
        assertEquals("detail.actions", Plural.key("detail.actions", 2));
        assertEquals("detail.actions", Plural.key("detail.actions", 11));
        assertEquals("detail.actions", Plural.key("detail.actions", -1));
    }
}
