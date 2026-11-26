package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * The server's network names (M7L2 row 13): fresh names, names held by another core, one entry per core (no ghost left
 * when a core takes another name), renames and releases.
 */
class NetworkNamesTest {
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION,
            Identifier.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION,
            Identifier.withDefaultNamespace("the_nether"));
    private static final UUID OWNER = new UUID(1, 2);

    private static List<String> names(NetworkNames names) {
        return names.entries().stream().map(NetworkNames.Entry::name).sorted().toList();
    }

    @Test
    void newCoresGetFreshNamesAndKeepTheirOwn() {
        NetworkNames names = new NetworkNames();
        assertEquals("Network 1", names.claim("", OVERWORLD, 10, OWNER));
        assertEquals("Network 2", names.claim("", OVERWORLD, 20, null));
        // Loading again with its name, or with none, keeps what the core holds.
        assertEquals("Network 1", names.claim("Network 1", OVERWORLD, 10, OWNER));
        assertEquals("Network 1", names.claim("", OVERWORLD, 10, OWNER));
        // The same position in another dimension is another core.
        assertEquals("Network 3", names.claim("network 1", NETHER, 10, OWNER));
        assertEquals(List.of("Network 1", "Network 2", "Network 3"), names(names));
    }

    @Test
    void aCoreTakingAnotherNameLeavesNoGhost() {
        NetworkNames names = new NetworkNames();
        // Placed from a named item: onLoad claims a fresh name, then the item's name comes.
        assertEquals("Network 1", names.claim("", OVERWORLD, 10, OWNER));
        assertEquals("North Base", names.claim("North Base", OVERWORLD, 10, OWNER));
        assertEquals(List.of("North Base"), names(names));
        assertTrue(names.find("network 1").isEmpty(), "the fresh name is free again");
        // An item whose name another core holds: the core keeps the name it has here.
        names.claim("", OVERWORLD, 20, OWNER);
        assertEquals("Network 2", names.claim("NORTH BASE", OVERWORLD, 20, OWNER));
        assertEquals(List.of("Network 2", "North Base"), names(names));
    }

    @Test
    void renamesAndReleases() {
        NetworkNames names = new NetworkNames();
        names.claim("North Base", OVERWORLD, 10, OWNER);
        names.claim("South", OVERWORLD, 20, OWNER);
        assertEquals(NetworkNames.Renamed.TAKEN, names.rename("South", "north base", OVERWORLD, 20, OWNER));
        assertEquals(NetworkNames.Renamed.EMPTY, names.rename("South", " @ ", OVERWORLD, 20, OWNER));
        assertEquals(NetworkNames.Renamed.SAME, names.rename("South", "South", OVERWORLD, 20, OWNER));
        assertEquals(NetworkNames.Renamed.DONE, names.rename("South", "West@Wing", OVERWORLD, 20, OWNER));
        assertEquals(List.of("North Base", "WestWing"), names(names));
        // Only the core holding a name frees it.
        names.release("North Base", OVERWORLD, 20);
        assertEquals(List.of("North Base", "WestWing"), names(names));
        names.release("North Base", OVERWORLD, 10);
        assertEquals(List.of("WestWing"), names(names));
        assertEquals("x".repeat(24), NetworkNames.clean("x".repeat(30)));
    }
}
