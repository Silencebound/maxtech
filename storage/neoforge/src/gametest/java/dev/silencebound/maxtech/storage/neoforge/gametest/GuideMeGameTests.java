package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.dev.GuideScenes;
import dev.silencebound.maxtech.storage.guide.GuideConfigText;
import dev.silencebound.maxtech.storage.guide.GuideContent;
import dev.silencebound.maxtech.storage.neoforge.guide.GuideWebExport;
import dev.silencebound.maxtech.storage.neoforge.guide.StorageGuide;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import guideme.compiler.MdAstNodeAdapter;
import guideme.compiler.PageCompiler;
import guideme.compiler.ParsedGuidePage;
import guideme.compiler.TagCompiler;
import guideme.extensions.ExtensionCollection;
import guideme.internal.extensions.DefaultExtensions;
import guideme.libs.mdast.mdx.model.MdxJsxAttribute;
import guideme.libs.mdast.mdx.model.MdxJsxAttributeNode;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import guideme.libs.mdast.model.MdAstNode;
import guideme.libs.mdast.model.MdAstParent;
import guideme.scene.SceneTagCompiler;
import guideme.scene.element.SceneElementTagCompiler;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * Storage's pages through GuideME's own Markdown parser ({@code PageCompiler.parse}), on the server: every page parses,
 * every tag is one GuideME or Storage ({@link StorageGuide#tagCompilers()}) draws, scene elements sit only in a
 * {@code <GameScene>}, every {@code <Config>} names a Storage server config value, every scene {@code <Block>} names a
 * block with the properties written, and every {@code <ImportStructure>} file ships and holds its blocks. GuideME
 * compiles pages only on a client, where it draws an unknown tag as red text instead of failing; this catches it in CI.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class GuideMeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final String[] NAMES = {"guide_guideme_tags", "guide_guideme_web"};
    /**
     * The tags GuideME 26.1.15-beta's website generator draws ({@code WebPageCompiler.compileCustomElement}); any other
     * tag is drawn as an error on the website unless {@link GuideWebExport} rewrites it at export. Not
     * {@code SubPages}: its attribute record has no constructor in the published jar, so the generator fails on it.
     */
    private static final Set<String> WEB_TAGS = Set.of("BlockImage", "CategoryIndex", "Column", "ItemLink", "ItemImage",
            "ItemIcon", "ItemGrid", "Recipe", "RecipeFor", "RecipesFor", "Row", "GameScene");
    /**
     * The scene children the website generator accepts ({@code WebPageCompiler.compileGameScene}), without the
     * annotations, which {@link GuideWebExport#DROPPED} leaves out.
     */
    private static final Set<String> WEB_SCENE_ELEMENTS = Set.of("ImportStructure", "Block", "IsometricCamera");
    /** The structure files a page may import: the scenes {@link GuideScenes} builds ({@code guide_scene_*}). */
    private static final Set<String> SCENE_FILES = sceneFiles();
    /** The facings whose front the default scene camera sees ({@code Page#facing}). */
    private static final Set<String> SCENE_FACINGS = Set.of("north", "east", "up");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAMES[0], () -> GuideMeGameTests::tags);
        FUNCTIONS.register(NAMES[1], () -> GuideMeGameTests::web);
    }

    private GuideMeGameTests() {
    }

    private static Set<String> sceneFiles() {
        Set<String> files = new HashSet<>();
        for (GuideScenes.Scene scene : GuideScenes.Scene.values()) {
            files.add(scene.file());
        }
        return Set.copyOf(files);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("guideme"));
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 20, 0, true)));
        }
    }

    private static void tags(GameTestHelper helper) {
        ExtensionCollection.Builder builder = ExtensionCollection.builder();
        DefaultExtensions.addAll(builder, Set.of());
        for (TagCompiler tag : StorageGuide.tagCompilers()) {
            builder.add(TagCompiler.EXTENSION_POINT, tag);
        }
        ExtensionCollection extensions = builder.build();
        Set<String> tags = new HashSet<>();
        for (TagCompiler compiler : extensions.get(TagCompiler.EXTENSION_POINT)) {
            tags.addAll(compiler.getTagNames());
        }
        Set<String> sceneElements = new HashSet<>();
        for (SceneElementTagCompiler compiler : extensions.get(SceneElementTagCompiler.EXTENSION_POINT)) {
            sceneElements.addAll(compiler.getTagNames());
        }
        helper.assertTrue(tags.contains("Config") && tags.contains("ItemLink"), "tag names found: " + tags);

        List<String> problems = new ArrayList<>();
        Map<Identifier, byte[]> assets = new TreeMap<>();
        Map<Identifier, String> pages = readGuideFolder(assets, problems);
        helper.assertTrue(pages.size() >= 40, "found " + pages.size() + " pages");
        Set<String> used = new HashSet<>();
        for (Map.Entry<Identifier, String> page : pages.entrySet()) {
            ParsedGuidePage parsed;
            try {
                parsed = PageCompiler.parse(MaxtechStorage.MOD_ID, "en_us", page.getKey(), page.getValue());
            } catch (RuntimeException e) {
                problems.add(page.getKey() + ": GuideME cannot parse it (" + e.getMessage() + ")");
                continue;
            }
            Page context = new Page(page.getKey(), tags, sceneElements, assets, used, problems);
            context.walk(parsed.getAstRoot(), false);
        }
        helper.assertTrue(used.contains(SceneTagCompiler.TAG_NAME), "the pages show a <GameScene>");
        helper.assertValueEqual(List.of(), problems, "GuideME page problems");
        helper.succeed();
    }

    /**
     * The guide website: every tag on a page is one GuideME's website draws or one {@link GuideWebExport} rewrites, and
     * the rewrite turns {@code <Config>} (inline and on its own line) and {@code <KeyBind>} into bold text that
     * GuideME's own JSON reader still reads.
     */
    private static void web(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        Map<Identifier, String> pages = readGuideFolder(new TreeMap<>(), problems);
        for (Map.Entry<Identifier, String> page : pages.entrySet()) {
            webTags(page.getKey(),
                    PageCompiler.parse(MaxtechStorage.MOD_ID, "en_us", page.getKey(), page.getValue()).getAstRoot(),
                    false, problems);
        }
        helper.assertValueEqual(List.of(), problems, "tags the guide website cannot draw");

        String key = "network.channels.tier1";
        String expected = GuideWebExport.configText(key, "channels").orElseThrow();
        String config = "<Config key=\"" + key + "\" unit=\"channels\" />";
        // The bare "interactive" attribute: GuideME's export writes it without a value, which its reader rejects;
        // the annotation is dropped.
        String text = "Up to " + config + ", key <KeyBind id=\"key.guideme.guide\" />.\n\n" + config + "\n\n"
                + "<GameScene zoom=\"2\" interactive>\n<DiamondAnnotation pos=\"0 0 0\">\nmarker\n"
                + "</DiamondAnnotation>\n</GameScene>\n";
        ParsedGuidePage parsed = PageCompiler.parse(MaxtechStorage.MOD_ID, "en_us", MaxtechStorage.id("web.md"), text);
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(MdAstNode.class, new MdAstNodeAdapter()).create();
        JsonObject root = new JsonObject();
        root.add("astRoot", gson.toJsonTree(parsed.getAstRoot(), MdAstNode.class));
        new GuideWebExport(id -> id.equals("key.guideme.guide") ? Optional.of("G") : Optional.empty())
                .postProcess(root);
        String json = root.toString();
        helper.assertFalse(
                json.contains("\"Config\"") || json.contains("\"KeyBind\"") || json.contains("\"DiamondAnnotation\""),
                "tags left: " + json);
        helper.assertTrue(json.indexOf(expected) != json.lastIndexOf(expected), expected + " twice in " + json);
        MdAstNode back = gson.fromJson(root.get("astRoot"), MdAstNode.class);
        helper.assertTrue(back.toText().contains("Up to " + expected + ", key G."), "read back: " + back.toText());
        helper.succeed();
    }

    private static void webTags(Identifier page, Object node, boolean inScene, List<String> problems) {
        boolean scene = false;
        if (node instanceof MdxJsxElementFields el) {
            String name = Objects.requireNonNullElse(el.name(), "");
            boolean drawn = inScene
                    ? WEB_SCENE_ELEMENTS.contains(name) || GuideWebExport.DROPPED.contains(name)
                    : WEB_TAGS.contains(name) || GuideWebExport.TAGS.contains(name)
                            || name.toLowerCase(Locale.ROOT).equals(name); // lower case: written out as HTML
            if (!drawn) {
                problems.add(page + ": <" + name + "> is not drawn on the guide website");
            }
            scene = name.equals(SceneTagCompiler.TAG_NAME);
            for (Object child : el.children()) {
                webTags(page, child, scene, problems);
            }
        } else if (node instanceof MdAstParent<?> parent) {
            for (Object child : parent.children()) {
                webTags(page, child, false, problems);
            }
        }
    }

    /** One page's walk: which tags it may use, the structure files beside the pages, and where problems go. */
    private record Page(Identifier id, Set<String> tags, Set<String> sceneElements, Map<Identifier, byte[]> assets,
            Set<String> used, List<String> problems) {
        void walk(Object node, boolean inScene) {
            if (node instanceof MdxJsxElementFields el) {
                String name = Objects.requireNonNullElse(el.name(), "");
                used.add(name);
                if (inScene) {
                    if (!sceneElements.contains(name)) {
                        problems.add(id + ": <" + name + "> is no scene element GuideME knows");
                    }
                    sceneElement(el);
                } else if (!tags.contains(name)) {
                    problems.add(id + ": <" + name + "> is no tag GuideME or Storage draws");
                } else if (name.equals("Config")) {
                    config(el);
                }
                boolean scene = name.equals(SceneTagCompiler.TAG_NAME);
                for (Object child : el.children()) {
                    if (scene && !(child instanceof MdxJsxElementFields)) {
                        // GuideME's scene skips anything that is not an element, such as an annotation written on
                        // one line, which Markdown wraps in a paragraph.
                        problems.add(id + ": <GameScene> holds content GuideME skips; write each element on its"
                                + " own lines");
                    }
                    walk(child, scene);
                }
            } else if (node instanceof MdAstParent<?> parent) {
                for (Object child : parent.children()) {
                    walk(child, false);
                }
            }
        }

        private void config(MdxJsxElementFields el) {
            String key = el.getAttributeString("key", "");
            if (GuideConfigText.path(key).flatMap(GuideConfigText::value).isEmpty()) {
                problems.add(id + ": <Config> " + key + " is no Storage server config value");
            }
            attributesOnly(el, Set.of("key", "unit"));
        }

        private void sceneElement(MdxJsxElementFields el) {
            switch (Objects.requireNonNullElse(el.name(), "")) {
                case "Block" -> block(el);
                case "ImportStructure" -> structure(el.getAttributeString("src", ""));
                default -> {
                }
            }
        }

        private void block(MdxJsxElementFields el) {
            String idText = el.getAttributeString("id", "");
            Identifier blockId = idText.contains(":")
                    ? Identifier.tryParse(idText)
                    : Identifier.tryBuild(id.getNamespace(), idText);
            Optional<Block> block = blockId == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(blockId);
            if (block.isEmpty()) {
                problems.add(id + ": <Block> " + idText + " is no block");
                return;
            }
            for (MdxJsxAttributeNode node : el.attributes()) {
                if (node instanceof MdxJsxAttribute attribute && attribute.name.startsWith("p:")) {
                    String property = attribute.name.substring(2);
                    Property<?> found = block.get().getStateDefinition().getProperty(property);
                    if (found == null || found.getValue(attribute.getStringValue()).isEmpty()) {
                        problems.add(
                                id + ": <Block> " + idText + " has no " + property + "=" + attribute.getStringValue());
                    }
                    facing("<Block> " + idText, property, attribute.getStringValue());
                }
            }
        }

        private void structure(String src) {
            byte[] bytes = assets.get(id.withPath(src));
            if (bytes == null || !SCENE_FILES.contains(src)) {
                problems.add(id + ": <ImportStructure> " + src + " is not shipped or no GuideScenes scene");
                return;
            }
            try {
                CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
                if (root.getListOrEmpty("blocks").isEmpty()) {
                    problems.add(id + ": " + src + " holds no blocks");
                }
                for (int i = 0; i < root.getListOrEmpty("palette").size(); i++) {
                    String name = root.getListOrEmpty("palette").getCompoundOrEmpty(i).getStringOr("Name", "");
                    Identifier blockId = Identifier.tryParse(name);
                    if (blockId == null || BuiltInRegistries.BLOCK.getOptional(blockId).isEmpty()) {
                        problems.add(id + ": " + src + " names the unknown block " + name);
                    }
                    CompoundTag properties = root.getListOrEmpty("palette").getCompoundOrEmpty(i)
                            .getCompoundOrEmpty("Properties");
                    facing(src + " " + name, "facing", properties.getStringOr("facing", "north"));
                }
            } catch (IOException e) {
                problems.add(id + ": " + src + " cannot be read (" + e.getMessage() + ")");
            }
        }

        /**
         * GuideME's default scene camera (yaw 225, pitch 30, {@code CameraSettings}) shows a block's north, east and
         * top faces, and our models put a block's front on the side its {@code facing} names, so any other facing turns
         * the front away from the reader. A scene with its own {@code <IsometricCamera>} would need its own rule.
         */
        private void facing(String what, String property, String value) {
            if (property.equals("facing") && !SCENE_FACINGS.contains(value)) {
                problems.add(id + ": " + what + " faces " + value + ", away from the scene camera; use one of "
                        + SCENE_FACINGS);
            }
        }

        private void attributesOnly(MdxJsxElementFields el, Set<String> allowed) {
            for (MdxJsxAttributeNode node : el.attributes()) {
                if (node instanceof MdxJsxAttribute attribute && !allowed.contains(attribute.name)) {
                    problems.add(id + ": <" + el.name() + "> has no attribute " + attribute.name);
                }
            }
        }
    }

    /**
     * Every file under the guide's folder in every loaded mod, as GuideME lists them: pages ({@code .md}, outside
     * language folders) returned, other files into {@code assets}. Client assets read through each mod file's pack,
     * since a server has no client resources.
     */
    static Map<Identifier, String> readGuideFolder(Map<Identifier, byte[]> assets, List<String> problems) {
        String folder = "guides/" + GuideContent.GUIDE.getNamespace() + "/" + GuideContent.GUIDE.getPath();
        Map<Identifier, String> pages = new TreeMap<>();
        PackLocationInfo location = new PackLocationInfo("maxtech_guideme_check", Component.empty(), PackSource.DEFAULT,
                Optional.empty());
        for (IModFileInfo file : ModList.get().getModFiles()) {
            try (PackResources pack = ResourcePackLoader.createPackForMod(file).openPrimary(location)) {
                for (String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                    pack.listResources(PackType.CLIENT_RESOURCES, namespace, folder, (resourceId, resource) -> {
                        Identifier id = resourceId.withPath(resourceId.getPath().substring(folder.length() + 1));
                        try (InputStream in = resource.get()) {
                            byte[] bytes = in.readAllBytes();
                            if (!id.getPath().endsWith(".md")) {
                                assets.put(id, bytes);
                            } else if (!id.getPath().startsWith("_")) {
                                pages.put(id, new String(bytes, StandardCharsets.UTF_8));
                            }
                        } catch (IOException e) {
                            problems.add(id + ": cannot read (" + e.getMessage() + ")");
                        }
                    });
                }
            }
        }
        return pages;
    }
}
