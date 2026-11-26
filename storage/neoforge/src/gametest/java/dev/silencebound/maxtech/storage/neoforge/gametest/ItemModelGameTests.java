package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialLooks;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Item models, read from the mod's resources the way the client would resolve them: every Storage item has an item
 * definition whose models exist and whose textures exist (no missing-texture items), and the tinted generic materials
 * draw only vanilla textures, so no Mojang pixels ship and no flat material texture is left behind.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ItemModelGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"item_models_resolve", "item_models_tinted_vanilla"};

    static {
        StorageTestEnvironment.init();
        register(NAMES[0], ItemModelGameTests::resolve);
        register(NAMES[1], ItemModelGameTests::tintedVanilla);
    }

    private ItemModelGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("item_models"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    /** Every Storage item's definition, the models it names (Storage's and Lib's) and their textures exist. */
    private static void resolve(GameTestHelper helper) {
        int items = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (!shipped(id)) {
                continue;
            }
            items++;
            JsonObject definition = json("assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json")
                    .orElseThrow(() -> fail(helper, "no item definition for " + id));
            List<String> models = new ArrayList<>();
            collectModels(definition, models);
            for (String model : models) {
                checkModel(helper, id, Identifier.parse(model), new HashSet<>());
            }
        }
        helper.assertTrue(items > 100, "only " + items + " items checked");
        helper.succeed();
    }

    /**
     * Each material form's shared model draws its vanilla texture; every item naming it is tinted, and no texture of
     * the item's own is left in Storage's item textures.
     */
    private static void tintedVanilla(GameTestHelper helper) {
        for (MaterialLooks.Form form : MaterialLooks.Form.values()) {
            Identifier model = form.model();
            JsonObject json = json(path(model, "models", ".json")).orElseThrow(() -> fail(helper, "no model " + model));
            for (Map.Entry<String, JsonElement> texture : json.getAsJsonObject("textures").entrySet()) {
                Identifier used = Identifier.parse(texture.getValue().getAsString());
                helper.assertTrue(used.getNamespace().equals(Identifier.DEFAULT_NAMESPACE),
                        model + " draws " + used + ", not a vanilla texture");
            }
            helper.assertValueEqual(Identifier.parse(json.getAsJsonObject("textures").get("layer0").getAsString()),
                    form.texture(), model + " layer0");
            int users = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (!shipped(id)) {
                    continue;
                }
                JsonObject definition = json("assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json")
                        .orElse(new JsonObject());
                List<String> models = new ArrayList<>();
                collectModels(definition, models);
                if (!models.contains(model.toString())) {
                    continue;
                }
                users++;
                JsonObject inner = definition.getAsJsonObject("model");
                helper.assertTrue(inner.has("tints") && !inner.getAsJsonArray("tints").isEmpty(),
                        id + " is not tinted");
                helper.assertTrue(!exists(path(id.withPrefix("item/"), "textures", ".png")),
                        id + " still has a texture of its own");
            }
            helper.assertTrue(users > 0, "no item uses " + model);
        }
        helper.succeed();
    }

    /** Checks {@code model} (and its parents in a mod's namespace) exists and every texture it names exists. */
    private static void checkModel(GameTestHelper helper, Identifier item, Identifier model, Set<Identifier> seen) {
        if (model.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) || !seen.add(model)) {
            return;
        }
        JsonObject json = json(path(model, "models", ".json"))
                .orElseThrow(() -> fail(helper, item + ": model " + model + " is missing"));
        if (json.has("textures")) {
            for (Map.Entry<String, JsonElement> texture : json.getAsJsonObject("textures").entrySet()) {
                String value = texture.getValue().getAsString();
                if (value.startsWith("#")) {
                    continue;
                }
                Identifier used = Identifier.parse(value);
                // The server's classpath has no vanilla assets; vanilla textures are checked by id where they matter.
                if (used.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
                    continue;
                }
                helper.assertTrue(exists(path(used, "textures", ".png")),
                        item + ": " + model + " names the missing texture " + used);
            }
        }
        if (json.has("parent")) {
            checkModel(helper, item, Identifier.parse(json.get("parent").getAsString()), seen);
        }
    }

    /** Adds every model id an item definition names ({@code model} and {@code base} strings at any depth). */
    private static void collectModels(JsonElement element, List<String> out) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectModels(child, out);
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if ((entry.getKey().equals("model") || entry.getKey().equals("base")) && value.isJsonPrimitive()) {
                    out.add(value.getAsString());
                } else {
                    collectModels(value, out);
                }
            }
        }
    }

    /** A Storage item the mod ships; the GameTest source set's own items are named {@code test_...} and have none. */
    private static boolean shipped(Identifier id) {
        return id.getNamespace().equals(MaxtechStorage.MOD_ID) && !id.getPath().startsWith("test_");
    }

    private static String path(Identifier id, String folder, String extension) {
        return "assets/" + id.getNamespace() + "/" + folder + "/" + id.getPath() + extension;
    }

    private static boolean exists(String path) {
        return ItemModelGameTests.class.getClassLoader().getResource(path) != null;
    }

    private static Optional<JsonObject> json(String path) {
        try (InputStream in = ItemModelGameTests.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional
                    .of(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RuntimeException fail(GameTestHelper helper, String message) {
        return helper.assertionException(Component.literal(message));
    }
}
