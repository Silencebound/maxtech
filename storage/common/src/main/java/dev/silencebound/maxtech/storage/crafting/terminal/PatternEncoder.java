package dev.silencebound.maxtech.storage.crafting.terminal;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternTable;
import dev.silencebound.maxtech.storage.crafting.pattern.TableRecipes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A pattern terminal's grid, shared by the block and the part: the {@link PatternDraft} and what the screen's buttons
 * and a recipe viewer do to it. It holds no items: encoding takes a blank pattern from the player's inventory and hands
 * the encoded one back, so breaking a terminal can never lose or duplicate patterns. Server thread only; the draft is
 * saved with the terminal and dropped with it (it is a scratch pad, not a machine's stored state).
 */
public final class PatternEncoder {
    /** Most stacks per input a recipe viewer may send; JEI lists every item of a tag. */
    public static final int MAX_CANDIDATES = 64;
    private static final String DRAFT = "pattern_draft";
    private static final int BUCKET_MB = 1000;

    /** Why encoding did nothing; the screen shows it. */
    public enum Result {
        ENCODED, EMPTY, NO_RECIPE, NO_BLANK;

        /** A small number for sending. */
        public int id() {
            return switch (this) {
                case ENCODED -> 0;
                case EMPTY -> 1;
                case NO_RECIPE -> 2;
                case NO_BLANK -> 3;
            };
        }

        /** The result with {@link #id()} {@code id}, or null. */
        public static @Nullable Result byId(int id) {
            for (Result result : values()) {
                if (result.id() == id) {
                    return result;
                }
            }
            return null;
        }
    }

    private final Runnable changed;
    private PatternDraft draft = PatternDraft.EMPTY;
    private int version;

    public PatternEncoder(Runnable changed) {
        this.changed = changed;
    }

    public PatternDraft draft() {
        return draft;
    }

    /** Bumped by every change, so open menus know when to send the draft again. */
    public int version() {
        return version;
    }

    public void setDraft(PatternDraft value) {
        if (!value.equals(draft)) {
            draft = value;
            version++;
            changed.run();
        }
    }

    /**
     * A left click on slot {@code slot} (inputs 0 to 8, outputs 9 to 11) with {@code carried}: sets the slot to the
     * carried item with its count, or clears it when the hand is empty. With {@code fluid}, a carried filled bucket
     * sets a bucket of its fluid instead.
     */
    public void set(int slot, ItemStack carried, boolean fluid) {
        StackKey key = carried.isEmpty() ? null : keyOf(carried, fluid);
        put(slot, key, key instanceof FluidKey ? BUCKET_MB : carried.getCount());
    }

    /**
     * Sets slot {@code slot} to one of {@code key} (a bucket of a fluid), as a click with one carried item would: the
     * ghost entry a terminal's item grid or a recipe viewer's drag sets. No item is involved.
     */
    public void setKey(int slot, StackKey key) {
        put(slot, key, key instanceof FluidKey ? BUCKET_MB : 1);
    }

    private void put(int slot, @Nullable StackKey key, long amount) {
        if (slot < PatternDraft.INPUTS) {
            setDraft(draft.withInput(slot, key == null ? null : PatternDraft.Input.of(key, amount)));
        } else {
            setDraft(draft.withOutput(slot - PatternDraft.INPUTS,
                    key == null ? null : new PatternDraft.Output(key, amount, 1f)));
        }
    }

    /**
     * One more (a bucket more for fluids) or one less in slot {@code slot}; the last one clears it. On a stonecutting
     * draft's output it picks the next or previous stonecutter recipe for the input instead.
     */
    public void adjust(ServerLevel level, int slot, boolean up) {
        if (draft.mode() == PatternDraft.Mode.STONECUTTING && slot >= PatternDraft.INPUTS) {
            cycleStonecutting(level, up);
            return;
        }
        if (slot < PatternDraft.INPUTS) {
            draft.inputs().get(slot).ifPresent(input -> {
                long amount = input.amount() + step(input.key(), input.amount(), up);
                setDraft(draft.withInput(slot, amount < 1 ? null : input.withAmount(amount)));
            });
        } else {
            int index = slot - PatternDraft.INPUTS;
            draft.outputs().get(index).ifPresent(output -> {
                long amount = output.amount() + step(output.key(), output.amount(), up);
                setDraft(draft.withOutput(index, amount < 1 ? null : output.withAmount(amount)));
            });
        }
    }

    private static long step(StackKey key, long amount, boolean up) {
        long step = key instanceof FluidKey && (up || amount > BUCKET_MB) ? BUCKET_MB : 1;
        return up ? step : -step;
    }

    /** Cycles input {@code slot}'s face: the provider's face, then down, up, north, south, west, east. */
    public void cycleFace(int slot) {
        draft.inputs().get(slot).ifPresent(input -> {
            Optional<Direction> face = input.face();
            int next = face.isEmpty() ? 0 : face.get().get3DDataValue() + 1;
            setDraft(draft.withInput(slot,
                    input.withFace(next < Direction.values().length
                            ? Optional.of(Direction.from3DDataValue(next))
                            : Optional.empty())));
        });
    }

    public void toggleReturned(int slot) {
        draft.inputs().get(slot)
                .ifPresent(input -> setDraft(draft.withInput(slot, input.withReturned(!input.returned()))));
    }

    /** Locks a tag input to its shown key, or unlocks it; inputs without a tag are always exact. */
    public void toggleExact(int slot) {
        draft.inputs().get(slot).ifPresent(input -> {
            if (input.tag().isPresent()) {
                setDraft(draft.withInput(slot, input.withExact(!input.exact())));
            }
        });
    }

    /** Processing to crafting, any other mode to processing. */
    public void toggleMode() {
        setDraft(draft.withMode(draft.mode() == PatternDraft.Mode.PROCESSING
                ? PatternDraft.Mode.CRAFTING
                : PatternDraft.Mode.PROCESSING));
    }

    /** Switches to {@code mode}; nothing changes when the draft is in it already. */
    public void setMode(PatternDraft.Mode mode) {
        if (draft.mode() != mode) {
            setDraft(draft.withMode(mode));
        }
    }

    /**
     * Multiplies ({@code multiply}) or divides every amount of a processing draft by {@code factor}; false, with
     * nothing changed, when {@link PatternDraft#scaleBlock} refuses.
     */
    public boolean scale(int factor, boolean multiply) {
        if (draft.scaleBlock(factor, multiply) != null) {
            return false;
        }
        setDraft(draft.scaled(factor, multiply));
        return true;
    }

    /**
     * Fills a stonecutting or smithing draft's output from the recipe its inputs match, so the screen shows what the
     * pattern will make, or clears the output when none matches. Crafting drafts find their output on encode;
     * processing drafts have the player's outputs.
     */
    public void refreshTable(ServerLevel level) {
        PatternDraft filled = switch (draft.mode()) {
            case STONECUTTING -> withStonecuttingOutput(level, draft, 0);
            case SMITHING -> withSmithingOutput(level, draft);
            case CRAFTING, PROCESSING -> draft;
        };
        setDraft(filled != null ? filled : clearOutputs(draft));
    }

    private void cycleStonecutting(ServerLevel level, boolean up) {
        PatternDraft next = withStonecuttingOutput(level, draft, up ? 1 : -1);
        if (next != null) {
            setDraft(next);
        }
    }

    /**
     * Substitutions for the whole pattern: while they are on (no tag input locked), locks every tag input to its shown
     * key; otherwise unlocks every tag input. Inputs without a tag stay exact either way.
     */
    public void toggleSubstitutions() {
        boolean lock = draft.substitutions();
        PatternDraft next = draft;
        for (int slot = 0; slot < PatternDraft.INPUTS; slot++) {
            Optional<PatternDraft.Input> input = next.inputs().get(slot);
            if (input.isPresent() && input.get().tag().isPresent()) {
                next = next.withInput(slot, input.get().withExact(lock));
            }
        }
        setDraft(next);
    }

    public void clear() {
        setDraft(PatternDraft.EMPTY.withMode(draft.mode()));
    }

    /**
     * Loads {@code stack}'s pattern into the grid for editing, in the mode that wrote it; false for a stack without
     * one. A smithing pattern with two inputs goes to template and base, or base and addition when its recipe has no
     * template.
     */
    public boolean load(ServerLevel level, ItemStack stack) {
        CraftingPattern pattern = PatternItem.pattern(stack);
        if (pattern == null) {
            return false;
        }
        PatternDraft.Mode mode = PatternDraft.Mode.of(pattern);
        List<Optional<PatternDraft.Input>> inputs = new ArrayList<>();
        if (mode == PatternDraft.Mode.SMITHING && pattern.inputs().size() < 3
                && (pattern.inputs().size() == 1 || !hasTemplate(level, pattern.recipe()))) {
            inputs.add(Optional.empty());
        }
        for (PatternInput input : pattern.inputs()) {
            StackKey shown = shownKey(input.ingredient());
            if (shown != null && inputs.size() < PatternDraft.INPUTS) {
                Optional<PatternIngredient> tag = input.ingredient() instanceof PatternIngredient.Exact
                        ? Optional.empty()
                        : Optional.of(input.ingredient());
                inputs.add(Optional
                        .of(new PatternDraft.Input(shown, input.amount(), tag, false, input.face(), input.returned())));
            }
        }
        List<Optional<PatternDraft.Output>> outputs = new ArrayList<>();
        for (PatternOutput output : pattern.outputs()) {
            if (outputs.size() < PatternDraft.OUTPUTS) {
                outputs.add(Optional.of(new PatternDraft.Output(output.key(), output.amount(), output.chance())));
            }
        }
        setDraft(new PatternDraft(mode, inputs, outputs, pattern.recipeType(), pattern.recipe()));
        return true;
    }

    private static boolean hasTemplate(ServerLevel level, Optional<Identifier> recipe) {
        if (recipe.isEmpty()) {
            return true;
        }
        Optional<RecipeHolder<?>> holder = level.recipeAccess()
                .byKey(ResourceKey.create(Registries.RECIPE, recipe.get()));
        return holder.isEmpty() || !(holder.get().value() instanceof SmithingRecipe smithing)
                || smithing.templateIngredient().isPresent();
    }

    /**
     * Encodes the draft onto one blank pattern from {@code player}'s inventory and gives the encoded pattern to the
     * player (dropped if the inventory is full). A crafting, stonecutting or smithing draft gets its output from the
     * recipe its inputs match.
     */
    public Result encode(ServerPlayer player) {
        PatternDraft current = switch (draft.mode()) {
            case CRAFTING -> withCraftingOutput(player.level(), draft);
            case STONECUTTING -> withStonecuttingOutput(player.level(), draft, 0);
            case SMITHING -> withSmithingOutput(player.level(), draft);
            case PROCESSING -> draft;
        };
        if (current == null) {
            return Result.NO_RECIPE;
        }
        Optional<CraftingPattern> pattern = current.toPattern();
        if (pattern.isEmpty()) {
            return Result.EMPTY;
        }
        Inventory inventory = player.getInventory();
        int blank = -1;
        for (int i = 0; i < inventory.getContainerSize() && blank < 0; i++) {
            if (inventory.getItem(i).is(PatternContent.BLANK_PATTERN.get())) {
                blank = i;
            }
        }
        if (blank < 0) {
            return Result.NO_BLANK;
        }
        inventory.removeItem(blank, 1);
        ItemStack encoded = PatternItem.encode(pattern.get());
        if (!inventory.add(encoded)) {
            player.drop(encoded, false);
        }
        setDraft(current);
        return Result.ENCODED;
    }

    /** The crafting draft with output 1 set from the recipe its grid matches, or null when none does. */
    static @Nullable PatternDraft withCraftingOutput(ServerLevel level, PatternDraft draft) {
        List<ItemStack> grid = new ArrayList<>(PatternDraft.INPUTS);
        for (Optional<PatternDraft.Input> slot : draft.inputs()) {
            grid.add(slot.isPresent() && slot.get().key() instanceof ItemKey item ? item.toStack(1) : ItemStack.EMPTY);
        }
        CraftingInput input = CraftingInput.of(3, 3, grid);
        Optional<RecipeHolder<CraftingRecipe>> recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input,
                level);
        if (recipe.isEmpty()) {
            return null;
        }
        ItemStack result = recipe.get().value().assemble(input);
        if (result.isEmpty()) {
            return null;
        }
        return withResult(draft, result, PatternTable.CRAFTING, recipe.get().id().identifier());
    }

    /**
     * The stonecutting draft with its output set from a stonecutter recipe its input takes, or null when it takes none.
     * With {@code step} 0 the recipe is the one giving the current output, else the one the draft names, else the
     * first; with 1 or -1 it is the next or previous one after the current output, wrapping around.
     */
    static @Nullable PatternDraft withStonecuttingOutput(ServerLevel level, PatternDraft draft, int step) {
        Optional<PatternDraft.Input> slot = draft.inputs().getFirst();
        if (slot.isEmpty() || !(slot.get().key() instanceof ItemKey item)) {
            return null;
        }
        ItemStack stack = item.toStack(1);
        List<RecipeHolder<StonecutterRecipe>> options = TableRecipes.stonecutting(level, stack);
        if (options.isEmpty()) {
            return null;
        }
        SingleRecipeInput input = new SingleRecipeInput(stack);
        Optional<PatternDraft.Output> output = draft.outputs().getFirst();
        int current = -1;
        for (int i = 0; i < options.size() && current < 0; i++) {
            if (output.isPresent() && output.get().key().equals(ItemKey.of(options.get(i).value().assemble(input)))) {
                current = i;
            }
        }
        for (int i = 0; i < options.size() && current < 0 && step == 0; i++) {
            if (draft.recipe().isPresent() && draft.recipe().get().equals(options.get(i).id().identifier())) {
                current = i;
            }
        }
        int chosen = current < 0 ? 0 : Math.floorMod(current + step, options.size());
        RecipeHolder<StonecutterRecipe> recipe = options.get(chosen);
        ItemStack result = recipe.value().assemble(input);
        return result.isEmpty() ? null : withResult(draft, result, PatternTable.STONECUTTING, recipe.id().identifier());
    }

    /**
     * The smithing draft with its output set from the smithing recipe its template, base and addition (inputs 1 to 3)
     * match, or null when none does. The recipe the draft names wins when it still matches.
     */
    static @Nullable PatternDraft withSmithingOutput(ServerLevel level, PatternDraft draft) {
        ItemStack[] slots = new ItemStack[3];
        for (int i = 0; i < slots.length; i++) {
            Optional<PatternDraft.Input> slot = draft.inputs().get(i);
            if (slot.isPresent() && !(slot.get().key() instanceof ItemKey)) {
                return null;
            }
            slots[i] = slot.isPresent() && slot.get().key() instanceof ItemKey item ? item.toStack(1) : ItemStack.EMPTY;
        }
        SmithingRecipeInput input = new SmithingRecipeInput(slots[0], slots[1], slots[2]);
        Optional<RecipeHolder<SmithingRecipe>> recipe = Optional.empty();
        if (draft.recipe().isPresent()) {
            Optional<RecipeHolder<?>> named = level.recipeAccess()
                    .byKey(ResourceKey.create(Registries.RECIPE, draft.recipe().get()));
            if (named.isPresent() && named.get().value() instanceof SmithingRecipe smithing
                    && smithing.matches(input, level)) {
                recipe = Optional.of(new RecipeHolder<>(named.get().id(), smithing));
            }
        }
        if (recipe.isEmpty()) {
            recipe = TableRecipes.smithing(level, input);
        }
        if (recipe.isEmpty()) {
            return null;
        }
        ItemStack result = recipe.get().value().assemble(input);
        return result.isEmpty()
                ? null
                : withResult(draft, result, PatternTable.SMITHING, recipe.get().id().identifier());
    }

    /** {@code draft} with {@code result} as its only output and the recipe it came from. */
    private static PatternDraft withResult(PatternDraft draft, ItemStack result, PatternTable table, Identifier id) {
        return clearOutputs(draft).withOutput(0, new PatternDraft.Output(ItemKey.of(result), result.getCount(), 1f))
                .withRecipe(Optional.of(table.recipeType()), Optional.of(id));
    }

    private static PatternDraft clearOutputs(PatternDraft draft) {
        PatternDraft out = draft;
        for (int i = 0; i < PatternDraft.OUTPUTS; i++) {
            out = out.withOutput(i, null);
        }
        return out;
    }

    /**
     * A recipe viewer's crafting recipe: nine cells of acceptable stacks, row by row. Each filled cell becomes an input
     * shown as its first stack; when the stacks are exactly one item tag's contents, the input takes that tag.
     */
    public void receiveCrafting(ServerLevel level, List<List<ItemStack>> cells) {
        PatternDraft next = PatternDraft.EMPTY;
        for (int i = 0; i < PatternDraft.INPUTS && i < cells.size(); i++) {
            List<ItemStack> candidates = cells.get(i);
            if (!candidates.isEmpty() && !candidates.getFirst().isEmpty()) {
                ItemStack shown = candidates.getFirst();
                Optional<PatternIngredient> tag = tagOf(candidates).map(PatternIngredient.ItemTag::new);
                next = next.withInput(i, new PatternDraft.Input(ItemKey.of(shown.copyWithCount(1)), 1, tag, false,
                        Optional.empty(), false));
            }
        }
        PatternDraft crafted = withCraftingOutput(level, next);
        setDraft(crafted != null ? crafted : next);
    }

    /**
     * A recipe viewer's recipe of any other type, as a processing draft: inputs and outputs in order, as many as fit,
     * and the recipe type and id it came from. A stonecutter or smithing-table recipe becomes a stonecutting or
     * smithing draft instead ({@link #receiveTable}).
     */
    public void receiveProcessing(ServerLevel level, PatternTransfer transfer) {
        if (transfer.recipeType().isPresent()) {
            Identifier type = transfer.recipeType().get();
            if (type.equals(PatternTable.STONECUTTING.recipeType())) {
                receiveTable(level, transfer, PatternDraft.Mode.STONECUTTING);
                return;
            }
            if (type.equals(PatternTable.SMITHING.recipeType())) {
                receiveTable(level, transfer, PatternDraft.Mode.SMITHING);
                return;
            }
        }
        List<Optional<PatternDraft.Input>> inputs = new ArrayList<>();
        for (PatternTransfer.Entry entry : transfer.inputs()) {
            if (inputs.size() < PatternDraft.INPUTS) {
                inputs.add(Optional.of(new PatternDraft.Input(entry.key(), entry.amount(), tagIngredient(entry), false,
                        Optional.empty(), false)));
            }
        }
        List<Optional<PatternDraft.Output>> outputs = new ArrayList<>();
        for (PatternTransfer.Entry entry : transfer.outputs()) {
            if (outputs.size() < PatternDraft.OUTPUTS) {
                outputs.add(Optional.of(new PatternDraft.Output(entry.key(), entry.amount(), entry.chance())));
            }
        }
        setDraft(new PatternDraft(PatternDraft.Mode.PROCESSING, inputs, outputs, transfer.recipeType(),
                transfer.recipe()));
    }

    /**
     * A stonecutter or smithing-table recipe from a recipe viewer as a table draft. Stonecutting takes the first input
     * and output. Smithing puts each input into the slot (template, base, addition) whose ingredient of the named
     * recipe takes it, since a viewer leaves an empty slot out; without that recipe, in order. The output is then found
     * by recipe, as on encode; the viewer's output picks the stonecutter recipe.
     */
    private void receiveTable(ServerLevel level, PatternTransfer transfer, PatternDraft.Mode mode) {
        List<Optional<PatternDraft.Input>> inputs = new ArrayList<>();
        List<PatternTransfer.Entry> entries = transfer.inputs();
        List<@Nullable Ingredient> slots = mode == PatternDraft.Mode.SMITHING
                ? smithingSlots(level, transfer.recipe())
                : null;
        if (slots != null) {
            boolean[] used = new boolean[entries.size()];
            for (@Nullable
            Ingredient ingredient : slots) {
                Optional<PatternDraft.Input> placed = Optional.empty();
                for (int e = 0; e < entries.size() && placed.isEmpty(); e++) {
                    PatternTransfer.Entry entry = entries.get(e);
                    if (!used[e] && ingredient != null && entry.key() instanceof ItemKey item
                            && ingredient.test(item.toStack(1))) {
                        used[e] = true;
                        placed = Optional.of(tableInput(entry));
                    }
                }
                inputs.add(placed);
            }
        } else {
            for (PatternTransfer.Entry entry : entries) {
                if (inputs.size() < mode.inputs() && entry.key() instanceof ItemKey) {
                    inputs.add(Optional.of(tableInput(entry)));
                }
            }
        }
        List<Optional<PatternDraft.Output>> outputs = new ArrayList<>();
        if (!transfer.outputs().isEmpty()) {
            PatternTransfer.Entry entry = transfer.outputs().getFirst();
            outputs.add(Optional.of(new PatternDraft.Output(entry.key(), entry.amount(), 1f)));
        }
        setDraft(new PatternDraft(mode, inputs, outputs, transfer.recipeType(), transfer.recipe()));
        refreshTable(level);
    }

    private static PatternDraft.Input tableInput(PatternTransfer.Entry entry) {
        return new PatternDraft.Input(entry.key(), 1, tagIngredient(entry), false, Optional.empty(), false);
    }

    /**
     * The template, base and addition ingredients (null for a slot the recipe leaves empty) of smithing recipe
     * {@code id}, or null when it is not one.
     */
    private static @Nullable List<@Nullable Ingredient> smithingSlots(ServerLevel level, Optional<Identifier> id) {
        if (id.isEmpty()) {
            return null;
        }
        Optional<RecipeHolder<?>> holder = level.recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, id.get()));
        if (holder.isEmpty() || !(holder.get().value() instanceof SmithingRecipe recipe)) {
            return null;
        }
        List<@Nullable Ingredient> slots = new ArrayList<>(3);
        slots.add(recipe.templateIngredient().orElse(null));
        slots.add(recipe.baseIngredient());
        slots.add(recipe.additionIngredient().orElse(null));
        return slots;
    }

    private static Optional<PatternIngredient> tagIngredient(PatternTransfer.Entry entry) {
        if (entry.tag().isEmpty()) {
            return Optional.empty();
        }
        Identifier id = entry.tag().get();
        return Optional.of(entry.key() instanceof FluidKey
                ? new PatternIngredient.FluidTag(TagKey.create(Registries.FLUID, id))
                : new PatternIngredient.ItemTag(TagKey.create(Registries.ITEM, id)));
    }

    /**
     * The item tag whose contents are exactly {@code candidates}' items, if one of the first item's tags is. Several
     * tags can hold the same items (vanilla's planks and wooden tool materials); the shortest name wins, so the plain
     * material tag is picked over a purpose tag.
     */
    static Optional<TagKey<Item>> tagOf(List<ItemStack> candidates) {
        if (candidates.size() < 2) {
            return Optional.empty();
        }
        Set<Item> items = new HashSet<>();
        for (ItemStack stack : candidates) {
            items.add(stack.getItem());
        }
        List<TagKey<Item>> tags = candidates.getFirst().getItem().builtInRegistryHolder().tags().toList();
        TagKey<Item> best = null;
        for (TagKey<Item> tag : tags) {
            Optional<HolderSet.Named<Item>> set = BuiltInRegistries.ITEM.get(tag);
            if (set.isPresent() && set.get().size() == items.size()) {
                boolean all = true;
                for (Item item : items) {
                    all &= item.builtInRegistryHolder().is(tag);
                }
                if (all && (best == null || shorter(tag, best))) {
                    best = tag;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean shorter(TagKey<Item> tag, TagKey<Item> than) {
        String a = tag.location().getPath();
        String b = than.location().getPath();
        return a.length() != b.length() ? a.length() < b.length() : tag.location().compareTo(than.location()) < 0;
    }

    private static @Nullable StackKey keyOf(ItemStack stack, boolean fluid) {
        if (fluid && stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY)) {
            Fluid content = bucket.content;
            return FluidKey.of(content);
        }
        return fluid ? null : ItemKey.of(stack.copyWithCount(1));
    }

    /** A key to show for {@code ingredient}: the exact key, or the first item or fluid of the tag. */
    private static @Nullable StackKey shownKey(PatternIngredient ingredient) {
        return switch (ingredient) {
            case PatternIngredient.Exact exact -> exact.key();
            case PatternIngredient.ItemTag tag -> {
                Optional<HolderSet.Named<Item>> set = BuiltInRegistries.ITEM.get(tag.tag());
                yield set.isPresent() && set.get().size() > 0 ? ItemKey.of(set.get().get(0).value()) : null;
            }
            case PatternIngredient.FluidTag tag -> {
                Optional<HolderSet.Named<Fluid>> set = BuiltInRegistries.FLUID.get(tag.tag());
                yield set.isPresent() && set.get().size() > 0 ? FluidKey.of(set.get().get(0).value()) : null;
            }
        };
    }

    public void save(ValueOutput output) {
        if (!draft.equals(PatternDraft.EMPTY)) {
            output.store(DRAFT, PatternDraft.CODEC, draft);
        }
    }

    public void load(ValueInput input) {
        draft = input.read(DRAFT, PatternDraft.CODEC).orElse(PatternDraft.EMPTY);
        version++;
    }
}
