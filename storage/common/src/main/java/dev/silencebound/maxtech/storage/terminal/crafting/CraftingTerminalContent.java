package dev.silencebound.maxtech.storage.terminal.crafting;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * The crafting terminal (B09 item 13): block, part, block entity type, menu type (the terminal menu with its crafting
 * panel, drawn by the terminal screen), recipes and texts. The block is an upgrade of the storage terminal: a terminal
 * with a crafting table and a logic processor; the two forms convert 1:1.
 */
public final class CraftingTerminalContent {
    public static final TagKey<Item> TERMINAL_TAG = MaterialTags.component("crafting_terminal");
    public static final TagKey<Item> TERMINAL_PART_TAG = MaterialTags.component("crafting_terminal_part");
    /** NeoForge's common tag of crafting tables. */
    private static final TagKey<Item> CRAFTING_TABLES = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "player_workstations/crafting_tables"));

    public static final String CLEAR_KEY = key("clear");
    public static final String RESULT_HINT_KEY = key("result.hint");
    public static final String VIEWER_MISSING_KEY = key("viewer_missing");

    public static final BlockEntry<CraftingTerminalBlock, BlockItem> TERMINAL = ModContent.CONTENT
            .block("crafting_terminal", CraftingTerminalBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .model(BlockModel.orientable(MaxtechStorage.id("block/crafting_terminal_front"),
                    MaxtechStorage.id("block/terminal/bezel"), MaxtechStorage.id("block/terminal/bezel")))
            .lang("Crafting Terminal").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(TERMINAL_TAG)
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item)
                    .requires(TerminalContent.TERMINAL_TAG).requires(CRAFTING_TABLES)
                    .requires(MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_terminal", recipes.has(TerminalContent.TERMINAL_TAG)).save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<CraftingTerminalBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("crafting_terminal",
                    () -> new BlockEntityType<>(CraftingTerminalBlockEntity::new, Set.of(TERMINAL.get())));

    /** The crafting terminal as a part on a cable's face; Storage's model provider draws it. */
    public static final RegistryEntry<Item, PartItem> TERMINAL_PART_ITEM = ModContent.CONTENT
            .item("crafting_terminal_part", p -> new PartItem(p, () -> CraftingTerminalContent.TERMINAL_PART))
            .lang("Crafting Terminal Part").tag(TERMINAL_PART_TAG)
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item).requires(TERMINAL_TAG)
                    .unlockedBy("has_crafting_terminal", recipes.has(TERMINAL_TAG)).save(recipes.output()))
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, TERMINAL.item().get())
                    .requires(TERMINAL_PART_TAG)
                    .unlockedBy("has_crafting_terminal_part", recipes.has(TERMINAL_PART_TAG)).save(recipes.output(),
                            ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("crafting_terminal_from_part"))))
            .creativeTab().register();
    public static final PartType TERMINAL_PART = PartTypes.register(new PartType(MaxtechStorage.id("crafting_terminal"),
            TERMINAL_PART_ITEM, List.of(new AABB(2, 2, 0, 14, 14, 2)), CraftingTerminalPart::new));

    /** The terminal menu with the crafting panel; the loader's client setup gives it the terminal screen. */
    public static final RegistryEntry<MenuType<?>, MenuType<TerminalMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("crafting_terminal", () -> Platform.get().menus().menuType(TerminalMenu::fromNetworkCrafting));

    static {
        ModContent.CONTENT.translation(CLEAR_KEY, "Clear the grid: its items go back to the network");
        ModContent.CONTENT.translation(RESULT_HINT_KEY, "Shift-click: craft up to %s times, refilled from the network");
        ModContent.CONTENT.translation(VIEWER_MISSING_KEY, "Missing items leave their cells empty");
    }

    private CraftingTerminalContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    /** The grid's stacks as item icons in overlays, one per kind; at most nine, so cheap enough for every glance. */
    static void glance(TerminalGrid grid, BlockInfoSink sink) {
        if (grid.isEmpty()) {
            return;
        }
        List<InfoStack> stacks = new ArrayList<>(TerminalGrid.SIZE);
        for (int cell = 0; cell < TerminalGrid.SIZE; cell++) {
            ItemStack stack = grid.getItem(cell);
            if (stack.isEmpty()) {
                continue;
            }
            ItemKey key = ItemKey.of(stack);
            boolean merged = false;
            for (int i = 0; i < stacks.size(); i++) {
                InfoStack seen = stacks.get(i);
                if (seen.key().equals(key)) {
                    stacks.set(i, new InfoStack(key, seen.amount() + stack.getCount()));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                stacks.add(new InfoStack(key, stack.getCount()));
            }
        }
        NetworkInfo.stacks(stacks, sink);
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".crafting_terminal." + name;
    }
}
