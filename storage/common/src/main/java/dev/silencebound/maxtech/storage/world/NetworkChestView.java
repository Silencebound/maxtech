package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the chest is an item handler for hoppers and pipes; every call is a network transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What a network chest is, in its block form and as a cable part (PRD 7.2, Devices): a filtered view of its network as
 * a plain chest of 27 or 54 slots. The slots show the network's items the filter names, each key over as many stacks as
 * its total fills: exact entries first in slot order, then what the tags match. A blank filter shows nothing and takes
 * anything, like a port's import; a filter limits what goes in to what it names.
 *
 * <p>
 * Hoppers and pipes see the view through {@link #handler()}: every insert or extract is one network operation through
 * the chest's own {@link StorageTransfer} (one batch at most, energy in the caller's transaction), so the view is never
 * the source of truth and a stale view moves nothing that is not there. The view is rebuilt only when read after a
 * change: per-key listeners mark it for the exact entries, a contents watcher (only while some entry is a tag) for the
 * rest, and a view with tags rescans the network at most every {@link #TAG_SCAN_TICKS} ticks. Nothing ticks.
 */
public final class NetworkChestView implements WorldDevice {
    /** Most often a view with tag entries scans the whole network; a performance bound, not balance. */
    static final int TAG_SCAN_TICKS = 4;
    static final int MAX_SLOTS = 54;
    /** How far beyond block reach a GUI stays usable, as for vanilla containers. */
    static final double RANGE_BUFFER = 4.0;
    private static final String SETTINGS = "settings";

    private final DeviceLink link;
    private final Owned owner;
    private final Supplier<@Nullable Level> level;
    private final Supplier<BlockPos> pos;
    private final BooleanSupplier removed;
    private final Runnable saved;
    private final WorldFilter filter = new WorldFilter(this::filterChanged);
    private final WorldGate gate;
    private final KeyListener listener = (key, total) -> markDirty();
    private final Watcher watcher = new Watcher();
    private final Handler handler = new Handler();
    private int mode;
    /** The exact keys currently listened to, so a filter change can unlisten them. */
    private ItemKey[] listened = new ItemKey[0];
    private @Nullable NetworkState watching;

    private final ItemKey[] keys = new ItemKey[MAX_SLOTS];
    private final ItemResource[] resources = new ItemResource[MAX_SLOTS];
    private final long[] amounts = new long[MAX_SLOTS];
    private final @Nullable ItemStack[] stacks = new ItemStack[MAX_SLOTS];
    private int filled;
    private boolean dirty = true;
    // Far in the past without overflowing the subtraction in refresh.
    private long builtAt = Long.MIN_VALUE / 2;
    private long scannedAt = Long.MIN_VALUE / 2;
    private boolean started;
    private ItemResource lastResource = ItemResource.EMPTY;
    private @Nullable ItemKey lastKey;

    /**
     * @param owner
     *            the device, whose owner the research gate checks
     * @param pos
     *            where players must stay within reach of to keep a GUI open
     * @param removed
     *            whether the device is gone
     * @param saved
     *            marks the device's holder for saving (a setting changed)
     */
    public NetworkChestView(DeviceLink link, Owned owner, Supplier<@Nullable Level> level, Supplier<BlockPos> pos,
            BooleanSupplier removed, Runnable saved) {
        this.link = link;
        this.owner = owner;
        this.level = level;
        this.pos = pos;
        this.removed = removed;
        this.saved = saved;
        this.gate = new WorldGate(ResearchGates.NETWORK_CHEST, owner, this::markDirty);
        Arrays.fill(resources, ItemResource.EMPTY);
    }

    @Override
    public WorldKind kind() {
        return WorldKind.NETWORK_CHEST;
    }

    @Override
    public WorldFilter filter() {
        return filter;
    }

    @Override
    public int mode() {
        return mode;
    }

    @Override
    public void setMode(int mode) {
        int next = Math.floorMod(mode, kind().modeCount());
        if (next != this.mode) {
            this.mode = next;
            saved.run();
            markDirty();
        }
    }

    @Override
    public OwnerRecord ownerRecord() {
        return owner.ownerRecord();
    }

    @Override
    public Owned holder() {
        return owner;
    }

    @Override
    public boolean researched() {
        Level current = level.get();
        return current instanceof ServerLevel server ? gate.open(server) : gate.lastAnswer();
    }

    /** The device is there and {@code player} within reach of it, as for vanilla containers. */
    @Override
    public boolean stillValid(Player player) {
        return !removed.getAsBoolean() && player.isWithinBlockInteractionRange(pos.get(), RANGE_BUFFER);
    }

    /** Opens the chest view; see {@link NetworkChestMenu#open}. */
    @Override
    public boolean openContents(ServerPlayer player) {
        NetworkChestMenu.open(player, this);
        return true;
    }

    /** The research gate; tests and the block and part's removal use it. */
    public WorldGate gate() {
        return gate;
    }

    /** Slots the chest shows: 27 or 54 by mode. */
    public int size() {
        return mode == 0 ? 27 : MAX_SLOTS;
    }

    /** The item handler hoppers and pipes use, on every side of the block or the part's face. */
    public ResourceHandler<ItemResource> handler() {
        return handler;
    }

    /** The network the chest works with, or null when it is in none, gone, or lacks research. */
    @Nullable
    NetworkState network() {
        if (removed.getAsBoolean() || !(level.get() instanceof ServerLevel server) || !gate.open(server)) {
            return null;
        }
        return link.network();
    }

    /** The key slot {@code slot} shows, or null when it is empty. */
    @Nullable
    ItemKey key(int slot) {
        refresh();
        return slot < filled ? keys[slot] : null;
    }

    /** What slot {@code slot} shows, as a stack built once per view; empty when it shows nothing. Do not change it. */
    ItemStack stack(int slot) {
        refresh();
        if (slot >= filled) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = stacks[slot];
        if (stack == null) {
            stack = keys[slot].toStack((int) amounts[slot]);
            stacks[slot] = stack;
        }
        return stack;
    }

    /** Whether the chest takes {@code key} in: everything with a blank filter, else what the filter names. */
    boolean accepts(ItemKey key) {
        return filter.isBlank() || filter.matches(key);
    }

    /**
     * The device joined a network or left one; a tag view follows it with its watcher. A part taken off a cable leaves
     * its network this way, so a removed device stops waiting for research here too.
     */
    public void networkChanged() {
        unwatch();
        watchIfTags();
        if (removed.getAsBoolean()) {
            gate.removed();
        }
        markDirty();
    }

    /** The device is in a loaded level: listen to the filter's keys. Call when the block loads or the part starts. */
    public void start() {
        started = true;
        relisten();
        watchIfTags();
        markDirty();
    }

    /** The device is gone: stop watching and waiting. */
    public void removedFromLevel() {
        unwatch();
        gate.removed();
    }

    private void filterChanged() {
        saved.run();
        relisten();
        unwatch();
        watchIfTags();
        markDirty();
    }

    private void unwatch() {
        NetworkState current = watching;
        if (current != null) {
            current.unwatch(watcher);
            watching = null;
        }
    }

    private void relisten() {
        for (ItemKey key : listened) {
            link.unlisten(key, listener);
        }
        listened = filter.exactKeys();
        for (ItemKey key : listened) {
            link.listen(key, listener);
        }
    }

    private void watchIfTags() {
        NetworkState network = link.network();
        if (watching == null && network != null && filter.tagEntries().length > 0 && !removed.getAsBoolean()) {
            network.watch(watcher);
            watching = network;
        }
    }

    private void markDirty() {
        dirty = true;
    }

    /** Rebuilds the view if something changed since it was built, at most once a tick. */
    private void refresh() {
        Level current = level.get();
        long now = current == null ? 0 : current.getGameTime();
        if (!dirty || now == builtAt) {
            return;
        }
        boolean tags = filter.tagEntries().length > 0;
        if (tags && now - scannedAt < TAG_SCAN_TICKS && now >= scannedAt) {
            return;
        }
        dirty = false;
        builtAt = now;
        filled = 0;
        Arrays.fill(stacks, null);
        NetworkState network = network();
        if (network != null) {
            StorageContents contents = network.contents();
            for (ItemKey key : filter.exactKeys()) {
                show(key, contents.amount(key));
            }
            if (tags) {
                scannedAt = now;
                scan(contents);
            }
        }
        for (int i = filled; i < MAX_SLOTS; i++) {
            resources[i] = ItemResource.EMPTY;
            amounts[i] = 0;
        }
    }

    /** Adds every network key a tag entry matches and no exact entry names. */
    // builtInRegistryHolder is deprecated for registry-agnostic code; items always have one.
    @SuppressWarnings("deprecation")
    private void scan(StorageContents contents) {
        TagKey<Item>[] tags = filter.tagEntries();
        ItemKey[] exact = filter.exactKeys();
        for (Int2LongMap.Entry entry : contents.snapshot().totals().int2LongEntrySet()) {
            if (filled >= size()) {
                return;
            }
            if (!(contents.key(entry.getIntKey()) instanceof ItemKey key) || named(exact, key)) {
                continue;
            }
            var holder = key.item().builtInRegistryHolder();
            for (TagKey<Item> tag : tags) {
                if (holder.is(tag)) {
                    show(key, entry.getLongValue());
                    break;
                }
            }
        }
    }

    private static boolean named(ItemKey[] exact, ItemKey key) {
        for (ItemKey named : exact) {
            if (named.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Spreads {@code total} of {@code key} over stacks in the next free slots. */
    private void show(ItemKey key, long total) {
        int size = size();
        if (total <= 0 || filled >= size) {
            return;
        }
        ItemResource resource = StackKeys.toResource(key);
        int max = Math.max(1, resource.getMaxStackSize());
        long left = total;
        while (left > 0 && filled < size) {
            long amount = Math.min(left, max);
            keys[filled] = key;
            resources[filled] = resource;
            amounts[filled] = amount;
            filled++;
            left -= amount;
        }
    }

    /** The key of {@code resource}, reusing the last one: pipes insert the same item many times in a row. */
    private ItemKey keyOf(ItemResource resource) {
        ItemKey key = lastKey;
        if (key == null || !resource.equals(lastResource)) {
            key = StackKeys.of(resource);
            lastKey = key;
            lastResource = resource;
        }
        return key;
    }

    /** Writes the filter, its tags and the mode. */
    public void save(ValueOutput output) {
        WorldSettings settings = WorldSettings.of(this);
        if (settings != null) {
            output.store(SETTINGS, WorldSettings.CODEC, settings);
        }
    }

    /** Reads what {@link #save} wrote, without saving or waking. */
    public void load(ValueInput input) {
        apply(input.read(SETTINGS, WorldSettings.CODEC).orElse(null));
    }

    /** Takes over settings from an item (or a new device's when null), without saving. */
    public void apply(@Nullable WorldSettings settings) {
        mode = Math.floorMod(WorldSettings.apply(settings, filter, null), kind().modeCount());
        // Loading runs before start; a placed item's settings arrive after it.
        if (started) {
            relisten();
            unwatch();
            watchIfTags();
        }
        markDirty();
    }

    /** Hears every change while a tag entry needs the whole network; it only marks the view. */
    private final class Watcher implements ContentsWatcher {
        @Override
        public void changed(int id, long total) {
            dirty = true;
        }

        @Override
        public void released(int id) {
            dirty = true;
        }
    }

    /**
     * The chest as hoppers and pipes see it. Each insert or extract is one operation; an extract never takes more than
     * the slot shows, and the network gives only what it has.
     */
    private final class Handler implements ResourceHandler<ItemResource> {
        /**
         * Whether a caller is on the server thread of the chest's level: the view, the network and its index are
         * server-thread only, so a call from another thread (another mod's worker) sees and moves nothing.
         */
        private boolean onServerThread() {
            return level.get() instanceof ServerLevel server && server.getServer().isSameThread();
        }

        @Override
        public int size() {
            return NetworkChestView.this.size();
        }

        @Override
        public ItemResource getResource(int index) {
            if (!onServerThread()) {
                return ItemResource.EMPTY;
            }
            refresh();
            return index < filled ? resources[index] : ItemResource.EMPTY;
        }

        @Override
        public long getAmountAsLong(int index) {
            if (!onServerThread()) {
                return 0;
            }
            refresh();
            return index < filled ? amounts[index] : 0;
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return resource.isEmpty() ? Item.DEFAULT_MAX_STACK_SIZE : resource.getMaxStackSize();
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return !resource.isEmpty() && onServerThread() && accepts(keyOf(resource));
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return insert(resource, amount, transaction);
        }

        /** One operation, whatever the index: the network decides where it goes. */
        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (amount == 0 || !onServerThread()) {
                return 0;
            }
            ItemKey key = keyOf(resource);
            if (network() == null || !accepts(key)) {
                return 0;
            }
            StorageTransfer transfer = link.transfer();
            return transfer == null ? 0 : (int) transfer.insert(key, amount, transaction);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (!onServerThread()) {
                return 0;
            }
            refresh();
            if (index >= filled || !resources[index].equals(resource)) {
                return 0;
            }
            return take(keys[index], Math.min(amount, amounts[index]), transaction);
        }

        /** One operation for what every slot of {@code resource} shows together. */
        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (!onServerThread()) {
                return 0;
            }
            refresh();
            long shown = 0;
            ItemKey key = null;
            for (int i = 0; i < filled; i++) {
                if (resources[i].equals(resource)) {
                    shown += amounts[i];
                    key = keys[i];
                }
            }
            return key == null ? 0 : take(key, Math.min(amount, shown), transaction);
        }

        private int take(StackKey key, long amount, TransactionContext transaction) {
            if (amount <= 0 || network() == null) {
                return 0;
            }
            StorageTransfer transfer = link.transfer();
            return transfer == null ? 0 : (int) transfer.extract(key, amount, transaction);
        }
    }
}
