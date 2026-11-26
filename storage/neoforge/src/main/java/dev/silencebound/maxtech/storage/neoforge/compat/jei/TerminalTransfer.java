package dev.silencebound.maxtech.storage.neoforge.compat.jei;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.client.CraftingScreens;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.TerminalShortfall;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jspecify.annotations.Nullable;

/**
 * JEI's "+" on the storage terminal (M7c, C22): the recipe's inputs are checked against what the terminal shows and the
 * player carries. When something is missing and the network can craft it, the button stays usable, the missing slots
 * are marked and a click opens the planner for the missing craftable input, or for all of them as one order list when
 * several are missing (M7C item 6). Otherwise a crafting recipe pulls its ingredients into the inventory (Lib's
 * crafting transfer payload, as before), and other recipe types, which have nothing to pull, show what is missing. On a
 * crafting terminal a crafting recipe always fills the grid with what there is and marks what is missing; the
 * terminal's request button orders it. Client only.
 */
final class TerminalTransfer {
    private static final int MISSING_HIGHLIGHT = 0x66FF0000;
    private static final int CRAFT_BUTTON = 0x8046B2BD;

    private final IRecipeTransferHandlerHelper helper;
    private final TerminalShortfall calculator = new TerminalShortfall();

    TerminalTransfer(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
    }

    /**
     * What the recipe lacks: the missing slots, the first missing input the network can craft and every missing
     * craftable input with its amount.
     */
    private record Shortfall(List<IRecipeSlotView> slots, @Nullable StackKey craftable, long amount,
            List<CraftingPlanner.Root> craftables) {
    }

    @Nullable
    IRecipeTransferError transfer(TerminalMenu menu, IRecipeSlotsView recipeSlots, Player player, boolean crafting,
            boolean maxTransfer, boolean doTransfer) {
        Shortfall shortfall = shortfall(menu, recipeSlots, player);
        if (crafting && menu.craftingPanel() != null) {
            // A crafting terminal fills its grid with what there is; its request button orders the rest.
            if (doTransfer) {
                Platform.get().network().sendToServer(
                        new CraftingGridMenus.Transfer(menu.containerId, cells(recipeSlots), maxTransfer));
                return null;
            }
            return shortfall.slots().isEmpty() ? null : new GridMissing(shortfall.slots());
        }
        if (shortfall.craftable() != null) {
            StackKey craftable = shortfall.craftable();
            if (doTransfer) {
                // JEI closes its screen back to the terminal after a transfer, so open the planner after that.
                long amount = shortfall.amount();
                List<CraftingPlanner.Root> all = shortfall.craftables();
                Minecraft.getInstance().schedule(() -> {
                    if (all.size() > 1) {
                        CraftingScreens.openOrder(all);
                    } else {
                        CraftingScreens.openRequest(craftable, amount, true);
                    }
                });
                return null;
            }
            return new CraftError(shortfall.slots(), craftable);
        }
        if (crafting) {
            if (doTransfer) {
                Platform.get().network().sendToServer(
                        new CraftingGridMenus.Transfer(menu.containerId, cells(recipeSlots), maxTransfer));
            }
            return null;
        }
        return shortfall.slots().isEmpty()
                ? helper.createInternalError()
                : helper.createUserErrorForMissingSlots(Component.translatable(RequestContent.JEI_MISSING_KEY),
                        shortfall.slots());
    }

    private Shortfall shortfall(TerminalMenu menu, IRecipeSlotsView recipeSlots, Player player) {
        List<IRecipeSlotView> views = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        List<TerminalShortfall.Input> inputs = new ArrayList<>(views.size());
        for (IRecipeSlotView view : views) {
            List<StackKey> candidates = new ArrayList<>();
            long need = 0;
            for (ITypedIngredient<?> ingredient : view.getAllIngredientsList()) {
                if (ingredient == null) {
                    continue;
                }
                Object value = ingredient.getIngredient();
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
        TerminalShortfall.Result result = calculator.compute(menu, player, inputs);
        List<IRecipeSlotView> missing = new ArrayList<>(result.missing().size());
        for (int index : result.missing()) {
            missing.add(views.get(index));
        }
        return new Shortfall(missing, result.craftable(), result.amount(), result.craftables());
    }

    /** JEI's crafting category lays out all nine input slots, row by row, empty ones included. */
    private static List<List<ItemStack>> cells(IRecipeSlotsView recipeSlots) {
        List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        List<List<ItemStack>> cells = new ArrayList<>(CraftingGridMenu.GRID_SIZE);
        for (int cell = 0; cell < CraftingGridMenu.GRID_SIZE; cell++) {
            List<ItemStack> stacks = new ArrayList<>();
            if (cell < inputs.size()) {
                IRecipeSlotView view = inputs.get(cell);
                view.getDisplayedItemStack().ifPresent(stack -> stacks.add(stack.copyWithCount(1)));
                view.getItemStacks().forEach(stack -> {
                    if (stacks.size() < CraftingGridMenus.MAX_CANDIDATES && !stack.isEmpty()) {
                        stacks.add(stack.copyWithCount(1));
                    }
                });
            }
            cells.add(stacks);
        }
        return cells;
    }

    /** A crafting terminal's grid: missing slots marked red; the button stays usable and fills what there is. */
    private record GridMissing(List<IRecipeSlotView> slots) implements IRecipeTransferError {
        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public void showError(GuiGraphicsExtractor graphics, int mouseX, int mouseY, IRecipeSlotsView recipeSlotsView,
                int recipeX, int recipeY) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(recipeX, recipeY);
            for (IRecipeSlotView slot : slots) {
                slot.drawHighlight(graphics, MISSING_HIGHLIGHT);
            }
            graphics.pose().popMatrix();
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable(RequestContent.JEI_MISSING_KEY));
            tooltip.add(Component.translatable(CraftingTerminalContent.VIEWER_MISSING_KEY));
            tooltip.add(Component.translatable(CraftingTabletContent.REQUEST_TOOLTIP_KEY));
        }

        @Override
        public int getMissingCountHint() {
            return slots.size();
        }
    }

    /** Missing slots marked red; the button stays usable and opens the planner. */
    private record CraftError(List<IRecipeSlotView> slots, StackKey craftable) implements IRecipeTransferError {
        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public int getButtonHighlightColor() {
            return CRAFT_BUTTON;
        }

        @Override
        public void showError(GuiGraphicsExtractor graphics, int mouseX, int mouseY, IRecipeSlotsView recipeSlotsView,
                int recipeX, int recipeY) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(recipeX, recipeY);
            for (IRecipeSlotView slot : slots) {
                slot.drawHighlight(graphics, MISSING_HIGHLIGHT);
            }
            graphics.pose().popMatrix();
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable(RequestContent.JEI_MISSING_KEY));
            tooltip.add(Component.translatable(RequestContent.JEI_CRAFT_KEY, KeyDisplay.name(craftable)));
        }

        @Override
        public int getMissingCountHint() {
            return slots.size();
        }
    }
}
