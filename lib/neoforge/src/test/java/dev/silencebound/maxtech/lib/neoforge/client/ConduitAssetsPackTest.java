package dev.silencebound.maxtech.lib.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.Test;

class ConduitAssetsPackTest {
    private static final Identifier STATE = Identifier.fromNamespaceAndPath("test", "blockstates/tube.json");
    private static final Identifier ITEM = Identifier.fromNamespaceAndPath("test", "items/tube.json");
    private static final byte[] JSON = "{}".getBytes(StandardCharsets.UTF_8);

    private static ConduitAssetsPack.Resources pack() {
        return new ConduitAssetsPack.Resources(
                new PackLocationInfo("test", Component.literal("test"), PackSource.BUILT_IN, Optional.empty()),
                Map.of(STATE, JSON, ITEM, JSON));
    }

    /** The client finds the files by location and by listing their folder; servers see nothing. */
    @Test
    void servesFilesAsClientResources() throws IOException {
        ConduitAssetsPack.Resources pack = pack();
        IoSupplier<InputStream> state = pack.getResource(PackType.CLIENT_RESOURCES, STATE);
        assertNotNull(state);
        try (InputStream in = state.get()) {
            assertArrayEquals(JSON, in.readAllBytes());
        }
        assertNull(pack.getResource(PackType.SERVER_DATA, STATE));
        assertEquals(Set.of("test"), pack.getNamespaces(PackType.CLIENT_RESOURCES));
        assertEquals(Set.of(), pack.getNamespaces(PackType.SERVER_DATA));
        List<Identifier> listed = new ArrayList<>();
        pack.listResources(PackType.CLIENT_RESOURCES, "test", "blockstates", (id, supplier) -> listed.add(id));
        assertEquals(List.of(STATE), listed);
    }
}
