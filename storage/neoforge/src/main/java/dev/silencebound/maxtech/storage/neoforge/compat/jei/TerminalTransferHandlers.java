package dev.silencebound.maxtech.storage.neoforge.compat.jei;

import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

/**
 * The storage terminal's two JEI "+" handlers, both over {@link TerminalTransfer}: one for crafting recipes (it pulls
 * ingredients or requests crafting; on a crafting terminal it fills the grid) and a universal one for every other
 * recipe type (it requests crafting of a missing input). JEI asks the crafting one first for crafting recipes. The
 * plain terminal's menu type and the crafting terminal's each get the pair.
 */
final class TerminalTransferHandlers {
    private TerminalTransferHandlers() {
    }

    static void register(IRecipeTransferRegistration registration) {
        TerminalTransfer transfer = new TerminalTransfer(registration.getTransferHelper());
        List<Supplier<MenuType<TerminalMenu>>> types = List.of(ModMenus.TERMINAL, CraftingTerminalContent.MENU);
        for (Supplier<MenuType<TerminalMenu>> type : types) {
            registration.addRecipeTransferHandler(new Crafting(transfer, type), RecipeTypes.CRAFTING);
            registration.addUniversalRecipeTransferHandler(new Universal(transfer, type));
        }
    }

    private record Crafting(TerminalTransfer transfer, Supplier<MenuType<TerminalMenu>> type)
            implements
                IRecipeTransferHandler<TerminalMenu, RecipeHolder<CraftingRecipe>> {
        @Override
        public Class<? extends TerminalMenu> getContainerClass() {
            return TerminalMenu.class;
        }

        @Override
        public Optional<MenuType<TerminalMenu>> getMenuType() {
            return Optional.of(type.get());
        }

        @Override
        public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
            return RecipeTypes.CRAFTING;
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(
                IRecipeTransferContext<RecipeHolder<CraftingRecipe>, TerminalMenu> context, boolean doTransfer) {
            IRecipeTransferError error = transfer.transfer(context.getContainer(), context.getRecipeSlots(),
                    context.getPlayer(), true, context.isMaxTransfer(), doTransfer);
            if (doTransfer && error == null) {
                context.completeRecipeTransfer(RecipeTransferResult.SUCCESS);
            }
            return error;
        }

        @Override
        @Deprecated(forRemoval = true) // JEI still declares it abstract; the context overload above is what JEI calls
        @SuppressWarnings("removal")
        public @Nullable IRecipeTransferError transferRecipe(TerminalMenu container,
                RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer,
                boolean doTransfer) {
            return transfer.transfer(container, recipeSlots, player, true, maxTransfer, doTransfer);
        }
    }

    private record Universal(TerminalTransfer transfer,
            Supplier<MenuType<TerminalMenu>> type) implements IUniversalRecipeTransferHandler<TerminalMenu> {
        @Override
        public Class<? extends TerminalMenu> getContainerClass() {
            return TerminalMenu.class;
        }

        @Override
        public Optional<MenuType<TerminalMenu>> getMenuType() {
            return Optional.of(type.get());
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(TerminalMenu container, Object recipe,
                IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {
            return transfer.transfer(container, recipeSlots, player, false, maxTransfer, doTransfer);
        }
    }
}
