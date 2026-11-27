package dev.silencebound.maxtech.lib.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.Identifier;

/**
 * The client resources Lib writes for addons' conduits that set a look ({@code Conduits.Builder#look}): the block state
 * file naming Lib's conduit model with the look, as datagen writes it for Storage's cables, and the item model
 * definition naming {@code <modid>:item/<name>}. Lib's loader side serves them from a built-in pack. Conduits are added
 * during parallel mod construction; the files are read when the client builds its packs.
 */
public final class ConduitAssets {
    /** Lib's conduit model type, as block state files name it. */
    static final String MODEL_TYPE = "maxtech_lib:conduit";

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    private ConduitAssets() {
    }

    /** Has Lib write the resources of conduit {@code id} drawn in {@code look}. Call during mod construction. */
    public static void add(Identifier id, ConduitLook look) {
        ENTRIES.add(new Entry(id, look));
    }

    /** Every file so far, by resource location under {@code assets/} (e.g. {@code farms:blockstates/tube.json}). */
    public static Map<Identifier, byte[]> files() {
        Map<Identifier, byte[]> files = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) {
            Identifier id = entry.id();
            files.put(id.withPath("blockstates/" + id.getPath() + ".json"), bytes(blockState(entry.look())));
            files.put(id.withPath("items/" + id.getPath() + ".json"), bytes(itemDefinition(id)));
        }
        return files;
    }

    /** A block state file with one variant for every state, Lib's conduit model drawn in {@code look}. */
    static JsonObject blockState(ConduitLook look) {
        JsonObject model = new JsonObject();
        model.addProperty("type", MODEL_TYPE);
        JsonElement fields = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, look).getOrThrow();
        for (Map.Entry<String, JsonElement> field : fields.getAsJsonObject().entrySet()) {
            model.add(field.getKey(), field.getValue());
        }
        JsonObject variants = new JsonObject();
        variants.add("", model);
        JsonObject state = new JsonObject();
        state.add("variants", variants);
        return state;
    }

    /** An item model definition drawing the model {@code <namespace>:item/<path>}. */
    static JsonObject itemDefinition(Identifier id) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", id.withPath("item/" + id.getPath()).toString());
        JsonObject definition = new JsonObject();
        definition.add("model", model);
        return definition;
    }

    private static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record Entry(Identifier id, ConduitLook look) {
    }
}
