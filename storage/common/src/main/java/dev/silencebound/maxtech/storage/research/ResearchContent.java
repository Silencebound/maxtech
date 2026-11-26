package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import java.util.List;
import java.util.Set;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * Research's registrations: the research terminal (block, item, block entity type), the view payload, the skip token
 * item, the processors tag the {@code processors_made} task counts, the shipped tree's names and the research messages.
 * The tree itself is data ({@link ResearchNode#REGISTRY}).
 */
public final class ResearchContent {
    /** Items the {@code processors_made} task counts; basic and advanced. */
    public static final TagKey<Item> PROCESSORS = TagKey.create(Registries.ITEM, MaxtechStorage.id("processors"));

    public static final RegistryEntry<Item, SkipTokenItem> SKIP_TOKEN = ModContent.CONTENT
            .item("research_skip_token", p -> new SkipTokenItem(p.rarity(Rarity.UNCOMMON)))
            .model(ItemModel.flat(MaxtechStorage.id("item/research_skip_token"))).lang("Research Skip Token")
            .creativeTab().register();

    /** The two forms of the research terminal, for the conversion recipes. */
    public static final TagKey<Item> TERMINAL_TAG = MaterialTags.component("research_terminal");
    public static final TagKey<Item> TERMINAL_PART_TAG = MaterialTags.component("research_terminal_part");

    public static final BlockEntry<ResearchTerminalBlock, BlockItem> TERMINAL = ModContent.CONTENT
            .block("research_terminal", ResearchTerminalBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .model(BlockModel.orientable(MaxtechStorage.id("block/research_terminal_front"),
                    MaxtechStorage.id("block/terminal/bezel"), MaxtechStorage.id("block/terminal/bezel")))
            .lang("Research Terminal").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(TERMINAL_TAG)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PDP").pattern("LOL")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .define('O', MaterialTags.component("oscillator"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_logic_processor",
                            recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ResearchTerminalBlockEntity>> TERMINAL_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("research_terminal",
                    () -> new BlockEntityType<>(ResearchTerminalBlockEntity::new, Set.of(TERMINAL.get())));

    /** The research terminal as a part on a cable's face (area P); converts 1:1 with the block. */
    public static final RegistryEntry<Item, PartItem> TERMINAL_PART_ITEM = ModContent.CONTENT
            .item("research_terminal_part", p -> new PartItem(p, () -> ResearchContent.TERMINAL_PART))
            .lang("Research Terminal Part").tag(TERMINAL_PART_TAG)
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item).requires(TERMINAL_TAG)
                    .unlockedBy("has_research_terminal", recipes.has(TERMINAL_TAG)).save(recipes.output()))
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, TERMINAL.item().get())
                    .requires(TERMINAL_PART_TAG)
                    .unlockedBy("has_research_terminal_part", recipes.has(TERMINAL_PART_TAG)).save(recipes.output(),
                            ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("research_terminal_from_part"))))
            .creativeTab().register();
    public static final PartType TERMINAL_PART = PartTypes.register(new PartType(MaxtechStorage.id("research_terminal"),
            TERMINAL_PART_ITEM, List.of(new AABB(2, 2, 0, 14, 14, 2)), ResearchTerminalPart::new));

    static {
        ModContent.CONTENT.translation(ResearchTerminalBlock.NO_POWER_KEY,
                "The research terminal needs an online, powered network");
        ModContent.CONTENT.translation(ResearchTerminalBlock.DENIED_KEY,
                "The network's owner has not given you access");
        ModContent.CONTENT.itemTag(PROCESSORS, MaterialTags.component("basic_logic_processor"),
                MaterialTags.component("basic_memory_processor"), MaterialTags.component("basic_signal_processor"),
                MaterialTags.component("basic_io_processor"), MaterialTags.component("advanced_logic_processor"),
                MaterialTags.component("advanced_memory_processor"),
                MaterialTags.component("advanced_signal_processor"), MaterialTags.component("advanced_io_processor"));
        ModContent.CONTENT.translation(Research.COMPLETE_KEY, "Research complete: %s");
        ModContent.CONTENT.translation(Research.TOKEN_KEY, "Your team earned a research skip token");
        ModContent.CONTENT.translation(SkipTokenItem.USED_KEY, "Skip token added; your team has %s");
        for (DraftTree.Draft draft : DraftTree.NODES) {
            ModContent.CONTENT.translation(ResearchNode.nameKey(draft.id()), draft.name());
        }
        for (int tier = 0; tier < DraftTree.TIERS.size(); tier++) {
            ModContent.CONTENT.translation(ResearchNode.tierKey(tier),
                    "Tier " + tier + ": " + DraftTree.TIERS.get(tier));
            ModContent.CONTENT.translation(ResearchNode.tierMaterialKey(tier), DraftTree.TIER_MATERIALS.get(tier));
        }
        java.util.Set<Identifier> unlocks = new java.util.LinkedHashSet<>();
        for (DraftTree.Draft draft : DraftTree.NODES) {
            unlocks.addAll(draft.node().unlocks());
        }
        for (Identifier unlock : unlocks) {
            ModContent.CONTENT.translation(ResearchNode.unlockKey(unlock), DraftTree.unlockName(unlock));
        }
        for (String branch : DraftTree.BRANCHES) {
            ModContent.CONTENT.translation(ResearchNode.branchKey(branch), DraftTree.branchName(branch));
        }
    }

    private ResearchContent() {
    }

    /** Loads the class during mod construction so the entries above are queued, and hooks research into events. */
    public static void init() {
        ResearchTexts.init();
        Research.init();
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, ResearchView.TYPE, ResearchView.STREAM_CODEC,
                (view, player) -> {
                    if (player.containerMenu instanceof ResearchMenu menu && menu.containerId == view.containerId()) {
                        menu.accept(view);
                    }
                });
    }
}
