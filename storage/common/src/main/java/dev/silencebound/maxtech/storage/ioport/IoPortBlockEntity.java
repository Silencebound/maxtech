package dev.silencebound.maxtech.storage.ioport;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.CellType;
import dev.silencebound.maxtech.storage.cell.CellTypes;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.Glance;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import dev.silencebound.maxtech.storage.redstone.RedstoneControl;
import dev.silencebound.maxtech.storage.redstone.RedstoneControlled;
import dev.silencebound.maxtech.storage.redstone.RedstoneMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the cell slots are NeoForge's item resource handler, exposed as the port's item capability.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: every move is one NeoForge transfer transaction (PRD 7.2, never dupe or lose).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The IO port (B09 item 11): six input and six output cell slots, a mode ({@link IoPortMode}: unload a cell into the
 * network or load it from the network) and when a cell moves on to the output ({@link IoPortOutput}). A network device
 * with one channel; it never holds storage of its own, so its cells are not providers of the network.
 *
 * <p>
 * A cell in an input slot is mounted from {@link CellStorage} (so a copied item cannot be used at the same time) and
 * unmounted when it leaves. The work is one {@link StorageTask} on the network's scheduler that works through the input
 * slots in order. Each run makes at most {@link #operations()} operations, one batch of one kind each, through the
 * port's own transfer, so it pays energy like a port. Every operation is one root transaction: the network side runs
 * first, then the cell side for exactly that amount, and both roll back unless they match. Loading simulates the cell's
 * insert first, so the cell's own rules (type, bytes, bulk, read-only, partition and whatever filter the cell has)
 * decide what it takes; void overflow never destroys network items, since only what the cell stores is taken.
 *
 * <p>
 * The task sleeps ({@link StorageTask#WAIT}) without input cells or when the cells that are done wait for a free output
 * slot, and backs off ({@link StorageTask#IDLE}) when every cell is done with but stays, such as one waiting to be full
 * while the network has nothing more; a slot, card or setting change wakes it. A cell that cannot be mounted (unknown
 * type, unreadable contents, mounted elsewhere) moves straight to the output. Server thread only, never ticks.
 */
public final class IoPortBlockEntity extends StorageDeviceBlockEntity implements RedstoneControlled {
    /** Input slots: indices 0 to 5 of {@link #slots()}. */
    public static final int INPUTS = 6;
    /** Output slots: indices 6 to 11 of {@link #slots()}. */
    public static final int OUTPUTS = 6;
    public static final int SLOTS = INPUTS + OUTPUTS;
    /** Speed card slots. */
    public static final int CARD_SLOTS = 3;
    /** The name of the slots in the saved tag; the drive's, so {@code DriveItem.cells} counts them too. */
    static final String CELLS = "cells";
    private static final String MODE = "mode";
    private static final String OUTPUT = "output";
    private static final String CARDS = "cards";

    private final CellSlots slots = new CellSlots();
    private final Automation automation = new Automation();
    private final Cards cards = new Cards();
    private final @Nullable CellInventory[] mounted = new CellInventory[INPUTS];
    /** Per input slot: the mounted cell's type must never be loaded ({@link #refusesLoad}). */
    private final boolean[] loadRefused = new boolean[INPUTS];
    private final Pass[] passes = new Pass[INPUTS];
    private final Task task = new Task();
    private IoPortMode mode = IoPortMode.UNLOAD;
    private IoPortOutput output = IoPortOutput.WORK_DONE;
    private boolean loaded;
    /** Inside the task's run: its own slot moves neither wake it nor reset the cells it set aside. */
    private boolean running;
    /** The last run returned {@link StorageTask#IDLE}: the next one looks at the cells it set aside again. */
    private boolean idled;
    private long moved;
    /** The redstone mode (B09 item 9); in pulse mode each rising edge allows one operation. */
    private final RedstoneControl redstone;

    public IoPortBlockEntity(BlockPos pos, BlockState state) {
        super(IoPortContent.BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < INPUTS; i++) {
            passes[i] = new Pass();
        }
        redstone = new RedstoneControl(deviceLink(), true, () -> true, this::setChanged, task::wakeFromOutside);
    }

    @Override
    public RedstoneControl redstone() {
        return redstone;
    }

    /** A neighbour changed: the redstone signal may have (B09 item 9). */
    @Override
    public void onNeighbourChanged() {
        if (level instanceof ServerLevel server) {
            redstone.neighbourChanged(server, worldPosition);
        }
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** All twelve slots, inputs first; the screen's slots. Automation goes through {@link #automation()}. */
    public ItemStacksResourceHandler slots() {
        return slots;
    }

    /** The item capability: inserts go to the input slots only, extracts come from the output slots only. */
    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    /** The speed card slots. */
    public SimpleContainer cards() {
        return cards;
    }

    public IoPortMode mode() {
        return mode;
    }

    public IoPortOutput output() {
        return output;
    }

    /** Sets the mode; cells being worked on start a new pass. Server side. */
    public void setMode(IoPortMode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            settingsChanged();
        }
    }

    /** Sets when a cell moves on to the output. Server side. */
    public void setOutput(IoPortOutput output) {
        if (this.output != output) {
            this.output = output;
            settingsChanged();
        }
    }

    /** The mounted cell in input slot {@code slot}; {@code null} for an empty slot or a cell that does not work. */
    public @Nullable CellInventory cell(int slot) {
        return mounted[slot];
    }

    /** Operations per tick now: {@code ioPort.operationsPerTick} raised by the speed cards. */
    public int operations() {
        return IoPortLogic.operations(StorageServerConfig.IO_PORT_OPERATIONS.getAsInt(), cards.count(),
                StorageServerConfig.IO_PORT_SPEED_CARD_MULTIPLIER.getAsDouble());
    }

    /** Items and mB moved since the port loaded; for tests. */
    public long movedTotal() {
        return moved;
    }

    /** The port's task; for tests. */
    public StorageTask task() {
        return task;
    }

    /** Cells in the input slots. */
    public int inputCount() {
        return count(0, INPUTS);
    }

    /** Cells in the output slots. */
    public int outputCount() {
        return count(INPUTS, SLOTS);
    }

    private int count(int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (!slots.stack(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Puts a cell in the first free input slot. Returns false, changing nothing, when {@code stack} is not a cell or no
     * input slot is free. Takes one item from {@code stack}, also in creative mode, so a cell's id is never copied.
     */
    public boolean insertCell(ItemStack stack) {
        if (!(stack.getItem() instanceof StorageCellItem)) {
            return false;
        }
        for (int i = 0; i < INPUTS; i++) {
            if (slots.stack(i).isEmpty()) {
                slots.set(i, ItemResource.of(stack), 1);
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Whether the port's item must carry anything: cells, cards or settings other than the defaults. */
    public boolean hasContents() {
        return inputCount() + outputCount() > 0 || !cards.isEmpty() || mode != IoPortMode.UNLOAD
                || output != IoPortOutput.WORK_DONE || redstone.mode() != RedstoneMode.IGNORE;
    }

    /**
     * The port as an item: its cells (with fresh stats), cards and settings in vanilla's block entity data unless it
     * has none, and its label as a component.
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
            loaded = true;
            redstone.start(server, worldPosition);
            mountAll();
            // The task belongs to this block entity; it sleeps until a cell is in an input slot.
            schedule(task);
        }
        super.onLoad();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (loaded) {
            loaded = false;
            for (int i = 0; i < INPUTS; i++) {
                refreshStats(i);
                unmount(i);
            }
        }
    }

    /** At a glance: the mode as an arrow with the cells waiting, and how full the cell being worked on is. */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        sink.icon(mode == IoPortMode.UNLOAD ? Glance.PORT_IMPORT : Glance.PORT_EXPORT,
                Component.translatable(IoPortContent.GLANCE_KEY, Component.translatable(mode.translationKey()),
                        InfoFormat.number(inputCount())));
        for (int i = 0; i < INPUTS; i++) {
            CellInventory cell = mounted[i];
            if (cell != null) {
                NetworkInfo.fill(cell.usedBytes(), cell.totalBytes(), sink);
                return;
            }
        }
    }

    /** With details: when cells move on, the speed and the cells in the output. */
    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        sink.line(Component.translatable(IoPortContent.DETAIL_KEY, Component.translatable(output.translationKey()),
                InfoFormat.number(operations()), InfoFormat.number(outputCount())));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        for (int i = 0; i < INPUTS; i++) {
            refreshStats(i);
        }
        slots.serialize(output.child(CELLS));
        if (mode != IoPortMode.UNLOAD) {
            output.putInt(MODE, mode.id());
        }
        if (this.output != IoPortOutput.WORK_DONE) {
            output.putInt(OUTPUT, this.output.id());
        }
        if (!cards.isEmpty()) {
            output.putInt(CARDS, cards.count());
        }
        redstone.save(output);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        slots.deserialize(input.childOrEmpty(CELLS));
        mode = IoPortMode.byId(input.getIntOr(MODE, IoPortMode.UNLOAD.id()));
        output = IoPortOutput.byId(input.getIntOr(OUTPUT, IoPortOutput.WORK_DONE.id()));
        cards.fill(input.getIntOr(CARDS, 0));
        redstone.load(input);
        for (Pass pass : passes) {
            pass.reset();
        }
        if (loaded) {
            // New slots on a loaded port (its item placed with data): mount them and go on.
            mountAll();
            task.wakeFromOutside();
        }
    }

    private void settingsChanged() {
        for (Pass pass : passes) {
            pass.reset();
        }
        setChanged();
        task.wakeFromOutside();
    }

    private void mountAll() {
        for (int i = 0; i < INPUTS; i++) {
            unmount(i);
            mount(i);
        }
    }

    private void mount(int slot) {
        ItemStack stack = slots.stack(slot);
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
        mounted[slot] = storage.mount(data.id(), type, cellItem.casing(), data, IoPortBlockEntity::cellCommitted);
        loadRefused[slot] = refusesLoad(type);
    }

    /**
     * Whether cells of {@code type} are never loaded: a creative cell would take the network's items and never give
     * them back. Storage has no creative cell type yet (only item and fluid, B09 item 11); when one is registered it is
     * refused here, and a refused cell in load mode moves straight to the output.
     */
    static boolean refusesLoad(CellType type) {
        return false;
    }

    /** A cell's change committed; the port keeps no view of its contents, so nothing follows. */
    private static void cellCommitted() {
    }

    private void unmount(int slot) {
        CellInventory inventory = mounted[slot];
        if (inventory != null && level instanceof ServerLevel server) {
            CellStorage.get(server.getServer()).unmount(inventory);
        }
        mounted[slot] = null;
        loadRefused[slot] = false;
    }

    /** Writes the mounted cell's stats into its item, so its tooltip and the screen's gauge are current. */
    private void refreshStats(int slot) {
        CellInventory inventory = mounted[slot];
        ItemStack stack = slots.stack(slot);
        CellData data = StorageCellItem.data(stack);
        if (inventory != null && data != null && data.id() == inventory.id()
                && (data.usedBytes() != inventory.usedBytes() || data.totalBytes() != inventory.totalBytes()
                        || data.kinds() != inventory.kinds() || data.kept() != inventory.keptKinds())) {
            stack.set(ModDataComponents.CELL.get(), data.withStats(inventory.usedBytes(), inventory.totalBytes(),
                    inventory.kinds(), inventory.keptKinds()));
        }
    }

    /** A slot's cell went in or out (after the root transaction, if any, committed). */
    private void slotChanged(int slot) {
        setChanged();
        if (slot < INPUTS) {
            passes[slot].reset();
            if (loaded) {
                unmount(slot);
                mount(slot);
            }
        }
        task.wakeFromOutside();
    }

    /**
     * Moves input slot {@code slot}'s cell, stats current, into the first free output slot. False, changing nothing,
     * when every output slot is taken.
     */
    private boolean moveOut(int slot) {
        for (int out = INPUTS; out < SLOTS; out++) {
            if (slots.stack(out).isEmpty()) {
                refreshStats(slot);
                ItemStack cell = slots.stack(slot).copy();
                slots.set(slot, ItemResource.EMPTY, 0);
                slots.set(out, ItemResource.of(cell), 1);
                return true;
            }
        }
        return false;
    }

    /** One input slot's pass: the keys it goes over, where it is, and whether anything moved since it started. */
    private static final class Pass {
        final ObjectArrayList<StackKey> keys = new ObjectArrayList<>();
        int cursor;
        boolean started;
        boolean moved;
        /** Done with but staying: the run skips it until something changes or the backoff runs out. */
        boolean parked;
        /** Parked because it should move on but every output slot is taken. */
        boolean blocked;

        void reset() {
            keys.clear();
            cursor = 0;
            started = false;
            moved = false;
            parked = false;
            blocked = false;
        }
    }

    /** What one slot's turn in a run ended with. */
    private enum Turn {
        /** The cell moved to the output. */
        MOVED,
        /** Done with the cell, but it stays: its output condition does not hold, or no output slot is free. */
        PARKED,
        /** The run's operations are spent. */
        SPENT,
        /** The network refused for lack of energy or because it is not working. */
        REFUSED
    }

    private final class Task implements StorageTask, StorageProvider.Visitor {
        private ObjectArrayList<StackKey> collecting = new ObjectArrayList<>();
        private int budget;
        private int scans;

        void wakeFromOutside() {
            if (!running) {
                for (Pass pass : passes) {
                    pass.parked = false;
                    pass.blocked = false;
                }
                if (loaded) {
                    wake(this);
                }
            }
        }

        @Override
        public void visit(StackKey key, long amount) {
            collecting.add(key);
        }

        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            // Pulse mode: one operation per rising edge since the last run. Edges without cells are dropped; edges
            // the network refuses (no energy) wait, as they are taken only once it can operate.
            boolean pulsing = redstone.pulsing();
            if (!loaded || inputCount() == 0) {
                if (pulsing) {
                    redstone.takePulses();
                }
                return WAIT;
            }
            if (pulsing && !redstone.hasPulses()) {
                return WAIT;
            }
            if (!transfer.canOperate()) {
                return Math.max(1, transfer.retryTicks());
            }
            int pulses = pulsing ? redstone.takePulses() : 0;
            if (idled) {
                idled = false;
                for (Pass pass : passes) {
                    pass.reset();
                }
            }
            budget = pulsing ? pulses : operations();
            scans = budget * IoPortLogic.SCANS_PER_OPERATION;
            boolean parked = false;
            boolean waitsForOutput = false;
            running = true;
            try {
                for (int slot = 0; slot < INPUTS; slot++) {
                    if (slots.stack(slot).isEmpty()) {
                        continue;
                    }
                    Pass pass = passes[slot];
                    Turn turn = pass.parked ? Turn.PARKED : turn(slot, pass, transfer);
                    if (turn == Turn.SPENT) {
                        return pulsing ? WAIT : NEXT_TICK;
                    }
                    if (turn == Turn.REFUSED) {
                        return Math.max(1, transfer.retryTicks());
                    }
                    if (turn == Turn.PARKED) {
                        if (pass.blocked) {
                            waitsForOutput = true;
                        } else {
                            parked = true;
                        }
                    }
                }
            } finally {
                running = false;
            }
            if (pulsing) {
                // The next edge wakes the port.
                return WAIT;
            }
            if (parked) {
                idled = true;
                return IDLE;
            }
            // Nothing in the inputs, or only cells waiting for a free output slot: a slot change wakes the port.
            return waitsForOutput ? WAIT : inputCount() > 0 ? NEXT_TICK : WAIT;
        }

        /** Works on input slot {@code slot}'s cell until it moves on, is set aside or the run's budget is spent. */
        private Turn turn(int slot, Pass pass, StorageTransfer transfer) {
            CellInventory cell = mounted[slot];
            if (cell == null) {
                // Unknown type, unreadable contents or mounted elsewhere: nothing to do with it.
                return leave(slot, pass);
            }
            if (mode == IoPortMode.LOAD && loadRefused[slot]) {
                return leave(slot, pass);
            }
            while (true) {
                // Kept entries (a removed mod's keys) cannot be unloaded: a cell holding only those counts as empty
                // here, but it stays stored under its id with them, so it is never treated as blank.
                boolean empty = cell.kinds() - cell.keptKinds() == 0;
                boolean full = cell.usedBytes() >= cell.totalBytes();
                if (output != IoPortOutput.WORK_DONE && IoPortLogic.leaves(output, empty, full, false)) {
                    return leave(slot, pass);
                }
                if (pass.cursor >= pass.keys.size()) {
                    boolean done = IoPortLogic.finished(mode, empty, full) || (pass.started && !pass.moved);
                    if (!done) {
                        startPass(pass, cell, transfer.network().contents());
                        done = pass.keys.isEmpty();
                    }
                    if (done) {
                        if (IoPortLogic.leaves(output, empty, full, true)) {
                            return leave(slot, pass);
                        }
                        pass.parked = true;
                        pass.blocked = false;
                        return Turn.PARKED;
                    }
                }
                if (budget <= 0 || scans <= 0) {
                    return Turn.SPENT;
                }
                StackKey key = pass.keys.get(pass.cursor);
                long count = mode == IoPortMode.UNLOAD ? unloadOne(cell, key, transfer) : loadOne(cell, key, transfer);
                if (count > 0) {
                    budget--;
                    moved += count;
                    pass.moved = true;
                    if (mode == IoPortMode.UNLOAD && cell.amount(key) <= 0) {
                        pass.cursor++;
                    }
                } else if (count == 0 && transfer.retryTicks() > 0) {
                    return Turn.REFUSED;
                } else {
                    // The cell refused the key, or the network had no room or none of it: the next key.
                    scans--;
                    pass.cursor++;
                }
            }
        }

        private Turn leave(int slot, Pass pass) {
            if (moveOut(slot)) {
                return Turn.MOVED;
            }
            pass.parked = true;
            pass.blocked = true;
            return Turn.PARKED;
        }

        private void startPass(Pass pass, CellInventory cell, StorageContents contents) {
            pass.keys.clear();
            pass.cursor = 0;
            pass.started = true;
            pass.moved = false;
            if (mode == IoPortMode.UNLOAD) {
                collecting = pass.keys;
                cell.forEachStored(this);
                return;
            }
            IntIterator ids = contents.snapshot().totals().keySet().iterator();
            while (ids.hasNext()) {
                StackKey key = contents.key(ids.nextInt());
                if (key != null) {
                    pass.keys.add(key);
                }
            }
        }
    }

    /**
     * One batch of {@code key} from the cell into the network: the network takes what it has room for, then the cell
     * gives exactly that, in one root transaction. Returns what moved; 0 when the network took nothing, -1 when the
     * cell holds none.
     */
    static long unloadOne(CellInventory cell, StackKey key, StorageTransfer transfer) {
        long want = Math.min(cell.amount(key), transfer.batchLimit(key.kind()));
        if (want <= 0) {
            return -1;
        }
        try (Transaction root = Transaction.openRoot()) {
            long stored = transfer.insert(key, want, root);
            if (stored <= 0 || cell.extract(key, stored, root) != stored) {
                return 0;
            }
            root.commit();
            return stored;
        }
    }

    /**
     * One batch of {@code key} from the network into the cell: what the cell would store is simulated (its own rules
     * decide), the network gives up to that, and the cell must then store exactly that, all in one root transaction.
     * Measured by the cell's count, so a void-overflow cell never takes from the network what it would destroy. Returns
     * what moved; -1 when the cell takes none of {@code key}, 0 when the network gave none.
     */
    static long loadOne(CellInventory cell, StackKey key, StorageTransfer transfer) {
        long want = transfer.batchLimit(key.kind());
        try (Transaction root = Transaction.openRoot()) {
            long room = room(cell, key, want, root);
            if (room <= 0) {
                return -1;
            }
            long taken = transfer.extract(key, room, root);
            if (taken <= 0) {
                return 0;
            }
            long before = cell.amount(key);
            cell.insert(key, taken, root);
            if (cell.amount(key) - before != taken) {
                return 0;
            }
            root.commit();
            return taken;
        }
    }

    private static long room(CellInventory cell, StackKey key, long want, TransactionContext root) {
        try (Transaction simulation = Transaction.open(root)) {
            long before = cell.amount(key);
            cell.insert(key, want, simulation);
            return cell.amount(key) - before;
        }
    }

    /** Twelve one-cell slots, inputs then outputs; only the inputs take cells from outside. */
    private final class CellSlots extends ItemStacksResourceHandler {
        CellSlots() {
            super(SLOTS);
        }

        ItemStack stack(int slot) {
            return stacks.get(slot);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return index < INPUTS && resource.getItem() instanceof StorageCellItem;
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return 1;
        }

        @Override
        public ItemResource getResource(int index) {
            // Whoever reads an input slot (the screen's gauge, a player taking the cell) gets current stats.
            if (loaded && index < INPUTS) {
                refreshStats(index);
            }
            return super.getResource(index);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            slotChanged(index);
        }
    }

    /** The view automation sees: inserts into the input slots, extracts from the output slots. */
    private final class Automation implements ResourceHandler<ItemResource> {
        @Override
        public int size() {
            return SLOTS;
        }

        @Override
        public ItemResource getResource(int index) {
            return slots.getResource(index);
        }

        @Override
        public long getAmountAsLong(int index) {
            return slots.getAmountAsLong(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return slots.getCapacityAsLong(index, resource);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return slots.isValid(index, resource);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index < INPUTS ? slots.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return index >= INPUTS ? slots.extract(index, resource, amount, transaction) : 0;
        }
    }

    /** The speed card slots: one card each, saved as a count since all cards are alike. */
    private final class Cards extends SimpleContainer {
        Cards() {
            super(CARD_SLOTS);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return stack.is(PortContent.SPEED_CARD.get());
        }

        int count() {
            int count = 0;
            for (int i = 0; i < CARD_SLOTS; i++) {
                if (getItem(i).is(PortContent.SPEED_CARD.get())) {
                    count++;
                }
            }
            return count;
        }

        void fill(int count) {
            getItems().clear();
            for (int i = 0; i < Math.min(count, CARD_SLOTS); i++) {
                getItems().set(i, new ItemStack(PortContent.SPEED_CARD.get()));
            }
        }

        @Override
        public void setChanged() {
            super.setChanged();
            IoPortBlockEntity.this.setChanged();
            task.wakeFromOutside();
        }
    }
}
