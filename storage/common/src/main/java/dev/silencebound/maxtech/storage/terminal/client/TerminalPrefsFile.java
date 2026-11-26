package dev.silencebound.maxtech.storage.terminal.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.terminal.TerminalKind;
import dev.silencebound.maxtech.storage.terminal.TerminalPrefs;
import dev.silencebound.maxtech.storage.terminal.TerminalSize;
import dev.silencebound.maxtech.storage.terminal.TerminalView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import org.slf4j.Logger;

/**
 * Keeps the player's {@link TerminalPrefs} in {@code config/maxtech_storage-terminal.json} of the game directory, so
 * pins, tabs, the grid's view and the screen sizes follow the player into every world and server on this machine. Keys
 * are written with the registries of the world that is open; a pin whose item no longer exists is skipped on load and
 * dropped at the next save. Client thread.
 */
public final class TerminalPrefsFile {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "maxtech_storage-terminal.json";
    private static final TerminalPrefs<StackKey> PREFS = new TerminalPrefs<>();
    private static boolean loaded;

    private TerminalPrefsFile() {
    }

    /** The prefs, read from the file the first time a terminal opens in this game session. */
    public static TerminalPrefs<StackKey> get(RegistryAccess registries) {
        if (!loaded) {
            loaded = true;
            load(registries);
        }
        return PREFS;
    }

    /** Writes the pins, tabs and view if they changed; called when a terminal screen closes. */
    public static void save(RegistryAccess registries) {
        if (!PREFS.isDirty()) {
            return;
        }
        JsonObject root = write(PREFS, registries.createSerializationContext(JsonOps.INSTANCE));
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
            PREFS.markSaved();
        } catch (IOException e) {
            LOGGER.warn("Could not save the terminal pins and tabs", e);
        }
    }

    private static void load(RegistryAccess registries) {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            read(root, registries.createSerializationContext(JsonOps.INSTANCE), PREFS);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the terminal pins and tabs from {}", path, e);
        }
    }

    /** The prefs as the file's JSON: {@code pins}, {@code tabs}, {@code view} and {@code sizes} by terminal kind. */
    static JsonObject write(TerminalPrefs<StackKey> prefs, DynamicOps<JsonElement> ops) {
        JsonObject root = new JsonObject();
        JsonArray pins = new JsonArray();
        for (StackKey key : prefs.pins()) {
            StackKey.CODEC.encodeStart(ops, key).ifSuccess(pins::add);
        }
        JsonArray tabs = new JsonArray();
        for (String tab : prefs.tabs()) {
            tabs.add(tab);
        }
        root.add("pins", pins);
        root.add("tabs", tabs);
        root.addProperty("view", prefs.view().serialName());
        JsonObject sizes = new JsonObject();
        for (Map.Entry<TerminalKind, TerminalSize> entry : prefs.sizes().entrySet()) {
            sizes.addProperty(entry.getKey().serialName(), entry.getValue().serialName());
        }
        root.add("sizes", sizes);
        return root;
    }

    /** Loads {@code root} into {@code prefs}; a missing or unknown view is {@link TerminalView#ALL}. */
    static void read(JsonObject root, DynamicOps<JsonElement> ops, TerminalPrefs<StackKey> prefs) {
        List<StackKey> pins = new ArrayList<>();
        List<String> tabs = new ArrayList<>();
        if (root.get("pins") instanceof JsonArray array) {
            for (JsonElement element : array) {
                // One at a time, so an item that left the game costs only its own pin.
                StackKey.CODEC.parse(ops, element).ifSuccess(pins::add);
            }
        }
        if (root.get("tabs") instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    tabs.add(element.getAsString());
                }
            }
        }
        String view = root.get("view") instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
        Map<TerminalKind, TerminalSize> sizes = new EnumMap<>(TerminalKind.class);
        if (root.get("sizes") instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                TerminalKind kind = TerminalKind.bySerialName(entry.getKey());
                if (kind != null && entry.getValue() instanceof JsonPrimitive size && size.isString()) {
                    sizes.put(kind, TerminalSize.bySerialName(size.getAsString()));
                }
            }
        }
        prefs.load(pins, tabs, TerminalView.bySerialName(view), sizes);
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE);
    }
}
