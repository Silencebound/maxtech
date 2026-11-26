package dev.silencebound.maxtech.lib.neoforge.datagen;

import com.google.common.hash.HashCode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.silencebound.maxtech.lib.api.conduit.ConduitTypes;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.lib.content.BlockDeclaration;
import dev.silencebound.maxtech.lib.content.Content;
import dev.silencebound.maxtech.lib.content.ContentExtension;
import dev.silencebound.maxtech.lib.content.ContentIndex;
import dev.silencebound.maxtech.lib.content.ItemDeclaration;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Writes a mod's {@link ContentIndex}. Registries come from the content declarations, so blocks, items and conduit
 * types the GameTest source set registers in dev runs stay out; recipes and tags come from running the mod's recipe and
 * tag providers once more into an output that only records what they write. Reading the generated files from disk
 * instead would list files a removed recipe left behind, because datagen deletes stale files only after every provider
 * has run.
 */
public final class ContentIndexProvider implements DataProvider {
    private final PackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;
    private final Content content;
    private final DataGenerator generator;

    private ContentIndexProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
            Content content, DataGenerator generator) {
        this.output = output;
        this.registries = registries;
        this.content = content;
        this.generator = generator;
    }

    /**
     * Adds the index provider for {@code content}. Call it last in the mod's data event handler: it takes the event's
     * registry lookup as it is at the call, so datapack entries added after it would be missing.
     */
    public static void register(GatherDataEvent.Client event, Content content) {
        event.createProvider(
                (output, registries) -> new ContentIndexProvider(output, registries, content, event.getGenerator()));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        Recording recording = new Recording();
        List<CompletableFuture<?>> reruns = new ArrayList<>();
        for (DataProvider provider : generator.getProvidersView().values()) {
            if (provider instanceof RecipeProvider.Runner || provider instanceof TagsProvider<?>) {
                reruns.add(provider.run(recording));
            }
        }
        Path file = output.getOutputFolder().resolve(ContentIndex.path(content.modId()));
        return CompletableFuture.allOf(reruns.toArray(CompletableFuture[]::new))
                .thenCombine(registries, (_, lookup) -> index(lookup, recording))
                .thenCompose(json -> DataProvider.saveStable(cache, json, file));
    }

    @Override
    public String getName() {
        return "Content index: " + content.modId();
    }

    private JsonObject index(HolderLookup.Provider lookup, Recording recording) {
        String modId = content.modId();
        Set<Identifier> formItems = new HashSet<>();
        JsonObject materials = new JsonObject();
        SortedMap<String, String> formFolders = new TreeMap<>();
        // Materials belong to the mod that registers them; it fills their sections and claims their form items, which
        // the lists below skip.
        for (ContentExtension extension : ContentExtension.all()) {
            extension.indexMaterials(modId, materials, formFolders, formItems);
        }

        Set<String> items = new TreeSet<>();
        Set<String> conduitTypes = new TreeSet<>();
        for (ItemDeclaration<?> declaration : ContentDatagen.items(content)) {
            Item item = declaration.entry().get();
            if (item instanceof ConduitItem conduit) {
                conduitTypes.add(ConduitTypes.idOf(conduit.conduitType()).toString());
            }
            if (!(item instanceof BlockItem) && !formItems.contains(declaration.entry().id())) {
                items.add(declaration.entry().id().toString());
            }
        }
        Set<String> blocks = new TreeSet<>();
        for (BlockDeclaration<?> declaration : ContentDatagen.blocks(content)) {
            if (!formItems.contains(declaration.entry().id())) {
                blocks.add(declaration.entry().id().toString());
            }
        }
        Set<String> fluids = new TreeSet<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
            // Material fluids (a liquid's flowing form too) are maxtech:'s whichever file declares them; each mod lists
            // those it declares, below.
            if (id.getNamespace().equals(modId) && !listedByExtension(fluid)) {
                fluids.add(id.toString());
            }
        }
        ContentDatagen.fluidTags(content, (tag, fluid) -> fluids.add(BuiltInRegistries.FLUID.getKey(fluid).toString()));
        List<String> tab = new ArrayList<>();
        for (RegistryEntry<Item, ?> item : content.creativeTabItems()) {
            tab.add(item.id().toString());
        }

        JsonObject index = new JsonObject();
        index.add(ContentIndex.ITEMS, array(items));
        index.add(ContentIndex.BLOCKS, array(blocks));
        index.add(ContentIndex.MATERIALS, materials);
        index.add(ContentIndex.MATERIAL_FORMS, object(formFolders));
        index.add(ContentIndex.FLUIDS, array(fluids));
        index.add(ContentIndex.CONDUIT_TYPES, array(conduitTypes));
        index.add(ContentIndex.CREATIVE_TAB, array(tab));
        index.add(ContentIndex.TAGS, tags(lookup, recording));
        index.add(ContentIndex.RECIPES, recipes(recording));
        index.add(ContentIndex.DATAPACK_ENTRIES, datapackEntries(lookup));
        return index;
    }

    private static boolean listedByExtension(Fluid fluid) {
        for (ContentExtension extension : ContentExtension.all()) {
            if (extension.listsFluid(fluid)) {
                return true;
            }
        }
        return false;
    }

    /** Every tag file the tag providers wrote, by registry, with entries sorted. */
    private JsonObject tags(HolderLookup.Provider lookup, Recording recording) {
        // Longest folder first, so worldgen/biome wins over a registry called worldgen.
        SortedMap<String, String> folders = new TreeMap<>(
                Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        lookup.listRegistryKeys()
                .forEach(key -> folders.put(Registries.tagsDirPath(key) + "/",
                        key.identifier().getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
                                ? key.identifier().getPath()
                                : key.identifier().toString()));
        SortedMap<String, SortedMap<String, JsonArray>> tags = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : recording.files(output, "tags/").entrySet()) {
            // <namespace>/tags/<registry>/<path>.json
            String relative = file.getKey();
            int slash = relative.indexOf('/');
            String namespace = relative.substring(0, slash);
            String rest = relative.substring(slash + 1);
            for (Map.Entry<String, String> folder : folders.entrySet()) {
                if (rest.startsWith(folder.getKey())) {
                    String path = rest.substring(folder.getKey().length(), rest.length() - ".json".length());
                    tags.computeIfAbsent(folder.getValue(), _ -> new TreeMap<>()).put(namespace + ":" + path,
                            tagEntries(parse(file.getValue())));
                    break;
                }
            }
        }
        JsonObject json = new JsonObject();
        for (Map.Entry<String, SortedMap<String, JsonArray>> registry : tags.entrySet()) {
            JsonObject section = new JsonObject();
            registry.getValue().forEach(section::add);
            json.add(registry.getKey(), section);
        }
        return json;
    }

    private static JsonArray tagEntries(JsonObject tagFile) {
        Set<String> entries = new TreeSet<>();
        for (JsonElement value : tagFile.getAsJsonArray("values")) {
            if (value.isJsonObject()) {
                JsonObject entry = value.getAsJsonObject();
                boolean required = !entry.has("required") || entry.get("required").getAsBoolean();
                entries.add(entry.get("id").getAsString() + (required ? "" : ContentIndex.OPTIONAL_SUFFIX));
            } else {
                entries.add(value.getAsString());
            }
        }
        return array(entries);
    }

    /** Every recipe the recipe providers wrote, grouped by recipe type. */
    private JsonObject recipes(Recording recording) {
        String folder = Registries.elementsDirPath(Registries.RECIPE) + "/";
        SortedMap<String, Set<String>> groups = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : recording.files(output, folder).entrySet()) {
            String relative = file.getKey();
            int slash = relative.indexOf('/');
            String id = relative.substring(0, slash) + ":"
                    + relative.substring(slash + 1 + folder.length(), relative.length() - ".json".length());
            JsonObject recipe = parse(file.getValue());
            String type = recipe.get("type").getAsString();
            groups.computeIfAbsent(type, _ -> new TreeSet<>()).add(id);
        }
        JsonObject json = new JsonObject();
        groups.forEach((group, ids) -> json.add(group, array(ids)));
        return json;
    }

    /** The mod's entries in every datapack (dynamic) registry: veins, worldgen features, biome modifiers, fuels. */
    private JsonObject datapackEntries(HolderLookup.Provider lookup) {
        SortedMap<String, Set<String>> entries = new TreeMap<>();
        lookup.listRegistries().forEach(registry -> {
            ResourceKey<? extends Registry<?>> key = registry.key();
            if (BuiltInRegistries.REGISTRY.containsKey(key.identifier())) {
                return;
            }
            registry.listElementIds().forEach(element -> {
                if (element.identifier().getNamespace().equals(content.modId())) {
                    entries.computeIfAbsent(key.identifier().toString(), _ -> new TreeSet<>())
                            .add(element.identifier().toString());
                }
            });
        });
        JsonObject json = new JsonObject();
        entries.forEach((registry, ids) -> json.add(registry, array(ids)));
        return json;
    }

    private static JsonObject parse(byte[] file) {
        return JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonArray array(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonObject object(Map<String, String> values) {
        JsonObject json = new JsonObject();
        values.forEach(json::addProperty);
        return json;
    }

    /** Keeps what a provider writes, by path, instead of writing it; providers write from several threads. */
    private static final class Recording implements CachedOutput {
        private final Map<Path, byte[]> files = new ConcurrentSkipListMap<>();

        @Override
        public void writeIfNeeded(Path path, byte[] input, HashCode hash) {
            files.put(path, input);
        }

        /**
         * The recorded data pack files under {@code data/<namespace>/<folder>}, keyed by their path below
         * {@code data/}.
         */
        SortedMap<String, byte[]> files(PackOutput output, String folder) {
            Path root = output.getOutputFolder(PackOutput.Target.DATA_PACK);
            SortedMap<String, byte[]> found = new TreeMap<>();
            for (Map.Entry<Path, byte[]> file : files.entrySet()) {
                if (!file.getKey().startsWith(root)) {
                    continue;
                }
                String relative = root.relativize(file.getKey()).toString().replace('\\', '/');
                int slash = relative.indexOf('/');
                if (slash > 0 && relative.startsWith(folder, slash + 1)) {
                    found.put(relative, file.getValue());
                }
            }
            return found;
        }
    }
}
