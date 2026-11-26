package dev.silencebound.maxtech.storage.crafting.assembler;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jspecify.annotations.Nullable;

/**
 * Lays a crafting pattern's inputs out on a grid (M7 C1). Patterns list only their filled cells, in grid order, so the
 * grid comes from the recipe: a shaped recipe's non-empty cells take the inputs in order (then the same mirrored, for a
 * pattern encoded from a mirrored layout); every recipe also gets the inputs row by row on a 3 x 3 grid, which is all a
 * shapeless recipe needs. An input of amount n fills n cells. The assembler uses the first layout the recipe matches.
 * Server thread.
 */
final class AssemblerGrid {
    /** One layout: {@code width} x {@code height} cells, row by row, and which cells hold a returned input. */
    static final class Layout {
        private final int width;
        private final int height;
        private final ItemStack[] cells;
        private final boolean[] returned;

        Layout(int width, int height, ItemStack[] cells, boolean[] returned) {
            this.width = width;
            this.height = height;
            this.cells = cells;
            this.returned = returned;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        /** The cells, shared: copy before changing them. */
        ItemStack[] cells() {
            return cells;
        }

        boolean[] returned() {
            return returned;
        }
    }

    private AssemblerGrid() {
    }

    /** The layouts to try for {@code pattern} with {@code keys} as its inputs; empty when nothing fits a 3 x 3 grid. */
    static List<Layout> layouts(@Nullable CraftingRecipe recipe, CraftingPattern pattern, StackKey[] keys) {
        List<ItemStack> stacks = new ArrayList<>(9);
        List<Boolean> returned = new ArrayList<>(9);
        for (int i = 0; i < keys.length; i++) {
            PatternInput input = pattern.inputs().get(i);
            if (!(keys[i] instanceof ItemKey item) || stacks.size() + input.amount() > 9) {
                return List.of();
            }
            for (long n = 0; n < input.amount(); n++) {
                stacks.add(item.toStack(1));
                returned.add(input.returned());
            }
        }
        List<Layout> layouts = new ArrayList<>(3);
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            List<Optional<Ingredient>> ingredients = shaped.pattern.ingredients();
            int filled = 0;
            for (Optional<Ingredient> ingredient : ingredients) {
                filled += ingredient.isPresent() ? 1 : 0;
            }
            if (filled == stacks.size() && ingredients.size() == width * height) {
                Layout plain = shaped(width, height, ingredients, stacks, returned);
                layouts.add(plain);
                layouts.add(mirrored(plain));
            }
        }
        ItemStack[] cells = new ItemStack[9];
        boolean[] marks = new boolean[9];
        for (int i = 0; i < 9; i++) {
            cells[i] = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
            marks[i] = i < stacks.size() && returned.get(i);
        }
        layouts.add(new Layout(3, 3, cells, marks));
        return layouts;
    }

    private static Layout shaped(int width, int height, List<Optional<Ingredient>> ingredients, List<ItemStack> stacks,
            List<Boolean> returned) {
        ItemStack[] cells = new ItemStack[width * height];
        boolean[] marks = new boolean[width * height];
        int next = 0;
        for (int i = 0; i < cells.length; i++) {
            if (ingredients.get(i).isPresent()) {
                cells[i] = stacks.get(next);
                marks[i] = returned.get(next);
                next++;
            } else {
                cells[i] = ItemStack.EMPTY;
            }
        }
        return new Layout(width, height, cells, marks);
    }

    private static Layout mirrored(Layout layout) {
        int width = layout.width();
        ItemStack[] cells = new ItemStack[layout.cells().length];
        boolean[] marks = new boolean[cells.length];
        for (int y = 0; y < layout.height(); y++) {
            for (int x = 0; x < width; x++) {
                cells[y * width + x] = layout.cells()[y * width + width - 1 - x];
                marks[y * width + x] = layout.returned()[y * width + width - 1 - x];
            }
        }
        return new Layout(width, layout.height(), cells, marks);
    }
}
