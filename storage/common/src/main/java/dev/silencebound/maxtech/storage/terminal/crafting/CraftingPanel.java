package dev.silencebound.maxtech.storage.terminal.crafting;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridFill;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.IngredientSource;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkActor;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalComfort;
import dev.silencebound.maxtech.storage.terminal.TerminalLayout;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.TabletRequests;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
// PLATFORM-LEAK: every move between the grid and the network runs in a NeoForge transaction (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The crafting part of a crafting terminal's menu: the grid's nine slots and the result slot over the terminal's
 * {@link TerminalGrid}, and what they do on the server.
 *
 * <ul>
 * <li>The result is worked out again whenever the grid changed ({@link #refresh}): in the menu's broadcast, before a
 * take, and after this menu's own changes. Several players at one terminal share the grid.</li>
 * <li>Taking the result consumes one of each ingredient, as vanilla's table does, and then refills each emptied cell
 * with one of the same item, from the network first and then the player's inventory. Shift-clicking it crafts again and
 * again into the inventory, up to {@link #MAX_SHIFT_CRAFTS} times, while the result stays the same and fits.</li>
 * <li>Remainders (an empty bucket) go to the network, then to their emptied cell, the inventory or the ground. A
 * remainder of the ingredient's own item (a tool that wears down) stays in its cell, so the next craft can use it.</li>
 * <li>A recipe viewer's "+" fills the grid from the network first ({@link #receiveRecipe}) and remembers the recipe for
 * the request button ({@link #requestMissing}). The clear button returns the grid to the network ({@link #clear}).</li>
 * </ul>
 *
 * Items only ever move: each pull and return is one committed transaction that counts what moved, and the result is
 * handed out only after its ingredients left the grid. Taking from the network needs the take right, returning needs
 * the deposit right; without them the inventory stands in. The client's panel has no source and only mirrors slots.
 */
public final class CraftingPanel {
    /**
     * Most crafts one shift-click makes; as many as a vanilla table makes from full stacks. A bound on the work of one
     * click, not a balance value.
     */
    public static final int MAX_SHIFT_CRAFTS = 64;
    /**
     * Fewest ticks between two requests from one menu. A bound on how fast a client can queue planner work, not a
     * balance value.
     */
    public static final int REQUEST_COOLDOWN = 20;

    private final TerminalGrid grid;
    private final ResultContainer results = new ResultContainer();
    private final @Nullable TerminalSource source;
    private final Player player;
    private List<Slot> gridSlots;
    private PanelResultSlot resultSlot;
    /** What the last recipe transfer asked for, per cell; server only. */
    private List<List<ItemStack>> wanted = List.of();
    private @Nullable RecipeHolder<CraftingRecipe> lastRecipe;
    private int seenVersion = -1;
    private long lastRequest = Long.MIN_VALUE / 2;

    /**
     * @param source
     *            the terminal on the server; null on the client
     */
    public CraftingPanel(TerminalGrid grid, @Nullable TerminalSource source, Player player, TerminalLayout layout) {
        this.grid = grid;
        this.source = source;
        this.player = player;
        this.gridSlots = gridSlots(grid, layout);
        this.resultSlot = new PanelResultSlot(player, layout.resultX(), layout.resultY());
    }

    private static List<Slot> gridSlots(TerminalGrid grid, TerminalLayout layout) {
        List<Slot> slots = new ArrayList<>(TerminalGrid.SIZE);
        for (int cell = 0; cell < TerminalGrid.SIZE; cell++) {
            slots.add(new Slot(grid, cell, layout.cellX(cell), layout.cellY(cell)));
        }
        return List.copyOf(slots);
    }

    /**
     * Makes the grid and result slots again at {@code layout}'s positions, for the client's menu when the terminal
     * screen changes its size ({@code Slot} positions are final). The menu puts the new slots in place of the old.
     */
    public void relayout(TerminalLayout layout) {
        this.gridSlots = gridSlots(grid, layout);
        this.resultSlot = new PanelResultSlot(player, layout.resultX(), layout.resultY());
    }

    /** The grid's slots, cell 0 first. */
    public List<Slot> gridSlots() {
        return gridSlots;
    }

    public Slot resultSlot() {
        return resultSlot;
    }

    public TerminalGrid grid() {
        return grid;
    }

    /** The result of the grid's recipe as last worked out, or empty. */
    public ItemStack result() {
        return results.getItem(0);
    }

    @SuppressWarnings("ReferenceEquality") // the result container is one object
    public boolean isResults(Container container) {
        return container == results;
    }

    /** Works the result out again if the grid changed since; server only. */
    public void refresh(Player player) {
        if (source != null && player instanceof ServerPlayer serverPlayer && grid.version() != seenVersion) {
            updateResult(serverPlayer);
        }
    }

    private void updateResult(ServerPlayer player) {
        seenVersion = grid.version();
        ServerLevel level = player.level();
        CraftingInput input = grid.asCraftInput();
        ItemStack result = ItemStack.EMPTY;
        RecipeHolder<CraftingRecipe> hint = lastRecipe;
        Optional<RecipeHolder<CraftingRecipe>> found = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level, hint);
        if (found.isPresent()) {
            RecipeHolder<CraftingRecipe> holder = found.get();
            lastRecipe = holder;
            if (results.setRecipeUsed(player, holder)) {
                ItemStack assembled = holder.value().assemble(input);
                if (assembled.isItemEnabled(level.enabledFeatures())) {
                    result = assembled;
                }
            }
        }
        results.setItem(0, result);
    }

    /** The network as a recipe transfer's source, within the player's rights. */
    public IngredientSource ingredients(ServerPlayer player) {
        return new IngredientSource() {
            @Override
            public ItemStack extract(ItemStack like, int amount) {
                return take(player, like, amount);
            }

            @Override
            public ItemStack insert(ItemStack stack) {
                return store(player, stack.copy());
            }
        };
    }

    /** A recipe viewer's "+": remembers the recipe's cells, then fills the grid, network first. */
    public void receiveRecipe(CraftingGridMenu menu, ServerPlayer player, List<List<ItemStack>> cells,
            boolean maxTransfer) {
        List<List<ItemStack>> copy = new ArrayList<>(cells.size());
        for (List<ItemStack> candidates : cells) {
            List<ItemStack> kept = new ArrayList<>(candidates.size());
            for (ItemStack candidate : candidates) {
                if (!candidate.isEmpty()) {
                    kept.add(candidate.copyWithCount(1));
                }
            }
            copy.add(List.copyOf(kept));
        }
        wanted = List.copyOf(copy);
        UUID previous = NetworkActor.begin(player);
        try {
            CraftingGridFill.fill(menu, player, cells, maxTransfer);
        } finally {
            NetworkActor.end(previous);
        }
        refresh(player);
    }

    /** The clear button: every stack in the grid goes to the network, what does not fit to the player. */
    public boolean clear(ServerPlayer player) {
        boolean moved = false;
        UUID previous = NetworkActor.begin(player);
        try {
            for (int cell = 0; cell < TerminalGrid.SIZE; cell++) {
                ItemStack stack = grid.removeItemNoUpdate(cell);
                if (!stack.isEmpty()) {
                    moved = true;
                    giveOrDrop(player, store(player, stack));
                }
            }
        } finally {
            NetworkActor.end(previous);
        }
        refresh(player);
        return moved;
    }

    /**
     * Shift-click on the result: crafts into the inventory until the result changes or runs out, the inventory has no
     * room for another together with its remainders ({@link #fits}), or {@link #MAX_SHIFT_CRAFTS} crafts are made.
     * Returns the crafts made.
     */
    public int craftMany(ServerPlayer player) {
        refresh(player);
        ItemStack first = results.getItem(0).copy();
        int crafts = 0;
        while (!first.isEmpty() && crafts < MAX_SHIFT_CRAFTS) {
            ItemStack result = results.getItem(0);
            if (result.isEmpty() || !ItemStack.isSameItemSameComponents(result, first)
                    || result.getCount() != first.getCount() || !fits(player, result)) {
                break;
            }
            ItemStack taken = resultSlot.remove(result.getCount());
            // Consumes the grid and refills it; the result is handed out only after that.
            resultSlot.onTake(player, taken);
            giveOrDrop(player, taken);
            crafts++;
        }
        return crafts;
    }

    /**
     * Whether the inventory takes {@code result} and still has a free slot for each remainder of the craft, which lands
     * there when the network and its cell refuse it. Counts merging only for the result, so it errs towards stopping
     * early rather than dropping anything on the ground.
     */
    private boolean fits(ServerPlayer player, ItemStack result) {
        if (TerminalComfort.room(player, result) < result.getCount()) {
            return false;
        }
        int remainders = 0;
        for (ItemStack remainder : remainders(player, grid.asCraftInput())) {
            if (!remainder.isEmpty()) {
                remainders++;
            }
        }
        if (remainders == 0) {
            return true;
        }
        Inventory inventory = player.getInventory();
        int free = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inventory.getItem(i).isEmpty()) {
                free++;
            }
        }
        int resultSlots = (result.getCount() + result.getMaxStackSize() - 1) / result.getMaxStackSize();
        return free >= remainders + resultSlots;
    }

    /**
     * The request button: orders a crafting job for each ingredient of the last transferred recipe that the grid still
     * lacks and the network does not hold, through {@link TabletRequests}, as the crafting tablet does. Needs the craft
     * access level; at most once every {@link #REQUEST_COOLDOWN} ticks.
     */
    public boolean requestMissing(ServerPlayer player) {
        ServerLevel level = player.level();
        long now = level.getGameTime();
        TerminalSource from = source;
        if (now - lastRequest < REQUEST_COOLDOWN || from == null) {
            return false;
        }
        lastRequest = now;
        NetworkState network = from.network();
        if (network == null || !network.accessLevel(player).atLeast(AccessLevel.CRAFT)) {
            tell(player, Component.translatable(PatternTerminalContent.DENIED_KEY));
            return false;
        }
        Object2LongMap<ItemKey> missing = new Object2LongLinkedOpenHashMap<>();
        boolean unknown = false;
        for (int cell = 0; cell < wanted.size() && cell < TerminalGrid.SIZE; cell++) {
            List<ItemStack> candidates = wanted.get(cell);
            if (candidates.isEmpty() || !grid.getItem(cell).isEmpty() || inStock(network, candidates)) {
                continue;
            }
            ItemKey pick = craftable(network, candidates);
            if (pick != null) {
                missing.put(pick, missing.getLong(pick) + 1);
            } else {
                unknown = true;
            }
        }
        if (missing.isEmpty()) {
            tell(player, Component.translatable(
                    unknown ? CraftingTabletContent.NO_PATTERN_KEY : CraftingTabletContent.NOTHING_MISSING_KEY));
            return false;
        }
        for (Object2LongMap.Entry<ItemKey> entry : missing.object2LongEntrySet()) {
            TabletRequests.requester().request(player, network, level, entry.getKey(), entry.getLongValue());
        }
        return true;
    }

    private static @Nullable ItemKey craftable(NetworkState network, List<ItemStack> candidates) {
        for (ItemStack candidate : candidates) {
            ItemKey key = ItemKey.of(candidate);
            if (network.patterns().isCraftable(key)) {
                return key;
            }
        }
        return null;
    }

    private static boolean inStock(NetworkState network, List<ItemStack> candidates) {
        for (ItemStack candidate : candidates) {
            if (network.contents().amount(ItemKey.of(candidate)) > 0) {
                return true;
            }
        }
        return false;
    }

    private static void tell(ServerPlayer player, Component message) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            player.sendOverlayMessage(message);
        }
    }

    /**
     * Consumes one craft's ingredients, refills emptied cells and places the remainders; see the class comment. Server
     * only.
     */
    private void consume(ServerPlayer player) {
        CraftingInput.Positioned positioned = grid.asPositionedCraftInput();
        CraftingInput input = positioned.input();
        NonNullList<ItemStack> remaining = remainders(player, input);
        UUID previous = NetworkActor.begin(player);
        try {
            for (int y = 0; y < input.height(); y++) {
                for (int x = 0; x < input.width(); x++) {
                    int cell = x + positioned.left() + (y + positioned.top()) * grid.getWidth();
                    ItemStack held = grid.getItem(cell);
                    ItemStack used = ItemStack.EMPTY;
                    if (!held.isEmpty()) {
                        used = held.copyWithCount(1);
                        grid.removeItem(cell, 1);
                        if (grid.getItem(cell).isEmpty()) {
                            refill(player, cell, used);
                        }
                    }
                    ItemStack remainder = remaining.get(x + y * input.width());
                    if (!remainder.isEmpty()) {
                        placeRemainder(player, cell, used, remainder.copy());
                    }
                }
            }
        } finally {
            NetworkActor.end(previous);
        }
        refresh(player);
    }

    private NonNullList<ItemStack> remainders(ServerPlayer player, CraftingInput input) {
        ServerLevel level = player.level();
        RecipeHolder<CraftingRecipe> hint = lastRecipe;
        Optional<RecipeHolder<CraftingRecipe>> found = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level, hint);
        if (found.isPresent()) {
            // With the crafting player set, as vanilla's result slot does.
            return Platform.get().menus().craftingRemainders(player, found.get().value(), input);
        }
        // As vanilla's result slot: with no recipe every input comes back, so nothing is used up.
        NonNullList<ItemStack> all = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int slot = 0; slot < all.size(); slot++) {
            all.set(slot, input.getItem(slot).copyWithCount(1));
        }
        return all;
    }

    /** Fills the emptied cell {@code cell} with one {@code like}: from the network, else the inventory. */
    private void refill(ServerPlayer player, int cell, ItemStack like) {
        ItemStack taken = take(player, like, 1);
        if (taken.isEmpty()) {
            taken = fromInventory(player, like);
        }
        if (!taken.isEmpty()) {
            grid.setItem(cell, taken);
        }
    }

    private void placeRemainder(ServerPlayer player, int cell, ItemStack used, ItemStack remainder) {
        ItemStack inCell = grid.getItem(cell);
        if (inCell.isEmpty() && !used.isEmpty() && remainder.is(used.getItem())) {
            // A tool that wears down stays where the recipe wants it.
            grid.setItem(cell, remainder);
            return;
        }
        ItemStack rest = store(player, remainder);
        if (rest.isEmpty()) {
            return;
        }
        if (inCell.isEmpty()) {
            grid.setItem(cell, rest);
        } else {
            giveOrDrop(player, rest);
        }
    }

    /** Takes up to {@code amount} of {@code like} out of the network; empty without the take right. */
    private ItemStack take(ServerPlayer player, ItemStack like, int amount) {
        TerminalSource from = source;
        if (from == null || like.isEmpty() || amount <= 0 || !from.canAccess(player) || !from.canTake(player)) {
            return ItemStack.EMPTY;
        }
        ItemKey key = ItemKey.of(like);
        long moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = from.extract(key, amount, transaction);
            if (moved <= 0) {
                return ItemStack.EMPTY;
            }
            transaction.commit();
        }
        return key.toStack((int) moved);
    }

    /** Stores {@code stack} in the network; returns what did not go in (all of it without the deposit right). */
    private ItemStack store(ServerPlayer player, ItemStack stack) {
        TerminalSource from = source;
        if (from == null || stack.isEmpty() || !from.canAccess(player) || !from.canDeposit(player)) {
            return stack;
        }
        long moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = from.insert(ItemKey.of(stack), stack.getCount(), transaction);
            if (moved <= 0) {
                return stack;
            }
            transaction.commit();
        }
        return stack.copyWithCount(stack.getCount() - (int) moved);
    }

    private static ItemStack fromInventory(Player player, ItemStack like) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, like)) {
                return inventory.removeItem(i, 1);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        // add() shrinks the stack by what it placed.
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    /** Vanilla's result slot, with the grid worked by {@link #consume} instead of vanilla's take. */
    private final class PanelResultSlot extends ResultSlot {
        PanelResultSlot(Player player, int x, int y) {
            super(player, grid, results, 0, x, y);
        }

        /** The result is checked against the grid first: another player may have changed it this tick. */
        @Override
        public boolean mayPickup(Player taker) {
            refresh(taker);
            return hasItem() && super.mayPickup(taker);
        }

        @Override
        public void onTake(Player taker, ItemStack carried) {
            if (source == null || !(taker instanceof ServerPlayer serverPlayer)) {
                // The client's grid is a mirror; the server's slots correct it.
                return;
            }
            checkTakeAchievements(carried);
            consume(serverPlayer);
        }
    }
}
