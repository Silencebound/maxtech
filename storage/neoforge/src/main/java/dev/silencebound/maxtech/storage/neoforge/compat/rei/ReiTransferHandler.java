package dev.silencebound.maxtech.storage.neoforge.compat.rei;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.client.CraftingScreens;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternFaces;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTransfer;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.TerminalShortfall;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.plugin.common.displays.crafting.CraftingDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * REI's "+" button on Storage's menus, one handler that does what the JEI handlers do: on the storage terminal it
 * checks the recipe against the network and the player's inventory (a missing craftable input makes the button open the
 * planner, a crafting recipe otherwise pulls its ingredients); on the pattern terminal it writes any recipe as a
 * processing draft (crafting recipes go to the grid like any crafting menu); on other registered crafting menus it
 * fills the grid. The client cannot see a crafting menu's ingredient source, so those never report missing items. REI
 * marks a click as usable only when the result is successful, so the planner case is a successful result with a tinted
 * button.
 */
final class ReiTransferHandler implements TransferHandler {
    private static final int CRAFT_BUTTON = 0x8046B2BD;

    private final TerminalShortfall calculator = new TerminalShortfall();

    @Override
    public double getPriority() {
        return 100;
    }

    @Override
    public ApplicabilityResult checkApplicable(Context context) {
        AbstractContainerMenu menu = context.getMenu();
        if (menu == null) {
            return ApplicabilityResult.createNotApplicable();
        }
        if (menu instanceof TerminalMenu || isGridMenu(menu)) {
            return ApplicabilityResult.createApplicable();
        }
        return ApplicabilityResult.createNotApplicable();
    }

    @Override
    public Result handle(Context context) {
        AbstractContainerMenu menu = context.getMenu();
        Display display = context.getDisplay();
        if (menu == null) {
            return Result.createNotApplicable();
        }
        if (menu instanceof TerminalMenu terminal && terminal.patternPanel() != null) {
            // The pattern terminal: a crafting recipe becomes a crafting draft, any other a processing one.
            if (context.isActuallyCrafting() && display instanceof CraftingDisplay crafting) {
                sendGrid(menu, crafting, false);
            } else if (context.isActuallyCrafting()) {
                sendPattern(terminal, display);
            }
            return Result.createSuccessful().blocksFurtherHandling(true);
        }
        if (menu instanceof TerminalMenu terminal) {
            return terminal(context, terminal, display);
        }
        if (display instanceof CraftingDisplay crafting && isGridMenu(menu)) {
            if (context.isActuallyCrafting()) {
                sendGrid(menu, crafting, context.isStackedCrafting());
            }
            return Result.createSuccessful().blocksFurtherHandling(true);
        }
        return Result.createNotApplicable();
    }

    private Result terminal(Context context, TerminalMenu menu, Display display) {
        Minecraft minecraft = context.getMinecraft();
        if (minecraft.player == null) {
            return Result.createNotApplicable();
        }
        TerminalShortfall.Result shortfall = calculator.compute(menu, minecraft.player,
                ReiRecipeInputs.shortfallInputs(display));
        if (display instanceof CraftingDisplay crafting && menu.craftingPanel() != null) {
            // A crafting terminal fills its grid with what there is; its request button orders the rest.
            if (context.isActuallyCrafting()) {
                sendGrid(menu, crafting, context.isStackedCrafting());
            }
            Result result = Result.createSuccessful().blocksFurtherHandling(true);
            if (!shortfall.missing().isEmpty()) {
                result.color(CRAFT_BUTTON).tooltip(Component.translatable(RequestContent.JEI_MISSING_KEY))
                        .tooltip(Component.translatable(CraftingTerminalContent.VIEWER_MISSING_KEY));
            }
            return result;
        }
        StackKey craftable = shortfall.craftable();
        if (craftable != null) {
            if (context.isActuallyCrafting()) {
                long amount = shortfall.amount();
                List<CraftingPlanner.Root> all = shortfall.craftables();
                // REI returns to the terminal after the transfer, so open the planner after that.
                minecraft.schedule(() -> {
                    if (all.size() > 1) {
                        CraftingScreens.openOrder(all);
                    } else {
                        CraftingScreens.openRequest(craftable, amount, true);
                    }
                });
            }
            return Result.createSuccessful().color(CRAFT_BUTTON)
                    .tooltip(Component.translatable(RequestContent.JEI_MISSING_KEY))
                    .tooltip(Component.translatable(RequestContent.JEI_CRAFT_KEY, KeyDisplay.name(craftable)))
                    .blocksFurtherHandling(true);
        }
        if (display instanceof CraftingDisplay crafting) {
            if (context.isActuallyCrafting()) {
                sendGrid(menu, crafting, context.isStackedCrafting());
            }
            return Result.createSuccessful().blocksFurtherHandling(true);
        }
        if (shortfall.missing().isEmpty()) {
            return Result.createNotApplicable();
        }
        List<EntryIngredient> missing = new ArrayList<>(shortfall.missing().size());
        for (int index : shortfall.missing()) {
            missing.add(display.getInputEntries().get(index));
        }
        return Result.createFailed(Component.translatable(RequestContent.JEI_MISSING_KEY)).tooltipMissing(missing);
    }

    private static boolean isGridMenu(AbstractContainerMenu menu) {
        for (CraftingGridMenus.Entry<?> entry : CraftingGridMenus.menus()) {
            if (entry.menuClass().isInstance(menu)) {
                return true;
            }
        }
        return false;
    }

    private static void sendGrid(AbstractContainerMenu menu, CraftingDisplay display, boolean maxTransfer) {
        Platform.get().network().sendToServer(new CraftingGridMenus.Transfer(menu.containerId,
                ReiRecipeInputs.gridCells(display.getOrganisedInputEntries(3, 3)), maxTransfer));
    }

    /**
     * Sends the display as a processing draft. Nothing is checked on the client; the server writes the grid. The recipe
     * type and id go along for Storage's own recipes and for the vanilla types REI shows from a recipe.
     */
    private static void sendPattern(TerminalMenu menu, Display display) {
        Optional<Identifier> type = Optional.empty();
        Optional<Identifier> id = Optional.empty();
        if (display instanceof StorageMachineDisplay machine) {
            type = Optional.ofNullable(BuiltInRegistries.RECIPE_TYPE.getKey(machine.holder().value().getType()));
            id = Optional.of(machine.holder().id().identifier());
        } else {
            type = ReiRecipeInputs.patternType(display);
            if (type.isPresent() && !type.get().equals(PatternFaces.BREWING)) {
                id = display.getDisplayLocation();
            }
        }
        Platform.get().network()
                .sendToServer(new PatternTransfer(menu.containerId, type, id,
                        ReiRecipeInputs.patternEntries(display.getInputEntries()),
                        ReiRecipeInputs.patternEntries(display.getOutputEntries())));
    }
}
