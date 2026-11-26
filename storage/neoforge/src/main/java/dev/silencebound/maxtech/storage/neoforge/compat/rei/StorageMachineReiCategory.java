package dev.silencebound.maxtech.storage.neoforge.compat.rei;

import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.viewer.RecipePageLayout;
import dev.silencebound.maxtech.lib.viewer.RecipeViewText;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.StorageRecipeViews;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * One processing machine's recipes in REI, laid out like the JEI category: inputs (the lithograph's mask marked as not
 * consumed), outputs with their chance, FE per tick, duration, total FE, the mode, and the research a recipe needs.
 * Built on Lib's page layout and viewer strings.
 */
final class StorageMachineReiCategory implements DisplayCategory<StorageMachineDisplay> {
    /** Vanilla's container label colour, light and dark. */
    private static final int TEXT_COLOUR = 0xFF404040;
    private static final int TEXT_COLOUR_DARK = 0xFFBBBBBB;
    /** Space between the page and the recipe base's edge. */
    private static final int PAD = 6;

    private final MachineKind kind;
    private final CategoryIdentifier<StorageMachineDisplay> id;
    private final Component title;
    private final RecipePageLayout layout;
    /** The text line of the research note, or -1 when no recipe of the category needs research. */
    private final int researchLine;

    StorageMachineReiCategory(StorageRecipeViews.Category category, boolean research) {
        this.kind = category.kind();
        this.id = CategoryIdentifier.of(category.id());
        this.title = Component.translatable(category.titleKey());
        int lines = 3 + (kind.modes().isEmpty() ? 0 : 1);
        this.researchLine = research ? lines : -1;
        this.layout = RecipePageLayout.of(kind.inputs() + kind.catalysts(), kind.outputs(), lines + (research ? 1 : 0));
    }

    CategoryIdentifier<StorageMachineDisplay> id() {
        return id;
    }

    Item machineItem() {
        return MachineContent.block(kind).asItem();
    }

    @Override
    public CategoryIdentifier<? extends StorageMachineDisplay> getCategoryIdentifier() {
        return id;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public Renderer getIcon() {
        return EntryStacks.of(machineItem());
    }

    @Override
    public int getDisplayWidth(StorageMachineDisplay display) {
        return layout.width() + 2 * PAD;
    }

    @Override
    public int getDisplayHeight() {
        return layout.height() + 2 * PAD;
    }

    @Override
    public List<Widget> setupDisplay(StorageMachineDisplay display, Rectangle bounds) {
        StorageMachineRecipe recipe = display.holder().value();
        int left = bounds.x + PAD;
        int top = bounds.y + PAD;
        List<Widget> widgets = new ArrayList<>();
        widgets.add(Widgets.createRecipeBase(bounds));
        int slot = 0;
        for (ItemInput input : recipe.inputs()) {
            Point at = new Point(left + layout.inputX(slot), top + layout.inputY(slot));
            Optional<Component> note = RecipeViewText.notConsumedLine(input);
            widgets.add(
                    Widgets.createSlot(at).entries(withNote(display.getInputEntries().get(slot), note)).markInput());
            slot++;
        }
        slot = 0;
        for (ItemOutput output : recipe.outputs()) {
            Point at = new Point(left + layout.outputX(slot), top + layout.outputY(slot));
            Optional<Component> note = RecipeViewText.chanceLine(output.chance());
            widgets.add(Widgets.createResultSlotBackground(at));
            widgets.add(Widgets.createSlot(at).entries(withNote(display.getOutputEntries().get(slot), note))
                    .disableBackground().markOutput());
            slot++;
        }
        widgets.add(Widgets.createArrow(new Point(left + layout.arrowX(), top + layout.arrowY()))
                .animationDurationTicks(recipe.duration()));
        line(widgets, left, top, 0,
                Component.translatable(StorageRecipeViews.FE_PER_TICK_KEY, InfoFormat.number(recipe.energyPerTick())));
        line(widgets, left, top, 1, Component.translatable(RecipeViewText.DURATION_KEY,
                InfoFormat.seconds(recipe.duration()), InfoFormat.number(recipe.duration())));
        line(widgets, left, top, 2, Component.translatable(StorageRecipeViews.TOTAL_FE_KEY,
                InfoFormat.number((long) recipe.energyPerTick() * recipe.duration())));
        if (recipe.mode().isPresent()) {
            line(widgets, left, top, 3, Component.translatable(RecipeViewText.MODE_KEY,
                    Component.translatable(kind.modeKey(recipe.mode().get()))));
        }
        if (researchLine >= 0 && recipe.research().isPresent()) {
            line(widgets, left, top, researchLine, Component.translatable(StorageRecipeViews.RESEARCH_KEY,
                    Component.translatable(ResearchNode.unlockKey(recipe.research().get()))));
        }
        return widgets;
    }

    private void line(List<Widget> widgets, int left, int top, int line, Component text) {
        widgets.add(Widgets.createLabel(new Point(left, top + layout.lineY(line)), text)
                .color(TEXT_COLOUR, TEXT_COLOUR_DARK).noShadow().leftAligned());
    }

    /** Copies of the ingredient's stacks with {@code note} (when present) added to their tooltips. */
    private static List<EntryStack<?>> withNote(EntryIngredient ingredient, Optional<Component> note) {
        List<EntryStack<?>> stacks = new ArrayList<>(ingredient.size());
        for (EntryStack<?> stack : ingredient) {
            EntryStack<?> copy = stack.copy();
            if (note.isPresent()) {
                copy.tooltip(note.get().copy().withStyle(ChatFormatting.GRAY));
            }
            stacks.add(copy);
        }
        return stacks;
    }
}
