package dev.silencebound.maxtech.storage.neoforge.compat.rei;

import dev.silencebound.maxtech.lib.content.ConduitEntry;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.label.client.LabelTab;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.ClientStorageRecipes;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.StorageRecipeViews;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSearchSync;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.widgets.TextField;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.registry.ReloadStage;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.forge.REIPluginClient;
import me.shedaniel.rei.plugin.common.BuiltinPlugin;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Storage's REI plugin, the counterpart of the JEI plugin: one category per processing machine
 * ({@link StorageRecipeViews}) with the machine as its workstation, the fuel generator on REI's fuel page, the "+"
 * transfer on the terminals, overlay exclusion zones for the tab strips, and the terminal search connected to REI's
 * search field, and the retired glass cables taken out of the item list. REI finds it by its annotation and loads it
 * only when REI is installed; Storage compiles against REI's API only. Client only. EMI has no 26.1.2 build, so there
 * is no EMI plugin yet.
 */
@REIPluginClient
public final class StorageReiPlugin implements REIClientPlugin {
    private final List<Entry> entries = new ArrayList<>();

    private record Entry(StorageMachineReiCategory category, List<RecipeHolder<StorageMachineRecipe>> recipes) {
    }

    @Override
    public void registerCategories(CategoryRegistry registry) {
        entries.clear();
        for (StorageRecipeViews.Category category : StorageRecipeViews.categories()) {
            List<RecipeHolder<StorageMachineRecipe>> recipes = new ArrayList<>(
                    ClientStorageRecipes.of(category.kind()));
            recipes.sort(Comparator.comparing(holder -> holder.id().identifier()));
            boolean research = false;
            for (RecipeHolder<StorageMachineRecipe> holder : recipes) {
                research |= holder.value().research().isPresent();
            }
            StorageMachineReiCategory reiCategory = new StorageMachineReiCategory(category, research);
            registry.add(reiCategory);
            registry.addWorkstations(reiCategory.id(), EntryStacks.of(reiCategory.machineItem()));
            entries.add(new Entry(reiCategory, List.copyOf(recipes)));
        }
        // The generator burns anything with a furnace burn time, so REI's fuel page lists its fuels.
        registry.addWorkstations(BuiltinPlugin.FUEL,
                EntryStacks.of(MachineContent.block(MachineKind.FUEL_GENERATOR).asItem()));
    }

    /**
     * Takes the retired glass cables out of REI's list. REI lists every registered item, not only those in creative
     * tabs (its default plugin, priority -100, adds them before this one runs); JEI follows the creative tabs.
     */
    @Override
    public void registerEntries(EntryRegistry registry) {
        for (ConduitEntry<StorageCableConduitType> cable : CableContent.GLASS) {
            registry.removeEntry(EntryStacks.of(cable.item().get()));
        }
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        for (Entry entry : entries) {
            for (RecipeHolder<StorageMachineRecipe> holder : entry.recipes) {
                registry.add(new StorageMachineDisplay(entry.category.id(), holder));
            }
        }
    }

    @Override
    public void registerTransferHandlers(TransferHandlerRegistry registry) {
        registry.register(new ReiTransferHandler());
    }

    /** Drags from REI's item list onto the ghost slots, as the JEI plugin does. */
    @Override
    public void registerScreens(ScreenRegistry registry) {
        ReiGhostTargets.register(registry);
    }

    /**
     * Keeps REI's overlay clear of the terminal's tab strips, a device screen's side tabs and open flyout, and the
     * terminals' label tab (REI matches a provider's class with {@code isAssignableFrom}, so one covers every screen).
     */
    @Override
    public void registerExclusionZones(ExclusionZones zones) {
        zones.register(AbstractContainerScreen.class,
                screen -> convert(joined(LabelTab.areas(screen.children()), DeviceScreen.areasOf(screen))));
        zones.register(TerminalScreen.class, screen -> convert(screen.extraAreas()));
    }

    private static List<Rect2i> joined(List<Rect2i> first, List<Rect2i> second) {
        List<Rect2i> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static List<Rectangle> convert(List<Rect2i> areas) {
        List<Rectangle> rectangles = new ArrayList<>(areas.size());
        for (Rect2i area : areas) {
            rectangles.add(new Rectangle(area.getX(), area.getY(), area.getWidth(), area.getHeight()));
        }
        return rectangles;
    }

    /**
     * REI has no runtime-available callback, so the search bridge goes in with the first reload and reads the search
     * field only when asked; without a field (overlay off) it reads empty and ignores writes.
     */
    @Override
    public void postStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) {
        TerminalSearchSync.install(new TerminalSearchSync.Bridge() {
            @Override
            public String get() {
                TextField field = REIRuntime.getInstance().getSearchTextField();
                return field == null ? "" : field.getText();
            }

            @Override
            public void set(String text) {
                TextField field = REIRuntime.getInstance().getSearchTextField();
                if (field != null) {
                    field.setText(text);
                }
            }
        });
    }
}
