package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.guide.GuideContent;
import dev.silencebound.maxtech.storage.guide.StorageManualItem;
import dev.silencebound.maxtech.storage.neoforge.datagen.StorageAdvancements;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.registry.ModCreativeTabs;
import guideme.compiler.FrontmatterNavigation;
import guideme.compiler.PageCompiler;
import guideme.compiler.ParsedGuidePage;
import guideme.libs.mdast.MdAstVisitor;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import guideme.libs.mdast.model.MdAstHeading;
import guideme.libs.mdast.model.MdAstLink;
import guideme.libs.mdast.model.MdAstNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jspecify.annotations.Nullable;

/**
 * The Storage manual on the server, with the pages as every loaded mod ships them (the generated pages too): they parse
 * with GuideME's own page parser ({@code PageCompiler.parse}, which needs no client), every item they name is
 * registered, every {@code <Config>} names a config value, page links lead to shipped pages, the navigation is the
 * expected tree and the key items open a page. The manual itself has its recipe and creative tab entry, and using it on
 * the server does nothing but succeed. Lib's guide and its parser are retired (MGM item 3).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class GuideGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final String[] NAMES = {"guide_pages", "guide_item", "guide_advancements", "guide_covers_items"};
    /** GuideME's folder for the guide {@code maxtech_storage:guide}, under a mod's {@code assets/<namespace>/}. */
    private static final String FOLDER = "guides/" + GuideContent.GUIDE.getNamespace() + "/"
            + GuideContent.GUIDE.getPath();
    /** What GuideME puts on a page it cannot parse ({@code PageCompiler.buildErrorPage}). */
    private static final String PARSE_ERROR = "PARSING ERROR";
    /**
     * Key mappings a {@code <KeyBind>} may name; only the client knows its key mappings. GuideME's hold key
     * ({@code OpenGuideHotkey}, a client class).
     */
    private static final Set<String> KEYS = Set.of("key.guideme.guide");
    /** Top-level pages of the manual. */
    private static final List<String> TOP_LEVEL = List.of("index.md", "getting_started.md", "materials.md",
            "machines.md", "cables.md", "cells.md", "terminals.md", "ports.md", "research.md", "security.md",
            "controller.md", "signal.md", "logic_controller.md");
    /** Sub-pages as {@code page, parent}. */
    private static final List<List<String>> CHILDREN = List.of(List.of("parts.md", "cables.md"),
            List.of("workbench.md", "cells.md"), List.of("research_puzzles.md", "research.md"),
            List.of("condition_switch.md", "ports.md"), List.of("sensor.md", "ports.md"),
            List.of("energy.md", "getting_started.md"), List.of("network_tools.md", "cables.md"),
            List.of("cable_layer.md", "cables.md"), List.of("hubs.md", "cables.md"),
            List.of("server_tools.md", "security.md"), List.of("logic_editor.md", "logic_controller.md"),
            List.of("logic_nodes.md", "logic_controller.md"), List.of("labels.md", "logic_controller.md"),
            List.of("logic_budget.md", "logic_controller.md"), List.of("logic_remote.md", "logic_controller.md"));
    /**
     * Items a player is sent to the manual for: each must open a page with the hold key. A new device or machine added
     * without a page fails here.
     */
    private static final List<String> ITEM_PAGES = List.of("storage_cable_tier_1", "storage_cable_tier_2",
            "storage_controller", "storage_drive", "storage_terminal", "storage_terminal_part", "storage_chest",
            "storage_port_tier_1", "small_storage_cell", "portable_storage_cell", "cell_workbench", "memory_module_2",
            "partition_addon", "fuel_generator", "lithograph", "fabricator", "assembler", "precision_module",
            "research_terminal", "research_skip_token", "lumen_quartz", "deepslate_tantalite_ore", "condition_switch",
            "condition_switch_part", "energy_cell_tier_1", "energy_cell_tier_3", "controller_bandwidth_array",
            "controller_batch_amplifier", "controller_device_bus", "storage_hub_tier_1", "antenna_tier_1",
            "wireless_terminal", "controller_antenna_mount", "controller_wireless_access_mount", "network_goggles",
            "trace_pen", "zone_painter", "pocket_uplink", "restock_belt", "cable_layer", "controller_dimension_bridge",
            "usage_report_book", "quarantine_tag", "sensor", "sensor_part", "logic_controller", "logic_remote",
            "storage_block_tier_1");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAMES[0], () -> GuideGameTests::pages);
        FUNCTIONS.register(NAMES[1], () -> GuideGameTests::guideItem);
        FUNCTIONS.register(NAMES[2], () -> GuideGameTests::advancements);
        FUNCTIONS.register(NAMES[3], () -> GuideGameTests::coversItems);
    }

    private GuideGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("guide"));
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 20, 0, true)));
        }
    }

    /** Every page parses, names real items and config values, links to shipped pages, and sits where expected. */
    private static void pages(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        Map<Identifier, ParsedGuidePage> pages = readPages(problems);
        Map<Identifier, Identifier> itemPages = new TreeMap<>();
        for (ParsedGuidePage page : pages.values()) {
            checkPage(page, pages, problems);
            for (Identifier item : itemIds(page, problems)) {
                if (itemPages.put(item, page.getId()) != null) {
                    problems.add(page.getId() + ": item_ids entry " + item + " opens another page too");
                }
            }
        }
        // GuideME draws an <ItemLink> to an item no page lists in item_ids as red "No page found" text
        for (ParsedGuidePage page : pages.values()) {
            String namespace = page.getId().getNamespace();
            visitElements(page, element -> {
                if ("ItemLink".equals(element.name())) {
                    String text = element.getAttributeString("id", "");
                    if (resolve(text, namespace).filter(itemPages::containsKey).isEmpty()) {
                        problems.add(page.getId() + ": <ItemLink> " + text
                                + " has no page (add it to item_ids, or use <ItemImage> or plain text)");
                    }
                }
            });
        }
        helper.assertValueEqual(List.of(), problems, "guide page problems");
        for (String page : TOP_LEVEL) {
            Identifier id = MaxtechStorage.id(page);
            helper.assertTrue(pages.containsKey(id), "Storage ships guide page " + page);
            helper.assertValueEqual(Optional.empty(), parent(pages.get(id)), page + " should be top-level");
        }
        for (List<String> child : CHILDREN) {
            Identifier id = MaxtechStorage.id(child.get(0));
            helper.assertTrue(pages.containsKey(id), "Storage ships guide page " + id);
            Identifier parent = MaxtechStorage.id(child.get(1));
            helper.assertValueEqual(Optional.of(parent), parent(pages.get(id)), id + " parent");
        }
        for (String item : ITEM_PAGES) {
            helper.assertTrue(itemPages.containsKey(MaxtechStorage.id(item)),
                    item + " should open a manual page (item_ids)");
        }
        helper.succeed();
    }

    private static Optional<Identifier> parent(@Nullable ParsedGuidePage page) {
        FrontmatterNavigation navigation = page == null ? null : page.getFrontmatter().navigationEntry();
        return navigation == null ? Optional.empty() : Optional.ofNullable(navigation.parent());
    }

    /**
     * Every item in Storage's creative tab (the rule for what is not hidden or retired) is named by an
     * {@code <ItemLink>}, {@code <RecipeFor>}, {@code <RecipesFor>} or {@code <ItemIcon>} (inside an
     * {@code <ItemGrid>}) on some page, or listed in a page's {@code item_ids}.
     */
    private static void coversItems(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        Set<Identifier> named = new HashSet<>();
        for (ParsedGuidePage page : readPages(problems).values()) {
            named.addAll(itemIds(page, problems));
            String namespace = page.getId().getNamespace();
            visitElements(page, element -> {
                String tag = element.name() == null ? "" : element.name();
                if (tag.equals("ItemLink") || tag.equals("RecipeFor") || tag.equals("RecipesFor")
                        || tag.equals("ItemIcon")) {
                    resolve(element.getAttributeString("id", ""), namespace).ifPresent(named::add);
                }
            });
        }
        helper.assertValueEqual(List.of(), problems, "guide read problems");
        List<String> missing = new ArrayList<>();
        for (RegistryEntry<Item, ?> item : ModContent.CONTENT.creativeTabItems()) {
            if (!named.contains(item.id())) {
                missing.add(item.id().getPath());
            }
        }
        helper.assertValueEqual(List.of(), missing, "creative tab items no guide page names");
        helper.succeed();
    }

    /**
     * The guide's advancements form one titled and described chain from a root, each earned by having its step's item.
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player on the player list
    private static void advancements(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AdvancementHolder previous = null;
        int lastStep = 0;
        for (StorageAdvancements.Entry entry : StorageAdvancements.STEPS) {
            helper.assertTrue(entry.step() > lastStep, "steps rise: " + entry.id());
            lastStep = entry.step();
            AdvancementHolder holder = helper.getLevel().getServer().getAdvancements().get(entry.id());
            if (holder == null) {
                throw helper.assertionException(Component.literal("advancement " + entry.id() + " is not loaded"));
            }
            helper.assertValueEqual(previous == null ? Optional.empty() : Optional.of(previous.id()),
                    holder.value().parent(), "parent of " + entry.id());
            helper.assertTrue(holder.value().display().isPresent(), entry.id() + " has a display");
            helper.assertTrue(Language.getInstance().has(entry.titleKey()), "lang " + entry.titleKey());
            helper.assertTrue(Language.getInstance().has(entry.descriptionKey()), "lang " + entry.descriptionKey());
            helper.assertFalse(player.getAdvancements().getOrStartProgress(holder).isDone(), entry.id() + " not yet");
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(MaxtechStorage.id(entry.item())));
            player.getInventory().add(stack.copy());
            CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), stack);
            helper.assertTrue(player.getAdvancements().getOrStartProgress(holder).isDone(),
                    entry.id() + " earned by having " + entry.item());
            previous = holder;
        }
        helper.succeed();
    }

    /**
     * The pages of every loaded mod under the guide's folder, as the client's resource manager would list them; a
     * client asset read through each mod file's pack, since a server has no client resources.
     */
    private static Map<Identifier, ParsedGuidePage> readPages(List<String> problems) {
        Map<Identifier, ParsedGuidePage> pages = new TreeMap<>();
        PackLocationInfo location = new PackLocationInfo("maxtech_guide_check", Component.empty(), PackSource.DEFAULT,
                Optional.empty());
        for (IModFileInfo file : ModList.get().getModFiles()) {
            try (PackResources pack = ResourcePackLoader.createPackForMod(file).openPrimary(location)) {
                for (String namespace : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                    pack.listResources(PackType.CLIENT_RESOURCES, namespace, FOLDER, (resourceId, resource) -> {
                        Identifier id = resourceId.withPath(resourceId.getPath().substring(FOLDER.length() + 1));
                        if (!id.getPath().endsWith(".md") || id.getPath().startsWith("_")) {
                            return;
                        }
                        try (InputStream in = resource.get()) {
                            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            if (pages.put(id, PageCompiler.parse(pack.packId(), "en_us", id, text)) != null) {
                                problems.add(id + ": shipped twice");
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

    private static void checkPage(ParsedGuidePage page, Map<Identifier, ParsedGuidePage> shipped,
            List<String> problems) {
        Identifier id = page.getId();
        String namespace = id.getNamespace();
        FrontmatterNavigation navigation = page.getFrontmatter().navigationEntry();
        if (navigation == null) {
            problems.add(id + ": no navigation entry");
        } else {
            if (navigation.iconItemId() != null && !BuiltInRegistries.ITEM.containsKey(navigation.iconItemId())) {
                problems.add(id + ": navigation icon " + navigation.iconItemId() + " is not an item");
            }
            if (navigation.parent() != null && !shipped.containsKey(navigation.parent())) {
                problems.add(id + ": navigation parent " + navigation.parent() + " is not shipped");
            }
        }
        page.getAstRoot().visit(new MdAstVisitor() {
            @Override
            public MdAstVisitor.Result beforeNode(MdAstNode node) {
                if (node instanceof MdAstHeading heading && heading.toText().equals(PARSE_ERROR)) {
                    problems.add(id + ": GuideME cannot parse it");
                } else if (node instanceof MdAstLink link) {
                    checkLink(id, link.url, shipped, problems);
                } else if (node instanceof MdxJsxElementFields element) {
                    checkTag(id, namespace, element, problems);
                }
                return MdAstVisitor.Result.CONTINUE;
            }
        });
    }

    private static void checkTag(Identifier page, String namespace, MdxJsxElementFields element,
            List<String> problems) {
        String name = element.name() == null ? "" : element.name();
        String idText = element.getAttributeString("id", "");
        switch (name) {
            case "ItemLink", "RecipeFor" -> {
                Optional<Identifier> item = resolve(idText, namespace);
                if (item.isEmpty() || !BuiltInRegistries.ITEM.containsKey(item.get())) {
                    problems.add(page + ": <" + name + "> " + idText + " is not an item");
                }
            }
            case "KeyBind" -> {
                if (!KEYS.contains(idText)) {
                    problems.add(page + ": <KeyBind> " + idText + " is not a key mapping the guide knows");
                }
            }
            case "Config" -> {
                String keyText = element.getAttributeString("key", "");
                int colon = keyText.indexOf(':');
                String modId = colon < 0 ? namespace : keyText.substring(0, colon);
                String path = keyText.substring(colon + 1);
                if (GuideConfigValues.source(modId).flatMap(source -> source.value(path)).isEmpty()) {
                    problems.add(page + ": <Config> " + keyText + " is not a config value");
                }
            }
            default -> {
            }
        }
    }

    /**
     * A link to a page of the guide ({@code cells.md}, {@code cells.md#drives}, {@code #drives}) leads to a shipped
     * page and, with a {@code #fragment}, to an anchor on it.
     */
    private static void checkLink(Identifier page, String url, Map<Identifier, ParsedGuidePage> shipped,
            List<String> problems) {
        if (url.contains("://")) {
            return;
        }
        int hash = url.indexOf('#');
        String target = hash < 0 ? url : url.substring(0, hash);
        Optional<Identifier> id;
        if (target.isEmpty()) {
            id = Optional.of(page);
        } else if (target.contains(":")) {
            id = Optional.ofNullable(Identifier.tryParse(target));
        } else {
            String path = URI.create(page.getPath()).resolve(target).normalize().getPath();
            id = Optional.ofNullable(Identifier.tryBuild(page.getNamespace(), path));
        }
        ParsedGuidePage to = id.map(shipped::get).orElse(null);
        if (to == null) {
            problems.add(page + ": link " + url + " leads to no shipped page");
        } else if (hash >= 0 && !anchors(to).contains(url.substring(hash + 1))) {
            problems.add(page + ": link " + url + " names no heading or <a name> on " + to.getId());
        }
    }

    /**
     * The anchors a page offers, as GuideME's {@code AnchorIndexer} matches them: each heading's text in lower case,
     * trimmed, with runs of whitespace as one {@code -}, and the {@code name} of every {@code <a name="...">}.
     */
    private static Set<String> anchors(ParsedGuidePage page) {
        Set<String> anchors = new HashSet<>();
        page.getAstRoot().visit(new MdAstVisitor() {
            @Override
            public MdAstVisitor.Result beforeNode(MdAstNode node) {
                if (node instanceof MdAstHeading heading) {
                    anchors.add(heading.toText().toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", "-"));
                } else if (node instanceof MdxJsxElementFields element && "a".equals(element.name())
                        && element.hasAttribute("name")) {
                    anchors.add(element.getAttributeString("name", ""));
                }
                return MdAstVisitor.Result.CONTINUE;
            }
        });
        return anchors;
    }

    /** The page's {@code item_ids}, resolved in its namespace; an entry that is no item is a problem. */
    private static List<Identifier> itemIds(ParsedGuidePage page, List<String> problems) {
        Object raw = page.getFrontmatter().additionalProperties().get("item_ids");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(page.getId() + ": item_ids is not a list");
            return List.of();
        }
        List<Identifier> ids = new ArrayList<>();
        for (Object entry : list) {
            Optional<Identifier> id = resolve(String.valueOf(entry), page.getId().getNamespace());
            if (id.isEmpty() || !BuiltInRegistries.ITEM.containsKey(id.get())) {
                problems.add(page.getId() + ": item_ids entry " + entry + " is not an item");
            } else {
                ids.add(id.get());
            }
        }
        return ids;
    }

    private static void visitElements(ParsedGuidePage page, Consumer<MdxJsxElementFields> action) {
        page.getAstRoot().visit(new MdAstVisitor() {
            @Override
            public MdAstVisitor.Result beforeNode(MdAstNode node) {
                if (node instanceof MdxJsxElementFields element) {
                    action.accept(element);
                }
                return MdAstVisitor.Result.CONTINUE;
            }
        });
    }

    /** An id as GuideME reads it: without a namespace it is in the page's. */
    private static Optional<Identifier> resolve(String text, String namespace) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional
                .ofNullable(text.contains(":") ? Identifier.tryParse(text) : Identifier.tryBuild(namespace, text));
    }

    /**
     * GuideME is loaded (Storage requires it), the manual opens Storage's guide, whose start page ships in GuideME's
     * folder, a book and a lumen quartz make it, Storage's tab lists it, and using it on the server succeeds and keeps
     * the book (the guide opens only on the using client).
     */
    private static void guideItem(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("guideme"), "GuideME is loaded");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.fromNamespaceAndPath("guideme", "guide")),
                "GuideME's items are registered");
        ItemStack manual = new ItemStack(GuideContent.STORAGE_MANUAL.get());
        if (!(manual.getItem() instanceof StorageManualItem book)) {
            throw helper.assertionException(Component.literal("the manual should be a StorageManualItem"));
        }
        helper.assertValueEqual(MaxtechStorage.id("guide"), book.guide(), "the manual's guide");
        helper.assertTrue(
                StorageManualItem.class.getResource("/assets/maxtech_storage/" + FOLDER + "/index.md") != null,
                "the guide's start page ships in GuideME's folder for its id");

        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("storage_manual"));
        RecipeHolder<?> recipe = helper.getLevel().recipeAccess().byKey(key)
                .orElseThrow(() -> helper.assertionException(Component.literal("the manual's recipe should load")));
        if (!(recipe.value() instanceof ShapelessRecipe shapeless)) {
            throw helper.assertionException(Component.literal("the manual's recipe is shapeless: " + recipe.value()));
        }
        // The c:gems/lumen_quartz ingredient holds Storage's lumen quartz.
        ItemStack quartz = new ItemStack(BuiltInRegistries.ITEM.getValue(MaxtechStorage.id("lumen_quartz")));
        CraftingInput input = CraftingInput.of(2, 1, List.of(new ItemStack(Items.BOOK), quartz));
        helper.assertTrue(shapeless.matches(input, helper.getLevel()), "a book and a lumen quartz make the manual");
        helper.assertFalse(
                shapeless.matches(CraftingInput.of(1, 1, List.of(new ItemStack(Items.BOOK))), helper.getLevel()),
                "a book alone makes nothing");
        helper.assertTrue(shapeless.assemble(input).is(GuideContent.STORAGE_MANUAL.get()), "the result");

        CreativeModeTab tab = ModCreativeTabs.STORAGE.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(helper.getLevel().enabledFeatures(), false,
                helper.getLevel().registryAccess()));
        boolean listed = false;
        for (ItemStack stack : tab.getDisplayItems()) {
            listed |= stack.is(GuideContent.STORAGE_MANUAL.get());
        }
        helper.assertTrue(listed, "Storage's tab should list the manual");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, manual);
        InteractionResult result = manual.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.consumesAction(), "using the manual succeeds: " + result);
        helper.assertTrue(player.getMainHandItem().is(GuideContent.STORAGE_MANUAL.get()), "the manual stays");
        helper.succeed();
    }
}
