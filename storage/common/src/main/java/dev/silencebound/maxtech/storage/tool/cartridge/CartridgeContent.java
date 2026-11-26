package dev.silencebound.maxtech.storage.tool.cartridge;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/** The puzzle cartridge's item and texts (M6e area E). */
public final class CartridgeContent {
    public static final RegistryEntry<Item, PuzzleCartridgeItem> PUZZLE_CARTRIDGE = ModContent.CONTENT
            .item("puzzle_cartridge", p -> new PuzzleCartridgeItem(p.stacksTo(16)))
            .model(ItemModel.flat(MaxtechStorage.id("item/puzzle_cartridge"))).lang("Puzzle Cartridge")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item, 2).pattern("PMP").pattern("PWP")
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('M', MaterialTags.component("basic_memory_processor")).define('W', MaterialTags.COPPER_WIRE)
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(PuzzleCartridgeItem.DESCRIPTION_KEY,
                "Use it to solve a research puzzle. Solving it uses the cartridge up and gives your team part of a skip token's points");
        ModContent.CONTENT.translation(PuzzleCartridgeItem.BLANK_KEY, "Blank: using it picks a puzzle");
        ModContent.CONTENT.translation(PuzzleCartridgeItem.WRITTEN_KEY, "Carries: %s (%s)");
        ModContent.CONTENT.translation(PuzzleCartridgeItem.RESEARCH_OFF_KEY, "Research is off on this server");
        ModContent.CONTENT.translation(PuzzleCartridgeItem.NO_PUZZLE_KEY,
                "No puzzle can be opened from this cartridge");
        ModContent.CONTENT.translation(PuzzleCartridgeItem.SOLVED_KEY,
                "Cartridge solved and used up; your team earned %s research points");
    }

    private CartridgeContent() {
    }

    /** Loads the class during mod construction so the entries above are queued, and hooks the solved listener. */
    public static void init() {
        PuzzleSessions.onSolved(PuzzleCartridgeItem::solved);
        Platform.get().lifecycle().onServerStopped(server -> PuzzleCartridgeItem.clear());
    }
}
