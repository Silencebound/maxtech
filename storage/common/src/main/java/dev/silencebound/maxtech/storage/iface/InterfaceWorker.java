package dev.silencebound.maxtech.storage.iface;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.redstone.RedstoneControl;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.save.KeptValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the interface offers NeoForge transfer handlers to its neighbours and moves stock in transactions
// (PRD 7.2: every transfer is a transaction against the real handlers).
import net.neoforged.neoforge.fluids.FluidStack;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.StacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.RootCommitJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What every interface does, whatever its form (PRD 7.2, Devices: interface in basic form). It offers its neighbours an
 * item and a fluid handler over the network and keeps up to nine item and three fluid stock slots filled from it.
 *
 * <p>
 * The item handler has ten indices: the nine stock slots, then one that takes inserts. An insert goes straight into the
 * network as one operation of this device (energy, in the inserter's transaction). An extract from a stock slot takes
 * from the slot's stock and, when that is short, the rest from the network in one operation. Only the stock slots'
 * configured keys can be pulled, so a neighbour never scans the network. The fluid handler is the same with three tanks
 * and a fourth index for inserts.
 *
 * <p>
 * The stock is real: items and fluid the interface holds, kept in its item when it is broken or taken off a cable. A
 * {@link StorageTask} on the network's scheduler tops each slot up to its target, one operation per slot and run, and
 * returns what a slot holds beyond its target or no longer names. Once every slot is satisfied, or waits for a key the
 * network lacks, it sleeps without a timer: a change of a configured key's total in the network ({@link KeyListener})
 * or a pull from the stock wakes it. A slot blocked by the network (full, refusing) backs off with Lib's idle backoff.
 * Nothing works until the owner's team has the {@link ResearchGates#INTERFACE} unlock.
 *
 * <p>
 * For autocrafting (M7 C13, C21): an insert of a key a running job waits for is reported to {@link CraftingDeliveries}
 * once its transaction commits, and a stock slot waiting for a key the network lacks asks the {@link InterfaceCrafting}
 * handler when the card slot holds a card. Turned off by logic (M7L), the stock task sleeps and neighbours can neither
 * insert nor extract. Never ticks; server thread.
 */
public final class InterfaceWorker {
    public static final int ITEM_SLOTS = 9;
    public static final int FLUID_SLOTS = 3;
    /** Ghost slots: nine items (count = target), then three filled buckets (count = target in buckets). */
    public static final int CONFIG_SIZE = ITEM_SLOTS + FLUID_SLOTS;
    /** Most items one stock slot keeps; the ghost stack's count. */
    public static final int MAX_ITEM_TARGET = 64;
    public static final int BUCKET_MB = 1000;
    private static final String CONFIG = "config";
    private static final String ITEMS = "items";
    private static final String FLUIDS = "fluids";
    /** Read only: where item 33's builds saved the tanks while one was kept. */
    private static final String OLD_KEPT_FLUIDS = "kept_fluids";
    /**
     * The stock as saved: NeoForge's handler format ({@link StacksResourceHandler#serialize}), but read slot by slot,
     * so a stack whose item or fluid no longer loads (a removed mod) is kept as saved instead of failing the whole
     * list.
     */
    private static final Codec<List<KeptValue<ItemStack>>> ITEM_STOCK_CODEC = KeptValue.codec(ItemStack.OPTIONAL_CODEC)
            .listOf();
    private static final Codec<List<KeptValue<FluidStack>>> FLUID_STOCK_CODEC = KeptValue
            .codec(FluidStack.OPTIONAL_CODEC).listOf();
    private static final String CARD = "card";
    /** Fallback tank size while no server config is loaded (a client building the block entity). */
    private static final long DEFAULT_TANK_MB = 16_000;

    private static final int SATISFIED = 0;
    private static final int WAITING = 1;
    private static final int MOVED = 2;
    private static final int BLOCKED = 3;
    private static final int REFUSED = 4;

    private final NetworkDevice device;
    private final DeviceLink link;
    private final Runnable saved;
    private final Config config = new Config();
    private final Card card = new Card();
    private final ItemResource[] itemTargets = new ItemResource[ITEM_SLOTS];
    private final int[] itemAmounts = new int[ITEM_SLOTS];
    private final @Nullable StackKey[] itemKeys = new StackKey[ITEM_SLOTS];
    private final FluidResource[] fluidTargets = new FluidResource[FLUID_SLOTS];
    private final int[] fluidAmounts = new int[FLUID_SLOTS];
    private final @Nullable StackKey[] fluidKeys = new StackKey[FLUID_SLOTS];
    private final ItemBuffer items = new ItemBuffer();
    private final FluidBuffer fluids = new FluidBuffer();
    /**
     * Per tank: saved contents whose fluid no longer loads (a removed mod), written back unchanged until the mod
     * returns; such a tank takes nothing in meanwhile. In the block's save form; the item's form is converted.
     */
    private final @Nullable Tag[] keptTanks = new Tag[FLUID_SLOTS];
    /** Per stock slot: a saved stack whose item no longer loads, kept like {@link #keptTanks}. */
    private final @Nullable Tag[] keptItems = new Tag[ITEM_SLOTS];
    private final ItemView itemView = new ItemView();
    private final FluidView fluidView = new FluidView();
    private final Task task = new Task();
    private final KeyListener listener = this::keyChanged;
    /** Keys this worker listens to now: those of its pending slots. */
    private final List<StackKey> listened = new ArrayList<>(CONFIG_SIZE);
    /** The pending slots {@link #listened} was built for, one bit per slot. */
    private int listenedMask = -1;
    private @Nullable ServerLevel level;
    /** Set while the task runs, so the stock's and the network's change events from its own moves do not wake it. */
    private boolean running;
    /** Some slot is below its target or holds what it should not. */
    private boolean unsatisfied = true;
    /** Per stock slot (items, then fluids): not satisfied after the last run, so its key is listened to. */
    private final boolean[] pendingSlots = new boolean[CONFIG_SIZE];
    /** The owner's team had the unlock the last time it was asked; research is never taken back. */
    private boolean unlocked;
    /** Runs of the stock task so far. */
    private long runs;
    /**
     * The redstone mode (B09 item 9); inactive, the stock task sleeps and neighbours can neither insert nor extract.
     */
    private final RedstoneControl redstone;

    /**
     * @param saved
     *            marks the interface's holder for saving (config or stock changed)
     */
    public InterfaceWorker(NetworkDevice device, Runnable saved) {
        this.device = device;
        this.link = device.deviceLink();
        this.saved = saved;
        this.redstone = new RedstoneControl(link, false, () -> false, saved, this::wake);
        Arrays.fill(pendingSlots, true);
        rebuild();
    }

    /** The redstone mode and last reading. */
    public RedstoneControl redstone() {
        return redstone;
    }

    /** A neighbour of the interface (the block, or the cable a part sits on) changed: reads the signal again. */
    public void redstoneChanged() {
        ServerLevel current = level;
        if (current != null) {
            redstone.neighbourChanged(current, BlockPos.of(device.nodePos()));
        }
    }

    /** The twelve ghost slots; changing one saves the interface, re-reads the targets and wakes the stock task. */
    public SimpleContainer config() {
        return config;
    }

    /** The card slot; a change saves the interface and wakes the stock task. */
    public SimpleContainer card() {
        return card;
    }

    /** The handler neighbours see for items. */
    public ResourceHandler<ItemResource> itemHandler() {
        return itemView;
    }

    /** The handler neighbours see for fluids. */
    public ResourceHandler<FluidResource> fluidHandler() {
        return fluidView;
    }

    /** The stock the interface holds, one index per item stock slot. */
    public ResourceHandler<ItemResource> itemStock() {
        return items;
    }

    /** The fluid stock, one tank per fluid stock slot. */
    public ResourceHandler<FluidResource> fluidStock() {
        return fluids;
    }

    /** Whether the stock task has nothing left to do: every slot holds its target, or waits for its key. */
    public boolean isSatisfied() {
        return !unsatisfied;
    }

    /** The scheduled stock task, for tests and diagnostics. */
    public StorageTask task() {
        return task;
    }

    /**
     * Runs of the stock task so far, for tests and diagnostics: unlike the network scheduler's count, other devices'
     * tasks (the controller's alarm panel, say) do not add to it. Server thread.
     */
    public long taskRuns() {
        return runs;
    }

    /** The target of fluid slot {@code slot} in mB: its buckets, capped at the tank size. */
    public int fluidTarget(int slot) {
        return fluidAmounts[slot];
    }

    /**
     * Starts work in {@code level}: listens to the configured keys and schedules the stock task. Call when the
     * interface loads or is placed.
     */
    public void start(ServerLevel level) {
        this.level = level;
        redstone.start(level, BlockPos.of(device.nodePos()));
        relisten();
        link.schedule(task);
    }

    private void configChanged() {
        rebuild();
        saved.run();
        wake();
        // The keys changed: listen again whatever the pending slots are.
        listenedMask = -1;
        relisten();
    }

    private void wake() {
        unsatisfied = true;
        Arrays.fill(pendingSlots, true);
        if (!running) {
            link.wake(task);
        }
    }

    /** Only pending slots' keys are listened to, so any change may let a slot move. */
    private void keyChanged(StackKey key, long total) {
        if (!running) {
            link.wake(task);
        }
    }

    /** Re-reads the targets from the ghost slots; loading calls it without saving or waking. */
    private void rebuild() {
        long tank = tankMb();
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack stack = config.getItem(i);
            itemTargets[i] = ItemResource.of(stack);
            itemAmounts[i] = stack.isEmpty() ? 0 : Math.min(stack.getCount(), MAX_ITEM_TARGET);
            itemKeys[i] = stack.isEmpty() ? null : ItemKey.of(stack);
        }
        for (int i = 0; i < FLUID_SLOTS; i++) {
            ItemStack stack = config.getItem(ITEM_SLOTS + i);
            if (stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY)) {
                fluidTargets[i] = FluidResource.of(bucket.content);
                fluidAmounts[i] = (int) Math.min((long) stack.getCount() * BUCKET_MB, tank);
                fluidKeys[i] = FluidKey.of(bucket.content);
            } else {
                fluidTargets[i] = FluidResource.EMPTY;
                fluidAmounts[i] = 0;
                fluidKeys[i] = null;
            }
        }
    }

    /** mB a fluid stock tank holds: the server config, or 16,000 while none is loaded. Any thread. */
    public static long tankMbOrDefault() {
        return tankMb();
    }

    private static long tankMb() {
        return StorageServerConfig.loadedOr(() -> StorageServerConfig.INTERFACE_FLUID_TANK_MB.getAsInt(),
                DEFAULT_TANK_MB);
    }

    /**
     * Listens to the keys of the pending slots only: a full slot needs no news from the network (a pull from its stock
     * wakes the task directly), so a busy key of a full slot costs this interface nothing. Rebuilt when the set of
     * pending slots changed, which happens on state changes, not per change of a key.
     */
    private void relisten() {
        if (level == null) {
            return;
        }
        int mask = 0;
        for (int i = 0; i < CONFIG_SIZE; i++) {
            if (pendingSlots[i]) {
                mask |= 1 << i;
            }
        }
        if (mask == listenedMask) {
            return;
        }
        listenedMask = mask;
        for (int i = 0; i < listened.size(); i++) {
            link.unlisten(listened.get(i), listener);
        }
        listened.clear();
        for (int i = 0; i < CONFIG_SIZE; i++) {
            if (pendingSlots[i]) {
                listen(i < ITEM_SLOTS ? itemKeys[i] : fluidKeys[i - ITEM_SLOTS]);
            }
        }
    }

    private void listen(@Nullable StackKey key) {
        if (key != null && !listened.contains(key)) {
            listened.add(key);
            link.listen(key, listener);
        }
    }

    /** Whether the owner's team may use the interface; cached once true. */
    boolean unlocked() {
        if (unlocked) {
            return true;
        }
        ServerLevel current = level;
        if (current == null) {
            return false;
        }
        unlocked = ResearchUnlocks.isUnlocked(current, device.ownerRecord(), ResearchGates.INTERFACE);
        return unlocked;
    }

    /** Research completed somewhere: a locked interface looks again. */
    void researchChanged() {
        wake();
    }

    boolean isRemoved() {
        return device.isRemoved();
    }

    /** One run of the stock task: at most one operation per slot. See the class comment for when it sleeps. */
    int work(StorageTransfer transfer) {
        if (!unlocked()) {
            InterfaceResearchWaits.waiting(this);
            return StorageTask.WAIT;
        }
        if (!transfer.canOperate()) {
            return Math.max(2, transfer.retryTicks());
        }
        running = true;
        try {
            boolean blocked = false;
            boolean refused = false;
            boolean moved = false;
            switch (giveBackItems(transfer)) {
                case MOVED -> moved = true;
                case BLOCKED -> blocked = true;
                case REFUSED -> refused = true;
                default -> {
                }
            }
            for (int i = 0; i < ITEM_SLOTS + FLUID_SLOTS; i++) {
                int result = i < ITEM_SLOTS ? itemSlot(i, transfer) : fluidSlot(i - ITEM_SLOTS, transfer);
                switch (result) {
                    case MOVED -> moved = true;
                    case BLOCKED -> blocked = true;
                    case REFUSED -> refused = true;
                    default -> {
                    }
                }
            }
            boolean more = false;
            for (int i = 0; i < CONFIG_SIZE; i++) {
                pendingSlots[i] = !satisfied(i);
                more |= pendingSlots[i];
            }
            unsatisfied = more;
            relisten();
            if (refused) {
                return Math.max(1, transfer.retryTicks());
            }
            if (moved && more) {
                return StorageTask.NEXT_TICK;
            }
            // A full or refusing network has no event to wait for: back off.
            if (blocked) {
                return StorageTask.IDLE;
            }
            // Satisfied, or waiting for keys the network lacks: their listeners wake the task.
            return StorageTask.WAIT;
        } finally {
            running = false;
        }
    }

    private boolean satisfied(int slot) {
        if (slot < ITEM_SLOTS) {
            ItemResource held = items.getResource(slot);
            int amount = items.getAmountAsInt(slot);
            return (amount == 0 && itemAmounts[slot] == 0)
                    || (held.equals(itemTargets[slot]) && amount == itemAmounts[slot]);
        }
        int tank = slot - ITEM_SLOTS;
        FluidResource held = fluids.getResource(tank);
        int amount = fluids.getAmountAsInt(tank);
        return (amount == 0 && fluidAmounts[tank] == 0)
                || (held.equals(fluidTargets[tank]) && amount == fluidAmounts[tank]);
    }

    private int itemSlot(int slot, StorageTransfer transfer) {
        ItemResource held = items.getResource(slot);
        int amount = items.getAmountAsInt(slot);
        ItemResource target = itemTargets[slot];
        int want = itemAmounts[slot];
        if (amount > 0 && (!held.equals(target) || amount > want)) {
            // The surplus went back in this run's one operation for all item slots (giveBackItems).
            return SATISFIED;
        }
        StackKey key = itemKeys[slot];
        if (key == null || amount >= want) {
            return SATISFIED;
        }
        return missing(slot, key, want - amount, topUp(items, slot, target, key, want - amount, transfer), transfer);
    }

    private int fluidSlot(int tank, StorageTransfer transfer) {
        FluidResource held = fluids.getResource(tank);
        int amount = fluids.getAmountAsInt(tank);
        FluidResource target = fluidTargets[tank];
        int want = fluidAmounts[tank];
        if (amount > 0 && (!held.equals(target) || amount > want)) {
            int surplus = held.equals(target) ? amount - want : amount;
            return giveBack(fluids, tank, held, StackKeys.of(held), surplus, transfer);
        }
        StackKey key = fluidKeys[tank];
        if (key == null || amount >= want) {
            return SATISFIED;
        }
        return missing(ITEM_SLOTS + tank, key, want - amount, topUp(fluids, tank, target, key, want - amount, transfer),
                transfer);
    }

    /** A slot left waiting for a key the network lacks asks the crafting card's handler, when there is a card. */
    private int missing(int slot, StackKey key, long need, int result, StorageTransfer transfer) {
        InterfaceCrafting.Handler handler = InterfaceCrafting.handler();
        if (result == WAITING && handler != null && !card.isEmpty() && device instanceof InterfaceDevice self) {
            handler.missing(self, transfer.network(), card.getItem(0), slot, key, need);
        }
        return result;
    }

    /** One operation taking up to {@code need} of {@code key} from the network into the stock, in one transaction. */
    private static <T extends Resource> int topUp(ResourceHandler<T> stock, int index, T resource, StackKey key,
            int need, StorageTransfer transfer) {
        if (transfer.network().contents().amount(key) <= 0) {
            return WAITING;
        }
        if (!transfer.canOperate()) {
            return REFUSED;
        }
        try (Transaction root = Transaction.openRoot()) {
            long got = transfer.extract(key, need, root);
            if (got <= 0) {
                return transfer.canOperate() ? BLOCKED : REFUSED;
            }
            int put = stock.insert(index, resource, (int) got, root);
            if (put != got) {
                return BLOCKED;
            }
            root.commit();
            return MOVED;
        }
    }

    /** What an item slot holds beyond its target (all of it when it holds something else); 0 for none. */
    private int itemSurplus(int slot) {
        int amount = items.getAmountAsInt(slot);
        if (amount <= 0) {
            return 0;
        }
        ItemResource held = items.getResource(slot);
        if (!held.equals(itemTargets[slot])) {
            return amount;
        }
        return Math.max(0, amount - itemAmounts[slot]);
    }

    /**
     * One operation returning the item slots' surplus to the network, whatever its kinds (M7E follow-up), up to one
     * batch in all: each slot in its own nested transaction (stored, then taken out of the stock for exactly that), the
     * energy paid once at the end, everything rolled back if that fails. {@link #MOVED} when something went back,
     * {@link #BLOCKED} when the network took none of it, {@link #REFUSED} when the network refused the operation,
     * {@link #SATISFIED} without any surplus.
     */
    private int giveBackItems(StorageTransfer transfer) {
        boolean any = false;
        for (int slot = 0; slot < ITEM_SLOTS && !any; slot++) {
            any = itemSurplus(slot) > 0;
        }
        if (!any) {
            return SATISFIED;
        }
        long budget = transfer.beginOperation(StackKey.Kind.ITEM);
        if (budget <= 0) {
            return REFUSED;
        }
        try (Transaction root = Transaction.openRoot()) {
            long moved = 0;
            boolean paid;
            try {
                for (int slot = 0; slot < ITEM_SLOTS && moved < budget; slot++) {
                    int surplus = itemSurplus(slot);
                    if (surplus > 0) {
                        moved += giveBackPart(slot, (int) Math.min(surplus, budget - moved), transfer, root);
                    }
                }
            } finally {
                paid = transfer.finishOperation(StackKey.Kind.ITEM, moved, root);
            }
            if (!paid) {
                return moved > 0 || !transfer.canOperate() ? REFUSED : BLOCKED;
            }
            root.commit();
            return MOVED;
        }
    }

    /** One slot's share of {@link #giveBackItems} in a transaction nested in {@code root}; what went back. */
    private long giveBackPart(int slot, int surplus, StorageTransfer transfer, Transaction root) {
        ItemResource held = items.getResource(slot);
        try (Transaction part = Transaction.open(root)) {
            long stored = transfer.insert(StackKeys.of(held), surplus, part);
            if (stored <= 0) {
                return 0;
            }
            int taken = items.extract(slot, held, (int) stored, part);
            if (taken != stored) {
                return 0;
            }
            part.commit();
            return stored;
        }
    }

    /** One operation returning {@code surplus} of what the stock holds to the network, in one transaction. */
    private static <T extends Resource> int giveBack(ResourceHandler<T> stock, int index, T held, StackKey key,
            int surplus, StorageTransfer transfer) {
        if (!transfer.canOperate()) {
            return REFUSED;
        }
        try (Transaction root = Transaction.openRoot()) {
            long stored = transfer.insert(key, surplus, root);
            if (stored <= 0) {
                return transfer.canOperate() ? BLOCKED : REFUSED;
            }
            int taken = stock.extract(index, held, (int) stored, root);
            if (taken != stored) {
                return BLOCKED;
            }
            root.commit();
            return MOVED;
        }
    }

    /**
     * A logic graph's move through this interface (M7L): the interface's side is its stock. Into the interface tops up
     * the stock slots configured for {@code key} (never past their targets); out of it returns what the stock holds of
     * {@code key} to the network. One operation per slot, each a transaction on the interface's own transfer of at most
     * one batch. The stock task keeps targets anyway, so this mostly helps a graph empty an interface at once. Returns
     * what moved; 0 while the interface is off, locked or in no network.
     */
    public long logicMove(StackKey key, long amount, boolean intoDevice) {
        StorageTransfer transfer = link.transfer();
        if (transfer == null || amount <= 0 || link.stopped() || !unlocked()) {
            return 0;
        }
        long batch = Math.max(1, transfer.batchLimit(key.kind()));
        long moved = 0;
        running = true;
        try {
            boolean item = key.kind() == StackKey.Kind.ITEM;
            int slots = item ? ITEM_SLOTS : FLUID_SLOTS;
            for (int i = 0; i < slots && moved < amount; i++) {
                int step = (int) Math.min(Math.min(amount - moved, batch), Integer.MAX_VALUE);
                moved += item
                        ? logicSlot(items, i, itemTargets[i], itemKeys[i], itemAmounts[i], key, step, intoDevice,
                                transfer)
                        : logicSlot(fluids, i, fluidTargets[i], fluidKeys[i], fluidAmounts[i], key, step, intoDevice,
                                transfer);
            }
        } finally {
            running = false;
        }
        if (moved > 0) {
            wake();
        }
        return moved;
    }

    /** How much of {@code key} the stock holds in the slots configured for it; for logic's "least full" pools. */
    public long stockAmount(StackKey key) {
        long total = 0;
        if (key.kind() == StackKey.Kind.ITEM) {
            for (int i = 0; i < ITEM_SLOTS; i++) {
                if (key.equals(itemKeys[i])) {
                    total += items.getAmountAsLong(i);
                }
            }
            return total;
        }
        for (int i = 0; i < FLUID_SLOTS; i++) {
            if (key.equals(fluidKeys[i])) {
                total += fluids.getAmountAsLong(i);
            }
        }
        return total;
    }

    private static <T extends Resource> long logicSlot(ResourceHandler<T> stock, int index, T target,
            @Nullable StackKey configured, int want, StackKey key, int step, boolean intoDevice,
            StorageTransfer transfer) {
        if (intoDevice) {
            int room = want - (int) stock.getAmountAsLong(index);
            if (!key.equals(configured) || room <= 0 || !transfer.canOperate()) {
                return 0;
            }
            try (Transaction root = Transaction.openRoot()) {
                long got = transfer.extract(key, Math.min(room, step), root);
                if (got <= 0 || stock.insert(index, target, (int) got, root) != got) {
                    return 0;
                }
                root.commit();
                return got;
            }
        }
        T held = stock.getResource(index);
        int amount = (int) Math.min(stock.getAmountAsLong(index), step);
        if (amount <= 0 || held.isEmpty() || !key.equals(StackKeys.of(held)) || !transfer.canOperate()) {
            return 0;
        }
        try (Transaction root = Transaction.openRoot()) {
            long stored = transfer.insert(key, amount, root);
            if (stored <= 0 || stock.extract(index, held, (int) stored, root) != stored) {
                return 0;
            }
            root.commit();
            return stored;
        }
    }

    /**
     * Whether a neighbour's call came on the server thread of the interface's level: the network, the stock and the
     * scheduler are server-thread only, so a call from another thread (another mod's worker) moves nothing.
     */
    private boolean onServerThread() {
        ServerLevel current = level;
        return current != null && current.getServer().isSameThread();
    }

    /** An external insert: one operation of this device into the network; 0 without a network or the unlock. */
    private long insertIntoNetwork(StackKey key, int amount, TransactionContext transaction) {
        if (!onServerThread() || device.isRemoved() || link.stopped() || !unlocked()) {
            return 0;
        }
        StorageTransfer transfer = link.transfer();
        if (transfer == null) {
            return 0;
        }
        long moved = transfer.insert(key, amount, transaction);
        NetworkState network = transfer.network();
        if (moved > 0 && CraftingDeliveries.expects(network, key)) {
            // Counted only if the inserter's transaction commits; a simulation reports nothing.
            new RootCommitJournal(() -> CraftingDeliveries.delivered(network, key, moved)).updateSnapshots(transaction);
        }
        return moved;
    }

    /** The rest of an external extract the stock could not serve: one operation from the network. */
    private long extractFromNetwork(@Nullable StackKey key, int amount, TransactionContext transaction) {
        if (key == null || amount <= 0 || !onServerThread() || device.isRemoved() || link.stopped() || !unlocked()) {
            return 0;
        }
        StorageTransfer transfer = link.transfer();
        return transfer == null ? 0 : transfer.extract(key, amount, transaction);
    }

    /** Writes the ghost slots and the stock. */
    public void save(ValueOutput output) {
        ContainerHelper.saveAllItems(output.child(CONFIG), config.getItems());
        output.child(ITEMS).store(StacksResourceHandler.VALUE_IO_KEY, ITEM_STOCK_CODEC, items.saved());
        output.child(FLUIDS).store(StacksResourceHandler.VALUE_IO_KEY, FLUID_STOCK_CODEC, fluids.saved());
        ContainerHelper.saveAllItems(output.child(CARD), card.getItems());
        redstone.save(output);
    }

    /**
     * The tanks as the item keeps them: kept tanks as saved, the others with their fluid; empty when every tank is
     * empty.
     */
    private List<KeptValue<FluidStock>> tanks() {
        boolean any = false;
        List<KeptValue<FluidStock>> tanks = new ArrayList<>(FLUID_SLOTS);
        for (int i = 0; i < FLUID_SLOTS; i++) {
            Tag kept = keptTanks[i];
            int amount = fluids.getAmountAsInt(i);
            if (kept != null) {
                tanks.add(KeptValue.ofKept(FluidStock.fromStackTag(kept)));
            } else {
                tanks.add(KeptValue.of(amount > 0 ? new FluidStock(fluids.getResource(i), amount) : FluidStock.EMPTY));
            }
            any |= kept != null || amount > 0;
        }
        return any ? List.copyOf(tanks) : List.of();
    }

    /**
     * Fills the tanks from {@code tanks} as {@link #tanks} writes them: a kept tank stays kept and empty, a readable
     * one (also one whose mod is back) holds its fluid.
     */
    private void readTanks(List<KeptValue<FluidStock>> tanks) {
        for (int i = 0; i < FLUID_SLOTS; i++) {
            KeptValue<FluidStock> saved = i < tanks.size() ? tanks.get(i) : null;
            FluidStock tank = saved == null ? FluidStock.EMPTY : saved.value();
            Tag kept = saved == null ? null : saved.kept();
            keptTanks[i] = kept == null ? null : FluidStock.toStackTag(kept);
            FluidResource fluid = FluidResource.EMPTY;
            int amount = 0;
            if (tank != null && !tank.isEmpty()) {
                fluid = tank.fluid();
                amount = tank.amount();
            }
            // Only real changes: on load the tanks already hold what was saved, and a change saves and wakes.
            if (!fluid.equals(fluids.getResource(i)) || amount != fluids.getAmountAsInt(i)) {
                fluids.set(i, fluid, amount);
            }
        }
    }

    /** {@code stacks} as saved: a kept slot as its saved data, the others as they are. */
    private static <S> List<KeptValue<S>> withKept(List<S> stacks, @Nullable Tag[] kept) {
        List<KeptValue<S>> saved = new ArrayList<>(stacks.size());
        for (int i = 0; i < stacks.size(); i++) {
            Tag tag = i < kept.length ? kept[i] : null;
            saved.add(tag != null ? KeptValue.ofKept(tag) : KeptValue.of(stacks.get(i)));
        }
        return saved;
    }

    /**
     * The stacks {@link #withKept} saved, one per slot of {@code kept}, which it fills: a kept slot stays empty, a
     * readable one (also one whose mod is back) holds its stack.
     */
    private static <S> NonNullList<S> readKept(List<KeptValue<S>> saved, @Nullable Tag[] kept, S empty) {
        NonNullList<S> stacks = NonNullList.withSize(kept.length, empty);
        for (int i = 0; i < kept.length; i++) {
            KeptValue<S> slot = i < saved.size() ? saved.get(i) : null;
            S value = slot == null ? null : slot.value();
            Tag tag = slot == null ? null : slot.kept();
            kept[i] = tag == null ? null : tag.copy();
            if (value != null) {
                stacks.set(i, value);
            }
        }
        return stacks;
    }

    /** Reads what {@link #save} wrote, without saving or waking. */
    public void load(ValueInput input) {
        config.getItems().clear();
        input.child(CONFIG).ifPresent(child -> ContainerHelper.loadAllItems(child, config.getItems()));
        rebuild();
        Arrays.fill(keptItems, null);
        Arrays.fill(keptTanks, null);
        input.child(ITEMS).flatMap(child -> child.read(StacksResourceHandler.VALUE_IO_KEY, ITEM_STOCK_CODEC))
                .ifPresent(items::restore);
        input.child(FLUIDS).flatMap(child -> child.read(StacksResourceHandler.VALUE_IO_KEY, FLUID_STOCK_CODEC))
                .ifPresent(fluids::restore);
        // Builds before #332 kept tanks only here, beside every tank; such a save loads them from it.
        input.read(OLD_KEPT_FLUIDS, FluidStock.LIST_CODEC).ifPresent(this::readTanks);
        card.getItems().clear();
        input.child(CARD).ifPresent(child -> ContainerHelper.loadAllItems(child, card.getItems()));
        redstone.load(input);
    }

    /**
     * Writes the settings and the stock into an item's components, each only when not empty, so a fresh interface
     * stacks with others: the ghost slots in {@code maxtech_storage:interface_config}, the items in vanilla's
     * {@code DataComponents.CONTAINER} (listed by vanilla's tooltip), the fluids in
     * {@code maxtech_storage:interface_fluids}, and stock slots whose item no longer loads in
     * {@code maxtech_storage:interface_kept_items} (only while one is kept).
     */
    public void write(DataComponentMap.Builder components) {
        if (!config.isEmpty()) {
            List<ItemStack> slots = new ArrayList<>(CONFIG_SIZE);
            for (int i = 0; i < CONFIG_SIZE; i++) {
                slots.add(config.getItem(i).copy());
            }
            components.set(InterfaceItems.configType(), ItemContainerContents.fromItems(slots));
        }
        boolean anyItem = false;
        boolean anyKept = false;
        List<ItemStack> held = new ArrayList<>(ITEM_SLOTS);
        List<KeptValue<ItemStack>> kept = new ArrayList<>(ITEM_SLOTS);
        for (int i = 0; i < ITEM_SLOTS; i++) {
            int amount = items.getAmountAsInt(i);
            held.add(amount > 0 ? items.getResource(i).toStack(amount) : ItemStack.EMPTY);
            anyItem |= amount > 0;
            Tag tag = keptItems[i];
            kept.add(tag != null ? KeptValue.ofKept(tag) : KeptValue.of(ItemStack.EMPTY));
            anyKept |= tag != null;
        }
        if (anyItem) {
            components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(held));
        }
        if (anyKept) {
            components.set(InterfaceItems.keptItemsType(), List.copyOf(kept));
        }
        List<KeptValue<FluidStock>> tanks = tanks();
        if (!tanks.isEmpty()) {
            components.set(InterfaceItems.fluidsType(), tanks);
        }
        if (!card.isEmpty()) {
            components.set(InterfaceItems.cardType(), ItemContainerContents.fromItems(List.of(card.getItem(0).copy())));
        }
        redstone.write(components);
    }

    /** Reads {@link #write}'s components (a placed item), replacing the settings and the stock. */
    public void read(DataComponentGetter components) {
        config.getItems().clear();
        ItemContainerContents slots = components.get(InterfaceItems.configType());
        if (slots != null) {
            slots.copyInto(config.getItems());
        }
        rebuild();
        ItemContainerContents held = components.get(DataComponents.CONTAINER);
        NonNullList<ItemStack> stacks = NonNullList.withSize(ITEM_SLOTS, ItemStack.EMPTY);
        if (held != null) {
            held.copyInto(stacks);
        }
        // A kept slot stays empty; one whose mod is back holds its stack (its container slot was written empty).
        NonNullList<ItemStack> kept = readKept(
                components.getOrDefault(InterfaceItems.keptItemsType(), List.<KeptValue<ItemStack>>of()), keptItems,
                ItemStack.EMPTY);
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack stack = stacks.get(i).isEmpty() ? kept.get(i) : stacks.get(i);
            items.set(i, ItemResource.of(stack), stack.getCount());
        }
        readTanks(components.getOrDefault(InterfaceItems.fluidsType(), List.of()));
        card.getItems().clear();
        ItemContainerContents cardContents = components.get(InterfaceItems.cardType());
        if (cardContents != null) {
            cardContents.copyInto(card.getItems());
        }
        redstone.restore(RedstoneControl.mode(components));
    }

    /** The ghost slots: a change re-reads the targets. */
    private final class Config extends GhostContainer {
        Config() {
            super(CONFIG_SIZE);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            configChanged();
        }
    }

    /** One card in {@link InterfaceCrafting#CARDS}; a change saves the interface and wakes the stock task. */
    private final class Card extends SimpleContainer {
        Card() {
            super(1);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return stack.is(InterfaceCrafting.CARDS);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            saved.run();
            wake();
        }
    }

    /** The item stock: each slot takes only its configured item, up to its target. */
    private final class ItemBuffer extends ItemStacksResourceHandler {
        ItemBuffer() {
            super(ITEM_SLOTS);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return keptItems[index] == null && !resource.isEmpty() && resource.equals(itemTargets[index]);
        }

        List<KeptValue<ItemStack>> saved() {
            return withKept(stacks, keptItems);
        }

        /** Replaces the stock with what {@link #saved} wrote, without saving or waking. */
        void restore(List<KeptValue<ItemStack>> saved) {
            setStacks(readKept(saved, keptItems, ItemStack.EMPTY));
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return itemAmounts[index];
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            saved.run();
            wake();
        }
    }

    /** The fluid stock: each tank takes only its configured fluid, up to its target. */
    private final class FluidBuffer extends FluidStacksResourceHandler {
        FluidBuffer() {
            super(FLUID_SLOTS, Integer.MAX_VALUE);
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return keptTanks[index] == null && !resource.isEmpty() && resource.equals(fluidTargets[index]);
        }

        List<KeptValue<FluidStack>> saved() {
            return withKept(stacks, keptTanks);
        }

        /** Replaces the stock with what {@link #saved} wrote, without saving or waking. */
        void restore(List<KeptValue<FluidStack>> saved) {
            setStacks(readKept(saved, keptTanks, FluidStack.EMPTY));
        }

        @Override
        protected int getCapacity(int index, FluidResource resource) {
            return fluidAmounts[index];
        }

        @Override
        protected void onContentsChanged(int index, FluidStack previousContents) {
            saved.run();
            wake();
        }
    }

    /** What neighbours see for items: the nine stock slots, then an index that stores into the network. */
    private final class ItemView implements ResourceHandler<ItemResource> {
        @Override
        public int size() {
            return ITEM_SLOTS + 1;
        }

        @Override
        public ItemResource getResource(int index) {
            if (index >= ITEM_SLOTS) {
                return ItemResource.EMPTY;
            }
            ItemResource held = items.getResource(index);
            return held.isEmpty() ? itemTargets[index] : held;
        }

        @Override
        public long getAmountAsLong(int index) {
            return index < ITEM_SLOTS ? items.getAmountAsLong(index) : 0;
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return index < ITEM_SLOTS ? itemAmounts[index] : MAX_ITEM_TARGET;
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return index == ITEM_SLOTS && !resource.isEmpty();
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (index != ITEM_SLOTS || amount == 0) {
                return 0;
            }
            return (int) insertIntoNetwork(StackKeys.of(resource), amount, transaction);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (index >= ITEM_SLOTS || amount == 0 || !onServerThread() || device.isRemoved() || link.stopped()) {
                return 0;
            }
            int taken = items.extract(index, resource, amount, transaction);
            if (taken < amount && resource.equals(itemTargets[index])) {
                taken += (int) extractFromNetwork(itemKeys[index], amount - taken, transaction);
            }
            return taken;
        }
    }

    /** What neighbours see for fluids: the three tanks, then an index that stores into the network. */
    private final class FluidView implements ResourceHandler<FluidResource> {
        @Override
        public int size() {
            return FLUID_SLOTS + 1;
        }

        @Override
        public FluidResource getResource(int index) {
            if (index >= FLUID_SLOTS) {
                return FluidResource.EMPTY;
            }
            FluidResource held = fluids.getResource(index);
            return held.isEmpty() ? fluidTargets[index] : held;
        }

        @Override
        public long getAmountAsLong(int index) {
            return index < FLUID_SLOTS ? fluids.getAmountAsLong(index) : 0;
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return index < FLUID_SLOTS ? fluidAmounts[index] : BUCKET_MB;
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return index == FLUID_SLOTS && !resource.isEmpty();
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (index != FLUID_SLOTS || amount == 0) {
                return 0;
            }
            return (int) insertIntoNetwork(StackKeys.of(resource), amount, transaction);
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (index >= FLUID_SLOTS || amount == 0 || !onServerThread() || device.isRemoved() || link.stopped()) {
                return 0;
            }
            int taken = fluids.extract(index, resource, amount, transaction);
            if (taken < amount && resource.equals(fluidTargets[index])) {
                taken += (int) extractFromNetwork(fluidKeys[index], amount - taken, transaction);
            }
            return taken;
        }
    }

    /** The interface's stock work on the network's scheduler; one per interface, scheduled when it starts. */
    private final class Task implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            runs++;
            return work(transfer);
        }
    }
}
