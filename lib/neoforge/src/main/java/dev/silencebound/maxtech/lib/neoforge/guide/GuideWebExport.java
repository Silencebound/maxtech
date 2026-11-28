package dev.silencebound.maxtech.lib.neoforge.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import guideme.siteexport.ExportPostProcessor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes MaxTech's own tags readable on a guide's website. GuideME's website generator (26.1.15-beta) draws only its
 * built-in tags and has no hook for others, so this rewrites the exported page trees before the export is written:
 * {@code <Config key unit />} becomes the config's <b>default</b> value with its unit in bold (the website belongs to
 * no server; a key without a namespace names a value of the guide's mod), and {@code <KeyBind id />} becomes the key's
 * default binding. A bare attribute such as {@code <GameScene interactive>} gets the value {@code "true"}: GuideME
 * exports it without one, and its website generator then fails to read the export; scene annotations are dropped
 * ({@link #DROPPED}). Runs once in the export client; the generator never sees MaxTech's tags.
 */
public final class GuideWebExport implements ExportPostProcessor {
    /** The tags this rewrites; every other tag on a page must be one GuideME's website draws. */
    public static final Set<String> TAGS = Set.of(ConfigTagCompiler.TAG_NAME, "KeyBind");
    /**
     * Scene annotations, left out of the website: GuideME 26.1.15-beta's published jar has their attribute records
     * without constructors, so the generator fails on any page that has one. The scene itself stays.
     */
    public static final Set<String> DROPPED = Set.of("DiamondAnnotation", "BoxAnnotation", "LineAnnotation");
    private static final Logger LOG = LoggerFactory.getLogger(GuideWebExport.class);
    private static final String TEXT_ELEMENT = "mdxJsxTextElement";
    private static final String FLOW_ELEMENT = "mdxJsxFlowElement";
    private static final String ATTRIBUTE = "mdxJsxAttribute";

    private final String namespace;
    private final Function<String, Optional<String>> keyNames;

    /**
     * @param namespace
     *            the guide's namespace, where a {@code <Config>} key without one is looked up
     * @param keyNames
     *            a key mapping's id to the name of its default key, empty for an unknown id
     */
    public GuideWebExport(String namespace, Function<String, Optional<String>> keyNames) {
        this.namespace = namespace;
        this.keyNames = keyNames;
    }

    @Override
    public void postProcess(JsonObject rootNode) {
        rewrite(rootNode);
    }

    private void rewrite(JsonElement node) {
        if (node instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                JsonElement child = array.get(i);
                if (child instanceof JsonObject object && string(object, "type").equals(FLOW_ELEMENT)
                        && DROPPED.contains(string(object, "name"))) {
                    array.remove(i);
                    i--;
                    continue;
                }
                Optional<JsonObject> replacement = child instanceof JsonObject object
                        ? replacement(object)
                        : Optional.empty();
                if (replacement.isPresent()) {
                    array.set(i, replacement.get());
                } else {
                    rewrite(child);
                }
            }
        } else if (node instanceof JsonObject object) {
            if (string(object, "type").equals(ATTRIBUTE) && !object.has("value")) {
                // A bare attribute (<GameScene interactive>) is exported without a value, which the website
                // generator's JSON reader fails on; it means true.
                object.addProperty("value", "true");
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                rewrite(entry.getValue());
            }
        }
    }

    /** The node that replaces a MaxTech tag; empty for anything else, or a tag whose value is unknown. */
    private Optional<JsonObject> replacement(JsonObject node) {
        String type = string(node, "type");
        if (!type.equals(TEXT_ELEMENT) && !type.equals(FLOW_ELEMENT)) {
            return Optional.empty();
        }
        String name = string(node, "name");
        Optional<String> text = switch (name) {
            case ConfigTagCompiler.TAG_NAME -> configText(attribute(node, "key"), namespace, attribute(node, "unit"));
            case "KeyBind" -> keyNames.apply(attribute(node, "id"));
            default -> Optional.empty();
        };
        if (text.isEmpty()) {
            if (TAGS.contains(name)) {
                LOG.warn("Guide website: <{}> has no value to show; left for the website to report", name);
            }
            return Optional.empty();
        }
        JsonObject strong = element("strong", textNode(text.get()));
        return Optional.of(type.equals(FLOW_ELEMENT) ? element("paragraph", strong) : strong);
    }

    /**
     * The default of a config value as the page shows it; empty for an unknown key. A key without a namespace is looked
     * up in {@code namespace}.
     */
    public static Optional<String> configText(String key, String namespace, String unit) {
        return GuideConfigText.value(key, namespace)
                .map(value -> GuideConfigText.text(value.defaultValue(), unit).getString());
    }

    private static String attribute(JsonObject node, String name) {
        if (node.get("attributes") instanceof JsonArray attributes) {
            for (JsonElement attribute : attributes) {
                if (attribute instanceof JsonObject object && string(object, "name").equals(name)) {
                    return string(object, "value");
                }
            }
        }
        return "";
    }

    private static String string(JsonObject node, String member) {
        JsonElement value = node.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonObject textNode(String text) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "text");
        node.addProperty("value", text);
        return node;
    }

    private static JsonObject element(String type, JsonObject child) {
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        JsonArray children = new JsonArray();
        children.add(child);
        node.add("children", children);
        return node;
    }
}
