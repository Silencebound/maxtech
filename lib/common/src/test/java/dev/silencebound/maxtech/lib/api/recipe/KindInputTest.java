package dev.silencebound.maxtech.lib.api.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.KindTags;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.TestKindTable;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Tank inputs over kinds of a table of their own (the built-in kinds need registries; their codecs run in the
 * {@code machine_recipes_*} GameTests). A "gas" key is a string; its tag {@code test:noble} holds the keys that start
 * with "noble_".
 */
class KindInputTest {
    private static final TestKindTable TABLE = new TestKindTable();
    private static final ResourceKind<String> GAS = TABLE.register(Identifier.fromNamespaceAndPath("test", "gas"),
            Codec.STRING, ByteBufCodecs.STRING_UTF8.cast(), AmountUnit.MILLIBUCKETS);
    private static final ResourceKind<String> UNTAGGED = TABLE.register(
            Identifier.fromNamespaceAndPath("test", "untagged"), Codec.STRING, ByteBufCodecs.STRING_UTF8.cast(),
            AmountUnit.ITEMS);
    private static final Identifier NOBLE = Identifier.fromNamespaceAndPath("test", "noble");

    static {
        KindTags.register(GAS,
                tag -> tag.equals(NOBLE) ? (String key) -> key.startsWith("noble_") : (String key) -> false);
    }

    @Test
    void exactInputMatchesOnlyItsOwnResource() {
        KindInput neon = KindInput.of(new KindKey<>(GAS, "noble_neon"), 250);
        assertTrue(neon.test(new KindKey<>(GAS, "noble_neon")));
        assertFalse(neon.test(new KindKey<>(GAS, "noble_argon")), "another key of the kind");
        assertFalse(neon.test(new KindKey<>(UNTAGGED, "noble_neon")), "the same key of another kind");
        assertEquals(Optional.empty(), neon.tag());
        assertEquals(250, neon.amount());
    }

    @Test
    void tagInputMatchesEveryKeyOfItsKindInTheTag() {
        KindInput noble = KindInput.tag(GAS, NOBLE, 1000);
        assertTrue(noble.test(new KindKey<>(GAS, "noble_neon")));
        assertTrue(noble.test(new KindKey<>(GAS, "noble_argon")));
        assertFalse(noble.test(new KindKey<>(GAS, "chlorine")), "outside the tag");
        assertFalse(noble.test(new KindKey<>(UNTAGGED, "noble_neon")), "a key of another kind");
        KindInput other = KindInput.tag(GAS, Identifier.fromNamespaceAndPath("test", "other"), 1000);
        assertFalse(other.test(new KindKey<>(GAS, "noble_neon")), "another tag");
    }

    @Test
    void tagNeedsTheKindsTagSupport() {
        assertThrows(IllegalArgumentException.class, () -> KindInput.tag(UNTAGGED, NOBLE, 1));
        assertFalse(KindTags.supports(UNTAGGED));
        assertThrows(IllegalArgumentException.class, () -> KindTags.register(GAS, tag -> key -> true));
    }

    @Test
    void amountBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> KindInput.of(new KindKey<>(GAS, "noble_neon"), 0));
        assertThrows(IllegalArgumentException.class, () -> KindInput.tag(GAS, NOBLE, -5));
        assertThrows(IllegalArgumentException.class, () -> new KindOutput(new KindKey<>(GAS, "noble_neon"), 0));
    }

    @Test
    void inputsAreEqualByWhatTheyMatchAndAmount() {
        assertEquals(KindInput.tag(GAS, NOBLE, 5), KindInput.tag(GAS, NOBLE, 5));
        assertNotEquals(KindInput.tag(GAS, NOBLE, 5), KindInput.tag(GAS, NOBLE, 6));
        assertNotEquals(KindInput.tag(GAS, NOBLE, 5), KindInput.of(new KindKey<>(GAS, "noble_neon"), 5));
    }
}
