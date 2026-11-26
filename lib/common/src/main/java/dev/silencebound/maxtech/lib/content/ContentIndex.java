package dev.silencebound.maxtech.lib.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import dev.silencebound.maxtech.lib.api.conduit.ConduitTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * A mod's content index: the sorted list of everything it registers and generates, written by datagen to
 * {@code <modid>/content_index.json} in the mod's generated resources. It is committed like every generated file, so a
 * PR that adds or removes content shows it as a readable diff of the index, and CI fails when the index was not
 * regenerated. Tests compare the loaded game against it instead of counting content by hand.
 *
 * <p>
 * Top-level keys, each present even when empty:
 * <ul>
 * <li>{@value #ITEMS}, {@value #BLOCKS}: declared items that are not block items, and declared blocks, both without
 * material form items and blocks, which {@value #MATERIALS} lists; an addon's include what the materials its own file
 * declares bring with them (tools, ores), though their ids are {@code maxtech:}'s;</li>
 * <li>{@value #MATERIALS}: material id to form id to item id, for the materials the mod's own {@code materials.json}
 * declares; {@value #MATERIAL_FORMS}: form id to its {@code c:} tag folder;</li>
 * <li>{@value #FLUIDS}, {@value #CONDUIT_TYPES};</li>
 * <li>{@value #CREATIVE_TAB}: the mod's tab in its display order, the only list that is not sorted;</li>
 * <li>{@value #TAGS}: registry (e.g. {@code item}) to every tag file datagen writes, in any namespace, to its entries
 * as the file names them ({@code #} for a tag, a trailing {@code ?} for an optional entry);</li>
 * <li>{@value #RECIPES}: recipe group to recipe ids, the recipe's type ({@code minecraft:crafting_shaped});</li>
 * <li>{@value #DATAPACK_ENTRIES}: datapack registry to the mod's entries in it (veins, worldgen features, fuels).</li>
 * </ul>
 * Reading is safe from any thread; the object is immutable once loaded. The {@code *Problems} checks read registries,
 * tags and recipes, so call them on the server thread once the game has loaded.
 */
public final class ContentIndex {
    public static final String ITEMS = "items";
    public static final String BLOCKS = "blocks";
    public static final String MATERIALS = "materials";
    public static final String MATERIAL_FORMS = "material_forms";
    public static final String FLUIDS = "fluids";
    public static final String CONDUIT_TYPES = "conduit_types";
    public static final String CREATIVE_TAB = "creative_tab";
    public static final String TAGS = "tags";
    public static final String RECIPES = "recipes";
    public static final String DATAPACK_ENTRIES = "datapack_entries";
    /** Marks an optional tag entry ({@code "required": false} in the tag file). */
    public static final String OPTIONAL_SUFFIX = "?";

    private final JsonObject json;

    private ContentIndex(JsonObject json) {
        this.json = json;
    }

    /** Where {@code modId}'s index lives, relative to the resource root. */
    public static String path(String modId) {
        return modId + "/content_index.json";
    }

    /**
     * Reads {@code modId}'s index through {@code anchor}, a class of that mod: in a named module a class only sees its
     * own module's resources. Throws {@link IllegalStateException} if the index is missing or malformed.
     */
    public static ContentIndex load(Class<?> anchor, String modId) {
        String resource = "/" + path(modId);
        InputStream stream = anchor.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Content index " + resource + " is missing; run " + modId + "'s runData");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new ContentIndex(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Malformed content index " + resource, e);
        }
    }

    /** One of the list sections ({@link #ITEMS}, {@link #BLOCKS}, {@link #FLUIDS}, ...), in file order. */
    public List<Identifier> ids(String section) {
        return identifiers(json.getAsJsonArray(section));
    }

    /** Material ids to form ids to the item each form is. */
    public Map<Identifier, Map<Identifier, Identifier>> materials() {
        Map<Identifier, Map<Identifier, Identifier>> materials = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> material : json.getAsJsonObject(MATERIALS).entrySet()) {
            materials.put(Identifier.parse(material.getKey()), idMap(material.getValue().getAsJsonObject()));
        }
        return materials;
    }

    /**
     * The tags datagen writes for {@code registry} (the tag folder name: {@code item}, {@code block}, {@code fluid}),
     * each to its entries as written, see the class Javadoc.
     */
    public Map<Identifier, List<String>> tags(String registry) {
        Map<Identifier, List<String>> tags = new LinkedHashMap<>();
        JsonObject section = json.getAsJsonObject(TAGS).getAsJsonObject(registry);
        if (section == null) {
            return tags;
        }
        for (Map.Entry<String, JsonElement> tag : section.entrySet()) {
            List<String> entries = new ArrayList<>();
            for (JsonElement entry : tag.getValue().getAsJsonArray()) {
                entries.add(entry.getAsString());
            }
            tags.put(Identifier.parse(tag.getKey()), entries);
        }
        return tags;
    }

    /** Recipe ids to their group (the recipe type). */
    public Map<Identifier, Identifier> recipeGroups() {
        Map<Identifier, Identifier> recipes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> group : json.getAsJsonObject(RECIPES).entrySet()) {
            Identifier groupId = Identifier.parse(group.getKey());
            for (Identifier recipe : identifiers(group.getValue().getAsJsonArray())) {
                recipes.put(recipe, groupId);
            }
        }
        return recipes;
    }

    /**
     * Differences between the loaded recipes and {@link #RECIPES}: an indexed recipe that did not load (a tag no pack
     * defines, a broken codec), one that loaded into another group, or a loaded recipe in {@code namespace} that the
     * index does not list, unless {@code ignore} accepts its id (GameTest data). Empty when they agree.
     */
    public List<String> recipeProblems(Iterable<RecipeHolder<?>> loaded, String namespace,
            Predicate<Identifier> ignore) {
        Map<Identifier, Identifier> expected = recipeGroups();
        List<String> problems = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        for (RecipeHolder<?> holder : loaded) {
            Identifier id = holder.id().identifier();
            Recipe<?> recipe = holder.value();
            Identifier group = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            Identifier indexed = expected.get(id);
            if (indexed != null) {
                seen.add(id);
                if (!Objects.equals(indexed, group)) {
                    problems.add(id + " loaded as " + group + ", indexed as " + indexed);
                }
            } else if (id.getNamespace().equals(namespace) && !ignore.test(id)) {
                problems.add(id + " loaded but is not in the index");
            }
        }
        for (Identifier id : expected.keySet()) {
            if (!seen.contains(id)) {
                problems.add(id + " is indexed but did not load");
            }
        }
        return problems;
    }

    /**
     * Indexed items, blocks, fluids, conduit types and material forms that the running game does not register as
     * indexed: content registered only in datagen, or behind a config switch. Empty when they agree.
     */
    public List<String> registryProblems() {
        List<String> problems = new ArrayList<>();
        missing(problems, ITEMS, BuiltInRegistries.ITEM);
        missing(problems, BLOCKS, BuiltInRegistries.BLOCK);
        missing(problems, FLUIDS, BuiltInRegistries.FLUID);
        for (Identifier type : ids(CONDUIT_TYPES)) {
            if (ConduitTypes.byId(type).isEmpty()) {
                problems.add("conduit type " + type + " is not registered");
            }
        }
        // Material forms are checked by whoever registers materials, which Lib does not know.
        for (ContentExtension extension : ContentExtension.all()) {
            extension.registryProblems(this, problems);
        }
        return problems;
    }

    /**
     * Indexed item, block and fluid tags whose loaded contents lack an entry the index lists: a plain entry, every
     * member of an included tag, and an optional entry if it is registered. Other mods may add to a tag, so extra
     * members are fine. Empty when every entry is there.
     */
    public List<String> tagProblems() {
        List<String> problems = new ArrayList<>();
        tagProblems(problems, "item", BuiltInRegistries.ITEM);
        tagProblems(problems, "block", BuiltInRegistries.BLOCK);
        tagProblems(problems, "fluid", BuiltInRegistries.FLUID);
        return problems;
    }

    private <T> void tagProblems(List<String> problems, String folder, Registry<T> registry) {
        for (Map.Entry<Identifier, List<String>> tag : tags(folder).entrySet()) {
            TagKey<T> key = TagKey.create(registry.key(), tag.getKey());
            for (String entry : tag.getValue()) {
                if (entry.startsWith("#")) {
                    TagKey<T> included = TagKey.create(registry.key(), Identifier.parse(entry.substring(1)));
                    for (Holder<T> member : registry.getTagOrEmpty(included)) {
                        if (!member.is(key)) {
                            problems.add(folder + " tag " + tag.getKey() + " lacks " + member.getRegisteredName()
                                    + " from " + entry);
                        }
                    }
                    continue;
                }
                boolean optional = entry.endsWith(OPTIONAL_SUFFIX);
                Identifier id = Identifier
                        .parse(optional ? entry.substring(0, entry.length() - OPTIONAL_SUFFIX.length()) : entry);
                Optional<Holder.Reference<T>> member = registry.get(id);
                if (member.isEmpty()) {
                    if (!optional) {
                        problems.add(folder + " tag " + tag.getKey() + " names unregistered " + id);
                    }
                } else if (!member.get().is(key)) {
                    problems.add(folder + " tag " + tag.getKey() + " lacks " + id);
                }
            }
        }
    }

    private void missing(List<String> problems, String section, Registry<?> registry) {
        for (Identifier id : ids(section)) {
            if (!registry.containsKey(id)) {
                problems.add(section + " entry " + id + " is not registered");
            }
        }
    }

    private static List<Identifier> identifiers(JsonArray array) {
        List<Identifier> ids = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            ids.add(Identifier.parse(element.getAsString()));
        }
        return ids;
    }

    private static Map<Identifier, Identifier> idMap(JsonObject object) {
        Map<Identifier, Identifier> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            map.put(Identifier.parse(entry.getKey()), Identifier.parse(entry.getValue().getAsString()));
        }
        return map;
    }
}
