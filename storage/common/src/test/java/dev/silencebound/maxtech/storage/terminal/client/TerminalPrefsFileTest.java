package dev.silencebound.maxtech.storage.terminal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.terminal.TerminalKind;
import dev.silencebound.maxtech.storage.terminal.TerminalPrefs;
import dev.silencebound.maxtech.storage.terminal.TerminalSize;
import dev.silencebound.maxtech.storage.terminal.TerminalView;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The prefs file's JSON without a game: the view and tabs survive a write and a read (no pins, so no registries). */
class TerminalPrefsFileTest {
    @Test
    void viewAndTabsSurviveTheFile() {
        TerminalPrefs<StackKey> saved = new TerminalPrefs<>();
        saved.addTab("@minecraft");
        saved.setView(TerminalView.FLUIDS);
        saved.setSize(TerminalKind.PATTERN, TerminalSize.FILL);
        saved.setSize(TerminalKind.TERMINAL, TerminalSize.TALL);
        JsonObject json = TerminalPrefsFile.write(saved, JsonOps.INSTANCE);
        assertEquals("fluids", json.get("view").getAsString());

        TerminalPrefs<StackKey> loaded = new TerminalPrefs<>();
        TerminalPrefsFile.read(JsonParser.parseString(json.toString()).getAsJsonObject(), JsonOps.INSTANCE, loaded);
        assertEquals(TerminalView.FLUIDS, loaded.view());
        assertEquals(List.of("@minecraft"), loaded.tabs());
        assertEquals(TerminalSize.FILL, loaded.size(TerminalKind.PATTERN));
        assertEquals(TerminalSize.TALL, loaded.size(TerminalKind.TERMINAL));
        assertEquals(TerminalSize.SMALL, loaded.size(TerminalKind.CRAFTING));
    }

    @Test
    void unknownSizesAreSkipped() {
        TerminalPrefs<StackKey> loaded = new TerminalPrefs<>();
        TerminalPrefsFile.read(JsonParser
                .parseString("{\"sizes\":{\"crafting\":\"fill\",\"robot\":\"tall\",\"pattern\":3}}").getAsJsonObject(),
                JsonOps.INSTANCE, loaded);
        assertEquals(TerminalSize.FILL, loaded.size(TerminalKind.CRAFTING));
        assertEquals(TerminalSize.SMALL, loaded.size(TerminalKind.PATTERN), "a size that is not a string");
        assertEquals(1, loaded.sizes().size(), "an unknown kind is skipped");
    }

    @Test
    void anOlderFileWithoutAViewShowsAll() {
        TerminalPrefs<StackKey> loaded = new TerminalPrefs<>();
        loaded.setView(TerminalView.ITEMS);
        TerminalPrefsFile.read(JsonParser.parseString("{\"pins\":[],\"tabs\":[\"x\"]}").getAsJsonObject(),
                JsonOps.INSTANCE, loaded);
        assertEquals(TerminalView.ALL, loaded.view());
        TerminalPrefsFile.read(JsonParser.parseString("{\"view\":7}").getAsJsonObject(), JsonOps.INSTANCE, loaded);
        assertEquals(TerminalView.ALL, loaded.view(), "a view that is not a string");
    }
}
