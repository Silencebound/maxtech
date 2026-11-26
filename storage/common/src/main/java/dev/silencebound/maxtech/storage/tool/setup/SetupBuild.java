package dev.silencebound.maxtech.storage.tool.setup;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkActor;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalSource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
// PLATFORM-LEAK: the items leave the network in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Auto-build of a setup blueprint ghost (M7C item 21 part B): places the ghost's missing blocks, then its missing cable
 * parts, with items from the network of the wireless terminal the player carries (found as pick block from the network
 * finds it: research, coverage, charge, dimension bridge), and only with the network's take-out right. Every placement
 * goes through {@link ItemStack#useOn} as the player, so placement events, claim and protection mods apply, and before
 * an item leaves storage the position is checked as the cable layer checks it ({@code mayBuild}, {@code mayInteract},
 * replaceable, unobstructed). One item leaves the network per placement, in its own transaction; whatever the world did
 * not take goes back to the network, or to the player when the network takes it no more, also when the placement
 * throws. In creative the stack is not consumed, so the item goes back and the placement costs nothing. The player is
 * turned for the placement so the block gets the facing of {@code entry.state().rotate(rotation)}, the copied block
 * turned with the ghost. The placed devices take their settings through the ghost's placement hook
 * ({@link SetupGhosts#blockPlaced}, {@link SetupGhosts#partPlaced}). A conduit copied with a colour is dyed with one
 * dye of that colour from the network right after this job placed it, through the same right-click path as dyeing by
 * hand. Items the network lacks and positions that refuse stay as ghost cells and are reported when the job ends.
 *
 * <p>
 * One job per player; jobs run on the server tick, together at most {@code setup.buildPerTick} placements per tick, and
 * the tick returns at once while no job runs. The carried terminal is looked up once when the job starts and kept; it
 * is looked up again only once the kept one is no longer valid (moved, dropped, out of coverage), so a running job does
 * not walk the inventory each tick (PRD 8.1). A job ends when it passed every cell, its ghost is gone, the player died
 * or changed dimension, or the terminal no longer reaches the network. A player who logs out keeps their job: it waits
 * in {@link Store} (saved with the world, as running jobs are) and resumes from the same cell when they log in again,
 * also after a restart. No item is ever between storage and the world while the world saves, since each placement
 * takes, places and gives back within one call, so a resumed job neither repeats a placement (built cells are passed as
 * present) nor loses an item. Server thread.
 */
public final class SetupBuild {
    /** Most cells one tick looks at, over all jobs, so already built cells cost a bounded amount too. */
    private static final int LOOKS_PER_TICK = 64;
    /** Most missing kinds of item named in the end report. */
    private static final int MISSING_SHOWN = 5;
    private static final Direction[] LOOKS = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
    private static final float[] PITCHES = {0.0F, 90.0F, -90.0F};

    /** Why a build did not start, or that it did. */
    public enum Start {
        STARTED,
        /** No carried wireless terminal reaches its network. */
        NO_TERMINAL,
        /** The network's security does not let the player take items out. */
        DENIED,
        /** The ghost is in another dimension than the player. */
        OTHER_LEVEL
    }

    /** What one placement attempt did. */
    enum Step {
        PLACED, PRESENT, MISSING, BLOCKED
    }

    /** A running or ended build; its counters are read by the end report and tests. */
    public static final class Job {
        private final ServerPlayer player;
        private final SetupGhosts.Ghost ghost;
        private final Map<Item, Integer> missing = new LinkedHashMap<>();
        /** The terminal found when the job started or last lost it; null until looked up. */
        private @Nullable WirelessTerminalSource source;
        private int locates;
        private int entry;
        private int part;
        private boolean parts;
        private int placed;
        private int blocked;
        private int ticks;
        private boolean ended;

        Job(ServerPlayer player, SetupGhosts.Ghost ghost) {
            this.player = player;
            this.ghost = ghost;
        }

        /** Blocks and parts placed so far. */
        public int placed() {
            return placed;
        }

        /** Positions or faces that refused a placement. */
        public int blocked() {
            return blocked;
        }

        /** Items the network lacked, by item, counted per position. */
        public Map<Item, Integer> missing() {
            return missing;
        }

        /** Server ticks this job placed or looked in. */
        public int ticks() {
            return ticks;
        }

        /** How often the job looked through the player's inventory for a terminal, the start included. */
        public int locates() {
            return locates;
        }

        /** Whether the job is over. */
        public boolean ended() {
            return ended;
        }
    }

    /**
     * A job saved with the world: its player, the ghost it builds (by dimension, origin, rotation and paste time) and
     * where it stands.
     */
    record Saved(UUID player, ResourceKey<Level> dimension, BlockPos origin, Rotation rotation, long created, int entry,
            int part, boolean parts, int placed, int blocked, List<Missing> missing) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i
                .group(UUIDUtil.CODEC.fieldOf("player").forGetter(Saved::player),
                        Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Saved::dimension),
                        BlockPos.CODEC.fieldOf("origin").forGetter(Saved::origin),
                        Rotation.CODEC.fieldOf("rotation").forGetter(Saved::rotation),
                        Codec.LONG.fieldOf("created").forGetter(Saved::created),
                        Codec.intRange(0, SetupBlueprint.MAX_ENTRIES).fieldOf("entry").forGetter(Saved::entry),
                        Codec.intRange(0, Direction.values().length).fieldOf("part").forGetter(Saved::part),
                        Codec.BOOL.fieldOf("parts").forGetter(Saved::parts),
                        Codec.INT.fieldOf("placed").forGetter(Saved::placed),
                        Codec.INT.fieldOf("blocked").forGetter(Saved::blocked),
                        Missing.CODEC.listOf(0, SetupBlueprint.MAX_WEIGHT).fieldOf("missing").forGetter(Saved::missing))
                .apply(i, Saved::new));

        static Saved of(Job job) {
            List<Missing> missing = new ArrayList<>(job.missing.size());
            for (Map.Entry<Item, Integer> entry : job.missing.entrySet()) {
                missing.add(new Missing(entry.getKey(), entry.getValue()));
            }
            SetupGhosts.Ghost ghost = job.ghost;
            return new Saved(job.player.getUUID(), ghost.dimension(), ghost.origin(), ghost.rotation(), ghost.created(),
                    job.entry, job.part, job.parts, job.placed, job.blocked, missing);
        }

        boolean builds(SetupGhosts.Ghost ghost) {
            return ghost.created() == created && ghost.rotation() == rotation && ghost.origin().equals(origin)
                    && ghost.dimension().equals(dimension);
        }
    }

    /** An item the network lacked and how many cells wanted it. */
    record Missing(Item item, int count) {
        static final Codec<Missing> CODEC = RecordCodecBuilder
                .create(i -> i.group(BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Missing::item),
                        Codec.INT.fieldOf("count").forGetter(Missing::count)).apply(i, Missing::new));
    }

    /**
     * The auto-builds saved with the world (one instance on the overworld): the running jobs, written as they stand
     * when the world saves, and the jobs of players who logged out, which wait here until they log in again.
     */
    public static final class Store extends SavedData {
        public static final Codec<Store> CODEC = Saved.CODEC.listOf().xmap(Store::new, Store::saved);
        public static final SavedDataType<Store> TYPE = new SavedDataType<>(MaxtechStorage.id("setup_builds"),
                Store::new, CODEC);

        private final List<Saved> waiting = new ArrayList<>();

        public Store() {
        }

        private Store(List<Saved> saved) {
            waiting.addAll(saved);
        }

        private List<Saved> saved() {
            List<Saved> out = new ArrayList<>(waiting.size() + JOBS.size());
            out.addAll(waiting);
            for (Job job : JOBS) {
                if (!job.ended) {
                    out.add(Saved.of(job));
                }
            }
            return out;
        }

        /** How many jobs wait for their player; for tests. */
        public int waiting() {
            return waiting.size();
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();
    /** Jobs that ended this tick; reused so a running build allocates no list per tick. */
    private static final List<Job> DONE = new ArrayList<>();
    private static int next;
    /** The loaded store, kept so a running tick does not look it up again. */
    private static @Nullable Store store;

    private SetupBuild() {
    }

    /**
     * Starts building {@code ghost} for {@code player}, replacing a job they still run. Checks the terminal and the
     * take-out right now; the placements follow over the next ticks.
     */
    public static Start start(ServerPlayer player, SetupGhosts.Ghost ghost) {
        if (!ghost.dimension().equals(player.level().dimension())) {
            return Start.OTHER_LEVEL;
        }
        WirelessTerminalSource source = source(player);
        if (source == null) {
            return Start.NO_TERMINAL;
        }
        if (!source.canAccess(player) || !source.canTake(player)) {
            return Start.DENIED;
        }
        Job job = new Job(player, ghost);
        job.source = source;
        job.locates = 1;
        add(player.level().getServer(), job);
        return Start.STARTED;
    }

    /** Adds {@code job}, ending a job its player still runs or has waiting. */
    private static void add(MinecraftServer server, Job job) {
        UUID player = job.player.getUUID();
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job other = it.next();
            if (other.player.getUUID().equals(player)) {
                other.ended = true;
                it.remove();
            }
        }
        Store saved = store(server);
        saved.waiting.removeIf(waiting -> waiting.player().equals(player));
        JOBS.add(job);
        saved.setDirty();
    }

    /** The store of saved jobs, loaded with the world on first use. */
    public static Store store(MinecraftServer server) {
        Store loaded = store;
        if (loaded == null) {
            loaded = server.overworld().getDataStorage().computeIfAbsent(Store.TYPE);
            store = loaded;
        }
        return loaded;
    }

    /** Puts {@code loaded} in place of the world's store, as loading the world would; for tests. */
    public static void restore(MinecraftServer server, Store loaded) {
        server.overworld().getDataStorage().set(Store.TYPE, loaded);
        store = loaded;
    }

    /**
     * {@code player} logs out: their running job waits in the store, saved with the world, until they log in again.
     */
    public static void pause(ServerPlayer player) {
        Job job = job(player.getUUID());
        if (job == null) {
            return;
        }
        Store saved = store(player.level().getServer());
        saved.waiting.add(Saved.of(job));
        job.ended = true;
        JOBS.remove(job);
        saved.setDirty();
    }

    /**
     * {@code player} logged in: their waiting job, saved at log-out or with the world, goes on from the cell it stood
     * at, once the ghost it builds is still theirs. Returns whether a job resumed. The terminal is looked up on the
     * next tick, and the take-out right is checked there as on every tick.
     */
    public static boolean resume(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Store saved = store(server);
        Saved found = null;
        Iterator<Saved> it = saved.waiting.iterator();
        while (it.hasNext()) {
            Saved waiting = it.next();
            if (waiting.player().equals(player.getUUID())) {
                found = waiting;
                it.remove();
                saved.setDirty();
            }
        }
        if (found == null) {
            return false;
        }
        for (SetupGhosts.Ghost ghost : SetupGhosts.get(server).ghosts(player.getUUID())) {
            if (found.builds(ghost)) {
                Job job = new Job(player, ghost);
                job.entry = found.entry();
                job.part = found.part();
                job.parts = found.parts();
                job.placed = found.placed();
                job.blocked = found.blocked();
                for (Missing missing : found.missing()) {
                    job.missing.merge(missing.item(), missing.count(), Integer::sum);
                }
                add(server, job);
                return true;
            }
        }
        return false;
    }

    /** {@code player}'s running job, or null. */
    public static @Nullable Job job(UUID player) {
        for (Job job : JOBS) {
            if (job.player.getUUID().equals(player)) {
                return job;
            }
        }
        return null;
    }

    /** How many jobs run; for tests. */
    public static int running() {
        return JOBS.size();
    }

    /** Drops every job, as when the server stops. */
    public static void clear() {
        for (Job job : JOBS) {
            job.ended = true;
        }
        JOBS.clear();
        store = null;
    }

    /** Runs the jobs for one server tick, at most {@code setup.buildPerTick} placements over all of them. */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) {
            return;
        }
        int budget = StorageServerConfig.SETUP_BUILD_PER_TICK.getAsInt();
        int looks = LOOKS_PER_TICK;
        SetupGhosts ghosts = SetupGhosts.get(server);
        // Start with another job each tick, so one big build does not hold the others back.
        int count = JOBS.size();
        int first = next % count;
        next = first + 1;
        for (int i = 0; i < count && budget > 0 && looks > 0; i++) {
            Job job = JOBS.get((first + i) % count);
            job.ticks++;
            boolean live = live(job, ghosts);
            WirelessTerminalSource source = live ? source(job) : null;
            if (source == null || !source.canAccess(job.player) || !source.canTake(job.player)) {
                // A ghost the placements finished, or one the player removed, ends the job with its report.
                if (live) {
                    tell(job.player, Component.translatable(SetupContent.BUILD_STOPPED_KEY));
                }
                if (!job.player.isRemoved()) {
                    report(job);
                }
                DONE.add(job);
                continue;
            }
            while (budget > 0 && looks > 0) {
                looks--;
                Step step = step(job, source);
                if (step == null) {
                    report(job);
                    DONE.add(job);
                    break;
                }
                if (step == Step.PLACED) {
                    budget--;
                }
            }
        }
        for (Job job : DONE) {
            job.ended = true;
            JOBS.remove(job);
        }
        DONE.clear();
        // The jobs moved on (or ended): the world's next save writes where they stand.
        store(server).setDirty();
    }

    /** Whether the player is still here, in the ghost's dimension, and still has this very ghost. */
    @SuppressWarnings("ReferenceEquality") // the same pasted ghost, not an equal one pasted again
    private static boolean live(Job job, SetupGhosts ghosts) {
        if (job.player.isRemoved() || !job.player.level().dimension().equals(job.ghost.dimension())) {
            return false;
        }
        for (SetupGhosts.Ghost ghost : ghosts.ghosts(job.player.getUUID())) {
            if (ghost == job.ghost) {
                return true;
            }
        }
        return false;
    }

    /**
     * The job's terminal: the one it kept while that is still valid (same terminal in the same slot, in coverage, same
     * network, the Wireless research still unlocked), else the first carried one, looked up again.
     */
    private static @Nullable WirelessTerminalSource source(Job job) {
        WirelessTerminalSource kept = job.source;
        if (kept != null && kept.stillValid(job.player)
                && ResearchUnlocks.isUnlocked(job.player, ResearchGates.WIRELESS_TERMINAL)) {
            return kept;
        }
        job.locates++;
        job.source = source(job.player);
        return job.source;
    }

    private static @Nullable WirelessTerminalSource source(ServerPlayer player) {
        Optional<CarriedSlot> slot = CarriedItems.locate(player, WirelessTerminalItem.class);
        return slot.isEmpty() ? null : WirelessTerminalItem.source(player, slot.get());
    }

    /** The job's next cell: blocks first, then parts; null once every cell was passed. */
    private static @Nullable Step step(Job job, WirelessTerminalSource source) {
        List<SetupBlueprint.Entry> entries = job.ghost.blueprint().entries();
        if (!job.parts) {
            if (job.entry < entries.size()) {
                return count(job, block(job, source, entries.get(job.entry++)));
            }
            job.parts = true;
            job.entry = 0;
        }
        while (job.entry < entries.size()) {
            SetupBlueprint.Entry entry = entries.get(job.entry);
            if (job.part < entry.parts().size()) {
                return count(job, part(job, source, entry, entry.parts().get(job.part++)));
            }
            job.entry++;
            job.part = 0;
        }
        return null;
    }

    private static Step count(Job job, Step step) {
        if (step == Step.PLACED) {
            job.placed++;
        } else if (step == Step.BLOCKED) {
            job.blocked++;
        }
        return step;
    }

    private static Step block(Job job, WirelessTerminalSource source, SetupBlueprint.Entry entry) {
        ServerPlayer player = job.player;
        ServerLevel level = player.level();
        BlockPos pos = job.ghost.posOf(entry);
        BlockState target = entry.state().rotate(job.ghost.rotation());
        if (!level.isLoaded(pos)) {
            return Step.BLOCKED;
        }
        if (level.getBlockState(pos).is(target.getBlock())) {
            return Step.PRESENT;
        }
        Item item = target.getBlock().asItem();
        if (new ItemStack(item).isEmpty() || !player.mayBuild() || !level.mayInteract(player, pos)
                || !level.getBlockState(pos).canBeReplaced()
                || !level.isUnobstructed(target, pos, CollisionContext.placementContext(player))) {
            return Step.BLOCKED;
        }
        ItemStack one = take(player, source, item);
        if (one.isEmpty()) {
            job.missing.merge(item, 1, Integer::sum);
            return Step.MISSING;
        }
        Aim aim = aim(player, level, pos, target, one);
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        float headRot = player.getYHeadRot();
        boolean built;
        try {
            if (aim != null) {
                player.setYRot(aim.yaw());
                player.setYHeadRot(aim.yaw());
                player.setXRot(aim.pitch());
            }
            Direction face = aim == null ? Direction.UP : aim.face();
            one.useOn(new UseOnContext(level, player, InteractionHand.MAIN_HAND, one, hit(pos, face)));
            built = level.getBlockState(pos).is(target.getBlock());
        } finally {
            player.setYRot(yRot);
            player.setYHeadRot(headRot);
            player.setXRot(xRot);
            giveBack(player, source, one);
        }
        if (!built) {
            return Step.BLOCKED;
        }
        // Only a cable this job just placed is dyed, never one that stood before. A missing dye is reported and a
        // refused one counted; the cable stays either way.
        Step dyed = dye(job, source, entry, pos);
        if (dyed == Step.BLOCKED) {
            job.blocked++;
        }
        return Step.PLACED;
    }

    /**
     * Dyes the conduit this job just placed at {@code pos} in the entry's colour with one dye from the network, the way
     * a player dyes it by hand: the dye is put in the player's main hand for the moment and used on the cable through
     * {@code ServerPlayerGameMode#useItemOn}, so the right-click event fires and claim and protection mods can refuse
     * it. Returns {@link Step#PRESENT} when there is no colour to give (none copied, already that colour, not a
     * conduit), {@link Step#PLACED} when the cable took the colour, {@link Step#MISSING} when the network has no dye
     * and {@link Step#BLOCKED} when the use did not colour it. What is left of the dye (all of it when refused, in
     * creative too) goes back to the network, then to the player; the held stack is put back. A conduit copied
     * uncoloured is never washed.
     */
    private static Step dye(Job job, WirelessTerminalSource source, SetupBlueprint.Entry entry, BlockPos pos) {
        DyeColor colour = entry.colour().orElse(null);
        ServerPlayer player = job.player;
        ServerLevel level = player.level();
        if (colour == null || !(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)
                || conduit.conduit().colour() == colour) {
            return Step.PRESENT;
        }
        Item dye = dyeItem(colour);
        ItemStack one = take(player, source, dye);
        if (one.isEmpty()) {
            job.missing.merge(dye, 1, Integer::sum);
            return Step.MISSING;
        }
        Inventory inventory = player.getInventory();
        int slot = inventory.getSelectedSlot();
        ItemStack held = inventory.getItem(slot);
        boolean sneaking = player.isShiftKeyDown();
        try {
            inventory.setItem(slot, one);
            // A sneaking player's block use is skipped; the dye is used on the cable as an unsneaking click would.
            player.setShiftKeyDown(false);
            player.gameMode.useItemOn(player, level, one, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, true));
        } finally {
            player.setShiftKeyDown(sneaking);
            ItemStack left = inventory.getItem(slot);
            inventory.setItem(slot, held);
            giveBack(player, source, left);
        }
        return conduit.conduit().colour() == colour ? Step.PLACED : Step.BLOCKED;
    }

    /** Vanilla's dye of {@code colour}; dyeing a conduit by hand takes any item with that dye component. */
    private static Item dyeItem(DyeColor colour) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(colour.getSerializedName() + "_dye"));
    }

    private static Step part(Job job, WirelessTerminalSource source, SetupBlueprint.Entry entry,
            SetupBlueprint.Part copied) {
        ServerPlayer player = job.player;
        ServerLevel level = player.level();
        BlockPos pos = job.ghost.posOf(entry);
        Direction face = job.ghost.rotation().rotate(copied.face());
        PartType type = PartTypes.byId(copied.type());
        if (!level.isLoaded(pos) || type == null) {
            return Step.BLOCKED;
        }
        CableParts parts = StorageCableBlock.parts(level, pos);
        if (parts == null) {
            return Step.BLOCKED;
        }
        CablePart there = parts.part(face);
        if (there != null && there.type().id().equals(copied.type())) {
            return Step.PRESENT;
        }
        Item item = type.item();
        ItemStack probe = new ItemStack(item);
        if (there != null || parts.occupies(face) || !player.mayBuild() || !level.mayInteract(player, pos)
                || !player.mayUseItemAt(pos, face, probe)) {
            return Step.BLOCKED;
        }
        ItemStack one = take(player, source, item);
        if (one.isEmpty()) {
            job.missing.merge(item, 1, Integer::sum);
            return Step.MISSING;
        }
        boolean built;
        try {
            one.useOn(new UseOnContext(level, player, InteractionHand.MAIN_HAND, one, hit(pos, face)));
            CableParts after = StorageCableBlock.parts(level, pos);
            CablePart placed = after == null ? null : after.part(face);
            built = placed != null && placed.type().id().equals(copied.type());
        } finally {
            giveBack(player, source, one);
        }
        return built ? Step.PLACED : Step.BLOCKED;
    }

    /** A click on the middle of {@code face} of the cell at {@code pos}. */
    private static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
    }

    /** How the player looks and which face they click for a placement. */
    private record Aim(Direction face, float yaw, float pitch) {
    }

    /**
     * A clicked face and view for which the block's own placement rule gives {@code target}'s facings, or null when it
     * has none to match or no tried view gives them (then the player's own view decides). The block rule is asked
     * without placing anything; the player's view is restored before returning.
     */
    private static @Nullable Aim aim(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState target,
            ItemStack stack) {
        if (!hasFacing(target)) {
            return null;
        }
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        float headRot = player.getYHeadRot();
        try {
            for (Direction look : LOOKS) {
                for (float pitch : PITCHES) {
                    player.setYRot(look.toYRot());
                    player.setYHeadRot(look.toYRot());
                    player.setXRot(pitch);
                    for (Direction face : Direction.values()) {
                        BlockState state = target.getBlock().getStateForPlacement(
                                new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hit(pos, face)));
                        if (state != null && sameFacing(state, target) && state.canSurvive(level, pos)) {
                            return new Aim(face, look.toYRot(), pitch);
                        }
                    }
                }
            }
            return null;
        } finally {
            player.setYRot(yRot);
            player.setYHeadRot(headRot);
            player.setXRot(xRot);
        }
    }

    private static boolean hasFacing(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == Direction.class) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameFacing(BlockState state, BlockState target) {
        for (Property<?> property : target.getProperties()) {
            if (property.getValueClass() == Direction.class
                    && (!state.hasProperty(property) || !state.getValue(property).equals(target.getValue(property)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * One {@code item} out of the network in its own transaction; empty when there is none or the terminal can't pay.
     */
    private static ItemStack take(ServerPlayer player, WirelessTerminalSource source, Item item) {
        ItemKey key = ItemKey.of(item);
        long taken;
        UUID previous = NetworkActor.begin(player);
        try (Transaction transaction = Transaction.openRoot()) {
            taken = source.extract(key, 1, transaction);
            transaction.commit();
        } finally {
            NetworkActor.end(previous);
        }
        return taken > 0 ? key.toStack((int) taken) : ItemStack.EMPTY;
    }

    /** Returns what is left of {@code stack} to the network, the rest to the player. */
    private static void giveBack(ServerPlayer player, WirelessTerminalSource source, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemKey key = ItemKey.of(stack);
        long stored;
        UUID previous = NetworkActor.begin(player);
        try (Transaction transaction = Transaction.openRoot()) {
            stored = source.insert(key, stack.getCount(), transaction);
            transaction.commit();
        } finally {
            NetworkActor.end(previous);
        }
        stack.shrink((int) stored);
        if (!stack.isEmpty()) {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }

    private static void report(Job job) {
        MutableComponent text = Component.translatable(SetupContent.BUILT_KEY, job.placed);
        if (!job.missing.isEmpty()) {
            MutableComponent list = Component.empty();
            int shown = 0;
            for (Map.Entry<Item, Integer> missing : job.missing.entrySet()) {
                if (shown == MISSING_SHOWN) {
                    list.append(Component.translatable(SetupContent.BUILD_MORE_KEY, job.missing.size() - shown));
                    break;
                }
                if (shown > 0) {
                    list.append(", ");
                }
                list.append(Component.translatable(SetupContent.BUILD_ITEM_KEY, missing.getValue(),
                        new ItemStack(missing.getKey()).getHoverName()));
                shown++;
            }
            text.append(" ").append(Component.translatable(SetupContent.BUILD_MISSING_KEY, list));
        }
        if (job.blocked > 0) {
            text.append(" ").append(Component.translatable(SetupContent.BUILD_BLOCKED_KEY, job.blocked));
        }
        tell(job.player, text);
    }

    private static void tell(ServerPlayer player, Component text) {
        // Test players have no connection; sending to them throws.
        if (player.connection != null) {
            player.sendSystemMessage(text);
        }
    }
}
