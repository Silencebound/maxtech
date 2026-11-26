package dev.silencebound.maxtech.storage.crafting.generator;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.List;
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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * The generator provider's GUI: an item ghost slot and a fluid ghost slot (a filled bucket stands for its fluid;
 * setting one clears the other), the signal strength and side buttons, the priority tab ({@link PriorityControl}), and
 * the state: idle, working with what is still owed, the measured rate and a stuck reason. Edits are vanilla menu button
 * clicks and ghost clicks, so no payload; the state travels as data slots. Every change checks access.
 */
public final class GeneratorMenu extends AbstractContainerMenu implements LabelMenu, PriorityMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 196;
    public static final int ITEM_X = 8;
    public static final int FLUID_X = 62;
    public static final int SLOT_Y = 32;
    public static final int BUTTON_Y = 56;
    public static final int STATE_Y = 76;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 114;

    public static final int STRENGTH_DOWN = 0;
    public static final int STRENGTH_UP = 1;
    public static final int TOGGLE_SIDES = 2;

    private static final int ITEM_SLOT = 0;
    private static final int FLUID_SLOT = 1;
    private static final int GHOSTS = 2;
    private static final int DATA_STRENGTH = 0;
    private static final int DATA_SIDES = 1;
    private static final int DATA_REMAINING_LOW = 2;
    private static final int DATA_REMAINING_HIGH = 3;
    private static final int DATA_RATE_LOW = 4;
    private static final int DATA_RATE_HIGH = 5;
    private static final int DATA_STUCK = 6;
    private static final int DATA_CLAIM = 7;
    private static final int DATA_COUNT = 8;
    /** The reasons in declared order; the stuck data slot sends a reason's index plus one, 0 for none. */
    private static final List<StuckReason> REASONS = List.of(StuckReason.values());

    private final @Nullable GeneratorProviderBlockEntity generator;
    private final Container ghosts;
    private final ContainerData data;
    private final PriorityControl.Sync priority;

    private GeneratorMenu(int containerId, Inventory inventory, @Nullable GeneratorProviderBlockEntity generator,
            ContainerData data) {
        super(ModMenus.GENERATOR_PROVIDER.get(), containerId);
        this.generator = generator;
        this.ghosts = new SimpleContainer(GHOSTS);
        this.data = data;
        addSlot(new GhostSlot(ghosts, ITEM_SLOT, ITEM_X, SLOT_Y));
        addSlot(new GhostSlot(ghosts, FLUID_SLOT, FLUID_X, SLOT_Y));
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        addDataSlots(data);
        priority = PriorityControl.sync(generator, this::addDataSlot);
    }

    /** The client's menu; slot sync and data slots fill it. */
    public static GeneratorMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new GeneratorMenu(containerId, inventory, null, new SimpleContainerData(DATA_COUNT));
    }

    /** The server's menu over {@code generator}'s live settings and state. */
    public static GeneratorMenu forGenerator(int containerId, Inventory inventory,
            GeneratorProviderBlockEntity generator) {
        GeneratorLogic logic = generator.logic();
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_STRENGTH -> logic.settings().strength();
                    case DATA_SIDES -> logic.settings().allSides() ? 1 : 0;
                    case DATA_REMAINING_LOW -> (int) (clampInt(logic.remaining()) & 0xFFFF);
                    case DATA_REMAINING_HIGH -> (int) ((clampInt(logic.remaining()) >>> 16) & 0xFFFF);
                    case DATA_RATE_LOW -> perMinute(logic.ticksPerThousand()) & 0xFFFF;
                    case DATA_RATE_HIGH -> (perMinute(logic.ticksPerThousand()) >>> 16) & 0xFFFF;
                    case DATA_STUCK -> {
                        StuckReason reason = logic.stuck();
                        yield reason == null ? 0 : REASONS.indexOf(reason) + 1;
                    }
                    case DATA_CLAIM -> logic.claimBlocked() ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
        return new GeneratorMenu(containerId, inventory, generator, data);
    }

    /** Opens {@code generator}'s GUI for {@code player}. */
    public static void open(ServerPlayer player, GeneratorProviderBlockEntity generator, BlockPos pos,
            Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forGenerator(id, inventory, generator), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    private static long clampInt(long value) {
        return Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    /** Units per minute from ticks per 1,000 units, 0 when unknown. */
    static int perMinute(long ticksPerThousand) {
        if (ticksPerThousand <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, 1200L * 1000L / ticksPerThousand));
    }

    public int strength() {
        return data.get(DATA_STRENGTH);
    }

    public boolean allSides() {
        return data.get(DATA_SIDES) != 0;
    }

    /** Units still owed on open orders; 0 while idle. */
    public int remaining() {
        return (data.get(DATA_REMAINING_HIGH) & 0xFFFF) << 16 | (data.get(DATA_REMAINING_LOW) & 0xFFFF);
    }

    /** Measured units per minute, 0 while unknown. */
    public int ratePerMinute() {
        return (data.get(DATA_RATE_HIGH) & 0xFFFF) << 16 | (data.get(DATA_RATE_LOW) & 0xFFFF);
    }

    /** The stuck data slot as sent: a reason's index plus one, 0 for none; a cheap key for the badge. */
    public int stuckCode() {
        return data.get(DATA_STUCK);
    }

    /** Why the generator holds a craft up, if it does. */
    public Optional<StuckReason> stuck() {
        int id = data.get(DATA_STUCK);
        return id <= 0 || id > REASONS.size() ? Optional.empty() : Optional.of(REASONS.get(id - 1));
    }

    public boolean claimBlocked() {
        return data.get(DATA_CLAIM) != 0;
    }

    /** The priority among patterns for the same output, from the priority tab's data slot. */
    @Override
    public int priority() {
        return PriorityControl.read(priority);
    }

    /** Whether the output is a fluid (its slot shows the bucket). */
    public boolean fluidOutput() {
        return !ghosts.getItem(FLUID_SLOT).isEmpty();
    }

    @Override
    public void broadcastChanges() {
        GeneratorProviderBlockEntity current = generator;
        if (current != null) {
            StackKey key = current.logic().settings().output().orElse(null);
            ItemStack item = key instanceof ItemKey stack ? stack.toStack(1) : ItemStack.EMPTY;
            ItemStack bucket = key instanceof FluidKey fluid
                    ? new ItemStack(fluid.fluid().getBucket())
                    : ItemStack.EMPTY;
            if (!ItemStack.isSameItemSameComponents(item, ghosts.getItem(ITEM_SLOT))) {
                ghosts.setItem(ITEM_SLOT, item);
            }
            if (!ItemStack.isSameItemSameComponents(bucket, ghosts.getItem(FLUID_SLOT))) {
                ghosts.setItem(FLUID_SLOT, bucket);
            }
        }
        super.broadcastChanges();
    }

    private boolean canChange(Player player) {
        GeneratorProviderBlockEntity current = generator;
        return current != null && NetworkAccess.canConfigure(player, current);
    }

    @Override
    public int minPriority() {
        return PatternItem.MIN_PRIORITY;
    }

    @Override
    public int maxPriority() {
        return PatternItem.MAX_PRIORITY;
    }

    @Override
    public boolean applyPriority(Player player, int priority) {
        GeneratorProviderBlockEntity current = generator;
        if (current == null || !canChange(player)) {
            return false;
        }
        current.setPriority(priority);
        return true;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        GeneratorProviderBlockEntity current = generator;
        if (current == null || !canChange(player)) {
            return false;
        }
        GeneratorSettings settings = current.logic().settings();
        GeneratorSettings next = switch (id) {
            case STRENGTH_DOWN -> settings.withStrength(settings.strength() - 1);
            case STRENGTH_UP -> settings.withStrength(settings.strength() + 1);
            case TOGGLE_SIDES -> settings.withAllSides(!settings.allSides());
            default -> null;
        };
        if (next == null) {
            return false;
        }
        current.logic().setSettings(next);
        return true;
    }

    /**
     * A ghost click with a stack sets the output: any item in the item slot, a filled bucket's fluid in the fluid slot.
     * An empty hand or a shift-click clears that slot's output.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex == ITEM_SLOT || slotIndex == FLUID_SLOT) {
            GeneratorProviderBlockEntity current = generator;
            if (current != null && canChange(player)
                    && (containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_MOVE)) {
                ItemStack carried = containerInput == ContainerInput.PICKUP ? getCarried() : ItemStack.EMPTY;
                set(current, slotIndex, carried);
            }
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    private static void set(GeneratorProviderBlockEntity current, int slot, ItemStack carried) {
        GeneratorSettings settings = current.logic().settings();
        Optional<StackKey> shown = settings.output();
        boolean fluidShown = shown.isPresent() && shown.get() instanceof FluidKey;
        if (carried.isEmpty()) {
            if (shown.isPresent() && fluidShown == (slot == FLUID_SLOT)) {
                current.logic().setSettings(settings.withOutput(Optional.empty()));
            }
            return;
        }
        Optional<StackKey> next = slot == FLUID_SLOT ? fluidOf(carried) : Optional.of(ItemKey.of(carried));
        if (next.isPresent()) {
            current.logic().setSettings(settings.withOutput(next));
        }
    }

    private static Optional<StackKey> fluidOf(ItemStack stack) {
        if (stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY)) {
            return Optional.of(FluidKey.of(bucket.content));
        }
        return Optional.empty();
    }

    /** A shift-clicked inventory stack becomes the output: a filled bucket's fluid, else the item. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        GeneratorProviderBlockEntity current = generator;
        if (index >= GHOSTS && current != null && canChange(player)) {
            ItemStack stack = slots.get(index).getItem();
            if (!stack.isEmpty()) {
                set(current, fluidOf(stack).isPresent() ? FLUID_SLOT : ITEM_SLOT, stack);
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        GeneratorProviderBlockEntity current = generator;
        return current == null || (current.stillValid(player) && NetworkAccess.canConfigure(player, current));
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return generator;
    }

    /** A slot that shows the output but cannot be taken from or placed into the vanilla way. */
    private static final class GhostSlot extends Slot {
        GhostSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
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
}
