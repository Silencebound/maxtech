package dev.silencebound.maxtech.storage.neoforge.compat.jei;

import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterContent;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.ClientStorageRecipes;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.StorageRecipeViews;
import dev.silencebound.maxtech.storage.board.client.BoardScreen;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.port.client.PortScreen;
import dev.silencebound.maxtech.storage.label.client.LabelTab;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.filter.client.ViewFilterScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSearchSync;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Storage's JEI plugin: one category per processing machine ({@link StorageRecipeViews}) with the machine as its
 * catalyst, the fuel generator on JEI's furnace fuel page, and the terminal search connected to JEI's
 * ({@link TerminalSearchSync}) while JEI's runtime is up. Items of disabled modules are hidden by Lib's plugin, and
 * their recipes never load. JEI finds it by its annotation and loads it only when JEI is installed; Storage compiles
 * against JEI's API only.
 */
@JeiPlugin
public final class StorageJeiPlugin implements IModPlugin {
    private static final Identifier UID = MaxtechStorage.id("jei_plugin");

    private List<Entry> entries = List.of();

    private record Entry(StorageMachineJeiCategory category, List<RecipeHolder<StorageMachineRecipe>> recipes) {
    }

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        List<Entry> built = new ArrayList<>();
        for (StorageRecipeViews.Category category : StorageRecipeViews.categories()) {
            List<RecipeHolder<StorageMachineRecipe>> recipes = new ArrayList<>(
                    ClientStorageRecipes.of(category.kind()));
            recipes.sort(Comparator.comparing(holder -> holder.id().identifier()));
            boolean research = false;
            for (RecipeHolder<StorageMachineRecipe> holder : recipes) {
                research |= holder.value().research().isPresent();
            }
            StorageMachineJeiCategory jeiCategory = new StorageMachineJeiCategory(category, research,
                    registration.getJeiHelpers().getGuiHelper());
            registration.addRecipeCategories(jeiCategory);
            built.add(new Entry(jeiCategory, List.copyOf(recipes)));
        }
        entries = List.copyOf(built);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        for (Entry entry : entries) {
            registration.addRecipes(entry.category.holderType(), entry.recipes);
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (Entry entry : entries) {
            registration.addCraftingStation(entry.category.holderType(), entry.category.machineItem());
        }
        // The generator burns anything with a furnace burn time, so JEI's fuel page lists its fuels.
        registration.addCraftingStation(RecipeTypes.SMELTING_FUEL,
                MachineContent.block(MachineKind.FUEL_GENERATOR).asItem());
        // The crafter crafts its crafting, stonecutting and smithing patterns itself (B09 item 29).
        registration.addCraftingStation(RecipeTypes.CRAFTING, CrafterContent.CRAFTER.get().asItem());
        registration.addCraftingStation(RecipeTypes.STONECUTTING, CrafterContent.CRAFTER.get().asItem());
        registration.addCraftingStation(RecipeTypes.SMITHING, CrafterContent.CRAFTER.get().asItem());
    }

    /**
     * JEI's "+" on the pattern terminal for every recipe type (it writes the draft), and on the storage terminal, where
     * it pulls ingredients or requests crafting of a missing input.
     */
    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addUniversalRecipeTransferHandler(new PatternTransferHandler());
        TerminalTransferHandlers.register(registration);
        // The crafting grid "+" for menus registered with CraftingGridMenus (the crafting tablet).
        for (CraftingGridMenus.Entry<?> menu : CraftingGridMenus.menus()) {
            addCraftingGridHandler(registration, menu);
        }
    }

    private static <M extends AbstractContainerMenu & CraftingGridMenu> void addCraftingGridHandler(
            IRecipeTransferRegistration registration, CraftingGridMenus.Entry<M> menu) {
        registration.addRecipeTransferHandler(new CraftingGridTransferHandler<>(menu), RecipeTypes.CRAFTING);
    }

    /**
     * Keeps JEI's overlays clear of the terminal's tab strips, a device screen's side tabs and open flyout, and the
     * terminals' label tab (JEI matches a handler's class with {@code isInstance}, so one handler covers every screen).
     */
    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGenericGuiContainerHandler(AbstractContainerScreen.class,
                new IGuiContainerHandler<AbstractContainerScreen<?>>() {
                    @Override
                    public List<Rect2i> getGuiExtraAreas(AbstractContainerScreen<?> screen) {
                        List<Rect2i> device = DeviceScreen.areasOf(screen);
                        List<Rect2i> label = LabelTab.areas(screen.children());
                        if (device.isEmpty()) {
                            return label;
                        }
                        List<Rect2i> all = new ArrayList<>(device);
                        all.addAll(label);
                        return all;
                    }
                });
        registration.addGuiContainerHandler(TerminalScreen.class, new IGuiContainerHandler<TerminalScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(TerminalScreen screen) {
                return screen.extraAreas();
            }
        });
        registration.addGhostIngredientHandler(TerminalScreen.class, new GhostTargets.Pattern());
        registration.addGhostIngredientHandler(BoardScreen.class, new GhostTargets.Board());
        registration.addGhostIngredientHandler(ViewFilterScreen.class, new GhostTargets.ViewFilter());
        registration.addGhostIngredientHandler(PortScreen.class, new GhostTargets.Port());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        IIngredientFilter filter = runtime.getIngredientFilter();
        TerminalSearchSync.install(new TerminalSearchSync.Bridge() {
            @Override
            public String get() {
                return filter.getFilterText();
            }

            @Override
            public void set(String text) {
                filter.setFilterText(text);
            }
        });
    }

    @Override
    public void onRuntimeUnavailable() {
        TerminalSearchSync.install(null);
    }
}
