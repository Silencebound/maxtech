package dev.silencebound.maxtech.storage.crafting.assembler;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternTable;
import dev.silencebound.maxtech.storage.crafting.pattern.TableRecipes;
import dev.silencebound.maxtech.storage.crafting.provider.InputAssignment;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: results wait in a NeoForge item handler and are crafted inside a transaction, so a full buffer rolls
// the whole batch back (PRD 7.2: transfers are transactions).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The crafting part shared by the crafting assembler (M7 C1) and the crafter (B09 item 29): it crafts a whole batch of
 * a crafting, stonecutting or smithing pattern at once ({@link PatternTable}) into a nine-slot result buffer: the
 * results, every crafting remainder (the buckets of a cake) and, after the last craft, the returned inputs (a tool, as
 * worn as the crafts left it). The ingredients are the batch's inputs handed over by the job; they are used up by the
 * craft and never stored. A batch whose results do not all fit is refused whole.
 *
 * <p>
 * It also remembers what it crafted last for the item display ({@link #craftingItem(long)}): the batch's main output
 * until {@link #DISPLAY_TICKS} after the batch, so the display clears by itself once crafting stops, without a ticker.
 * Never ticks; server thread, except the display fields, which the holder's client copy reads.
 */
public final class AssemblerCore {
    public static final int BUFFER_SLOTS = 9;
    /** How long the item display shows a batch's output after the batch. */
    public static final int DISPLAY_TICKS = 40;
    private static final String BUFFER = "buffer";
    private static final String CRAFTING = "crafting";
    private static final String CRAFTING_UNTIL = "crafting_until";

    private final Buffer buffer = new Buffer();
    private final OutputView output = new OutputView();
    private final Runnable changed;
    private final Runnable displayChanged;
    private final LongSupplier energyPerCraft;
    /** Whether the last stonecutting or smithing batch was refused because its recipe no longer gives its output. */
    private boolean recipeChanged;
    /** The main output of the last batch, for the item display; empty before the first. */
    private ItemStack crafting = ItemStack.EMPTY;
    /** Game time until which {@link #crafting} shows. */
    private long craftingUntil;

    /**
     * @param changed
     *            marks the holder for saving
     * @param displayChanged
     *            sends the holder's display fields to clients
     * @param energyPerCraft
     *            MU the network pays per craft
     */
    public AssemblerCore(Runnable changed, Runnable displayChanged, LongSupplier energyPerCraft) {
        this.changed = changed;
        this.displayChanged = displayChanged;
        this.energyPerCraft = energyPerCraft;
    }

    /** What neighbours and the provider see: the result buffer, extract only. */
    public ResourceHandler<ItemResource> output() {
        return output;
    }

    /**
     * Whether the last batch handed over was a stonecutting or smithing one refused because no recipe gives the
     * pattern's output any more (a datapack changed it).
     */
    public boolean recipeChanged() {
        return recipeChanged;
    }

    /** Whether the buffer has an empty slot, so a batch may fit. */
    public boolean hasRoom() {
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            if (buffer.getAmountAsInt(i) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether the buffer holds nothing. */
    public boolean isEmpty() {
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            if (buffer.getAmountAsInt(i) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The item being crafted at game time {@code now}: the last batch's main output until {@link #DISPLAY_TICKS} after
     * it, else empty. The stack is the core's own; never change it.
     */
    public ItemStack craftingItem(long now) {
        return now < craftingUntil ? crafting : ItemStack.EMPTY;
    }

    /**
     * Crafts {@code crafts} crafts of {@code pattern} from {@code inputs} (key to total amount) into the buffer, all or
     * nothing; false when the recipe is unknown or does not match, an input is short, a returned tool breaks before the
     * last craft, the results do not fit or {@code network} cannot pay the energy per craft for every craft. The MU are
     * paid in the same transaction as the craft, which opens and commits its own, so no outcome crafts without paying
     * or pays without crafting.
     */
    public boolean craft(ServerLevel level, CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs,
            NetworkState network, DeviceLink payer) {
        StackKey[] keys = InputAssignment.assign(pattern, crafts, inputs);
        recipeChanged = false;
        if (keys == null || crafts < 1) {
            return false;
        }
        boolean crafted = false;
        PatternTable table = PatternTable.of(pattern);
        if (table == PatternTable.STONECUTTING || table == PatternTable.SMITHING) {
            TableRecipes.Craft craft = table == PatternTable.STONECUTTING
                    ? TableRecipes.stonecutting(level, pattern, keys)
                    : TableRecipes.smithing(level, pattern, keys);
            recipeChanged = craft == null;
            crafted = craft != null && runSimple(pattern, craft, crafts, network, payer);
        } else {
            CraftingRecipe recipe = recipe(level, pattern);
            for (AssemblerGrid.Layout layout : AssemblerGrid.layouts(recipe, pattern, keys)) {
                CraftingInput.Positioned first = CraftingInput.ofPositioned(layout.width(), layout.height(),
                        Arrays.asList(layout.cells()));
                CraftingRecipe matched = recipe != null && recipe.matches(first.input(), level)
                        ? recipe
                        : recipe == null ? find(level, first.input()) : null;
                if (matched != null) {
                    crafted = run(level, matched, layout, crafts, network, payer);
                    break;
                }
            }
        }
        if (crafted) {
            show(pattern, level.getGameTime());
        }
        return crafted;
    }

    /** Puts the batch's main output on the display; tells clients when the item changes or its time was running out. */
    private void show(CraftingPattern pattern, long now) {
        ItemStack next = pattern.mainOutput().key() instanceof ItemKey item ? item.toStack(1) : ItemStack.EMPTY;
        boolean same = now < craftingUntil && ItemStack.isSameItemSameComponents(crafting, next);
        // A batch while the same item shows only refreshes the time; clients are told when half of it has passed, so
        // a busy assembler sends at most one update per DISPLAY_TICKS / 2.
        boolean tell = !same || craftingUntil - now < DISPLAY_TICKS / 2;
        crafting = next;
        craftingUntil = now + DISPLAY_TICKS;
        if (tell) {
            displayChanged.run();
        }
    }

    private static @Nullable CraftingRecipe recipe(ServerLevel level, CraftingPattern pattern) {
        if (pattern.recipe().isEmpty()) {
            return null;
        }
        Optional<RecipeHolder<?>> holder = level.recipeAccess()
                .byKey(ResourceKey.create(Registries.RECIPE, pattern.recipe().get()));
        return holder.isPresent() && holder.get().value() instanceof CraftingRecipe crafting ? crafting : null;
    }

    private static @Nullable CraftingRecipe find(ServerLevel level, CraftingInput input) {
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).map(RecipeHolder::value)
                .orElse(null);
    }

    private boolean run(ServerLevel level, CraftingRecipe recipe, AssemblerGrid.Layout layout, int crafts,
            NetworkState network, DeviceLink payer) {
        ItemStack[] base = layout.cells();
        ItemStack[] cells = new ItemStack[base.length];
        for (int i = 0; i < base.length; i++) {
            cells[i] = base[i].copy();
        }
        try (Transaction root = Transaction.openRoot()) {
            for (int c = 0; c < crafts; c++) {
                for (int i = 0; i < base.length; i++) {
                    if (!layout.returned()[i]) {
                        cells[i] = base[i].copy();
                    }
                }
                CraftingInput.Positioned positioned = CraftingInput.ofPositioned(layout.width(), layout.height(),
                        Arrays.asList(cells));
                CraftingInput input = positioned.input();
                if (!recipe.matches(input, level)) {
                    return false;
                }
                ItemStack result = recipe.assemble(input);
                if (result.isEmpty() || !put(result, root)) {
                    return false;
                }
                // No crafting player (MenuHelper.craftingRemainders) on purpose: an autocrafting craft has no player,
                // the network's owner may be offline, and the craft runs in a transaction that may still roll back, so
                // no player hook or crafted event may see it; vanilla's crafter block does the same.
                List<ItemStack> remainders = recipe.getRemainingItems(input);
                for (int r = 0; r < remainders.size(); r++) {
                    int cell = (r / input.width() + positioned.top()) * layout.width() + r % input.width()
                            + positioned.left();
                    ItemStack remainder = remainders.get(r);
                    if (layout.returned()[cell]) {
                        cells[cell] = remainder.copy();
                    } else if (!remainder.isEmpty() && !put(remainder, root)) {
                        return false;
                    }
                }
            }
            for (int i = 0; i < cells.length; i++) {
                if (layout.returned()[i] && !cells[i].isEmpty() && !put(cells[i], root)) {
                    return false;
                }
            }
            if (!network.payAutomation(payer, energyPerCraft.getAsLong() * crafts, root)) {
                return false;
            }
            root.commit();
            return true;
        }
    }

    /**
     * Runs {@code crafts} crafts of a stonecutting or smithing recipe: each consumes its inputs whole (no remainders,
     * no returned inputs) and gives one result into the buffer; the MU are paid in the same transaction.
     */
    private boolean runSimple(CraftingPattern pattern, TableRecipes.Craft craft, int crafts, NetworkState network,
            DeviceLink payer) {
        try (Transaction root = Transaction.openRoot()) {
            for (int c = 0; c < crafts; c++) {
                ItemStack result = craft.assemble();
                // Another item or count than the pattern promises: refuse the batch, nothing is taken.
                if (!TableRecipes.gives(pattern, result)) {
                    recipeChanged = true;
                    return false;
                }
                if (!put(result, root)) {
                    return false;
                }
            }
            if (!network.payAutomation(payer, energyPerCraft.getAsLong() * crafts, root)) {
                return false;
            }
            root.commit();
            return true;
        }
    }

    private boolean put(ItemStack stack, TransactionContext transaction) {
        return buffer.insert(ItemResource.of(stack), stack.getCount(), transaction) == stack.getCount();
    }

    /** The buffer's contents, for tests and the tooltip. */
    public List<ItemStack> contents() {
        List<ItemStack> stacks = new ArrayList<>(BUFFER_SLOTS);
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            int amount = buffer.getAmountAsInt(i);
            if (amount > 0) {
                stacks.add(buffer.getResource(i).toStack(amount));
            }
        }
        return stacks;
    }

    /** Writes the buffer into the holder's save. */
    public void save(ValueOutput output) {
        buffer.serialize(output.child(BUFFER));
    }

    /** Reads {@link #save}'s buffer; absent on a client update, which leaves the buffer as it is. */
    public void load(ValueInput input) {
        input.child(BUFFER).ifPresent(buffer::deserialize);
    }

    /**
     * Writes the display fields into the holder's client update while an item shows ({@link #craftingItem(long)}), else
     * nothing.
     */
    public void saveDisplay(ValueOutput output, long now) {
        if (now < craftingUntil && !crafting.isEmpty()) {
            output.store(CRAFTING, ItemStack.CODEC, crafting);
            output.putLong(CRAFTING_UNTIL, craftingUntil);
        }
    }

    /** Reads {@link #saveDisplay}'s fields on a client; an update without them clears the display. */
    public void loadDisplay(ValueInput input) {
        crafting = input.read(CRAFTING, ItemStack.CODEC).orElse(ItemStack.EMPTY);
        craftingUntil = crafting.isEmpty() ? 0 : input.getLongOr(CRAFTING_UNTIL, 0L);
    }

    /** Puts the buffer, when anything is in it, into {@code type} of a dropped item. */
    public void write(DataComponentMap.Builder components, DataComponentType<ItemContainerContents> type) {
        List<ItemStack> held = new ArrayList<>(BUFFER_SLOTS);
        boolean any = false;
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            int amount = buffer.getAmountAsInt(i);
            held.add(amount > 0 ? buffer.getResource(i).toStack(amount) : ItemStack.EMPTY);
            any |= amount > 0;
        }
        if (any) {
            components.set(type, ItemContainerContents.fromItems(held));
        }
    }

    /** Reads {@link #write}'s buffer from a placed item, replacing the buffer. */
    public void read(DataComponentGetter components, DataComponentType<ItemContainerContents> type) {
        NonNullList<ItemStack> held = NonNullList.withSize(BUFFER_SLOTS, ItemStack.EMPTY);
        ItemContainerContents contents = components.get(type);
        if (contents != null) {
            contents.copyInto(held);
        }
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            ItemStack stack = held.get(i);
            buffer.set(i, ItemResource.of(stack), stack.getCount());
        }
    }

    /** Nine result slots; a change marks the holder for saving. */
    private final class Buffer extends ItemStacksResourceHandler {
        Buffer() {
            super(BUFFER_SLOTS);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            changed.run();
        }
    }

    /** The buffer as neighbours see it: they may take, never put. */
    private final class OutputView implements ResourceHandler<ItemResource> {
        @Override
        public int size() {
            return BUFFER_SLOTS;
        }

        @Override
        public ItemResource getResource(int index) {
            return buffer.getResource(index);
        }

        @Override
        public long getAmountAsLong(int index) {
            return buffer.getAmountAsLong(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return buffer.getCapacityAsLong(index, resource);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return false;
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            return 0;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            return buffer.extract(index, resource, amount, transaction);
        }
    }
}
