package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.content.ContentToggle;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.config.MovedKeys;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.registry.ModCreativeTabs;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchOption;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldKind;
import dev.silencebound.maxtech.storage.worldgen.WorldgenContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The config step: module switches drop their recipes and hide their items (and leave placed devices inert), puzzle
 * difficulty offsets and switches, the placer's blacklist and the ores' dimension tags.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ConfigGameTests {
    static {
        StorageTests.function("config_modules_on_recipes_load", ConfigGameTests::modulesOn);
        StorageTests.function("config_modules_off_hide_recipes_and_items", ConfigGameTests::modulesOff);
        StorageTests.function("config_puzzle_difficulty_offset", ConfigGameTests::puzzleOffset);
        StorageTests.function("config_puzzle_disabled", ConfigGameTests::puzzleDisabled);
        StorageTests.function("config_placer_blacklist", ConfigGameTests::placerBlacklist);
        StorageTests.function("config_ore_dimension_tags", ConfigGameTests::oreDimensions);
        StorageTests.function("config_moved_key_still_read", ConfigGameTests::movedKeyStillRead);
        StorageTests.function("config_presets_unset_moved_keys", ConfigGameTests::presetsUnsetMovedKeys);
        StorageTests.function("config_moved_key_unset_uses_new", ConfigGameTests::movedKeyUnsetUsesNew);
        StorageTestEnvironment.init();
    }

    private ConfigGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("config"));
        StorageTests.test(event, plain, "config_modules_on_recipes_load", StorageTests.SMALL, 20);
        StorageTests.test(event, plain, "config_modules_off_hide_recipes_and_items", StorageTests.SMALL, 20);
        StorageTests.test(event, plain, "config_placer_blacklist", StorageTests.SMALL, 20);
        StorageTests.test(event, plain, "config_ore_dimension_tags", StorageTests.SMALL, 20);
        StorageTests.test(event, plain, "config_presets_unset_moved_keys", StorageTests.SMALL, 20);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("config_puzzle_offset"),
                        new StorageTestEnvironment("research.puzzles.bytePacking.difficultyOffset", "1", false)),
                "config_puzzle_difficulty_offset", StorageTests.SMALL, 20);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("config_puzzle_off"),
                        new StorageTestEnvironment("research.puzzles.maskAlignment.enabled", "false", false)),
                "config_puzzle_disabled", StorageTests.SMALL, 20);
        StorageTests
                .test(event,
                        event.registerEnvironment(MaxtechStorage.id("config_moved_key"),
                                new StorageTestEnvironment(StorageTestEnvironment.MOVED_EFFICIENCY
                                        + ";controller.cards.efficiencyCore.percent", "30;20", false)),
                        "config_moved_key_still_read", StorageTests.SMALL, 20);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("config_moved_key_unset"),
                        new StorageTestEnvironment("controller.cards.efficiencyCore.percent", "20", false)),
                "config_moved_key_unset_uses_new", StorageTests.SMALL, 20);
    }

    /** A module and the recipes it gates, by id. */
    private record Gated(StorageModules.Module module, List<String> recipes) {
    }

    private static List<Gated> gated() {
        List<Gated> all = new ArrayList<>();
        all.add(new Gated(StorageModules.PLACER_BREAKER, List.of("placer_port", "placer_port_part",
                "placer_port_from_part", "breaker_port", "breaker_port_part", "breaker_port_from_part")));
        all.add(new Gated(StorageModules.COLLECTOR, List.of("collector", "collector_part", "collector_from_part")));
        all.add(new Gated(StorageModules.NETWORK_ANCHOR, List.of("controller_network_anchor")));
        all.add(new Gated(StorageModules.WIRELESS, List.of("wireless_terminal", "controller_wireless_access_mount")));
        all.add(new Gated(StorageModules.VOID_GEODES, List.of("void_crystal_block", "lithograph/void_lattice",
                "memory_module_5", "memory_module_6", "void_module", "controller_dimension_bridge")));
        for (P2PKind kind : P2PKind.values()) {
            String name = kind.blockName();
            all.add(new Gated(StorageModules.p2p(kind), List.of(name, name + "_part", name + "_from_part")));
        }
        return all;
    }

    /** With every module on, each gated recipe loaded and its conditions hold. */
    private static void modulesOn(GameTestHelper helper) {
        Set<Identifier> loaded = new HashSet<>();
        for (RecipeHolder<?> holder : helper.getLevel().recipeAccess().getRecipes()) {
            loaded.add(holder.id().identifier());
        }
        for (Gated gated : gated()) {
            helper.assertTrue(gated.module().isEnabled(), gated.module().id() + " should be on");
            for (String recipe : gated.recipes()) {
                Identifier id = MaxtechStorage.id(recipe);
                helper.assertTrue(loaded.contains(id), id + " did not load");
                helper.assertTrue(conditionsHold(helper, id, gated.module()), id + " conditions should hold");
            }
        }
        // Ungated content keeps its recipe: the network chest and the linker.
        helper.assertTrue(loaded.contains(MaxtechStorage.id("network_chest")), "network chest recipe");
        helper.assertTrue(loaded.contains(MaxtechStorage.id("p2p_linker")), "linker recipe");
        helper.succeed();
    }

    /**
     * With each module off in turn: its recipes' conditions fail (so they would not load), its items leave the creative
     * tab and JEI's set, and its placed devices go inert; the other modules' items stay.
     */
    private static void modulesOff(GameTestHelper helper) {
        for (Gated gated : gated()) {
            StorageModules.Module module = gated.module();
            module.overrideEnabled(false);
            try {
                helper.assertFalse(ContentToggle.isEnabled(module.id()), module.id() + " should read off");
                for (String recipe : gated.recipes()) {
                    Identifier id = MaxtechStorage.id(recipe);
                    helper.assertFalse(conditionsHold(helper, id, module), id + " conditions hold with it off");
                }
                Set<Item> shown = tabItems(helper);
                // Every item the module's recipes make, by the recipe ids that are item ids.
                List<Item> hidden = new ArrayList<>();
                for (String recipe : gated.recipes()) {
                    if (!recipe.endsWith("_from_part")) {
                        hidden.add(BuiltInRegistries.ITEM
                                .getValue(MaxtechStorage.id(recipe.substring(recipe.indexOf('/') + 1))));
                    }
                }
                if (module == StorageModules.VOID_GEODES) {
                    // Items no recipe of the module makes.
                    hidden.add(VoidCrystalContent.VOID_CRYSTAL.get());
                    hidden.add(VoidCrystalContent.BUDDING_VOID_CRYSTAL.get().asItem());
                    hidden.add(VoidCrystalContent.VOID_CRYSTAL_CLUSTER.get().asItem());
                    helper.assertTrue(!hidden.contains(CellContent.BULK_CELL_CASING.get()), "bulk casing stays");
                    helper.assertTrue(shown.contains(CellContent.BULK_CELL_CASING.get()), "bulk casing still shown");
                }
                for (Item item : hidden) {
                    helper.assertFalse(shown.contains(item), item + " still in the creative tab");
                    helper.assertTrue(ContentToggle.hiddenItems().contains(item), item + " not hidden from JEI");
                }
                helper.assertTrue(shown.contains(WorldContent.NETWORK_CHEST.get().asItem()), "the network chest stays");
            } finally {
                module.overrideEnabled(null);
            }
        }
        StorageModules.PLACER_BREAKER.overrideEnabled(false);
        try {
            helper.assertFalse(StorageModules.enabled(WorldKind.PLACER), "placer inert");
            helper.assertFalse(StorageModules.enabled(WorldKind.BREAKER), "breaker inert");
            helper.assertTrue(StorageModules.enabled(WorldKind.COLLECTOR), "collector unaffected");
            helper.assertTrue(StorageModules.enabled(WorldKind.NETWORK_CHEST), "chest has no switch");
        } finally {
            StorageModules.PLACER_BREAKER.overrideEnabled(null);
        }
        helper.assertTrue(StorageModules.enabled(WorldKind.PLACER), "placer back on");
        helper.succeed();
    }

    /** Byte packing's offset of 1 raises every node's difficulty by one, clamped at hard; others keep theirs. */
    private static void puzzleOffset(GameTestHelper helper) {
        Identifier packing = MaxtechStorage.id("byte_packing");
        helper.assertValueEqual(Research.difficulty(new ResearchOption.Puzzle(packing, 0)), 1, "easy + 1");
        helper.assertValueEqual(Research.difficulty(new ResearchOption.Puzzle(packing, 2)), 2, "hard stays hard");
        helper.assertValueEqual(Research.difficulty(new ResearchOption.Puzzle(MaxtechStorage.id("signal_routing"), 0)),
                0, "signal routing has no offset");
        helper.succeed();
    }

    /**
     * A key moved by M7R item 8 that a server still sets (the old efficiency core percent at 30, the new key at 20) is
     * still read, over the new key, and the config load names its new key in a warning.
     */
    private static void movedKeyStillRead(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.EFFICIENCY_PERCENT.getAsInt(), 30, "the old key's value");
        helper.assertValueEqual(StorageServerConfig.EFFICIENCY_PERCENT_KEY.getAsInt(), 20, "the new key is kept");
        helper.assertTrue(
                MovedKeys.warnings().stream()
                        .anyMatch(warning -> warning.contains("'" + StorageTestEnvironment.MOVED_EFFICIENCY + "'")
                                && warning.contains("'controller.cards.efficiencyCore.percent'")),
                "a warning names the new key");
        helper.succeed();
    }

    /**
     * Both built-in presets ship every key M7R item 8 moved as -1 (unset), so the preset resets an old key nobody
     * edited, and a value for its new key.
     */
    private static void presetsUnsetMovedKeys(GameTestHelper helper) {
        helper.assertFalse(MovedKeys.all().isEmpty(), "no moved keys registered");
        for (String preset : List.of("standalone", "pack")) {
            String path = "maxtech_storage/presets/" + preset + ".json";
            JsonObject values;
            try (Reader reader = new InputStreamReader(
                    Objects.requireNonNull(StorageServerConfig.class.getClassLoader().getResourceAsStream(path), path),
                    StandardCharsets.UTF_8)) {
                values = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (MovedKeys.Moved moved : MovedKeys.all()) {
                JsonElement old = values.get(moved.oldKey());
                helper.assertTrue(old != null && old.getAsLong() == MovedKeys.UNSET,
                        preset + " preset: " + moved.oldKey() + " is " + old + ", not " + MovedKeys.UNSET);
                helper.assertTrue(values.has(moved.newKey()), preset + " preset lacks " + moved.newKey());
            }
        }
        helper.succeed();
    }

    /** With the old key unset (-1, as the presets leave it), the new key applies and nothing warns about it. */
    private static void movedKeyUnsetUsesNew(GameTestHelper helper) {
        helper.assertValueEqual(StorageServerConfig.EFFICIENCY_PERCENT.getAsInt(), 20, "the new key's value");
        helper.assertFalse(
                MovedKeys.warnings().stream()
                        .anyMatch(warning -> warning.contains("'" + StorageTestEnvironment.MOVED_EFFICIENCY + "'")),
                "no warning for an unset old key");
        helper.succeed();
    }

    /** Mask alignment switched off is no longer offered; the other puzzles still are. */
    private static void puzzleDisabled(GameTestHelper helper) {
        helper.assertFalse(Research.offered(new ResearchOption.Puzzle(MaxtechStorage.id("mask_alignment"), 0)),
                "mask alignment should be off");
        helper.assertTrue(Research.offered(new ResearchOption.Puzzle(MaxtechStorage.id("byte_packing"), 0)),
                "byte packing still offered");
        helper.succeed();
    }

    /** The blacklist tag is loaded (empty by default), and only block items outside it are placeable. */
    private static void placerBlacklist(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.get(WorldContent.PLACER_BLACKLIST).isPresent(),
                "placer_blacklist tag not loaded");
        helper.assertTrue(WorldContent.placeable(ItemKey.of(new ItemStack(Items.STONE))), "stone placeable");
        helper.assertFalse(WorldContent.placeable(ItemKey.of(new ItemStack(Items.STICK))), "a stick is no block");
        helper.succeed();
    }

    /** Both ores' dimension tags hold the Overworld's dimension type and not the Nether's or the End's. */
    private static void oreDimensions(GameTestHelper helper) {
        Registry<DimensionType> types = helper.getLevel().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        for (var tag : List.of(WorldgenContent.LUMEN_QUARTZ_DIMENSIONS, WorldgenContent.TANTALITE_DIMENSIONS)) {
            helper.assertTrue(types.getOrThrow(BuiltinDimensionTypes.OVERWORLD).is(tag), "overworld in " + tag);
            helper.assertFalse(types.getOrThrow(BuiltinDimensionTypes.NETHER).is(tag), "nether in " + tag);
            helper.assertFalse(types.getOrThrow(BuiltinDimensionTypes.END).is(tag), "end in " + tag);
        }
        helper.assertTrue(helper.getLevel().dimensionTypeRegistration().is(WorldgenContent.LUMEN_QUARTZ_DIMENSIONS),
                "the test level's dimension type is tagged");
        helper.succeed();
    }

    private static Set<Item> tabItems(GameTestHelper helper) {
        CreativeModeTab tab = ModCreativeTabs.STORAGE.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(helper.getLevel().enabledFeatures(), false,
                helper.getLevel().registryAccess()));
        Set<Item> items = new HashSet<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            items.add(stack.getItem());
        }
        return items;
    }

    /** Whether every condition of the shipped recipe file {@code recipe}, which must name {@code module}, holds. */
    private static boolean conditionsHold(GameTestHelper helper, Identifier recipe, StorageModules.Module module) {
        Identifier file = recipe.withPath(path -> "recipe/" + path + ".json");
        Optional<Resource> resource = helper.getLevel().getServer().getResourceManager().getResource(file);
        helper.assertTrue(resource.isPresent(), file + " is missing");
        JsonObject json;
        try (Reader reader = resource.orElseThrow().openAsReader()) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw helper.assertionException(file + " unreadable: " + e);
        }
        JsonElement conditions = json.get("neoforge:conditions");
        helper.assertTrue(conditions != null, file + " has no conditions");
        helper.assertTrue(String.valueOf(conditions).contains(module.id().toString()),
                file + " has no condition on " + module.id());
        for (ICondition condition : ICondition.LIST_CODEC.parse(JsonOps.INSTANCE, conditions).getOrThrow()) {
            if (!condition.test(ICondition.IContext.EMPTY)) {
                return false;
            }
        }
        return true;
    }
}
