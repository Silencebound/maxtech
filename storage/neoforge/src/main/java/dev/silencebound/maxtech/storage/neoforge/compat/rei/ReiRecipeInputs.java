package dev.silencebound.maxtech.storage.neoforge.compat.rei;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternFaces;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTransfer;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.TerminalShortfall;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Turns REI's {@link Display} inputs into what Storage's transfers need: shortfall inputs for the terminal, crafting
 * grid cells and pattern entries. REI's entries are item stacks and NeoForge fluid stacks, read without compiling
 * against REI's entry types.
 */
final class ReiRecipeInputs {
    private static final Set<String> COOKING = Set.of("smelting", "smoking", "blasting");

    private ReiRecipeInputs() {
    }

    /** One shortfall input per display input; an ingredient with no item or fluid has no candidates. */
    static List<TerminalShortfall.Input> shortfallInputs(Display display) {
        List<EntryIngredient> entries = display.getInputEntries();
        List<TerminalShortfall.Input> inputs = new ArrayList<>(entries.size());
        for (EntryIngredient ingredient : entries) {
            List<StackKey> candidates = new ArrayList<>();
            long need = 0;
            for (EntryStack<?> entry : ingredient) {
                Object value = entry.getValue();
                if (value instanceof ItemStack stack && !stack.isEmpty()) {
                    candidates.add(ItemKey.of(stack.copyWithCount(1)));
                    need = Math.max(need, stack.getCount());
                } else if (value instanceof FluidStack fluid && !fluid.isEmpty()) {
                    candidates.add(FluidKey.of(fluid.getFluid(), fluid.getComponentsPatch()));
                    need = Math.max(need, fluid.getAmount());
                }
            }
            inputs.add(new TerminalShortfall.Input(candidates, need));
        }
        return inputs;
    }

    /** The nine cells of a crafting grid, row by row, each with the stacks acceptable there (empty cells empty). */
    static List<List<ItemStack>> gridCells(List<EntryIngredient> organised) {
        List<List<ItemStack>> cells = new ArrayList<>(CraftingGridMenu.GRID_SIZE);
        for (int cell = 0; cell < CraftingGridMenu.GRID_SIZE; cell++) {
            List<ItemStack> stacks = new ArrayList<>();
            if (cell < organised.size()) {
                for (EntryStack<?> entry : organised.get(cell)) {
                    if (stacks.size() < CraftingGridMenus.MAX_CANDIDATES && entry.getValue() instanceof ItemStack stack
                            && !stack.isEmpty()) {
                        stacks.add(stack.copyWithCount(1));
                    }
                }
            }
            cells.add(stacks);
        }
        return cells;
    }

    /** The recipe type Storage records with a pattern, when the display's category maps to one. */
    static Optional<Identifier> patternType(Display display) {
        Identifier category = display.getCategoryIdentifier().getIdentifier();
        if (category.getNamespace().equals("minecraft") && category.getPath().startsWith("plugins/")) {
            String name = category.getPath().substring("plugins/".length());
            if (name.equals("brewing")) {
                return Optional.of(PatternFaces.BREWING);
            }
            if (COOKING.contains(name)) {
                return Optional.of(Identifier.withDefaultNamespace(name));
            }
            if (name.equals("stone_cutting")) {
                return Optional.of(Identifier.withDefaultNamespace("stonecutting"));
            }
            if (name.equals("campfire")) {
                return Optional.of(Identifier.withDefaultNamespace("campfire_cooking"));
            }
        }
        return Optional.empty();
    }

    /** Pattern entries of the first stack of each ingredient, with the item tag the whole ingredient shares if any. */
    static List<PatternTransfer.Entry> patternEntries(List<EntryIngredient> ingredients) {
        List<PatternTransfer.Entry> entries = new ArrayList<>();
        for (EntryIngredient ingredient : ingredients) {
            if (entries.size() >= PatternTransfer.MAX_ENTRIES) {
                break;
            }
            if (ingredient.isEmpty()) {
                continue;
            }
            Object value = ingredient.get(0).getValue();
            if (value instanceof ItemStack stack && !stack.isEmpty()) {
                entries.add(new PatternTransfer.Entry(ItemKey.of(stack.copyWithCount(1)), stack.getCount(),
                        sharedTag(ingredient), 1f));
            } else if (value instanceof FluidStack fluid && !fluid.isEmpty()) {
                entries.add(new PatternTransfer.Entry(FluidKey.of(fluid.getFluid(), fluid.getComponentsPatch()),
                        fluid.getAmount(), Optional.empty(), 1f));
            }
        }
        return entries;
    }

    /**
     * REI keeps no tag on an ingredient, so find one: an item tag that every stack of a multi-item ingredient is in and
     * whose members are exactly those stacks.
     */
    private static Optional<Identifier> sharedTag(EntryIngredient ingredient) {
        if (ingredient.size() < 2) {
            return Optional.empty();
        }
        List<Item> items = new ArrayList<>(ingredient.size());
        for (EntryStack<?> entry : ingredient) {
            if (!(entry.getValue() instanceof ItemStack stack) || stack.isEmpty()) {
                return Optional.empty();
            }
            items.add(stack.getItem());
        }
        for (TagKey<?> candidate : ingredient.get(0).getTagsFor().toList()) {
            if (!candidate.registry().equals(Registries.ITEM)) {
                continue;
            }
            TagKey<Item> tag = TagKey.create(Registries.ITEM, candidate.location());
            boolean exact = BuiltInRegistries.ITEM.get(tag).map(set -> set.size() == items.size()).orElse(false);
            if (exact) {
                boolean all = true;
                for (Item item : items) {
                    all &= item.builtInRegistryHolder().is(tag);
                }
                if (all) {
                    return Optional.of(tag.location());
                }
            }
        }
        return Optional.empty();
    }
}
