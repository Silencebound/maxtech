package dev.silencebound.maxtech.storage.crafting.stock;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The stock keeper's GUI: one rule at a time, picked with the arrow buttons. The ghost slot sets the rule's item; the
 * step buttons change the keep amount, the restart threshold or the batch size, whichever the field button picked.
 * Every edit is a vanilla menu button click, so it needs no payload; the rule travels back as data slots (amounts in
 * four 16-bit pieces). The terminal's stock tab edits rules through {@link StockPayloads} instead.
 */
public final class StockKeeperMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 204;
    public static final int SLOT_X = 8;
    public static final int SLOT_Y = 38;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 122;

    public static final int PREVIOUS_RULE = 0;
    public static final int NEXT_RULE = 1;
    public static final int CYCLE_FIELD = 2;
    public static final int PRIORITY_DOWN = 3;
    public static final int PRIORITY_UP = 4;
    public static final int CYCLE_CPU = 5;
    public static final int CYCLE_REDSTONE = 6;
    /** Buttons {@code FIRST_STEP + i} change the picked amount by {@link #STEPS}{@code [i]}. */
    public static final int FIRST_STEP = 7;
    /** Steps: -64, -16, -1, +1, +16, +64, then divide and multiply by ten. */
    public static final int STEP_COUNT = 8;
    /** Switches the shown rule on or off (a logic rule may switch it too). */
    public static final int TOGGLE_ON = FIRST_STEP + STEP_COUNT;
    private static final long[] STEPS = {-64, -16, -1, 1, 16, 64};

    /** The amount the step buttons change. */
    public enum Field {
        KEEP, RESTART, BATCH;

        /** The id the GUI syncs. */
        public int id() {
            return switch (this) {
                case KEEP -> 0;
                case RESTART -> 1;
                case BATCH -> 2;
            };
        }

        /** The field with {@code id}; {@link #KEEP} for an unknown one. */
        public static Field byId(int id) {
            return switch (id) {
                case 1 -> RESTART;
                case 2 -> BATCH;
                default -> KEEP;
            };
        }

        public Field next() {
            return byId((id() + 1) % 3);
        }

        public String translationKey() {
            return switch (this) {
                case KEEP -> StockTexts.KEEP;
                case RESTART -> StockTexts.RESTART;
                case BATCH -> StockTexts.BATCH;
            };
        }
    }

    private static final int SELECTED = 0;
    private static final int FIELD = 1;
    private static final int KEEP = 2;
    private static final int RESTART = 6;
    private static final int BATCH = 10;
    private static final int PRIORITY = 14;
    private static final int CPU = 15;
    private static final int REDSTONE = 16;
    private static final int FILLING = 17;
    private static final int RULE_SLOTS = 18;
    private static final int SWITCHED_OFF = 19;
    private static final int DATA = 20;

    private final @Nullable StockKeeperBlockEntity keeper;
    private final Container ghost;
    private final DataSlot[] data = new DataSlot[DATA];
    private int selected;
    private Field field = Field.KEEP;

    private StockKeeperMenu(int containerId, Inventory inventory, @Nullable StockKeeperBlockEntity keeper,
            Container ghost) {
        super(ModMenus.STOCK_KEEPER.get(), containerId);
        this.keeper = keeper;
        this.ghost = ghost;
        addSlot(new GhostSlot(ghost, SLOT_X, SLOT_Y));
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        for (int i = 0; i < DATA; i++) {
            data[i] = keeper == null ? DataSlot.standalone() : new Live(i);
            addDataSlot(data[i]);
        }
    }

    /** The client's menu; data slots and slot sync fill it. */
    public static StockKeeperMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new StockKeeperMenu(containerId, inventory, null, new SimpleContainer(1));
    }

    /** The server's menu over {@code keeper}'s live rules. */
    public static StockKeeperMenu forKeeper(int containerId, Inventory inventory, StockKeeperBlockEntity keeper) {
        return new StockKeeperMenu(containerId, inventory, keeper, new SimpleContainer(1));
    }

    /** Opens {@code keeper}'s GUI for {@code player}. */
    public static void open(ServerPlayer player, StockKeeperBlockEntity keeper, BlockPos pos, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forKeeper(id, inventory, keeper), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    /** The rule shown, 0-based. */
    public int selected() {
        return data[SELECTED].get();
    }

    public Field field() {
        return Field.byId(data[FIELD].get());
    }

    public long keep() {
        return amount(KEEP);
    }

    public long restart() {
        return amount(RESTART);
    }

    public long batch() {
        return amount(BATCH);
    }

    public int priority() {
        return (short) data[PRIORITY].get();
    }

    public int cpuTier() {
        return data[CPU].get();
    }

    public StockRule.Redstone redstone() {
        return StockRule.Redstone.byId(data[REDSTONE].get());
    }

    public boolean filling() {
        return data[FILLING].get() != 0;
    }

    /** Whether the shown rule is switched off. */
    public boolean switchedOff() {
        return data[SWITCHED_OFF].get() != 0;
    }

    /** The rules the keeper offers. */
    public int ruleSlots() {
        return data[RULE_SLOTS].get();
    }

    /** The ghost slot's item: the shown rule's item. */
    public ItemStack ruleItem() {
        return slots.get(0).getItem();
    }

    private long amount(int first) {
        long value = 0;
        for (int i = 3; i >= 0; i--) {
            value = (value << 16) | (data[first + i].get() & 0xFFFF);
        }
        return value;
    }

    @Override
    public void broadcastChanges() {
        StockKeeperBlockEntity current = keeper;
        if (current != null) {
            StackKey key = current.worker().rule(selected).key().orElse(null);
            ItemStack shown = key instanceof ItemKey item ? item.toStack(1) : ItemStack.EMPTY;
            if (!ItemStack.isSameItemSameComponents(shown, ghost.getItem(0))) {
                ghost.setItem(0, shown);
            }
        }
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        StockKeeperBlockEntity current = keeper;
        if (current == null || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        int slots = StockKeeperWorker.ruleSlots();
        switch (id) {
            case PREVIOUS_RULE -> {
                selected = (selected + slots - 1) % slots;
                return true;
            }
            case NEXT_RULE -> {
                selected = (selected + 1) % slots;
                return true;
            }
            case CYCLE_FIELD -> {
                field = field.next();
                return true;
            }
            default -> {
            }
        }
        selected = Math.min(selected, slots - 1);
        StockRule rule = current.worker().rule(selected);
        StockRule changed = switch (id) {
            case PRIORITY_DOWN -> rule.withPriority(rule.priority() - 1);
            case PRIORITY_UP -> rule.withPriority(rule.priority() + 1);
            case CYCLE_CPU -> rule.withCpuTier((rule.cpuTier() + 1) % 4);
            case CYCLE_REDSTONE -> rule.withRedstone(rule.redstone().next());
            case TOGGLE_ON -> rule.withSwitchedOff(!rule.switchedOff());
            default -> id >= FIRST_STEP && id < FIRST_STEP + STEP_COUNT ? stepped(rule, id - FIRST_STEP) : null;
        };
        if (changed == null) {
            return false;
        }
        current.worker().setRule(selected, changed);
        return true;
    }

    private StockRule stepped(StockRule rule, int step) {
        return switch (field) {
            case KEEP -> rule.withKeep(step(rule.keep(), step));
            case RESTART -> rule.withRestart(step(rule.restart(), step));
            case BATCH -> rule.withBatch(step(rule.batch(), step));
        };
    }

    /** The label of step button {@code step}. */
    public static String stepLabel(int step) {
        return switch (step) {
            case 6 -> "/10";
            case 7 -> "x10";
            default -> (STEPS[step] > 0 ? "+" : "") + STEPS[step];
        };
    }

    static long step(long amount, int step) {
        return switch (step) {
            case 6 -> amount / 10;
            case 7 -> amount > StockRule.MAX_AMOUNT / 10 ? StockRule.MAX_AMOUNT : Math.max(1, amount * 10);
            default -> Math.max(0, amount + STEPS[step]);
        };
    }

    /** The ghost slot takes a copy of the carried item, or clears the rule's item with an empty hand. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex == 0) {
            StockKeeperBlockEntity current = keeper;
            if (current != null && NetworkAccess.canConfigure(player, current)
                    && (containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_MOVE)) {
                setItem(current, containerInput == ContainerInput.PICKUP ? getCarried() : ItemStack.EMPTY);
            }
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /** A shift-clicked inventory stack becomes the shown rule's item. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        StockKeeperBlockEntity current = keeper;
        if (index > 0 && current != null && NetworkAccess.canConfigure(player, current)) {
            ItemStack stack = slots.get(index).getItem();
            if (!stack.isEmpty()) {
                setItem(current, stack);
            }
        }
        return ItemStack.EMPTY;
    }

    /** A new item on an empty rule starts it at one stack, so the rule does something at once. */
    private void setItem(StockKeeperBlockEntity current, ItemStack stack) {
        selected = Math.min(selected, StockKeeperWorker.ruleSlots() - 1);
        StockRule rule = current.worker().rule(selected);
        if (stack.isEmpty()) {
            current.worker().setRule(selected, rule.withKey(Optional.empty()));
            return;
        }
        StockRule changed = rule.withKey(Optional.of(ItemKey.of(stack)));
        if (changed.keep() == 0) {
            changed = changed.withKeep(stack.getMaxStackSize());
        }
        current.worker().setRule(selected, changed);
    }

    @Override
    public boolean stillValid(Player player) {
        StockKeeperBlockEntity current = keeper;
        return current == null || (current.stillValid(player) && NetworkAccess.canConfigure(player, current));
    }

    /** A data slot read from the live rule on the server. */
    private final class Live extends DataSlot {
        private final int index;

        Live(int index) {
            this.index = index;
        }

        @Override
        public int get() {
            StockKeeperBlockEntity current = keeper;
            if (current == null) {
                return 0;
            }
            StockKeeperWorker worker = current.worker();
            int shown = Math.min(selected, StockKeeperWorker.ruleSlots() - 1);
            StockRule rule = worker.rule(shown);
            return switch (index) {
                case SELECTED -> shown;
                case FIELD -> field.id();
                case PRIORITY -> rule.priority() & 0xFFFF;
                case CPU -> rule.cpuTier();
                case REDSTONE -> rule.redstone().id();
                case FILLING -> worker.filling(shown) ? 1 : 0;
                case SWITCHED_OFF -> rule.switchedOff() ? 1 : 0;
                case RULE_SLOTS -> StockKeeperWorker.ruleSlots();
                default -> {
                    int first = index >= BATCH ? BATCH : index >= RESTART ? RESTART : KEEP;
                    long value = first == KEEP ? rule.keep() : first == RESTART ? rule.restart() : rule.batch();
                    yield (int) ((value >>> (16 * (index - first))) & 0xFFFF);
                }
            };
        }

        @Override
        public void set(int value) {
        }
    }

    /** A ghost slot: it shows the rule's item and cannot be taken from or placed into the vanilla way. */
    private static final class GhostSlot extends Slot {
        GhostSlot(Container container, int x, int y) {
            super(container, 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return keeper;
    }
}
