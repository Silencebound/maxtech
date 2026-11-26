package dev.silencebound.maxtech.storage.neoforge.compat.jei;

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
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * One processing machine's recipes: inputs (the lithograph's mask marked as not consumed), outputs with their chance,
 * FE per tick, duration, total FE, the mode, and the research a recipe needs. Built on Lib's page layout and viewer
 * strings, with FE lines in place of Lib's default energy lines.
 */
final class StorageMachineJeiCategory implements IRecipeCategory<RecipeHolder<StorageMachineRecipe>> {
    /** Vanilla's container label colour. */
    private static final int TEXT_COLOUR = 0xFF404040;

    private final MachineKind kind;
    private final IRecipeHolderType<StorageMachineRecipe> type;
    private final Component title;
    private final IDrawable icon;
    private final RecipePageLayout layout;
    /** The text line of the research note, or -1 when no recipe of the category needs research. */
    private final int researchLine;

    StorageMachineJeiCategory(StorageRecipeViews.Category category, boolean research, IGuiHelper guiHelper) {
        this.kind = category.kind();
        this.type = IRecipeHolderType.create(category.id());
        this.title = Component.translatable(category.titleKey());
        this.icon = guiHelper.createDrawableItemLike(machineItem());
        int lines = 3 + (kind.modes().isEmpty() ? 0 : 1);
        this.researchLine = research ? lines : -1;
        this.layout = RecipePageLayout.of(kind.inputs() + kind.catalysts(), kind.outputs(), lines + (research ? 1 : 0));
    }

    IRecipeHolderType<StorageMachineRecipe> holderType() {
        return type;
    }

    Item machineItem() {
        return MachineContent.block(kind).asItem();
    }

    @Override
    public IRecipeType<RecipeHolder<StorageMachineRecipe>> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public int getWidth() {
        return layout.width();
    }

    @Override
    public int getHeight() {
        return layout.height();
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<StorageMachineRecipe> holder,
            IFocusGroup focuses) {
        StorageMachineRecipe recipe = holder.value();
        int slot = 0;
        for (ItemInput input : recipe.inputs()) {
            List<ItemStack> stacks = new ArrayList<>();
            input.ingredient().items().forEach(item -> stacks.add(new ItemStack(item, input.count())));
            IRecipeSlotBuilder inputSlot = builder.addInputSlot(layout.inputX(slot), layout.inputY(slot))
                    .setStandardSlotBackground().addItemStacks(stacks);
            RecipeViewText.notConsumedLine(input).ifPresent(line -> inputSlot.addRichTooltipCallback(
                    (view, tooltip) -> tooltip.add(line.copy().withStyle(ChatFormatting.GRAY))));
            slot++;
        }
        slot = 0;
        for (ItemOutput output : recipe.outputs()) {
            IRecipeSlotBuilder outputSlot = builder.addOutputSlot(layout.outputX(slot), layout.outputY(slot))
                    .setStandardSlotBackground().add(output.item());
            RecipeViewText.chanceLine(output.chance()).ifPresent(line -> outputSlot.addRichTooltipCallback(
                    (view, tooltip) -> tooltip.add(line.copy().withStyle(ChatFormatting.GRAY))));
            slot++;
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RecipeHolder<StorageMachineRecipe> holder,
            IFocusGroup focuses) {
        StorageMachineRecipe recipe = holder.value();
        builder.addAnimatedRecipeArrowWidget(recipe.duration()).setPosition(layout.arrowX(), layout.arrowY());
        line(builder, 0,
                Component.translatable(StorageRecipeViews.FE_PER_TICK_KEY, InfoFormat.number(recipe.energyPerTick())));
        line(builder, 1, Component.translatable(RecipeViewText.DURATION_KEY, InfoFormat.seconds(recipe.duration()),
                InfoFormat.number(recipe.duration())));
        line(builder, 2, Component.translatable(StorageRecipeViews.TOTAL_FE_KEY,
                InfoFormat.number((long) recipe.energyPerTick() * recipe.duration())));
        if (recipe.mode().isPresent()) {
            line(builder, 3, Component.translatable(RecipeViewText.MODE_KEY,
                    Component.translatable(kind.modeKey(recipe.mode().get()))));
        }
        if (researchLine >= 0 && recipe.research().isPresent()) {
            // Full width: the line sits below the slots.
            builder.addText(
                    Component.translatable(StorageRecipeViews.RESEARCH_KEY,
                            Component.translatable(ResearchNode.unlockKey(recipe.research().get()))),
                    layout.width(), RecipePageLayout.LINE_HEIGHT).setPosition(0, layout.lineY(researchLine))
                    .setColor(TEXT_COLOUR);
        }
    }

    private void line(IRecipeExtrasBuilder builder, int line, Component text) {
        builder.addText(text, layout.textWidth(), RecipePageLayout.LINE_HEIGHT).setPosition(0, layout.lineY(line))
                .setColor(TEXT_COLOUR);
    }
}
