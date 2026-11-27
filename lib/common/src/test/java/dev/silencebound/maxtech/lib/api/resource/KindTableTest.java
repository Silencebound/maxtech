package dev.silencebound.maxtech.lib.api.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Registration, freezing and lookups of {@link KindTable}, the registry behind {@link ResourceKinds}. */
class KindTableTest {
    private static ResourceKind<String> register(KindTable table, String path) {
        return table.register(Identifier.fromNamespaceAndPath("test", path), Codec.STRING,
                ByteBufCodecs.STRING_UTF8.cast(), key -> key, AmountUnit.ITEMS);
    }

    @Test
    void indicesFollowRegistrationAndMapBack() {
        KindTable table = new KindTable();
        ResourceKind<String> first = register(table, "first");
        ResourceKind<String> second = register(table, "second");
        assertEquals(0, first.index());
        assertEquals(1, second.index());
        assertEquals(2, table.count());
        assertSame(first, table.byIndex(0));
        assertSame(second, table.byIndex(1));
        assertEquals(Optional.of(second), table.byId(Identifier.fromNamespaceAndPath("test", "second")));
        assertEquals(Optional.empty(), table.byId(Identifier.fromNamespaceAndPath("test", "third")));
        assertThrows(IndexOutOfBoundsException.class, () -> table.byIndex(2));
        assertThrows(IndexOutOfBoundsException.class, () -> table.byIndex(-1));
    }

    @Test
    void registerAfterFreezeThrows() {
        KindTable table = new KindTable();
        ResourceKind<String> kind = register(table, "early");
        assertFalse(table.isFrozen());
        table.freeze();
        assertTrue(table.isFrozen());
        assertThrows(IllegalStateException.class, () -> register(table, "late"));
        table.freeze();
        assertSame(kind, table.byIndex(0), "lookups after the freeze");
        assertEquals(1, table.count());
    }

    @Test
    void rejectsDuplicateAndReservedIds() {
        KindTable table = new KindTable();
        register(table, "twice");
        assertThrows(IllegalArgumentException.class, () -> register(table, "twice"));
        assertThrows(IllegalArgumentException.class, () -> table.register(UnknownResource.KIND.id(), Codec.STRING,
                ByteBufCodecs.STRING_UTF8.cast(), key -> key, AmountUnit.ITEMS));
        assertEquals(1, table.count());
        assertEquals(-1, UnknownResource.KIND.index());
    }
}
