package dev.silencebound.maxtech.storage.togglebus;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.ChannelFree;
import dev.silencebound.maxtech.storage.network.Glance;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A toggle bus on a storage cable's face (B09 item 19): it sits in the cable's line and links the cable to whatever is
 * beyond that face while the cable's block is powered (the inverted one while it is not), like AE2's toggle bus. A
 * switch is a topology change: the part tells its cable ({@link CableParts#lookChanged()}), the network graph re-reads
 * the cable's links on its next tick and splits or merges the networks as for a broken or placed cable, with everything
 * that follows from that (devices beyond go offline, their storage leaves the views, crafting jobs behave as on a
 * broken cable).
 *
 * <p>
 * Debounced: two switches are at least {@code network.toggleBus.minIntervalTicks} apart. A signal that changes sooner
 * asks for the cable block's scheduled tick when that interval is over and is read again then, so a redstone clock
 * switches the bus at most once per interval and its last state always wins. The bus holds no channel
 * ({@link ChannelFree}, as in AE2) and never ticks; it reads the signal only on neighbour updates, its own scheduled
 * tick and when it starts. It does not read the signal while a chunk the read would touch is not loaded: it keeps its
 * saved state, waits without retrying and reads again when that chunk loads ({@link ToggleBusWaits}) or a neighbour
 * changes.
 */
public final class ToggleBusPart extends CablePart implements ChannelFree {
    private static final String LINKED = "linked";
    /** A redstone read touches blocks up to three away (a conductor's neighbours, a wire's further reads). */
    private static final int READ_REACH = 3;
    private static final ChunkCheck LEVEL_CHUNKS = ServerLevel::hasChunk;
    private static ChunkCheck chunkCheck = LEVEL_CHUNKS;

    private final boolean inverted;
    private boolean linked;
    /** Game time of the last switch; far in the past at first, so the first switch is never held back. */
    private long lastSwitch = Long.MIN_VALUE / 2;
    /**
     * Whether a scheduled tick is pending for a held-back switch. Not saved: the tick itself is saved with the chunk.
     */
    private boolean waiting;
    private int switches;

    public ToggleBusPart(PartType type, CableParts host, Direction face, boolean inverted) {
        super(type, host, face);
        this.inverted = inverted;
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    /** Whether this bus links while unpowered. */
    public boolean inverted() {
        return inverted;
    }

    /** Whether the bus links its face now. */
    public boolean linked() {
        return linked;
    }

    /** How often this part instance switched since it was made; for tests and diagnostics. */
    public int switches() {
        return switches;
    }

    @Override
    public boolean linksThrough() {
        return linked;
    }

    /** 1 while linked, 0 while open; the cable model draws the collar's band lit or dark by it. */
    @Override
    public int lookVariant() {
        return linked ? 1 : 0;
    }

    @Override
    protected void start(ServerLevel level) {
        update();
    }

    /** Only a block update can carry a redstone change; a container's comparator update ({@code from} set) cannot. */
    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        if (from == null) {
            update();
        }
    }

    @Override
    public void scheduledTick() {
        waiting = false;
        update();
    }

    /** Reads the signal and switches, or holds the switch back until the interval since the last one is over. */
    private void update() {
        Level level = host().level();
        if (!(level instanceof ServerLevel server) || isRemoved()) {
            return;
        }
        BlockPos pos = pos();
        if (!chunksLoaded(server, pos)) {
            return;
        }
        boolean wanted = server.hasNeighborSignal(pos) != inverted;
        if (wanted == linked) {
            return;
        }
        long now = server.getGameTime();
        long due = lastSwitch + StorageServerConfig.TOGGLE_BUS_MIN_INTERVAL.getAsInt();
        if (now < due) {
            schedule((int) (due - now));
            return;
        }
        linked = wanted;
        lastSwitch = now;
        switches++;
        host().changed();
        // Re-reads the cable's links (a topology change) and redraws the collar on clients.
        host().lookChanged();
    }

    /**
     * Whether every chunk a signal read at {@code pos} could touch is loaded. Each one that is not gets this bus on its
     * wait list ({@link ToggleBusWaits}), since a loading chunk sends no block update; nothing retries meanwhile.
     */
    private boolean chunksLoaded(ServerLevel server, BlockPos pos) {
        boolean loaded = true;
        int minX = SectionPos.blockToSectionCoord(pos.getX() - READ_REACH);
        int maxX = SectionPos.blockToSectionCoord(pos.getX() + READ_REACH);
        int minZ = SectionPos.blockToSectionCoord(pos.getZ() - READ_REACH);
        int maxZ = SectionPos.blockToSectionCoord(pos.getZ() + READ_REACH);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!chunkCheck.loaded(server, x, z)) {
                    loaded = false;
                    ToggleBusWaits.await(server, ChunkPos.pack(x, z), this);
                }
            }
        }
        return loaded;
    }

    /** A chunk this bus waited for loaded: it reads its signal on the cable's next scheduled tick. */
    void chunkArrived() {
        // Inside the chunk event the chunk may not be readable yet (LifecycleEvents.onChunkLoaded).
        schedule(1);
    }

    /**
     * Replaces how the bus tells whether a chunk is loaded, or restores the level's own answer with null; for tests,
     * which cannot unload a chunk next to a test. Server thread.
     */
    public static void setChunkCheck(@Nullable ChunkCheck check) {
        chunkCheck = check == null ? LEVEL_CHUNKS : check;
    }

    /** Whether the chunk at {@code chunkX}, {@code chunkZ} of {@code level} is loaded. */
    @FunctionalInterface
    public interface ChunkCheck {
        boolean loaded(ServerLevel level, int chunkX, int chunkZ);
    }

    /** Asks for the cable's scheduled tick in {@code delay} ticks unless one is pending for this bus. */
    private void schedule(int delay) {
        if (!waiting) {
            waiting = true;
            host().scheduleTick(delay);
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        sink.icon(linked ? Glance.SWITCH_ON : Glance.SWITCH_OFF,
                Component.translatable(linked ? ToggleBusContent.LINKED_KEY : ToggleBusContent.OPEN_KEY));
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        sink.line(Component.translatable(inverted ? ToggleBusContent.INVERTED_KEY : ToggleBusContent.NORMAL_KEY));
        Level level = host().level();
        if (waiting && level != null) {
            long left = lastSwitch + StorageServerConfig.TOGGLE_BUS_MIN_INTERVAL.getAsInt() - level.getGameTime();
            sink.line(Component.translatable(ToggleBusContent.WAITING_KEY, Long.toString(Math.max(0, left))));
        }
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        if (linked) {
            output.putBoolean(LINKED, true);
        }
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        linked = input.getBooleanOr(LINKED, false);
    }
}
