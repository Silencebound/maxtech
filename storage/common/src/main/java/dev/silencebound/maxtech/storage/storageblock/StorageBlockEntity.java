package dev.silencebound.maxtech.storage.storageblock;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.cell.CellBytes;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The storage block (B09 item 27, Refined Storage's storage block): one block that is one cell of its network, holding
 * any number of item and fluid kinds in its tier's bytes ({@link StorageBlockTier}), with no drive and no cell item.
 * The cell is a {@link CellStorage} cell like any other ({@link UniversalCellType}), so bytes, kept entries of removed
 * mods, the save format and recovery behave exactly as for cells; the block keeps only the cell's id and its priority.
 *
 * <p>
 * The cell is mounted when the block loads or is placed and unmounted when it is broken or unloaded. The block drops
 * itself with the id, priority and stats as {@link StorageBlockData} (also in creative mode and when picked up with the
 * storage wrench), so the contents follow the item wherever it goes and the item never grows with them. While its chunk
 * is unloaded it counts as one offline cell of its network, like a drive. Never ticks. Server thread.
 */
public final class StorageBlockEntity extends StorageDeviceBlockEntity implements Prioritized {
    private static final String CELL = "cell";
    private static final String PRIORITY = "priority";

    private final Runnable committed = this::committed;
    private long cellId = CellData.NO_ID;
    private int priority;
    private @Nullable CellInventory mounted;
    private List<StorageProvider> providers = List.of();
    private boolean loaded;
    /**
     * The cell storage refused the cell: its id is mounted elsewhere (a copied item) or its contents are unreadable.
     */
    private boolean refused;
    /** Whether the cell was full at the last commit or mount, to see it turn full or not (M7L). */
    private boolean full;
    /** The stats as last seen on the mounted cell or brought by the placed item; what the dropped item carries. */
    private long usedBytes;
    private long totalBytes;
    private int kinds;
    private int kept;

    public StorageBlockEntity(BlockPos pos, BlockState state) {
        super(StorageBlockContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.STORAGE;
    }

    @Override
    public List<StorageProvider> providers() {
        return providers;
    }

    /** The block's tier, from its block. */
    public StorageBlockTier tier() {
        return getBlockState().getBlock() instanceof StorageBlock block ? block.tier() : StorageBlockTier.ONE;
    }

    /** The mounted cell; {@code null} before the block loaded, after it was removed, or while refused. */
    public @Nullable CellInventory cell() {
        return mounted;
    }

    /** The cell's id in {@link CellStorage}; {@link CellData#NO_ID} before the block first loaded. */
    public long cellId() {
        return cellId;
    }

    /** Bytes used now (or when last seen). */
    public long usedBytes() {
        refreshStats();
        return usedBytes;
    }

    /** The capacity in bytes now (or when last seen); 0 before the cell was ever mounted. */
    public long totalBytes() {
        refreshStats();
        return totalBytes;
    }

    /** Kinds held now (or when last seen), kept entries included. */
    public int kinds() {
        refreshStats();
        return kinds;
    }

    /** Whether the cell has no byte free (M7L: logic's "drive or cell full"). */
    public boolean isFull() {
        CellInventory cell = mounted;
        return cell != null && cell.band() == CellBytes.BAND_FULL;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * Sets the priority, clamped to {@link PriorityControl#MIN}..{@link PriorityControl#MAX}; the cell leaves and
     * rejoins its network's index with it. Server side.
     */
    @Override
    public void setPriority(int priority) {
        int clamped = PriorityControl.clamp(priority);
        if (clamped == this.priority) {
            return;
        }
        this.priority = clamped;
        CellInventory cell = mounted;
        if (cell != null) {
            cell.setPriority(clamped);
        }
        setChanged();
        if (loaded) {
            providersChanged();
        }
    }

    /**
     * Whether the dropped item must carry the cell id: the cell holds something, or the cell storage refused it, so
     * what it holds is unknown and must not be cut off from the item.
     */
    boolean holdsAnything() {
        refreshStats();
        return cellId != CellData.NO_ID && (kinds > 0 || refused);
    }

    /** The block as an item, with its cell id, priority and stats unless it holds nothing and has priority 0. */
    public ItemStack toItemStack() {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        stack.applyComponents(collectComponents());
        return stack;
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the block entity is in a loaded chunk.
    @Override
    public void onLoad() {
        if (level instanceof ServerLevel server) {
            // Mounted before joining the network, which reads providers() when the device joins.
            loaded = true;
            mount();
            StorageNetworkLogic logic = StorageNetworks.logic(server, networkId());
            if (logic != null) {
                logic.driveOnline(worldPosition.asLong());
            }
        }
        super.onLoad();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs before setRemoved when the chunk unloads, never
    // when the block is broken.
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        NetworkState network = network();
        if (network != null && holdsAnything()) {
            network.recordOffline(this, 1);
        }
    }

    @Override
    public void setRemoved() {
        // Before the device leaves its network: the cell remembers its controller (M7C item 20).
        CellInventory cell = mounted;
        if (cell != null && level instanceof ServerLevel server) {
            rememberHome(CellStorage.get(server.getServer()), cell);
        }
        super.setRemoved();
        if (loaded) {
            loaded = false;
            unmount();
            providers = List.of();
        }
    }

    private void mount() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        CellStorage storage = CellStorage.get(server.getServer());
        if (cellId == CellData.NO_ID) {
            cellId = storage.newId();
            setChanged();
        }
        CellInventory cell = storage.mount(cellId, StorageBlockContent.UNIVERSAL.get(), tier().bytes(), committed);
        refused = cell == null;
        if (cell != null) {
            cell.setPriority(priority);
        }
        mounted = cell;
        providers = cell == null ? List.of() : List.of(cell);
        refreshStats();
        full = isFull();
    }

    private void unmount() {
        CellInventory cell = mounted;
        if (cell != null && level instanceof ServerLevel server) {
            refreshStats();
            CellStorage.get(server.getServer()).unmount(cell);
        }
        mounted = null;
    }

    /** Tells the cell storage the controller of the block's network, for when its item is destroyed (M7C item 20). */
    private void rememberHome(CellStorage storage, CellInventory cell) {
        NetworkState network = network();
        NetworkDevice controller = network == null ? null : network.controllerDevice();
        if (controller != null && level != null) {
            storage.setHome(cell.id(), level.dimension().identifier().toString(), controller.nodePos());
        }
    }

    private void refreshStats() {
        CellInventory cell = mounted;
        if (cell != null) {
            usedBytes = cell.usedBytes();
            totalBytes = cell.totalBytes();
            kinds = cell.kinds();
            kept = cell.keptKinds();
        }
    }

    /**
     * A root transaction changed the cell: tell the network how full it is (alarms) and, when it turned full or stopped
     * being full, let the logic graphs watching "drive or cell full" look again.
     */
    private void committed() {
        CellInventory cell = mounted;
        NetworkState network = network();
        if (cell == null || network == null) {
            return;
        }
        if (cell.totalBytes() > 0 && cell.usedBytes() > 0) {
            network.cellFilled((int) Math.min(100, cell.usedBytes() * 100 / cell.totalBytes()));
        }
        boolean now = isFull();
        if (now != full) {
            full = now;
            network.fullCellsChanged();
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        refreshStats();
        if (totalBytes > 0) {
            NetworkInfo.fill(usedBytes, totalBytes, sink);
        }
        sink.line(StorageBlockTexts.kinds(kinds));
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        refreshStats();
        sink.line(StorageBlockTexts.bytes(usedBytes, totalBytes));
        if (kept > 0) {
            sink.line(Component.translatable(StorageBlockTexts.KEPT, kept).withStyle(ChatFormatting.GOLD));
        }
        if (refused && loaded) {
            sink.line(Component.translatable(StorageBlockTexts.INACTIVE).withStyle(ChatFormatting.RED));
        }
        sink.line(StorageBlockTexts.priority(priority));
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        boolean holds = holdsAnything();
        if (holds || priority != 0) {
            components.set(StorageBlockContent.DATA.get(), new StorageBlockData(holds ? cellId : CellData.NO_ID,
                    priority, holds ? usedBytes : 0, totalBytes, holds ? kinds : 0, holds ? kept : 0));
        }
    }

    /**
     * A placed item: its cell and priority; the cell is mounted again if the block already loaded. Components applied
     * to a block whose own cell may hold something (it holds kinds, was refused, or has an id but has not loaded yet)
     * set the priority only and keep the block's cell, so its contents are never cut off from every block and item.
     */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        StorageBlockData data = components.get(StorageBlockContent.DATA.get());
        if (data == null) {
            return;
        }
        boolean ownCellInUse = loaded ? holdsAnything() : cellId != CellData.NO_ID;
        if (ownCellInUse && data.id() != cellId) {
            setPriority(data.priority());
            return;
        }
        if (loaded) {
            unmount();
        }
        cellId = data.id();
        priority = PriorityControl.clamp(data.priority());
        usedBytes = data.usedBytes();
        totalBytes = data.totalBytes();
        kinds = data.kinds();
        kept = data.kept();
        refused = false;
        setChanged();
        if (loaded) {
            mount();
            providersChanged();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (cellId != CellData.NO_ID) {
            output.putLong(CELL, cellId);
        }
        if (priority != 0) {
            output.putInt(PRIORITY, priority);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        cellId = input.getLongOr(CELL, CellData.NO_ID);
        priority = PriorityControl.clamp(input.getIntOr(PRIORITY, 0));
    }
}
