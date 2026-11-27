package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentsPacking}: the server packs 0, the client's installed packer is used, and the block entity stays free
 * of client classes.
 */
class ContentsPackingTest {
    @AfterEach
    void reset() {
        ContentsPacking.reset();
    }

    @Test
    void packsZeroUntilAClientPackerIsInstalled() {
        ConduitContents flowing = new ConduitContents(Optional.empty(), 0, Optional.of(Direction.UP), true);
        assertEquals(0, ContentsPacking.pack(flowing));
        assertEquals(0, ContentsPacking.pack(ConduitContents.EMPTY));
        ContentsPacking.install(contents -> 7);
        assertEquals(7, ContentsPacking.pack(flowing));
        ContentsPacking.reset();
        assertEquals(0, ContentsPacking.pack(flowing), "back to the server default");
    }

    /** Lib's client classes are not on a server; the block entity reaches them only through the hook. */
    @Test
    void blockEntityNamesNoClientClass() throws IOException {
        try (InputStream in = ConduitBlockEntity.class.getResourceAsStream("ConduitBlockEntity.class")) {
            String constants = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("maxtech/lib/client/"), "references a lib.client class");
            assertFalse(constants.contains("net/minecraft/client/"), "references a Minecraft client class");
        }
    }
}
