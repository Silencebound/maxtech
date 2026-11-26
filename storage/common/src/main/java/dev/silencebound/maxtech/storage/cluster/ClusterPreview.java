package dev.silencebound.maxtech.storage.cluster;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerBlock;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCluster;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPart;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPartBlock;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuStats;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Ghost preview for clusters (M7C item 22), server side. While a player holds a controller block or a crafting CPU
 * part, the client asks with {@link Request} every {@link #REQUEST_TICKS} ticks; the server answers with a
 * {@link Preview} of the nearest cluster of that kind that has a block within {@link #NEAR} blocks of the player: its
 * blocks, the free cells where the held block would join it (the face-to-face shape rule of {@link ClusterGrowth} with
 * the cluster's own conflict rules: {@link ControllerBlockEntity#foreign} and {@link CpuCluster#canGrowInto}), whether
 * one more such block raises what the cluster can do, and its {@link ClusterNeeds}. A controller's blocks come from its
 * primary's saved lists (M7R); a CPU cluster is filled once with {@link CpuCluster#scan}. Nothing runs unless a player
 * holds such a block, and at most once per {@link #REQUEST_TICKS} per player. Server thread.
 */
public final class ClusterPreview {
    /** How close (blocks, to a block's centre) a cluster must be to the player to be previewed. */
    public static final int NEAR = 8;
    /** Cores farther than this from the player are not looked at. */
    static final int SEARCH = 48;
    /** Most growth cells one preview carries. */
    public static final int MAX_GROWTH = 512;
    /** Most cluster blocks one preview carries. */
    public static final int MAX_MEMBERS = 1024;
    /** How often the client asks while a cluster block is held, and how often the server answers one player. */
    public static final int REQUEST_TICKS = 10;
    /** The Network tab's heading of the cluster lines. */
    public static final String HEADER_KEY = "gui.maxtech_storage.cluster_need.header";
    private static final Map<Player, Long> LAST = new WeakHashMap<>();
    private static final Direction[] SIDES = Direction.values();
    private static Preview received = Preview.NONE;
    private static int receivedCount;

    static {
        ModContent.CONTENT.translation(HEADER_KEY, "Clusters");
        text(ClusterNeeds.Kind.CONTROLLER_FREE, "Controller: %1$s of %2$s blocks, room for %3$s more");
        text(ClusterNeeds.Kind.CONTROLLER_FULL, "Controller full: %1$s of %2$s blocks; research raises the limit");
        text(ClusterNeeds.Kind.CONTROLLER_FULL_MAX, "Controller full: %1$s of %2$s blocks, the highest limit");
        text(ClusterNeeds.Kind.CONTROLLER_OVER,
                "Controller over its size limit: %1$s of %2$s blocks; the rest stay dark");
        text(ClusterNeeds.Kind.CONTROLLER_IDLE, "This block belongs to another controller");
        text(ClusterNeeds.Kind.CPU, "Crafting CPU tier %1$s at %4$s %5$s %6$s");
        text(ClusterNeeds.Kind.CPU_CONFLICT, "Another CPU core touches these parts: neither runs");
        text(ClusterNeeds.Kind.CPU_PART_LIMIT, "The part limit of %1$s is reached: more parts do not count");
        text(ClusterNeeds.Kind.CPU_STORAGE_MORE, "%1$s more storage blocks reach the tier's %2$s steps");
        text(ClusterNeeds.Kind.CPU_LANE_MORE, "%1$s more lane blocks reach the tier's %2$s lanes");
        text(ClusterNeeds.Kind.CPU_CACHE_MORE, "%1$s more cache blocks reach the tier's %2$s crafts per dispatch");
        text(ClusterNeeds.Kind.CPU_STORAGE_EXTRA, "%1$s storage blocks add nothing: the tier's step cap is reached");
        text(ClusterNeeds.Kind.CPU_LANE_EXTRA, "%1$s lane blocks add nothing: the tier's lane cap is reached");
        text(ClusterNeeds.Kind.CPU_CACHE_EXTRA, "%1$s cache blocks add nothing: the tier's batch cap is reached");
        text(ClusterNeeds.Kind.CPU_COMPLETE, "Every cap of the tier is reached");
    }

    /** What the preview is of. */
    public enum Mode {
        NONE, CONTROLLER, CPU
    }

    /** Client to server: preview the cluster near me for the block I hold. */
    public record Request() implements CustomPacketPayload {
        public static final Request INSTANCE = new Request();
        public static final Type<Request> TYPE = new Type<>(MaxtechStorage.id("cluster_preview_request"));
        public static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the previewed cluster, or {@link #NONE}.
     *
     * @param core
     *            the core's position
     * @param members
     *            the cluster's blocks, core first
     * @param growth
     *            free cells where the held block would join the cluster
     * @param helps
     *            whether one more of the held block raises what the cluster can do
     * @param conflict
     *            whether the cluster is in a conflict (runs nothing; no growth then)
     * @param needs
     *            the cluster's needs
     */
    public record Preview(Mode mode, long core, LongList members, LongList growth, boolean helps, boolean conflict,
            List<ClusterNeeds.Need> needs) implements CustomPacketPayload {
        public static final Preview NONE = new Preview(Mode.NONE, 0, new LongArrayList(), new LongArrayList(), false,
                false, List.of());
        public static final Type<Preview> TYPE = new Type<>(MaxtechStorage.id("cluster_preview"));
        public static final StreamCodec<FriendlyByteBuf, Preview> STREAM_CODEC = StreamCodec.of(Preview::write,
                Preview::read);

        @Override
        public Type<Preview> type() {
            return TYPE;
        }

        private static void write(FriendlyByteBuf buf, Preview preview) {
            buf.writeEnum(preview.mode);
            buf.writeLong(preview.core);
            writeLongs(buf, preview.members);
            writeLongs(buf, preview.growth);
            buf.writeBoolean(preview.helps);
            buf.writeBoolean(preview.conflict);
            buf.writeVarInt(preview.needs.size());
            for (ClusterNeeds.Need need : preview.needs) {
                need.write(buf);
            }
        }

        private static Preview read(FriendlyByteBuf buf) {
            Mode mode = buf.readEnum(Mode.class);
            long core = buf.readLong();
            LongList members = readLongs(buf, MAX_MEMBERS);
            LongList growth = readLongs(buf, MAX_GROWTH);
            boolean helps = buf.readBoolean();
            boolean conflict = buf.readBoolean();
            int count = Math.min(buf.readVarInt(), ClusterNeeds.MAX);
            List<ClusterNeeds.Need> needs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                needs.add(ClusterNeeds.Need.read(buf));
            }
            return new Preview(mode, core, members, growth, helps, conflict, needs);
        }
    }

    private ClusterPreview() {
    }

    /** Registers the payloads; during mod construction. */
    public static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Request.TYPE, Request.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer serverPlayer) {
                        answer(serverPlayer);
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Preview.TYPE, Preview.STREAM_CODEC,
                (payload, player) -> {
                    if (player.level().isClientSide()) {
                        received = payload;
                        receivedCount++;
                    }
                });
    }

    /** Client side: the last preview the server sent. */
    public static Preview received() {
        return received;
    }

    /** Client side: how many previews arrived, so the client glue rebuilds its ghosts only on a new one. */
    public static int receivedCount() {
        return receivedCount;
    }

    /** Client side: forgets the last preview (no cluster block held any more, or leaving a world). */
    public static void forget() {
        received = Preview.NONE;
        receivedCount++;
    }

    /** Client side: asks for a preview. */
    public static void request() {
        Platform.get().network().sendToServer(Request.INSTANCE);
    }

    /** Sends {@code player} the preview for what they hold, at most once per {@link #REQUEST_TICKS}. */
    static void answer(ServerPlayer player) {
        long now = player.level().getServer().getTickCount();
        Long last = LAST.get(player);
        // A little slack, so a client asking exactly every REQUEST_TICKS is never turned away.
        if (last != null && now - last < REQUEST_TICKS / 2) {
            return;
        }
        LAST.put(player, now);
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, compute(player));
        }
    }

    /** The preview for the cluster block {@code player} holds (main hand first), or {@link Preview#NONE}. */
    public static Preview compute(ServerPlayer player) {
        Block held = held(player.getMainHandItem());
        if (held == null) {
            held = held(player.getOffhandItem());
        }
        ServerLevel level = player.level();
        if (held instanceof ControllerBlock) {
            return controller(level, player.position());
        }
        if (held instanceof CpuPartBlock part) {
            return cpu(level, player.position(), part.part());
        }
        return Preview.NONE;
    }

    private static @Nullable Block held(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem item
                && (item.getBlock() instanceof ControllerBlock || item.getBlock() instanceof CpuPartBlock)) {
            return item.getBlock();
        }
        return null;
    }

    /**
     * The preview of the nearest multiblock controller for a held controller block: its blocks, active and dark, the
     * free cells where the block would join it (not those touching another controller, where it would join that one
     * too), and whether one more block would be active under the size cap.
     */
    public static Preview controller(ServerLevel level, Vec3 at) {
        ControllerBlockEntity best = null;
        double bestDistance = (double) NEAR * NEAR;
        for (ControllerBlockEntity core : ControllerBlockEntity.loadedIn(level)) {
            if (core.getBlockPos().distToCenterSqr(at) > (double) SEARCH * SEARCH) {
                continue;
            }
            double distance = Math.min(nearest(core.getBlockPos().asLong(), core.blocks(), at),
                    nearest(core.getBlockPos().asLong(), core.darkBlocks(), at));
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = core;
            }
        }
        if (best == null) {
            return Preview.NONE;
        }
        ControllerBlockEntity core = best;
        LongArrayList cluster = new LongArrayList();
        cluster.add(core.getBlockPos().asLong());
        addAll(cluster, core.blocks());
        addAll(cluster, core.darkBlocks());
        LongArrayList growth = new LongArrayList();
        boolean idle = core.isIdle();
        if (!idle) {
            ClusterGrowth.frontier(cluster, cell -> free(level, cell) && !touchesForeign(level, core, cell), growth,
                    MAX_GROWTH);
        }
        // One more block counts while the multiblock is under its research cap; past it the block would stay dark.
        boolean helps = !idle && core.blockCount() < core.cap();
        List<ClusterNeeds.Need> needs = new ArrayList<>();
        controllerNeeds(core, needs);
        return new Preview(Mode.CONTROLLER, core.getBlockPos().asLong(), cluster, growth, helps, idle, needs);
    }

    private static void addAll(LongArrayList cluster, LongList positions) {
        for (int i = 0; i < positions.size() && cluster.size() < MAX_MEMBERS; i++) {
            cluster.add(positions.getLong(i));
        }
    }

    /** The preview of the nearest CPU cluster for a held part of kind {@code part}. */
    public static Preview cpu(ServerLevel level, Vec3 at, CpuPart part) {
        int maxParts = StorageServerConfig.CPU_MAX_PARTS.getAsInt();
        CpuCoreBlockEntity best = null;
        LongArrayList bestMembers = null;
        CpuCluster.Scan bestScan = null;
        double bestDistance = (double) NEAR * NEAR;
        List<JobQueue.Cpu> cpus = JobQueue.cpusIn(level);
        for (int i = 0; i < cpus.size(); i++) {
            if (!(cpus.get(i) instanceof CpuCoreBlockEntity core) || core.isRemoved()
                    || core.getBlockPos().distToCenterSqr(at) > (double) SEARCH * SEARCH) {
                continue;
            }
            LongArrayList members = new LongArrayList();
            CpuCluster.Scan scan = CpuCluster.scan(level, core.getBlockPos(), maxParts, members);
            double distance = nearest(core.getBlockPos().asLong(), members, at);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = core;
                bestMembers = members;
                bestScan = scan;
            }
        }
        if (best == null || bestMembers == null || bestScan == null) {
            return Preview.NONE;
        }
        long corePos = best.getBlockPos().asLong();
        LongArrayList cluster = new LongArrayList();
        cluster.add(corePos);
        for (int i = 0; i < bestMembers.size() && cluster.size() < MAX_MEMBERS; i++) {
            cluster.add(bestMembers.getLong(i));
        }
        LongOpenHashSet set = new LongOpenHashSet(bestMembers);
        LongArrayList growth = new LongArrayList();
        boolean conflict = bestScan.conflict();
        if (!conflict) {
            ClusterGrowth.frontier(cluster,
                    cell -> free(level, cell) && CpuCluster.canGrowInto(level, cell, corePos, set), growth, MAX_GROWTH);
        }
        CpuStats.Caps caps = CpuStats.Caps.of(best.cpuTier());
        CpuStats.Parts parts = CpuStats.Parts.fromConfig();
        int total = bestScan.storage() + bestScan.lanes() + bestScan.caches();
        boolean helps = !conflict && ClusterNeeds.helps(bestScan.count(part), total, maxParts, caps, parts, part);
        List<ClusterNeeds.Need> needs = new ArrayList<>();
        ClusterNeeds.cpu(corePos, best.cpuTier().level(), bestScan.storage(), bestScan.lanes(), bestScan.caches(),
                conflict, maxParts, caps, parts, needs);
        return new Preview(Mode.CPU, corePos, cluster, growth, helps, conflict, needs);
    }

    /** Adds the controller cluster's line of {@code core} to {@code out}. Server thread. */
    public static void controllerNeeds(ControllerBlockEntity core, List<ClusterNeeds.Need> out) {
        ClusterNeeds.controller(core.getBlockPos().asLong(), core.blockCount(), core.cap(),
                StorageServerConfig.SIZE_MAX.getAsInt(), core.overCapacity(), core.isIdle(), out);
    }

    /** Adds the lines of {@code core} from its last count to {@code out}. Server thread. */
    public static void cpuNeeds(CpuCoreBlockEntity core, List<ClusterNeeds.Need> out) {
        ClusterNeeds.cpu(core.getBlockPos().asLong(), core.cpuTier().level(), core.count(CpuPart.STORAGE),
                core.count(CpuPart.LANE), core.count(CpuPart.CACHE), core.conflict(),
                StorageServerConfig.CPU_MAX_PARTS.getAsInt(), CpuStats.Caps.of(core.cpuTier()),
                CpuStats.Parts.fromConfig(), out);
    }

    /**
     * The cluster lines of the network {@code core} runs: the controller's, then each CPU core in the network's (at
     * most {@link ClusterNeeds#MAX} lines). For the Network tab's cadence. Server thread.
     */
    public static List<ClusterNeeds.Need> networkNeeds(ControllerBlockEntity core, NetworkState network) {
        List<ClusterNeeds.Need> out = new ArrayList<>();
        controllerNeeds(core, out);
        List<JobQueue.Cpu> cpus = JobQueue.cpusIn(network.level());
        for (int i = 0; i < cpus.size() && out.size() < ClusterNeeds.MAX; i++) {
            if (cpus.get(i) instanceof CpuCoreBlockEntity cpu && cpu.network() == network) {
                cpuNeeds(cpu, out);
            }
        }
        return out.size() > ClusterNeeds.MAX ? new ArrayList<>(out.subList(0, ClusterNeeds.MAX)) : out;
    }

    /** The line of {@code need}. */
    public static Component text(ClusterNeeds.Need need) {
        BlockPos pos = BlockPos.of(need.pos());
        return Component.translatable(need.kind().key(), InfoFormat.number(need.a()), InfoFormat.number(need.b()),
                InfoFormat.number(need.c()), pos.getX(), pos.getY(), pos.getZ());
    }

    private static void text(ClusterNeeds.Kind kind, String english) {
        ModContent.CONTENT.translation(kind.key(), english);
    }

    /** Whether a block can be placed at {@code cell}: loaded and replaceable (air, grass, water). */
    private static boolean free(ServerLevel level, long cell) {
        BlockPos pos = BlockPos.of(cell);
        return level.isLoaded(pos) && level.getBlockState(pos).canBeReplaced();
    }

    /** Whether a block at {@code cell} would touch another working controller, so the two would merge. */
    private static boolean touchesForeign(ServerLevel level, ControllerBlockEntity core, long cell) {
        for (Direction side : SIDES) {
            BlockPos next = BlockPos.of(BlockPos.offset(cell, side));
            if (level.isLoaded(next) && core.foreign(level, level.getBlockEntity(next))) {
                return true;
            }
        }
        return false;
    }

    /** The squared distance from {@code at} to the nearest centre of {@code core} and {@code members}. */
    private static double nearest(long core, LongList members, Vec3 at) {
        double best = distance(core, at);
        for (int i = 0; i < members.size(); i++) {
            best = Math.min(best, distance(members.getLong(i), at));
        }
        return best;
    }

    private static double distance(long pos, Vec3 at) {
        double dx = BlockPos.getX(pos) + 0.5 - at.x;
        double dy = BlockPos.getY(pos) + 0.5 - at.y;
        double dz = BlockPos.getZ(pos) + 0.5 - at.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void writeLongs(FriendlyByteBuf buf, LongList values) {
        buf.writeVarInt(values.size());
        for (int i = 0; i < values.size(); i++) {
            buf.writeLong(values.getLong(i));
        }
    }

    private static LongList readLongs(FriendlyByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new DecoderException("Too many positions: " + count);
        }
        LongArrayList values = new LongArrayList(count);
        for (int i = 0; i < count; i++) {
            values.add(buf.readLong());
        }
        return values;
    }
}
