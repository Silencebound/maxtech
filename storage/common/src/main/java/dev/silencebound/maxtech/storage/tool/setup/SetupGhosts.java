package dev.silencebound.maxtech.storage.tool.setup;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprint;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The setup blueprints players pasted as ghosts (M7C item 21), kept on the server per player and saved with the world
 * (one instance on the overworld for every dimension). A ghost is a blueprint at an origin with a rotation; its owner's
 * client draws it from a {@link View} the server sends when the player's ghosts change, they log in or change
 * dimension. When the owner places a device block or a part where the ghost has the same block or part type, the device
 * takes the blueprint's settings, once the player may configure it ({@link NetworkAccess#canConfigure}); a block device
 * not yet in a network waits up to {@link #PENDING_TICKS} for it, so a network's security decides, not just the new
 * block's owner. A ghost whose every block and part is there goes away. Other players' placements never use someone's
 * ghost. Auto-build ({@link SetupBuild}) places blocks with the facings of {@code entry.state().rotate(rotation)}; any
 * directional setting added to a blueprint later must be turned the same way. Server thread.
 */
public final class SetupGhosts extends SavedData {
    /** Most ghosts per player; pasting another drops the oldest. */
    public static final int MAX_GHOSTS = 4;
    /** How long a placed block device may wait to join a network before its settings are applied anyway. */
    public static final int PENDING_TICKS = 40;
    private static final int MAX_CELLS = MAX_GHOSTS * SetupBlueprint.MAX_ENTRIES;

    /**
     * One pasted blueprint.
     *
     * @param dimension
     *            where it was pasted
     * @param origin
     *            the lowest corner of the turned box
     * @param rotation
     *            the turn around the vertical axis
     * @param created
     *            the game time it was pasted at, for pruning
     */
    public record Ghost(ResourceKey<Level> dimension, BlockPos origin, Rotation rotation, SetupBlueprint blueprint,
            long created) {
        static final Codec<Ghost> CODEC = RecordCodecBuilder
                .create(i -> i.group(Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Ghost::dimension),
                        BlockPos.CODEC.fieldOf("origin").forGetter(Ghost::origin),
                        Rotation.CODEC.fieldOf("rotation").forGetter(Ghost::rotation),
                        SetupBlueprint.CODEC.fieldOf("blueprint").forGetter(Ghost::blueprint),
                        Codec.LONG.fieldOf("created").forGetter(Ghost::created)).apply(i, Ghost::new));

        /** Whether {@code pos} in {@code level} is inside the ghost's box. */
        public boolean contains(ResourceKey<Level> level, BlockPos pos) {
            if (!dimension.equals(level)) {
                return false;
            }
            Vec3i size = blueprint.rotatedSize(rotation);
            int x = pos.getX() - origin.getX();
            int y = pos.getY() - origin.getY();
            int z = pos.getZ() - origin.getZ();
            return x >= 0 && y >= 0 && z >= 0 && x < size.getX() && y < size.getY() && z < size.getZ();
        }

        /** The entry that lands on {@code pos}, or empty. */
        public Optional<SetupBlueprint.Entry> entryAt(BlockPos pos) {
            return blueprint.at(blueprint.offsetOf(origin, rotation, pos));
        }

        /** Where {@code entry} lands. */
        public BlockPos posOf(SetupBlueprint.Entry entry) {
            return blueprint.place(origin, rotation, entry.offset());
        }
    }

    private record Saved(UUID player, List<Ghost> ghosts) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i
                .group(UUIDUtil.CODEC.fieldOf("player").forGetter(Saved::player),
                        Ghost.CODEC.listOf(0, MAX_GHOSTS).fieldOf("ghosts").forGetter(Saved::ghosts))
                .apply(i, Saved::new));
    }

    public static final Codec<SetupGhosts> CODEC = Saved.CODEC.listOf().xmap(SetupGhosts::new, SetupGhosts::saved);
    public static final SavedDataType<SetupGhosts> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("setup_ghosts"), SetupGhosts::new, CODEC);

    /** What happened to a device placed into a ghost. */
    public enum Placed {
        /** Not in any of the player's ghosts, or another block than the ghost's. */
        NONE,
        /** The ghost's block or part, with no settings to apply. */
        MATCHED,
        /** Settings applied. */
        APPLIED,
        /** Settings wait for the block to join a network. */
        PENDING,
        /** The player may not configure the device; nothing applied. */
        DENIED
    }

    private record Pending(Player player, ResourceKey<Level> dimension, BlockPos pos, @Nullable Direction face,
            Blueprint settings, long deadline) {
    }

    /** Placed devices waiting for a network; not saved, a restart drops them. */
    private static final List<Pending> PENDING = new ArrayList<>();

    private final Map<UUID, List<Ghost>> ghosts = new HashMap<>();

    public SetupGhosts() {
    }

    private SetupGhosts(List<Saved> saved) {
        for (Saved entry : saved) {
            if (!entry.ghosts().isEmpty()) {
                ghosts.put(entry.player(), new ArrayList<>(entry.ghosts()));
            }
        }
    }

    private List<Saved> saved() {
        List<Saved> out = new ArrayList<>(ghosts.size());
        for (Map.Entry<UUID, List<Ghost>> entry : ghosts.entrySet()) {
            out.add(new Saved(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return out;
    }

    public static SetupGhosts get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** {@code player}'s ghosts, oldest first; an unmodifiable view. Auto-build builds from these. */
    public List<Ghost> ghosts(UUID player) {
        List<Ghost> list = ghosts.get(player);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** Pastes {@code blueprint} as a ghost for {@code player}, dropping their oldest past {@link #MAX_GHOSTS}. */
    public Ghost paste(Player player, ServerLevel level, BlockPos origin, Rotation rotation, SetupBlueprint blueprint) {
        long now = level.getServer().overworld().getGameTime();
        Ghost ghost = new Ghost(level.dimension(), origin.immutable(), rotation, blueprint, now);
        List<Ghost> list = ghosts.computeIfAbsent(player.getUUID(), id -> new ArrayList<>());
        list.add(ghost);
        while (list.size() > MAX_GHOSTS) {
            list.removeFirst();
        }
        prune(now);
        setDirty();
        sync(player);
        return ghost;
    }

    /**
     * Drops ghosts older than {@code setup.ghostKeepTicks} (never finished, or their player is gone), then the oldest
     * past {@code setup.maxGhostsTotal} over all players. Returns how many went. Called on paste and on login.
     */
    public int prune(long now) {
        return prune(now, StorageServerConfig.SETUP_GHOST_KEEP_TICKS.getAsInt(),
                StorageServerConfig.SETUP_MAX_GHOSTS_TOTAL.getAsInt());
    }

    /** {@link #prune(long)} with the limits given; {@code keepTicks} 0 keeps ghosts of any age. */
    public int prune(long now, long keepTicks, int maxTotal) {
        int dropped = 0;
        int total = 0;
        Iterator<Map.Entry<UUID, List<Ghost>>> players = ghosts.entrySet().iterator();
        while (players.hasNext()) {
            List<Ghost> list = players.next().getValue();
            int before = list.size();
            if (keepTicks > 0) {
                list.removeIf(ghost -> now - ghost.created() > keepTicks);
            }
            dropped += before - list.size();
            if (list.isEmpty()) {
                players.remove();
            }
            total += list.size();
        }
        while (total > maxTotal) {
            Map.@Nullable Entry<UUID, List<Ghost>> oldestEntry = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<UUID, List<Ghost>> entry : ghosts.entrySet()) {
                if (entry.getValue().getFirst().created() < oldest) {
                    oldest = entry.getValue().getFirst().created();
                    oldestEntry = entry;
                }
            }
            if (oldestEntry == null) {
                break;
            }
            oldestEntry.getValue().removeFirst();
            if (oldestEntry.getValue().isEmpty()) {
                ghosts.remove(oldestEntry.getKey());
            }
            total--;
            dropped++;
        }
        if (dropped > 0) {
            setDirty();
        }
        return dropped;
    }

    /** {@code player}'s newest ghost whose box contains {@code pos} in {@code dimension}, or empty. */
    public Optional<Ghost> ghostAt(UUID player, ResourceKey<Level> dimension, BlockPos pos) {
        List<Ghost> list = ghosts.get(player);
        if (list != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).contains(dimension, pos)) {
                    return Optional.of(list.get(i));
                }
            }
        }
        return Optional.empty();
    }

    /** Removes {@code player}'s newest ghost containing {@code pos}; false when none does. */
    public boolean removeAt(Player player, ServerLevel level, BlockPos pos) {
        List<Ghost> list = ghosts.get(player.getUUID());
        if (list == null) {
            return false;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).contains(level.dimension(), pos)) {
                remove(player, list, i);
                return true;
            }
        }
        return false;
    }

    private void remove(Player player, List<Ghost> list, int index) {
        list.remove(index);
        if (list.isEmpty()) {
            ghosts.remove(player.getUUID());
        }
        setDirty();
        sync(player);
    }

    /**
     * {@code player} placed a block at {@code pos}; if it is the block one of their ghosts has there, the device takes
     * the ghost's settings (now or once it is in a network). A finished ghost goes away. Called after the placement
     * went through ({@code BlockEvent.EntityPlaceEvent}, last).
     */
    public Placed blockPlaced(Player player, ServerLevel level, BlockPos pos) {
        List<Ghost> list = ghosts.get(player.getUUID());
        if (list == null) {
            return Placed.NONE;
        }
        BlockState there = level.getBlockState(pos);
        for (int i = list.size() - 1; i >= 0; i--) {
            Ghost ghost = list.get(i);
            if (!ghost.contains(level.dimension(), pos)) {
                continue;
            }
            SetupBlueprint.Entry entry = ghost.entryAt(pos).orElse(null);
            if (entry == null || !there.is(entry.state().getBlock())) {
                continue;
            }
            Placed placed = entry.settings().isPresent()
                    ? apply(player, level, pos, null, entry.settings().get())
                    : Placed.MATCHED;
            finishIfDone(player, level, list, i);
            return placed;
        }
        return Placed.NONE;
    }

    /** {@code player} put a part on {@code face} of the cable at {@code pos}; as {@link #blockPlaced} for parts. */
    public Placed partPlaced(Player player, ServerLevel level, BlockPos pos, Direction face, CablePart part) {
        List<Ghost> list = ghosts.get(player.getUUID());
        if (list == null) {
            return Placed.NONE;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            Ghost ghost = list.get(i);
            if (!ghost.contains(level.dimension(), pos)) {
                continue;
            }
            SetupBlueprint.Entry entry = ghost.entryAt(pos).orElse(null);
            SetupBlueprint.Part copied = entry == null
                    ? null
                    : entry.part(SetupBlueprint.inverse(ghost.rotation()).rotate(face)).orElse(null);
            if (copied == null || !copied.type().equals(part.type().id())) {
                continue;
            }
            Placed placed = copied.settings().isPresent()
                    ? apply(player, level, pos, face, copied.settings().get())
                    : Placed.MATCHED;
            finishIfDone(player, level, list, i);
            return placed;
        }
        return Placed.NONE;
    }

    private void finishIfDone(Player player, ServerLevel level, List<Ghost> list, int index) {
        if (done(level, list.get(index))) {
            remove(player, list, index);
            message(player, Component.translatable(SetupContent.FINISHED_KEY));
        }
    }

    /** Whether every block and part of {@code ghost} is in place (unloaded cells count as missing). */
    public static boolean done(ServerLevel level, Ghost ghost) {
        for (SetupBlueprint.Entry entry : ghost.blueprint().entries()) {
            BlockPos pos = ghost.posOf(entry);
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(entry.state().getBlock())) {
                return false;
            }
            if (entry.parts().isEmpty()) {
                continue;
            }
            CableParts parts = StorageCableBlock.parts(level, pos);
            if (parts == null) {
                return false;
            }
            for (SetupBlueprint.Part copied : entry.parts()) {
                CablePart part = parts.part(ghost.rotation().rotate(copied.face()));
                if (part == null || !part.type().id().equals(copied.type())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Placed apply(Player player, ServerLevel level, BlockPos pos, @Nullable Direction face,
            Blueprint settings) {
        SettingsCopyable device = device(level, pos, face);
        if (device == null) {
            return Placed.MATCHED;
        }
        if (device instanceof NetworkDevice networked && networked.deviceLink().network() == null) {
            PENDING.add(new Pending(player, level.dimension(), pos.immutable(), face, settings,
                    level.getGameTime() + PENDING_TICKS));
            return Placed.PENDING;
        }
        return applyNow(player, device, settings);
    }

    private static Placed applyNow(Player player, SettingsCopyable device, Blueprint settings) {
        if (!NetworkAccess.canConfigure(player, device)) {
            message(player, Component.translatable(SetupContent.DENIED_KEY));
            return Placed.DENIED;
        }
        if (!Blueprints.paste(device, settings)) {
            return Placed.MATCHED;
        }
        message(player, Component.translatable(SetupContent.APPLIED_KEY));
        return Placed.APPLIED;
    }

    private static @Nullable SettingsCopyable device(ServerLevel level, BlockPos pos, @Nullable Direction face) {
        if (face == null) {
            return level.getBlockEntity(pos) instanceof SettingsCopyable device ? device : null;
        }
        CableParts parts = StorageCableBlock.parts(level, pos);
        CablePart part = parts == null ? null : parts.part(face);
        return part instanceof SettingsCopyable device ? device : null;
    }

    /**
     * Applies waiting settings to devices that joined a network or waited {@link #PENDING_TICKS}; each server tick,
     * returning at once while nothing waits.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            ServerLevel level = server.getLevel(pending.dimension());
            if (level == null || pending.player().isRemoved() || !level.isLoaded(pending.pos())) {
                it.remove();
                continue;
            }
            SettingsCopyable device = device(level, pending.pos(), pending.face());
            if (device == null) {
                it.remove();
                continue;
            }
            boolean joined = !(device instanceof NetworkDevice networked) || networked.deviceLink().network() != null;
            if (joined || level.getGameTime() >= pending.deadline()) {
                it.remove();
                applyNow(pending.player(), device, pending.settings());
            }
        }
    }

    /**
     * Whether the placed device at {@code pos} in {@code dimension} waits for a network; for tests. Asks about one
     * position because the waiting list is shared by every level and player, so its size depends on what else runs.
     */
    public static boolean isPending(ResourceKey<Level> dimension, BlockPos pos) {
        for (int i = 0; i < PENDING.size(); i++) {
            Pending pending = PENDING.get(i);
            if (pending.pos().equals(pos) && pending.dimension().equals(dimension)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets waiting devices, as when the server stops. */
    public static void clearPending() {
        PENDING.clear();
    }

    /** Sends {@code player} the ghosts in their current dimension. */
    public void sync(Player player) {
        // Mock and fake players have a connection without the mod's channels; sending to them throws.
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null
                && serverPlayer.connection.hasChannel(View.TYPE)) {
            Platform.get().network().sendToPlayer(serverPlayer, view(player.getUUID(), player.level().dimension()));
        }
    }

    /** The cells of {@code player}'s ghosts in {@code dimension}. */
    public View view(UUID player, ResourceKey<Level> dimension) {
        LongArrayList positions = new LongArrayList();
        IntArrayList states = new IntArrayList();
        for (Ghost ghost : ghosts(player)) {
            if (!ghost.dimension().equals(dimension)) {
                continue;
            }
            for (SetupBlueprint.Entry entry : ghost.blueprint().entries()) {
                positions.add(ghost.posOf(entry).asLong());
                states.add(Block.BLOCK_STATE_REGISTRY.getId(entry.state().rotate(ghost.rotation())));
            }
        }
        return new View(positions, states);
    }

    static void message(Player player, Component text) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.connection != null) {
            player.sendOverlayMessage(text);
        }
    }

    /**
     * The cells of a player's ghosts in their dimension: positions and the block state each should hold, by
     * {@link Block#BLOCK_STATE_REGISTRY} id. Empty clears the client's ghosts.
     */
    public record View(LongList positions, IntList states) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(MaxtechStorage.id("setup_ghosts"));
        public static final StreamCodec<FriendlyByteBuf, View> STREAM_CODEC = StreamCodec.of(View::write, View::read);
        public static final View NONE = new View(new LongArrayList(), new IntArrayList());

        @Override
        public Type<View> type() {
            return TYPE;
        }

        private static void write(FriendlyByteBuf buf, View view) {
            int size = Math.min(view.positions().size(), MAX_CELLS);
            buf.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                buf.writeLong(view.positions().getLong(i));
                buf.writeVarInt(view.states().getInt(i));
            }
        }

        private static View read(FriendlyByteBuf buf) {
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_CELLS) {
                throw new IllegalArgumentException("Too many ghost cells: " + size);
            }
            LongArrayList positions = new LongArrayList(size);
            IntArrayList states = new IntArrayList(size);
            for (int i = 0; i < size; i++) {
                positions.add(buf.readLong());
                states.add(buf.readVarInt());
            }
            return new View(positions, states);
        }
    }

    private static View received = View.NONE;
    private static int receivedCount;

    /** Registers the payload; during mod construction. */
    public static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, View.TYPE, View.STREAM_CODEC,
                (payload, player) -> {
                    if (player.level().isClientSide()) {
                        received = payload;
                        receivedCount++;
                    }
                });
    }

    /** Client side: the last ghosts the server sent. */
    public static View received() {
        return received;
    }

    /** Client side: how many views arrived, so the client glue rebuilds its ghosts only on a new one. */
    public static int receivedCount() {
        return receivedCount;
    }

    /** Client side: forgets the ghosts, as on leaving a world. */
    public static void forget() {
        received = View.NONE;
        receivedCount++;
    }
}
