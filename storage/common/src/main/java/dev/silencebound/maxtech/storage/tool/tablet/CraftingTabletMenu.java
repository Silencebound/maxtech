package dev.silencebound.maxtech.storage.tool.tablet;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridFill;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.IngredientSource;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.jspecify.annotations.Nullable;

/**
 * The crafting tablet's GUI: vanilla's 3x3 grid, result and inventory, with the bound network behind it.
 *
 * <ul>
 * <li>A recipe viewer's "+" fills the grid ({@link #receiveRecipe}) from the network first, then the player's
 * inventory; the grid is emptied into the network first (items only move, so nothing is created or lost). Without
 * reach, power or the right to take, it works from the inventory alone, like a crafting table.</li>
 * <li>Taking the result consumes the grid as vanilla does, then refills each emptied cell from the recipe the viewer
 * sent, again network first.</li>
 * <li>The request button ({@link #REQUEST}) orders a crafting job for the ingredients of that recipe the grid still
 * lacks, through {@link TabletRequests}; it needs the craft access level.</li>
 * <li>The jobs button ({@link #JOBS}) shows the network's crafting jobs ({@link TabletJobs}); it needs the craft access
 * level.</li>
 * <li>Closing hands the grid back to the player.</li>
 * </ul>
 *
 * Slots as in vanilla's crafting table: 0 is the result, 1 to 9 the grid, then the inventory. Server thread; the
 * client's menu has no access and only mirrors slots and data.
 */
public final class CraftingTabletMenu extends AbstractContainerMenu implements CraftingGridMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 166;
    public static final int GRID_X = 30;
    public static final int GRID_Y = 17;
    public static final int RESULT_X = 124;
    public static final int RESULT_Y = 35;
    public static final int INVENTORY_Y = 84;
    /** The menu button that requests the missing ingredients. */
    public static final int REQUEST = 0;
    /** The menu button that opens the network's crafting jobs (B09 item 23). */
    public static final int JOBS = 1;
    /**
     * Data slots: the tablet's FE, its capacity and the reach (0 in reach and powered, 1 out of power, 2 out of reach).
     */
    public static final int ENERGY = 0;
    public static final int CAPACITY = 1;
    public static final int REACH = 2;
    /** Values of the {@link #REACH} data slot. */
    private static final int REACH_OK = 0;
    private static final int REACH_NO_POWER = 1;
    private static final int REACH_AWAY = 2;
    private static final int DATA = 3;
    /**
     * Fewest ticks between two requests from one menu. A bound on how fast a client can queue planner work, not a
     * balance value.
     */
    public static final int REQUEST_COOLDOWN = 20;

    private final @Nullable TabletAccess access;
    private final Player player;
    private final ContainerData data;
    private final CraftingContainer craftSlots = new TransientCraftingContainer(this, 3, 3);
    private final ResultContainer resultSlots = new ResultContainer();
    /** What the last recipe transfer asked for, per cell; server only. */
    private List<List<ItemStack>> wanted = List.of();
    private long lastRequest = Long.MIN_VALUE / 2;

    private CraftingTabletMenu(int containerId, Inventory inventory, @Nullable TabletAccess access,
            ContainerData data) {
        super(CraftingTabletContent.MENU.get(), containerId);
        this.access = access;
        this.player = inventory.player;
        this.data = data;
        addSlot(new TabletResultSlot(player, craftSlots, resultSlots, RESULT_X, RESULT_Y));
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                addSlot(new Slot(craftSlots, x + y * 3, GRID_X + x * 18, GRID_Y + y * 18));
            }
        }
        addStandardInventorySlots(inventory, 8, INVENTORY_Y);
        addDataSlots(data);
    }

    /** The client's menu; its data comes from the server. */
    public static CraftingTabletMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        return new CraftingTabletMenu(containerId, inventory, null, new SimpleContainerData(DATA));
    }

    /** The server's menu over {@code access}. */
    public static CraftingTabletMenu server(int containerId, Inventory inventory, TabletAccess access) {
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case ENERGY -> access.energy();
                    case CAPACITY -> CraftingTabletItem.capacity();
                    case REACH -> reachNow(access);
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA;
            }
        };
        return new CraftingTabletMenu(containerId, inventory, access, data);
    }

    private static int reachNow(TabletAccess access) {
        if (access.network() == null) {
            return REACH_AWAY;
        }
        return access.energy() < CraftingTabletItem.energyPerOperation() ? REACH_NO_POWER : REACH_OK;
    }

    public int energy() {
        return data.get(ENERGY);
    }

    public int capacity() {
        return data.get(CAPACITY);
    }

    /** Whether the tablet reaches the network and can pay (the screen greys the request button otherwise). */
    public boolean reaches() {
        return data.get(REACH) == REACH_OK;
    }

    /** The result of the grid's recipe now, or empty. */
    public ItemStack result() {
        return resultSlots.getItem(0);
    }

    /** The grid, for tests. */
    public CraftingContainer grid() {
        return craftSlots;
    }

    @Override
    public int gridSlot(int cell) {
        return 1 + cell;
    }

    @Override
    public IngredientSource ingredientSource(ServerPlayer sender) {
        TabletAccess current = access;
        return current != null && current.canView() ? current.ingredients() : IngredientSource.NONE;
    }

    /** A recipe viewer's "+": remembers the recipe's cells, then fills the grid; see the class comment. */
    @Override
    public void receiveRecipe(ServerPlayer sender, List<List<ItemStack>> cells, boolean maxTransfer) {
        List<List<ItemStack>> copy = new ArrayList<>(cells.size());
        for (List<ItemStack> candidates : cells) {
            List<ItemStack> kept = new ArrayList<>(candidates.size());
            for (ItemStack candidate : candidates) {
                kept.add(candidate.copyWithCount(1));
            }
            copy.add(List.copyOf(kept));
        }
        wanted = List.copyOf(copy);
        CraftingGridFill.fill(this, sender, cells, maxTransfer);
    }

    @Override
    public void slotsChanged(Container container) {
        if (player instanceof ServerPlayer serverPlayer && player.level() instanceof ServerLevel level) {
            CraftingInput input = craftSlots.asCraftInput();
            ItemStack result = ItemStack.EMPTY;
            var maybeRecipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
            if (maybeRecipe.isPresent()) {
                RecipeHolder<CraftingRecipe> holder = maybeRecipe.get();
                if (resultSlots.setRecipeUsed(serverPlayer, holder)) {
                    ItemStack assembled = holder.value().assemble(input);
                    if (assembled.isItemEnabled(level.enabledFeatures())) {
                        result = assembled;
                    }
                }
            }
            resultSlots.setItem(0, result);
            setRemoteSlot(0, result);
            if (serverPlayer.connection != null) {
                serverPlayer.connection
                        .send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), 0, result));
            }
        }
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        TabletAccess current = access;
        if (id == JOBS && current != null && clicker instanceof ServerPlayer serverPlayer) {
            if (!TabletJobs.open(serverPlayer, current, Component.translatable(RequestContent.JOBS_TITLE_KEY))) {
                CraftingTabletItem.tell(serverPlayer, Component.translatable(CraftingTabletContent.DENIED_KEY));
                return false;
            }
            return true;
        }
        if (id != REQUEST || current == null || !(clicker instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        long now = serverPlayer.level().getGameTime();
        if (now - lastRequest < REQUEST_COOLDOWN) {
            return false;
        }
        lastRequest = now;
        NetworkState network = current.network();
        ServerLevel level = current.level();
        if (network == null || level == null || !current.canCraft()) {
            CraftingTabletItem.tell(serverPlayer, Component.translatable(CraftingTabletContent.DENIED_KEY));
            return false;
        }
        Object2LongMap<ItemKey> missing = new Object2LongLinkedOpenHashMap<>();
        boolean unknown = false;
        for (int cell = 0; cell < wanted.size() && cell < CraftingGridMenu.GRID_SIZE; cell++) {
            List<ItemStack> candidates = wanted.get(cell);
            if (candidates.isEmpty() || !craftSlots.getItem(cell).isEmpty()) {
                continue;
            }
            ItemKey pick = pickMissing(network, candidates);
            if (pick != null) {
                missing.put(pick, missing.getLong(pick) + 1);
            } else if (!inStock(network, candidates)) {
                unknown = true;
            }
        }
        if (missing.isEmpty()) {
            CraftingTabletItem.tell(serverPlayer, Component.translatable(
                    unknown ? CraftingTabletContent.NO_PATTERN_KEY : CraftingTabletContent.NOTHING_MISSING_KEY));
            return false;
        }
        if (!current.pay()) {
            CraftingTabletItem.tell(serverPlayer, Component.translatable(SignalContent.WIRELESS_NO_POWER_KEY));
            return false;
        }
        for (Object2LongMap.Entry<ItemKey> entry : missing.object2LongEntrySet()) {
            TabletRequests.requester().request(serverPlayer, network, level, entry.getKey(), entry.getLongValue());
        }
        return true;
    }

    /**
     * The key of {@code candidates} to order: none when the network holds one (the cell stayed empty for lack of power
     * or rights, so a job would not help), else the first with a pattern, or null.
     */
    private static @Nullable ItemKey pickMissing(NetworkState network, List<ItemStack> candidates) {
        if (inStock(network, candidates)) {
            return null;
        }
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

    /** Refills grid cell {@code cell} with one item of the recipe last sent, network first; for the result slot. */
    private void refill(int cell) {
        if (cell >= wanted.size() || !craftSlots.getItem(cell).isEmpty()) {
            return;
        }
        TabletAccess current = access;
        for (ItemStack candidate : wanted.get(cell)) {
            ItemStack taken = current != null && current.canView() ? current.take(candidate, 1) : ItemStack.EMPTY;
            if (taken.isEmpty()) {
                taken = fromInventory(candidate);
            }
            if (!taken.isEmpty()) {
                craftSlots.setItem(cell, taken);
                return;
            }
        }
    }

    private ItemStack fromInventory(ItemStack like) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, like)) {
                return inventory.removeItem(i, 1);
            }
        }
        return ItemStack.EMPTY;
    }

    /** The result slot: vanilla's, plus a refill of every emptied cell once the craft took its inputs. */
    private final class TabletResultSlot extends ResultSlot {
        TabletResultSlot(Player player, CraftingContainer craftSlots, Container results, int x, int y) {
            super(player, craftSlots, results, 0, x, y);
        }

        @Override
        public void onTake(Player taker, ItemStack carried) {
            super.onTake(taker, carried);
            if (taker instanceof ServerPlayer) {
                for (int cell = 0; cell < CraftingGridMenu.GRID_SIZE; cell++) {
                    refill(cell);
                }
            }
        }
    }

    /** Vanilla's crafting table shift-click rules. */
    @Override
    public ItemStack quickMoveStack(Player clicker, int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            clicked = stack.copy();
            if (slotIndex == 0) {
                stack.getItem().onCraftedBy(stack, clicker);
                if (!moveItemStackTo(stack, 10, 46, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(stack, clicked);
            } else if (slotIndex >= 10 && slotIndex < 46) {
                if (!moveItemStackTo(stack, 1, 10, false)) {
                    if (slotIndex < 37) {
                        if (!moveItemStackTo(stack, 37, 46, false)) {
                            return ItemStack.EMPTY;
                        }
                    } else if (!moveItemStackTo(stack, 10, 37, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            } else if (!moveItemStackTo(stack, 10, 46, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == clicked.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(clicker, stack);
            if (slotIndex == 0) {
                clicker.drop(stack, false);
            }
        }
        return clicked;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // the result container is one object
    public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
        return target.container != resultSlots && super.canTakeItemForPickAll(carried, target);
    }

    @Override
    public boolean stillValid(Player viewer) {
        TabletAccess current = access;
        return current == null || current.network() != null;
    }

    /** The grid goes back to the player, as at a crafting table. */
    @Override
    public void removed(Player viewer) {
        super.removed(viewer);
        clearContainer(viewer, craftSlots);
    }

    /** Whether the stack in the grid cell {@code cell} is non-empty; for tests. */
    public boolean filled(int cell) {
        return !craftSlots.getItem(cell).isEmpty();
    }
}
