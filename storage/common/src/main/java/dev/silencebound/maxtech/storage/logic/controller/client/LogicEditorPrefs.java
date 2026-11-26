package dev.silencebound.maxtech.storage.logic.controller.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * The node editor's choices this player keeps on this machine, in {@code config/maxtech_storage-logic-editor.json} of
 * the game directory: whether the palette lists all node types or the common ones (the default). Read once per game
 * session, written when the choice changes. Client thread.
 */
public final class LogicEditorPrefs {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "maxtech_storage-logic-editor.json";
    private static final String PALETTE = "palette";
    private static final String ALL = "all";
    private static boolean loaded;
    private static boolean paletteAll;

    private LogicEditorPrefs() {
    }

    /** Whether the palette lists every node type; false (the common ones) until the player switches. */
    public static boolean paletteAll() {
        if (!loaded) {
            loaded = true;
            load();
        }
        return paletteAll;
    }

    /** Remembers the palette's view and writes it out. */
    public static void setPaletteAll(boolean all) {
        loaded = true;
        if (paletteAll == all) {
            return;
        }
        paletteAll = all;
        JsonObject root = new JsonObject();
        root.addProperty(PALETTE, all ? ALL : "common");
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not save the logic editor's palette choice", e);
        }
    }

    private static void load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            paletteAll = root.has(PALETTE) && ALL.equals(root.get(PALETTE).getAsString());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the logic editor's choices from {}", path, e);
        }
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE);
    }
}
