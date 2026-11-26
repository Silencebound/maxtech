package dev.silencebound.maxtech.storage.drive;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.cell.CellBytes;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.CellType;
import dev.silencebound.maxtech.storage.cell.CellTypes;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the drive hands its cell LEDs to its baked model through getModelData (ADR-0002 allows it there).
import net.neoforged.neoforge.model.data.ModelData;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.model.data.ModelProperty;
// PLATFORM-LEAK: the cell slots are NeoForge's item resource handler, exposed as the drive's item capability.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import org.jspecify.annotations.Nullable;

/**
 * The drive (PRD 7.2): eight cell slots, each cell one {@link StorageProvider} of its network. A cell is mounted from
 * {@link CellStorage} when it goes in or the drive loads (getting its id on its first drive), and unmounted when it
 * comes out or the drive is broken or unloaded; each change re-indexes the drive's providers. Its contents never live
 * here, so the drive saves only the cell items, its priority and which cells it empties or fills ({@link CellTransfer},
 * a task on the network scheduler). Every mounted cell joins the index at the drive's priority ({@link #setPriority});
 * cells have no priority of their own.
 *
 * <p>
 * The cell slots are an item handler that takes cells only, one per slot, from hand, hoppers or ports. Clients get one
 * int per drive, {@link #CELL_BANDS}: three bits per slot, slot 0 lowest, holding {@link #BAND_EMPTY},
 * {@link CellBytes#BAND_LOW}, {@link CellBytes#BAND_HIGH}, {@link CellBytes#BAND_FULL} or {@link #BAND_INACTIVE}; it is
 * sent only when a band changes, and the drive model reads it from the model data. Never ticks.
 */
public final class DriveBlockEntity extends StorageDeviceBlockEntity implements Prioritized {
    public static final int SLOTS = 8;
    /** Band of an empty slot. */
    public static final int BAND_EMPTY = 0;
    /** Band of a cell that does not work: its type is unknown, its contents unreadable or its id in use elsewhere. */
    public static final int BAND_INACTIVE = 4;
    public static final int BAND_BITS = 3;
    /** The packed bands of the eight slots; see the class comment. */
    public static final ModelProperty<Integer> CELL_BANDS = new ModelProperty<>();
    private static final int BAND_MASK = (1 << BAND_BITS) - 1;
    private static final String CELLS = "cells";
    private static final String BANDS = "bands";
    private static final String PRIORITY = "priority";
    private static final String TRANSFERS = "transfers";

    private final CellSlots cells = new CellSlots();
    private final @Nullable CellInventory[] mounted = new CellInventory[SLOTS];
    private final Runnable cellCommitted = this::updateBands;
    private final CellTransfer transfer = new CellTransfer(this);
    private List<StorageProvider> providers = List.of();
    private int bands;
    private int priority;
    private boolean loaded;

    public DriveBlockEntity(BlockPos pos, BlockState state) {
        super(DriveContent.DRIVE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.STORAGE;
    }

    @Override
    public List<StorageProvider> providers() {
        return providers;
    }

    /** The cell slots, as the drive's item capability exposes them. */
    public ItemStacksResourceHandler cells() {
        return cells;
    }

    /** The mounted cell in {@code slot}; {@code null} for an empty slot or a cell that does not work. */
    public @Nullable CellInventory cell(int slot) {
        return mounted[slot];
    }

    /** The cell transfers of the slots (M7C item 12). */
    public CellTransfer transfer() {
        return transfer;
    }

    /**
     * Starts emptying ({@link CellTransfer#EMPTY}) or filling ({@link CellTransfer#FILL}) {@code slot}'s cell, or stops
     * it when that is already running there (the screen's button toggles). False when the slot holds no working cell.
     * The caller checks access. Server side.
     */
    public boolean toggleTransfer(int slot, int mode) {
        if (slot < 0 || slot >= SLOTS || !loaded) {
            return false;
        }
        if (transfer.mode(slot) == mode) {
            transfer.stop(slot);
            return true;
        }
        return transfer.start(slot, mode);
    }

    /** A slot's transfer started, stopped or ended: the mode is saved. */
    void changedTransfers() {
        setChanged();
    }

    /** The packed fill bands; see the class comment. */
    public int bands() {
        return bands;
    }

    /** The band of {@code slot}, unpacked from {@code bands}. */
    public static int band(int bands, int slot) {
        return (bands >>> (slot * BAND_BITS)) & BAND_MASK;
    }

    /** The priority of every cell in the drive (owner decision 2026-10-07: cells have none of their own). */
    @Override
    public int priority() {
        return priority;
    }

    /**
     * Sets the priority, clamped to {@link PriorityControl#MIN}..{@link PriorityControl#MAX}; the mounted cells take it
     * and leave and rejoin their network's index with it. Server side.
     */
    @Override
    public void setPriority(int priority) {
        int clamped = PriorityControl.clamp(priority);
        if (clamped == this.priority) {
            return;
        }
        this.priority = clamped;
        for (CellInventory inventory : mounted) {
            if (inventory != null) {
                inventory.setPriority(clamped);
            }
        }
        setChanged();
        if (loaded) {
            providersChanged();
        }
    }

    /** Cells in the drive. */
    public int cellCount() {
        int count = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!cells.stack(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Puts a cell in the first free slot. Returns false, changing nothing, when {@code stack} is not a cell or no slot
     * is free. Takes one item from {@code stack}, also in creative mode, so a cell's id is never copied by hand.
     */
    public boolean insertCell(ItemStack stack) {
        if (!(stack.getItem() instanceof StorageCellItem)) {
            return false;
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            if (cells.stack(slot).isEmpty()) {
                cells.set(slot, ItemResource.of(stack), 1);
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Takes out the cell in the highest filled slot with its stats up to date; empty when the drive holds none. */
    public ItemStack removeCell() {
        for (int slot = SLOTS - 1; slot >= 0; slot--) {
            if (!cells.stack(slot).isEmpty()) {
                refreshStats(slot);
                ItemStack stack = cells.stack(slot).copy();
                cells.set(slot, ItemResource.EMPTY, 0);
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Whether the drive holds anything its item must carry: cells or a priority. */
    public boolean hasContents() {
        return priority != 0 || cellCount() > 0;
    }

    /**
     * The drive as an item, its cells (with fresh stats) and priority in vanilla's block entity data unless it holds
     * neither, and its label as a component.
     */
    public ItemStack toItemStack(HolderLookup.Provider registries) {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        if (hasContents()) {
            stack.set(DataComponents.BLOCK_ENTITY_DATA,
                    TypedEntityData.<BlockEntityType<?>>of(getType(), saveCustomOnly(registries)));
        }
        stack.applyComponents(collectComponents());
        return stack;
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the block entity is in a loaded chunk.
    @Override
    public void onLoad() {
        if (level instanceof ServerLevel server) {
            // Mounted before joining the network, which reads providers() when the device joins.
            loaded = true;
            mountAll();
            // The task belongs to this block entity; it sleeps until a transfer starts or a saved one goes on.
            schedule(transfer.task());
            StorageNetworkLogic logic = StorageNetworks.logic(server, networkId());
            if (logic != null) {
                logic.driveOnline(worldPosition.asLong());
            }
        }
        super.onLoad();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs before setRemoved when the chunk unloads, never
    // when the drive is broken.
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        NetworkState network = network();
        int count = cellCount();
        if (network != null && count > 0) {
            network.recordOffline(this, count);
        }
    }

    /** Most stored kinds the overlay shows as icons. */
    public static final int GLANCE_STACKS = 5;
    /** The largest kinds are worked out again at most this often while the contents keep changing. */
    public static final int GLANCE_REFRESH_TICKS = 20;

    /** The largest stored kinds as last worked out; see {@link #glanceTop()}. */
    private List<InfoStack> glanceTop = List.of();
    /** Whether a cell committed a change or the cells changed since {@link #glanceTop} was worked out. */
    private boolean glanceDirty = true;
    private long glanceAt = Long.MIN_VALUE;
    private int glanceRecounts;

    /**
     * At a glance: how full the mounted cells are together (a bar in the cell bands, O(cells)) and the largest stored
     * kinds as icons, cached: worked out again only after the contents changed, and then at most once per
     * {@link #GLANCE_REFRESH_TICKS}, so overlays asking several times a second cost O(cells) between changes.
     */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        long used = 0;
        long total = 0;
        for (CellInventory inventory : mounted) {
            if (inventory != null) {
                used += inventory.usedBytes();
                total += inventory.totalBytes();
            }
        }
        if (total > 0) {
            NetworkInfo.fill(used, total, sink);
        }
        NetworkInfo.stacks(glanceTop(), sink);
    }

    /**
     * The largest stored kinds, summed over the mounted cells, largest first, at most {@link #GLANCE_STACKS}. Walks
     * every stored kind when the contents changed since the last count and that count is {@link #GLANCE_REFRESH_TICKS}
     * old; otherwise returns the cached list.
     */
    public List<InfoStack> glanceTop() {
        long now = level == null ? 0 : level.getGameTime();
        if (!glanceDirty || (glanceAt != Long.MIN_VALUE && now - glanceAt < GLANCE_REFRESH_TICKS)) {
            return glanceTop;
        }
        Object2LongOpenHashMap<StackKey> totals = new Object2LongOpenHashMap<>();
        for (CellInventory inventory : mounted) {
            if (inventory != null) {
                inventory.forEachStored(totals::addTo);
            }
        }
        List<StackKey> keys = new ArrayList<>(GLANCE_STACKS + 1);
        LongArrayList amounts = new LongArrayList(GLANCE_STACKS + 1);
        ObjectIterator<Object2LongMap.Entry<StackKey>> it = Object2LongMaps.fastIterator(totals);
        while (it.hasNext()) {
            Object2LongMap.Entry<StackKey> entry = it.next();
            LargestKinds.offer(keys, amounts, GLANCE_STACKS, entry.getKey(), entry.getLongValue());
        }
        List<InfoStack> top = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            top.add(new InfoStack(keys.get(i), amounts.getLong(i)));
        }
        glanceTop = List.copyOf(top);
        glanceDirty = false;
        glanceAt = now;
        glanceRecounts++;
        return glanceTop;
    }

    /** How often {@link #glanceTop()} walked the stored kinds; for tests. */
    public int glanceRecounts() {
        return glanceRecounts;
    }

    /** With details: the exact bytes and the cells. */
    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        long used = 0;
        long total = 0;
        for (CellInventory inventory : mounted) {
            if (inventory != null) {
                used += inventory.usedBytes();
                total += inventory.totalBytes();
            }
        }
        sink.line(Component.translatable(NetworkInfo.BYTES_KEY, InfoFormat.number(used), InfoFormat.number(total),
                InfoFormat.number(cellCount()), InfoFormat.number(fullCells())));
    }

    @Override
    public void setRemoved() {
        // Before the device leaves its network: the cells remember its controller (M7C item 20).
        if (loaded && level instanceof ServerLevel server) {
            CellStorage storage = CellStorage.get(server.getServer());
            for (CellInventory inventory : mounted) {
                if (inventory != null) {
                    rememberHome(storage, inventory);
                }
            }
        }
        super.setRemoved();
        if (loaded) {
            loaded = false;
            for (int slot = 0; slot < SLOTS; slot++) {
                unmount(slot);
            }
            providers = List.of();
        }
    }

    private void mountAll() {
        for (int slot = 0; slot < SLOTS; slot++) {
            unmount(slot);
            mount(slot);
        }
        providers = buildProviders();
        updateBands();
    }

    private void mount(int slot) {
        ItemStack stack = cells.stack(slot);
        CellData data = StorageCellItem.data(stack);
        CellType type = data == null ? null : CellTypes.byId(data.type());
        if (data == null || type == null || !(stack.getItem() instanceof StorageCellItem cellItem)
                || !(level instanceof ServerLevel server)) {
            return;
        }
        CellStorage storage = CellStorage.get(server.getServer());
        if (!data.hasId()) {
            data = data.withId(storage.newId());
            stack.set(ModDataComponents.CELL.get(), data);
            setChanged();
        }
        CellInventory inventory = storage.mount(data.id(), type, cellItem.casing(), data, cellCommitted);
        if (inventory != null) {
            inventory.setPriority(priority);
            inventory.setDraining(transfer.draining(slot));
        }
        mounted[slot] = inventory;
    }

    private void unmount(int slot) {
        CellInventory inventory = mounted[slot];
        if (inventory != null && level instanceof ServerLevel server) {
            CellStorage storage = CellStorage.get(server.getServer());
            rememberHome(storage, inventory);
            storage.unmount(inventory);
        }
        mounted[slot] = null;
    }

    /**
     * Tells the cell storage the controller of the drive's network, if it has one, so the contents of the cell's item
     * go to that network's recovery bin when the item is destroyed (M7C item 20).
     */
    private void rememberHome(CellStorage storage, CellInventory inventory) {
        NetworkState network = network();
        NetworkDevice controller = network == null ? null : network.controllerDevice();
        if (controller != null && level != null) {
            storage.setHome(inventory.id(), level.dimension().identifier().toString(), controller.nodePos());
        }
    }

    private List<StorageProvider> buildProviders() {
        List<StorageProvider> list = new ArrayList<>(SLOTS);
        for (CellInventory inventory : mounted) {
            if (inventory != null) {
                list.add(inventory);
            }
        }
        return List.copyOf(list);
    }

    /** A slot's cell went in or out (after the root transaction, if any, committed). */
    private void slotChanged(int slot) {
        setChanged();
        transfer.cellChanged(slot);
        if (!loaded) {
            return;
        }
        unmount(slot);
        mount(slot);
        providers = buildProviders();
        providersChanged();
        updateBands();
    }

    /** Writes the mounted cell's stats into its item, so the item's tooltip is current when it leaves. */
    private void refreshStats(int slot) {
        CellInventory inventory = mounted[slot];
        ItemStack stack = cells.stack(slot);
        CellData data = StorageCellItem.data(stack);
        if (inventory != null && data != null && data.id() == inventory.id()
                && (data.usedBytes() != inventory.usedBytes() || data.totalBytes() != inventory.totalBytes()
                        || data.kinds() != inventory.kinds() || data.kept() != inventory.keptKinds())) {
            stack.set(ModDataComponents.CELL.get(), data.withStats(inventory.usedBytes(), inventory.totalBytes(),
                    inventory.kinds(), inventory.keptKinds()));
        }
    }

    /** Recomputes the bands; sends them to clients only when one changed. O(slots), no allocation. */
    private void updateBands() {
        // Runs after every committed cell change and after cells mount or unmount.
        glanceDirty = true;
        int packed = 0;
        int fullest = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            int band;
            if (cells.stack(slot).isEmpty()) {
                band = BAND_EMPTY;
            } else {
                CellInventory inventory = mounted[slot];
                band = inventory == null ? BAND_INACTIVE : inventory.band();
                if (inventory != null && inventory.totalBytes() > 0) {
                    fullest = Math.max(fullest, (int) (inventory.usedBytes() * 100 / inventory.totalBytes()));
                }
            }
            packed |= band << (slot * BAND_BITS);
        }
        NetworkState network = network();
        if (network != null && fullest > 0) {
            network.cellFilled(fullest);
        }
        if (packed != bands) {
            int fullBefore = fullCells(bands);
            bands = packed;
            if (network != null && fullCells(packed) != fullBefore) {
                // A cell turned full or stopped being full: logic graphs watching "drive or cell full" look (M7L).
                network.fullCellsChanged();
            }
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** Mounted cells with no byte free now (M7L: logic's "drive or cell full"). */
    public int fullCells() {
        return fullCells(bands);
    }

    private static int fullCells(int packed) {
        int full = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (((packed >>> (slot * BAND_BITS)) & BAND_MASK) == CellBytes.BAND_FULL) {
                full++;
            }
        }
        return full;
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, read by the drive model on the client.
    @Override
    public ModelData getModelData() {
        return ModelData.of(CELL_BANDS, bands);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(BANDS, bands);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        for (int slot = 0; slot < SLOTS; slot++) {
            refreshStats(slot);
        }
        cells.serialize(output.child(CELLS));
        output.putInt(BANDS, bands);
        if (priority != 0) {
            output.putInt(PRIORITY, priority);
        }
        int transfers = transfer.packedModes();
        if (transfers != 0) {
            output.putInt(TRANSFERS, transfers);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        cells.deserialize(input.childOrEmpty(CELLS));
        bands = input.getIntOr(BANDS, bands);
        // Read before mountAll below, which hands it to the cells.
        priority = PriorityControl.clamp(input.getIntOr(PRIORITY, 0));
        // Before mountAll too: a cell being emptied is mounted draining.
        transfer.loadModes(input.getIntOr(TRANSFERS, 0));
        if (level != null && level.isClientSide()) {
            requestModelDataUpdate();
            // The server's block update carries an unchanged state, which re-meshes nothing on its own.
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
        } else if (loaded) {
            // New slots on a loaded drive (its item placed with data): mount them and tell the network.
            mountAll();
            providersChanged();
            wake(transfer.task());
        }
    }

    /** Eight one-cell slots that take storage cells only and report changes to the drive. */
    private final class CellSlots extends ItemStacksResourceHandler {
        CellSlots() {
            super(SLOTS);
        }

        ItemStack stack(int slot) {
            return stacks.get(slot);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return resource.getItem() instanceof StorageCellItem;
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return 1;
        }

        @Override
        public ItemResource getResource(int index) {
            // Whoever reads a slot to extract its cell gets the cell with current stats.
            if (loaded) {
                refreshStats(index);
            }
            return super.getResource(index);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            slotChanged(index);
        }
    }
}
