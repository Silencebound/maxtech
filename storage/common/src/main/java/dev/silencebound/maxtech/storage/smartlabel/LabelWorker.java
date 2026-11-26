package dev.silencebound.maxtech.storage.smartlabel;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderDevice;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.display.DisplayViewers;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.sensor.MachineReader;
import dev.silencebound.maxtech.storage.sensor.SensorRef;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

/**
 * The server side of one smart label: works out what it shows and sends it to the players who can see it
 * ({@link DisplayViewers}), only when it changed and at most every {@code display.updateTicks} ticks. With no viewer it
 * sleeps and costs nothing.
 *
 * <ul>
 * <li>An item count in the network is pushed: the per-key listener wakes the label.
 * <li>Everything read from the block the label sits on goes through a {@link MachineReader} (cached handler lookups,
 * the sensor's claim check and team budget). The reading is refreshed when the block or its handlers change and at most
 * once per update interval while someone sees the label, so a container that tells nobody still shows its contents.
 * <li>A machine's state comes from the pattern or generator provider facing it (item 9's stuck reasons), found among
 * the machine's neighbours when the label starts, its settings or neighbours change, and at most every
 * {@link #SCAN_TICKS} while seen without one; then from its {@code lit} block state.
 * </ul>
 *
 * The work runs as a task on the network's scheduler. A label links only to the block it sits on, so on a cable or a
 * device it is in that network and counts its items; on anything else it is a network of its own (one node, ad hoc) and
 * counts the block's own inventory. Server thread.
 */
public final class LabelWorker implements StorageTask, KeyListener, DisplayViewers.Screen {
    /** Ticks between two searches for a provider facing the machine while seen without one. */
    static final int SCAN_TICKS = 100;
    private static final Direction[] SIDES = Direction.values();

    private final SmartLabelBlockEntity device;
    private final MachineReader reader = new MachineReader(() -> {
    });
    private @Nullable ServerLevel level;
    private long chunk;
    /** The players who see the chunk, kept up to date by {@link DisplayViewers} while started; empty otherwise. */
    private List<ServerPlayer> viewers = List.of();
    private @Nullable StackKey listened;
    private @Nullable SensorRef itemRef;
    private @Nullable ProviderLogic provider;
    private @Nullable GeneratorProviderBlockEntity generator;
    private long nextScan = Long.MIN_VALUE;
    private long nextSend = Long.MIN_VALUE;
    private boolean sentAny;
    private int sentStatus;
    private int sentDetail;
    private long sentValue;
    private long sentMax;
    private int status;
    private int detail;
    private long value;
    private long max;
    private int sends;

    LabelWorker(SmartLabelBlockEntity device) {
        this.device = device;
    }

    /** Starts watching the label's chunk and item and schedules its task; once per load, server side. */
    void start(ServerLevel level) {
        if (this.level != null) {
            return;
        }
        this.level = level;
        this.chunk = ChunkPos.pack(device.getBlockPos());
        viewers = DisplayViewers.register(level, chunk, this);
        subscribe();
        device.schedule(this);
        resend();
    }

    /** Whether {@link #start} ran and {@link #stop} did not; for tests. */
    public boolean isStarted() {
        return level != null;
    }

    /** Stops everything {@link #start} began (the label is broken or unloaded). */
    void stop() {
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        level = null;
        DisplayViewers.unregister(current, chunk, this);
        viewers = List.of();
        unsubscribe();
        device.cancel(this);
        reader.close();
    }

    /** The settings changed: listen to the new item and send the new value. */
    void settingsChanged() {
        if (level == null) {
            return;
        }
        unsubscribe();
        subscribe();
        provider = null;
        generator = null;
        nextScan = Long.MIN_VALUE;
        resend();
    }

    /** The label joined a network or left it: read and send again. */
    void networkChanged() {
        resend();
    }

    /** The block the label sits on, or a neighbour, changed: read it again on the next run. */
    void targetChanged() {
        reader.invalidate();
        nextScan = Long.MIN_VALUE;
        if (hasViewers()) {
            wake();
        }
    }

    private void resend() {
        sentAny = false;
        nextSend = Long.MIN_VALUE;
        wake();
    }

    private void wake() {
        if (level != null) {
            // Every placed label is in a network, alone if it sits on nothing of one; the task waits until it is.
            device.wake(this);
        }
    }

    private void subscribe() {
        Optional<Item> item = device.settings().item();
        itemRef = item.isPresent() ? SensorRef.item(item.get()) : null;
        if (item.isPresent()) {
            StackKey key = ItemKey.of(item.get());
            listened = key;
            device.deviceLink().listen(key, this);
        }
    }

    private void unsubscribe() {
        StackKey key = listened;
        if (key != null) {
            device.deviceLink().unlisten(key, this);
            listened = null;
        }
        itemRef = null;
    }

    @Override
    public void keyChanged(StackKey key, long total) {
        if (hasViewers()) {
            wake();
        }
    }

    @Override
    public void viewersChanged(boolean added) {
        if (added) {
            resend();
        }
    }

    @Override
    public boolean isGone() {
        return level == null || device.isRemoved();
    }

    private boolean hasViewers() {
        return level != null && !viewers.isEmpty();
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        return step(transfer.network(), gameTime);
    }

    /** Reads and sends if due; returns ticks until the next run, or {@link #WAIT}. */
    private int step(NetworkState network, long gameTime) {
        ServerLevel current = level;
        if (current == null || !hasViewers()) {
            return WAIT;
        }
        if (gameTime < nextSend) {
            return (int) Math.min(Integer.MAX_VALUE, nextSend - gameTime);
        }
        int interval = StorageServerConfig.DISPLAY_UPDATE_TICKS.getAsInt();
        LabelSettings settings = device.settings();
        read(current, network, settings, gameTime, interval);
        if (!sentAny || status != sentStatus || detail != sentDetail || value != sentValue || max != sentMax) {
            send(current);
            nextSend = gameTime + interval;
        }
        // A network's count is pushed by the key listener; everything else is read again on the interval.
        boolean pushed = settings.mode() == LabelMode.ITEM && (linked(network) || settings.item().isEmpty());
        return pushed ? WAIT : interval;
    }

    private void read(ServerLevel level, NetworkState network, LabelSettings settings, long gameTime, int interval) {
        status = LabelUpdate.NOTHING;
        detail = 0;
        value = 0;
        max = 0;
        switch (settings.mode()) {
            case ITEM -> readItem(level, network, gameTime, interval);
            case FILL -> readFill(level, network, gameTime, interval);
            case MACHINE -> readMachine(level, gameTime);
        }
    }

    private void readItem(ServerLevel level, NetworkState network, long gameTime, int interval) {
        StackKey key = listened;
        SensorRef ref = itemRef;
        if (key == null || ref == null) {
            return;
        }
        if (linked(network)) {
            show(LabelUpdate.FROM_NETWORK, network.contents().amount(key), 0);
        } else if (readBlock(level, gameTime, interval)) {
            show(LabelUpdate.FROM_BLOCK, reader.value(ref), 0);
        }
    }

    private void readFill(ServerLevel level, NetworkState network, long gameTime, int interval) {
        if (readBlock(level, gameTime, interval)) {
            long slots = reader.value(SensorRef.ITEMS_SLOTS_USED) + reader.value(SensorRef.ITEMS_SLOTS_FREE);
            long fluids = reader.value(SensorRef.FLUIDS_CAPACITY);
            long energy = reader.value(SensorRef.ENERGY_CAPACITY);
            if (slots > 0) {
                // Per slot against its item's stack size, so a slot of 64 cobblestone is full.
                show(LabelUpdate.FILL_ITEMS, reader.itemFill(), MachineReader.FILL_SCALE);
                return;
            }
            if (fluids > 0) {
                show(LabelUpdate.FILL_FLUIDS, reader.value(SensorRef.FLUIDS_TOTAL), fluids);
                return;
            }
            if (energy > 0) {
                show(LabelUpdate.FILL_ENERGY, reader.value(SensorRef.ENERGY_STORED), energy);
                return;
            }
        }
        // A network device without handlers of its own (a drive, the controller): the network's storage.
        if (linked(network) && network.totalBytes() > 0) {
            show(LabelUpdate.FILL_NETWORK, network.usedBytes(), network.totalBytes());
        }
    }

    private void readMachine(ServerLevel level, long gameTime) {
        BlockPos target = device.targetPos();
        if (!loaded(level, target.asLong())) {
            return;
        }
        if (!providerValid(target.asLong()) && gameTime >= nextScan) {
            nextScan = gameTime + SCAN_TICKS;
            scan(level, target);
        }
        ProviderLogic logic = providerValid(target.asLong()) ? provider : null;
        GeneratorProviderBlockEntity gen = providerValid(target.asLong()) ? generator : null;
        StuckReason reason = logic != null ? logic.stuck() : gen != null ? gen.logic().stuck() : null;
        if (reason != null) {
            show(LabelUpdate.STUCK, SmartLabelContent.reasonId(reason), 0);
            return;
        }
        boolean working = (logic != null && logic.inFlight() > 0) || (gen != null && gen.logic().busy());
        BlockState state = level.getBlockState(target);
        boolean lit = state.hasProperty(BlockStateProperties.LIT);
        if (logic == null && gen == null && !lit) {
            return;
        }
        if (!working && lit) {
            working = state.getValue(BlockStateProperties.LIT);
        }
        show(working ? LabelUpdate.WORKING : LabelUpdate.IDLE, 0, 0);
    }

    /** Whether the provider found last still faces (or is) the block at {@code target}. */
    private boolean providerValid(long target) {
        ProviderLogic logic = provider;
        if (logic != null) {
            long at = logic.pos().asLong();
            return !logic.device().isRemoved() && (at == target || BlockPos.offset(at, logic.facing()) == target);
        }
        GeneratorProviderBlockEntity gen = generator;
        if (gen != null) {
            long at = gen.getBlockPos().asLong();
            return !gen.isRemoved() && (at == target || BlockPos.offset(at, gen.facing()) == target);
        }
        return false;
    }

    /**
     * Finds the provider to show: the block itself when it is one, else a provider block or a provider part on a
     * neighbouring cable facing it. Looks at seven block entities, so it runs only as {@link #readMachine} allows.
     */
    private void scan(ServerLevel level, BlockPos target) {
        provider = null;
        generator = null;
        BlockEntity at = level.getBlockEntity(target);
        if (at instanceof ProviderDevice self) {
            provider = self.logic();
            return;
        }
        if (at instanceof GeneratorProviderBlockEntity self) {
            generator = self;
            return;
        }
        for (Direction side : SIDES) {
            BlockPos pos = target.relative(side);
            if (!loaded(level, pos.asLong())) {
                continue;
            }
            Direction back = side.getOpposite();
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof PatternProviderBlockEntity block && block.logic().facing() == back) {
                provider = block.logic();
                return;
            }
            if (entity instanceof GeneratorProviderBlockEntity block && block.facing() == back) {
                generator = block;
                return;
            }
            CableParts parts = StorageCableBlock.parts(level, pos);
            if (parts != null && parts.part(back) instanceof ProviderDevice part) {
                provider = part.logic();
                return;
            }
        }
    }

    /**
     * Whether the label sits on a part of its network (a cable or a device): a label on anything else is a network of
     * its own, with nothing to count.
     */
    private static boolean linked(NetworkState network) {
        return network.nodeCount() > 1;
    }

    /** Refreshes the reading of the block the label sits on; false when there is none to use. */
    private boolean readBlock(ServerLevel level, long gameTime, int interval) {
        return reader.refresh(level, device.getBlockPos().asLong(), device.facing().getOpposite(), device.ownerRecord(),
                TeamBudget.team(level, device), gameTime, interval);
    }

    private void show(int detail, long value, long max) {
        this.status = LabelUpdate.SHOWN;
        this.detail = detail;
        this.value = value;
        this.max = max;
    }

    private static boolean loaded(ServerLevel level, long pos) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(BlockPos.getX(pos)),
                SectionPos.blockToSectionCoord(BlockPos.getZ(pos))) != null;
    }

    private void send(ServerLevel level) {
        sentAny = true;
        sentStatus = status;
        sentDetail = detail;
        sentValue = value;
        sentMax = max;
        List<ServerPlayer> players = DisplayViewers.players(level, chunk);
        if (players.isEmpty()) {
            return;
        }
        LabelUpdate update = new LabelUpdate(device.getBlockPos(), status, detail, value, max);
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            // Players without a connection (fake players) are skipped.
            if (player.connection != null) {
                Platform.get().network().sendToPlayer(player, update);
            }
        }
        sends++;
    }

    /** The last update sent, or null before the first; for tests. */
    public @Nullable LabelUpdate lastSent() {
        return sentAny ? new LabelUpdate(device.getBlockPos(), sentStatus, sentDetail, sentValue, sentMax) : null;
    }

    /** Updates sent so far (one per change, whatever the number of viewers); for tests. */
    public int sends() {
        return sends;
    }
}
