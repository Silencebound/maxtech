package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The condition switch's GUI: one ghost slot for the watched item (or the item whose tags a tag count picks from) and
 * buttons for everything else, sent as vanilla menu button clicks, so it needs no payload of its own. The settings
 * travel back as data slots: type, side, inversion, output, the threshold in four 16-bit pieces, and the indices of the
 * tag and the research node in lists both sides build the same way ({@link #tags}, {@link #nodes}).
 */
public final class ConditionMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 186;
    public static final int SLOT_X = 8;
    public static final int SLOT_Y = 40;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 104;

    public static final int CYCLE_TYPE = 0;
    public static final int TOGGLE_SIDE = 1;
    public static final int TOGGLE_INVERTED = 2;
    public static final int NEXT_TAG = 3;
    public static final int NEXT_NODE = 4;
    /** Buttons {@code FIRST_STEP + i} change the threshold by {@link #STEPS}{@code [i]}. */
    public static final int FIRST_STEP = 5;
    /** Threshold step buttons: -100, -10, -1, +1, +10, +100, then divide and multiply by ten. */
    public static final int STEP_COUNT = 8;
    /** Steps the redstone channel of a channel switch to the next dye colour (M7L2). */
    public static final int NEXT_CHANNEL = FIRST_STEP + STEP_COUNT;
    private static final long[] STEPS = {-100, -10, -1, 1, 10, 100};

    private static final int TYPE = 0;
    private static final int ABOVE = 1;
    private static final int INVERTED = 2;
    private static final int OUTPUT = 3;
    private static final int THRESHOLD = 4;
    private static final int TAG = 8;
    private static final int NODE = 9;
    private static final int CHANNEL = 10;
    private static final int SLOTS = 11;

    private final @Nullable ConditionDevice device;
    private final Container ghost;
    private final DataSlot[] data = new DataSlot[SLOTS];

    private ConditionMenu(int containerId, Inventory inventory, @Nullable ConditionDevice device, Container ghost) {
        super(ModMenus.CONDITION.get(), containerId);
        this.device = device;
        this.ghost = ghost;
        addSlot(new GhostSlot(ghost, SLOT_X, SLOT_Y));
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        for (int i = 0; i < SLOTS; i++) {
            data[i] = device == null ? DataSlot.standalone() : new Live(device, inventory.player, i);
            addDataSlot(data[i]);
        }
    }

    /** The client's menu; data slots and slot sync fill it. */
    public static ConditionMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new ConditionMenu(containerId, inventory, null, new SimpleContainer(1));
    }

    /** The server's menu over {@code device}'s live settings. */
    public static ConditionMenu forDevice(int containerId, Inventory inventory, ConditionDevice device) {
        return new ConditionMenu(containerId, inventory, device, new SimpleContainer(1));
    }

    /** Opens {@code device}'s GUI for {@code player}. */
    public static void open(ServerPlayer player, ConditionDevice device, BlockPos pos, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDevice(id, inventory, device), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    /** {@code item}'s item tags, by id; the order the tag button steps through, on both sides. */
    public static List<TagKey<Item>> tags(Item item) {
        List<TagKey<Item>> tags = new ArrayList<>(item.builtInRegistryHolder().tags().toList());
        tags.sort(Comparator.comparing(tag -> tag.location().toString()));
        return tags;
    }

    /** Every research node, by id; the order the node button steps through, on both sides. */
    public static List<Identifier> nodes(RegistryAccess registries) {
        List<Identifier> nodes = new ArrayList<>();
        for (ResourceKey<ResearchNode> key : registries.lookupOrThrow(ResearchNode.REGISTRY).listElementIds()
                .toList()) {
            nodes.add(key.identifier());
        }
        nodes.sort(Comparator.comparing(Identifier::toString));
        return nodes;
    }

    public ConditionType type() {
        return ConditionType.byId(data[TYPE].get());
    }

    public boolean above() {
        return data[ABOVE].get() != 0;
    }

    public boolean inverted() {
        return data[INVERTED].get() != 0;
    }

    public boolean output() {
        return data[OUTPUT].get() != 0;
    }

    public long threshold() {
        long value = 0;
        for (int i = 3; i >= 0; i--) {
            value = (value << 16) | (data[THRESHOLD + i].get() & 0xFFFF);
        }
        return value;
    }

    /** The synced redstone channel, a dye colour id. */
    public int channel() {
        return Math.floorMod(data[CHANNEL].get(), 16);
    }

    /** The synced tag index, -1 for none. */
    public int tagIndex() {
        return (short) data[TAG].get();
    }

    /** The synced research node index, -1 for none. */
    public int nodeIndex() {
        return (short) data[NODE].get();
    }

    /** The ghost slot's item: the watched item, or the tag's source item. */
    public ItemStack watchedItem() {
        return slots.get(0).getItem();
    }

    @Override
    public void broadcastChanges() {
        ConditionDevice current = device;
        if (current != null) {
            ItemStack shown = current.worker().settings().item().map(key -> key.toStack(1)).orElse(ItemStack.EMPTY);
            if (!ItemStack.isSameItemSameComponents(shown, ghost.getItem(0))) {
                ghost.setItem(0, shown);
            }
        }
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        ConditionDevice current = device;
        if (current == null || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        ConditionWorker worker = current.worker();
        ConditionSettings settings = worker.settings();
        ConditionSettings changed = switch (id) {
            case CYCLE_TYPE -> settings.withType(settings.type().next());
            case TOGGLE_SIDE -> settings.withAbove(!settings.above());
            case TOGGLE_INVERTED -> settings.withInverted(!settings.inverted());
            case NEXT_TAG -> settings.withTag(nextTag(settings));
            case NEXT_NODE -> settings.withNode(next(nodes(player.level().registryAccess()), settings.node()));
            case NEXT_CHANNEL -> settings.withChannel(settings.channel() + 1);
            default -> id >= FIRST_STEP && id < FIRST_STEP + STEP_COUNT
                    ? settings.withThreshold(step(settings.threshold(), id - FIRST_STEP))
                    : null;
        };
        if (changed == null) {
            return false;
        }
        worker.setSettings(changed);
        return true;
    }

    private static Optional<TagKey<Item>> nextTag(ConditionSettings settings) {
        if (settings.item().isEmpty()) {
            return Optional.empty();
        }
        return next(tags(settings.item().get().item()), settings.tag());
    }

    /** The entry after {@code current} in {@code list}, wrapping round; the first when {@code current} is not in it. */
    private static <T> Optional<T> next(List<T> list, Optional<T> current) {
        if (list.isEmpty()) {
            return Optional.empty();
        }
        int index = current.isPresent() ? list.indexOf(current.get()) : -1;
        return Optional.of(list.get((index + 1) % list.size()));
    }

    /** The label of threshold button {@code step}. */
    public static String stepLabel(int step) {
        return switch (step) {
            case 6 -> "/10";
            case 7 -> "x10";
            default -> (STEPS[step] > 0 ? "+" : "") + STEPS[step];
        };
    }

    static long step(long threshold, int step) {
        return switch (step) {
            case 6 -> threshold / 10;
            case 7 -> threshold > ConditionSettings.MAX_COUNT / 10 ? ConditionSettings.MAX_COUNT : threshold * 10;
            default -> threshold + STEPS[step];
        };
    }

    /**
     * The ghost slot takes a copy of the carried item, or clears with an empty hand; nothing real moves. A new item
     * also picks its first tag, so a tag count works at once.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex == 0) {
            ConditionDevice current = device;
            if (current != null && NetworkAccess.canConfigure(player, current)
                    && (containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_MOVE)) {
                ItemStack carried = containerInput == ContainerInput.PICKUP ? getCarried() : ItemStack.EMPTY;
                setItem(current.worker(), carried);
            }
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /** A shift-clicked inventory stack becomes the watched item. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ConditionDevice current = device;
        if (index > 0 && current != null && NetworkAccess.canConfigure(player, current)) {
            ItemStack stack = slots.get(index).getItem();
            if (!stack.isEmpty()) {
                setItem(current.worker(), stack);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void setItem(ConditionWorker worker, ItemStack stack) {
        ConditionSettings settings = worker.settings();
        if (stack.isEmpty()) {
            worker.setSettings(settings.withItem(Optional.empty()).withTag(Optional.empty()));
            return;
        }
        List<TagKey<Item>> tags = tags(stack.getItem());
        worker.setSettings(settings.withItem(Optional.of(ItemKey.of(stack)))
                .withTag(tags.isEmpty() ? Optional.empty() : Optional.of(tags.get(0))));
    }

    @Override
    public boolean stillValid(Player player) {
        ConditionDevice current = device;
        return current == null || (current.stillValid(player) && NetworkAccess.canConfigure(player, current));
    }

    /** A data slot read from the live settings on the server. */
    private static final class Live extends DataSlot {
        private final ConditionDevice device;
        private final Player player;
        private final int index;
        /** The settings the tag and node indices were worked out for; they need lists, so not every tick. */
        private @Nullable ConditionSettings cachedFor;
        private int cached;

        Live(ConditionDevice device, Player player, int index) {
            this.device = device;
            this.player = player;
            this.index = index;
        }

        // Settings are replaced, never changed, so identity says whether they are the ones the cache was made for.
        @SuppressWarnings("ReferenceEquality")
        @Override
        public int get() {
            ConditionWorker worker = device.worker();
            ConditionSettings settings = worker.settings();
            if ((index == TAG || index == NODE) && settings != cachedFor) {
                cachedFor = settings;
                cached = index == TAG ? tagIndex(settings) : nodeIndex(settings);
            }
            return switch (index) {
                case TYPE -> settings.type().id();
                case ABOVE -> settings.above() ? 1 : 0;
                case INVERTED -> settings.inverted() ? 1 : 0;
                case OUTPUT -> worker.output() ? 1 : 0;
                case TAG, NODE -> cached;
                case CHANNEL -> settings.channel();
                default -> (int) ((settings.threshold() >>> (16 * (index - THRESHOLD))) & 0xFFFF);
            };
        }

        private int tagIndex(ConditionSettings settings) {
            return settings.item().isEmpty() || settings.tag().isEmpty()
                    ? -1
                    : tags(settings.item().get().item()).indexOf(settings.tag().get());
        }

        private int nodeIndex(ConditionSettings settings) {
            return settings.node().isEmpty()
                    ? -1
                    : nodes(player.level().registryAccess()).indexOf(settings.node().get());
        }

        @Override
        public void set(int value) {
        }
    }

    /** A ghost slot: it shows the watched item and cannot be taken from or placed into the vanilla way. */
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
        return LabelMenu.device(device);
    }
}
