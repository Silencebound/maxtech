package dev.silencebound.maxtech.storage.neoforge.compat.jei;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.board.client.BoardScreen;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.port.PortFilter;
import dev.silencebound.maxtech.storage.port.client.PortScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.filter.client.ViewFilterScreen;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jspecify.annotations.Nullable;

/**
 * JEI's ingredients dragged onto Storage's ghost slots: the pattern terminal's draft slots (an item or a fluid sets the
 * slot, B09 item 30), the request board's request slot (an item picks what to ask for), a port's filter slots and the
 * view filter's entries (an item or a fluid sets the entry, B09 item 24). Only the key travels; no item moves.
 */
final class GhostTargets {
    private GhostTargets() {
    }

    /** The view filter's nine entries. */
    static final class ViewFilter implements IGhostIngredientHandler<ViewFilterScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(ViewFilterScreen gui, ITypedIngredient<I> ingredient,
                boolean doStart) {
            List<Target<I>> targets = new ArrayList<>(CellFilter.SIZE);
            if (key(ingredient.getIngredient()) == null) {
                return targets;
            }
            for (int index = 0; index < CellFilter.SIZE; index++) {
                Rect2i area = gui.entryArea(index);
                int target = index;
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return area;
                    }

                    @Override
                    public void accept(I dropped) {
                        StackKey key = key(dropped);
                        if (key != null) {
                            gui.dropOnEntry(target, key);
                        }
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
        }
    }

    /** The key an ingredient stands for: an item or a fluid, one of it; null for anything else. */
    private static @Nullable StackKey key(Object ingredient) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            return ItemKey.of(stack.copyWithCount(1));
        }
        if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) {
            return FluidKey.of(fluid.getFluid(), fluid.getComponentsPatch());
        }
        return null;
    }

    /** The pattern terminal's draft slots the draft's mode uses. */
    static final class Pattern implements IGhostIngredientHandler<TerminalScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(TerminalScreen gui, ITypedIngredient<I> ingredient,
                boolean doStart) {
            List<Target<I>> targets = new ArrayList<>();
            if (key(ingredient.getIngredient()) == null) {
                return targets;
            }
            for (int slot = 0; slot < PatternPanel.SLOTS; slot++) {
                Rect2i area = gui.patternSlotArea(slot);
                if (area != null) {
                    int target = slot;
                    targets.add(new Target<>() {
                        @Override
                        public Rect2i getArea() {
                            return area;
                        }

                        @Override
                        public void accept(I dropped) {
                            StackKey key = key(dropped);
                            if (key != null) {
                                gui.dropOnPattern(target, key);
                            }
                        }
                    });
                }
            }
            return targets;
        }

        @Override
        public void onComplete() {
        }
    }

    /** The request board's request slot: an item to ask for. */
    static final class Board implements IGhostIngredientHandler<BoardScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(BoardScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
            List<Target<I>> targets = new ArrayList<>(1);
            if (!(key(ingredient.getIngredient()) instanceof ItemKey)) {
                return targets;
            }
            Rect2i area = gui.requestSlotArea();
            targets.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I dropped) {
                    if (key(dropped) instanceof ItemKey item) {
                        gui.pick(item);
                    }
                }
            });
            return targets;
        }

        @Override
        public void onComplete() {
        }
    }

    /** A port's filter slots (B09 item 18): an item names itself, a fluid its filled bucket. */
    static final class Port implements IGhostIngredientHandler<PortScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(PortScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
            List<Target<I>> targets = new ArrayList<>(PortFilter.SIZE);
            if (ghost(ingredient.getIngredient()).isEmpty()) {
                return targets;
            }
            for (int slot = 0; slot < PortFilter.SIZE; slot++) {
                Rect2i area = gui.filterSlotArea(slot);
                int target = slot;
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return area;
                    }

                    @Override
                    public void accept(I dropped) {
                        gui.dropOnFilter(target, ghost(dropped));
                    }
                });
            }
            return targets;
        }

        /**
         * What a filter slot holds for an ingredient: the item, or a fluid's filled bucket; empty for anything else.
         */
        private static ItemStack ghost(Object ingredient) {
            if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
                return stack.copyWithCount(1);
            }
            if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) {
                return new ItemStack(fluid.getFluid().getBucket());
            }
            return ItemStack.EMPTY;
        }

        @Override
        public void onComplete() {
        }
    }
}
