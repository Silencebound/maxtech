package dev.silencebound.maxtech.storage.crafting.provider;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerCore;
import dev.silencebound.maxtech.storage.crafting.assembler.CraftingAssemblerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.job.CraftingRuns;
import dev.silencebound.maxtech.storage.crafting.job.PatternDispatch;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternFaces;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternPriorities;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternReturns;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.FacingAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.OperationMoves;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.redstone.RedstoneSignal;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.save.KeptList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the provider puts inputs into machines and pulls outputs through NeoForge transfer handlers in
// transactions (PRD 7.2: every transfer is a transaction against the real handlers).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above; FE is the transfer API's energy handler, read to tell a machine without power (M7C item 9).
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * What every pattern provider does, whatever its form (M7 C2, C12 to C15): it holds encoded patterns and runs them on
 * the one block it faces. It is the network's {@link PatternProvider} and, for the executor, a {@link PatternDispatch}.
 *
 * <p>
 * A push puts every input of the batch into its face of the machine (the pattern's face, filled from
 * {@link PatternFaces} for known blocks, else the face touching the provider; fluids into that face's tank) in one
 * transaction, all or nothing. Crafting patterns go to a {@link CraftingAssemblerBlockEntity} instead, which crafts
 * them on the spot. After a push the provider waits for the batch's outputs: a task on the network's scheduler pulls
 * the expected outputs from the machine's output face into the network, one operation per kind and run, every
 * {@code crafting.providerPullTicks} or sooner when the machine reports a change, and reports them to
 * {@link CraftingDeliveries}. Pulls are transactions too (network insert, then machine extract of exactly that much),
 * so a machine emptied behind the network's back yields nothing. A batch whose outputs never show up is given up after
 * twice its estimated time without progress, and the task sleeps again without a timer.
 *
 * <p>
 * Blocking mode (on by default; processing patterns only) holds new batches while one is in flight or the machine still
 * holds any of the pattern's inputs. The first nine slots always work; {@code crafting.providerExtraSlots} more open
 * once the owner's team has {@link #EXTRA_SLOTS_GATE}.
 *
 * <p>
 * The lock mode (B09 item 26, AE2's) holds batches on top of blocking: while the provider gets a redstone signal, while
 * it gets none, after each batch until a redstone pulse, or after each batch until that batch's main output entered the
 * network (counted from {@link CraftingDeliveries}, so a machine that pushes into an interface unlocks it too). A
 * locked provider accepts nothing, so dispatch picks another provider or the job waits and names the lock as its stuck
 * reason; an unlock, or any provider on the network finishing its batches, wakes the jobs held that way. The signal is
 * read on neighbour updates only, never polled.
 *
 * <p>
 * The crafter (B09 item 29) is a provider with its own {@link AssemblerCore} in place of a machine: it takes crafting,
 * stonecutting and smithing patterns only, in nine slots without research, crafts every batch itself and pulls the
 * results from its own buffer; it faces no block, so no land claim check applies. Never ticks; server thread.
 */
public final class ProviderLogic implements PatternProvider, PatternDispatch, PatternPriorities {
    /** The research unlock that opens the extra slots (area D's Crafting branch). */
    public static final Identifier EXTRA_SLOTS_GATE = ResearchGates.PROVIDER_SLOTS;
    public static final int BASE_SLOTS = 9;
    /** Slots the container has: nine, plus the most {@code crafting.providerExtraSlots} allows. */
    public static final int MAX_SLOTS = 36;
    /** Ticks per craft assumed for a pattern whose recipe gives no duration (a vanilla furnace's). */
    static final int DEFAULT_TICKS = 200;
    /** Ticks byproducts are still pulled after the last batch's main output came. */
    static final int LINGER_TICKS = 40;
    /**
     * The longest wait between retries for results left in a crafter's or assembler's buffer that the network had no
     * room for (B09 item 29): the wait doubles from {@code crafting.providerPullTicks} on each run that moves nothing.
     * A cadence, not a balance number.
     */
    public static final int MAX_LEFTOVER_TICKS = 100;
    /** The least time without progress before a batch is given up. */
    static final int MIN_GIVE_UP_TICKS = 200;
    private static final String SLOTS = "patterns";
    private static final String BLOCKING = "blocking";
    private static final String BATCHES = "batches";
    private static final String EXPECTED = "expected";
    private static final String LAST_DISPATCH = "last_dispatch";
    private static final String RETURNS = "returns";
    private static final String CHANCE_EXPECTED = "chance_expected";
    private static final String LOCK = "lock_mode";
    private static final String LOCK_POWERED = "lock_powered";
    private static final String LOCK_PULSE = "lock_pulse";
    private static final String LOCK_RESULT = "lock_result";

    /** One pushed batch waiting for its main output. */
    record Batch(CraftingPattern pattern, int crafts, long remaining) {
        static final Codec<Batch> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(PatternCodecs.PATTERN.fieldOf("pattern").forGetter(Batch::pattern),
                                Codec.INT.fieldOf("crafts").forGetter(Batch::crafts),
                                Codec.LONG.fieldOf("remaining").forGetter(Batch::remaining))
                        .apply(instance, Batch::new));
    }

    /** One output key still expected, for saving. */
    record Expected(StackKey key, long amount) {
        static final Codec<Expected> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(StackKey.CODEC.fieldOf("key").forGetter(Expected::key),
                                Codec.LONG.fieldOf("amount").forGetter(Expected::amount))
                        .apply(instance, Expected::new));
    }

    /**
     * A returned input of a processing batch (M7C item 3) to take back once the batch's outputs are in: from the
     * machine's output face, else from the face it went into.
     */
    record Returned(StackKey key, long amount, Direction face) {
        static final Codec<Returned> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(StackKey.CODEC.fieldOf("key").forGetter(Returned::key),
                                Codec.LONG.fieldOf("amount").forGetter(Returned::amount),
                                Direction.CODEC.fieldOf("face").forGetter(Returned::face))
                        .apply(instance, Returned::new));
    }

    private static final Codec<KeptList<Batch>> BATCHES_CODEC = KeptList.codec(Batch.CODEC);
    private static final Codec<KeptList<Expected>> EXPECTED_CODEC = KeptList.codec(Expected.CODEC);
    private static final Codec<KeptList<Returned>> RETURNS_CODEC = KeptList.codec(Returned.CODEC);

    private final ProviderDevice device;
    private final DeviceLink link;
    private final Runnable saved;
    private final Supplier<BlockPos> pos;
    private final Supplier<Direction> facing;
    private final Slots slots = new Slots();
    private final Task task = new Task();
    private final FacingAccess access = new FacingAccess();
    private final ObjectArrayList<Batch> batches = new ObjectArrayList<>();
    /** Saved batches, expected outputs and returns that no longer load (a removed mod), written back unchanged. */
    private List<Tag> keptBatches = List.of();
    private List<Tag> keptExpected = List.of();
    private List<Tag> keptChanceExpected = List.of();
    private List<Tag> keptReturns = List.of();
    /** Guaranteed outputs still due from the batches pushed, by key; never more than they declared. */
    private final Object2LongOpenHashMap<StackKey> expected = new Object2LongOpenHashMap<>();
    /** Chance outputs that may still come while a batch is in flight; dropped once none is. */
    private final Object2LongOpenHashMap<StackKey> chanceExpected = new Object2LongOpenHashMap<>();
    /**
     * Game time until which guaranteed byproducts are still pulled after the last batch's main output came (M7C item
     * 3), -1 while a batch is in flight or before the first pull after it.
     */
    private long lingerUntil = -1;
    /** Returned inputs to take back once no batch is in flight. */
    private final ObjectArrayList<Returned> returns = new ObjectArrayList<>();
    private final Object2IntOpenHashMap<CraftingPattern> ticks = new Object2IntOpenHashMap<>();
    @SuppressWarnings("unchecked")
    private final @Nullable CachedBlockLookup<ResourceHandler<ItemResource>>[] itemFaces = new CachedBlockLookup[6];
    @SuppressWarnings("unchecked")
    private final @Nullable CachedBlockLookup<ResourceHandler<FluidResource>>[] fluidFaces = new CachedBlockLookup[6];
    private List<CraftingPattern> patterns = List.of();
    /** The priority of each of {@link #patterns}, from its pattern item (M7C item 2). */
    private IntList priorities = IntList.of();
    /** The slot each of {@link #patterns} sits in. */
    private IntList patternSlots = IntList.of();
    private boolean blocking = true;
    private LockMode lockMode = LockMode.NONE;
    /** The redstone signal at the last reading; read only while the lock mode uses it. */
    private boolean lockPowered;
    /** In a mode without redstone, updates are not read: the reading may be old until the mode changes. */
    private boolean lockStale = true;
    /** Pulse mode: a batch went and no rising edge came since. */
    private boolean awaitingPulse;
    /** Result mode: the main output the last batch owes before the next may go, or null while unlocked. */
    private @Nullable StackKey resultKey;
    private long resultOwed;
    /** Set by an output pass when the network could operate but took nothing. */
    private boolean refused;
    /** What the last item output operation moved, credited once it committed; reused by every pass. */
    private final OperationMoves outputMoves = new OperationMoves();
    private int usable = BASE_SLOTS;
    private int inFlight;
    private long lastProgress;
    private long lastDispatch;
    /** The current wait between retries for buffered leftovers; 0 after a move or a push. */
    private int leftoverDelay;
    private @Nullable ServerLevel level;
    private @Nullable CraftingAssemblerBlockEntity assembler;
    private boolean assemblerKnown;
    /** The crafter's own crafting part, or null for a provider that works on the block it faces. */
    private final @Nullable AssemblerCore own;
    /** Why the last batch could not go in (M7C item 9); cleared by the next batch that does. */
    private @Nullable StuckReason dispatchStuck;
    /** Why the last output pass moved nothing while outputs were due; cleared by a pass that moves something. */
    private @Nullable StuckReason outputStuck;
    private @Nullable CachedBlockLookup<EnergyHandler> machineEnergy;

    /**
     * @param pos
     *            the provider's block position (a part's cable)
     * @param facing
     *            the side the target machine is on
     * @param saved
     *            marks the holder for saving
     */
    public ProviderLogic(ProviderDevice device, Supplier<BlockPos> pos, Supplier<Direction> facing, Runnable saved) {
        this(device, pos, facing, saved, null);
    }

    /**
     * A provider that crafts itself with {@code own} (the crafter) when it is not null; see the class comment.
     *
     * @param facing
     *            for the crafter, the side its window faces
     */
    public ProviderLogic(ProviderDevice device, Supplier<BlockPos> pos, Supplier<Direction> facing, Runnable saved,
            @Nullable AssemblerCore own) {
        this.own = own;
        this.device = device;
        this.link = device.deviceLink();
        this.pos = pos;
        this.facing = facing;
        this.saved = saved;
        ticks.defaultReturnValue(-1);
    }

    /** Whether this is a crafter's logic: crafting patterns only, crafted on the spot. */
    public boolean crafter() {
        return own != null;
    }

    /** Whether {@code stack} may go into a pattern slot: a pattern, and for a crafter one it can craft itself. */
    public static boolean accepts(ItemStack stack, boolean crafter) {
        CraftingPattern pattern = PatternItem.pattern(stack);
        return pattern != null && (!crafter || pattern.kind() == CraftingPattern.Kind.CRAFTING);
    }

    /** The pattern slots; a change re-reads the patterns and tells the network. */
    public SimpleContainer slots() {
        return slots;
    }

    /** Slots that work now: nine, plus the extra slots once researched. */
    public int usableSlots() {
        return usable;
    }

    public boolean blocking() {
        return blocking;
    }

    /** Switches blocking mode; saved with the provider and kept in its item. */
    public void setBlocking(boolean on) {
        if (blocking != on) {
            blocking = on;
            saved.run();
        }
    }

    public LockMode lockMode() {
        return lockMode;
    }

    /**
     * Sets the lock mode; a lock the old mode held is dropped, the signal is read again for a redstone mode (without
     * counting an edge), and jobs waiting for the provider are woken. Saved with the provider and kept in its item.
     */
    public void setLockMode(LockMode mode) {
        if (lockMode == mode) {
            return;
        }
        boolean wasLocked = locked() != null;
        lockMode = mode;
        awaitingPulse = false;
        resultKey = null;
        resultOwed = 0;
        if (mode.redstone() && lockStale) {
            readSignal();
        }
        saved.run();
        if (wasLocked && locked() == null) {
            unlocked();
        }
    }

    /** The redstone signal as last read by the lock. */
    public boolean lockPowered() {
        return lockPowered;
    }

    /**
     * Why the lock mode holds batches now, or null while it lets them go. Allocation-free; called by every
     * {@link #canAccept}.
     */
    public @Nullable StuckReason locked() {
        return switch (lockMode) {
            case NONE -> null;
            case WITH_SIGNAL -> lockPowered ? StuckReason.LOCKED_SIGNAL : null;
            case WITHOUT_SIGNAL -> lockPowered ? null : StuckReason.LOCKED_NO_SIGNAL;
            case UNTIL_PULSE -> awaitingPulse ? StuckReason.LOCKED_PULSE : null;
            case UNTIL_RESULT -> resultKey != null ? StuckReason.LOCKED_RESULT : null;
        };
    }

    /**
     * A neighbour changed: reads the signal for a redstone lock mode; a rising edge ends a pulse lock. Other modes only
     * note that the reading is old, so most providers pay nothing per update. A neighbour in an unloaded chunk keeps
     * the last reading.
     */
    public void redstoneChanged() {
        if (!lockMode.redstone()) {
            lockStale = true;
            return;
        }
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        int read = RedstoneSignal.read(current, pos.get());
        if (read == RedstoneSignal.UNKNOWN) {
            return;
        }
        boolean now = read == RedstoneSignal.POWERED;
        if (now == lockPowered) {
            return;
        }
        boolean wasLocked = locked() != null;
        lockPowered = now;
        if (now) {
            // A rising edge; pulses are not banked, as in AE2.
            awaitingPulse = false;
        }
        saved.run();
        if (wasLocked && locked() == null) {
            unlocked();
        }
    }

    /** Reads the signal without counting an edge (start, mode change). */
    private void readSignal() {
        ServerLevel current = level;
        if (current == null) {
            return;
        }
        lockStale = false;
        int read = RedstoneSignal.read(current, pos.get());
        if (read != RedstoneSignal.UNKNOWN) {
            lockPowered = read == RedstoneSignal.POWERED;
        }
    }

    /**
     * {@code amount} of {@code key} entered the network (a {@link CraftingDeliveries} report): counts against the main
     * output a result lock waits for; every provider that waits for the key counts the whole report, as in AE2.
     */
    void resultDelivered(StackKey key, long amount) {
        if (amount <= 0 || !awaitsResult(key)) {
            return;
        }
        resultOwed -= amount;
        if (resultOwed <= 0) {
            resultKey = null;
            resultOwed = 0;
            unlocked();
        }
        saved.run();
    }

    /** Whether a result lock waits for {@code key}. */
    boolean awaitsResult(StackKey key) {
        StackKey waiting = resultKey;
        return waiting != null && waiting.equals(key);
    }

    /** The lock let go: jobs on the network held by a lock run again. */
    private void unlocked() {
        freed();
    }

    /**
     * The provider may take work again (its lock let go, its last batch came back, its buffer emptied): jobs on the
     * network held by a lock run again, since this provider may hold their step's pattern unlocked (B09 item 26).
     */
    private void freed() {
        NetworkState network = link.network();
        if (network != null) {
            CraftingRuns.wakeHeld(network);
        }
    }

    /** Crafts pushed and not yet come back. */
    public int inFlight() {
        return inFlight;
    }

    /** Game time of the last push, for round robin; 0 before the first. */
    public long lastDispatch() {
        return lastDispatch;
    }

    /** The scheduled output task, for tests and diagnostics. */
    public StorageTask task() {
        return task;
    }

    /** Starts work in {@code level}: reads the patterns and schedules the output task. */
    public void start(ServerLevel level) {
        this.level = level;
        access.forget();
        if (lockMode.redstone()) {
            readSignal();
        }
        refresh();
        link.schedule(task);
    }

    /** The machine changed or was replaced: forget the cached assembler and look at its output again. */
    public void targetChanged() {
        assemblerKnown = false;
        access.targetChanged();
        if (!batches.isEmpty()) {
            link.wake(task);
        }
    }

    /** Re-reads which slots work (the research may have completed) and the patterns in them. */
    public void refresh() {
        ServerLevel current = level;
        int extra = (int) StorageServerConfig
                .loadedOr(() -> StorageServerConfig.CRAFTING_PROVIDER_EXTRA_SLOTS.getAsInt(), 0);
        boolean unlocked = own == null && extra > 0 && current != null
                && ResearchUnlocks.isUnlocked(current, device.ownerRecord(), EXTRA_SLOTS_GATE);
        usable = Math.min(MAX_SLOTS, BASE_SLOTS + (unlocked ? extra : 0));
        List<CraftingPattern> next = new ArrayList<>(usable);
        IntArrayList nextPriorities = new IntArrayList(usable);
        IntArrayList nextSlots = new IntArrayList(usable);
        for (int i = 0; i < usable; i++) {
            ItemStack stack = slots.getItem(i);
            CraftingPattern pattern = PatternItem.pattern(stack);
            if (pattern != null && (own == null || pattern.kind() == CraftingPattern.Kind.CRAFTING)) {
                next.add(pattern);
                nextPriorities.add(PatternItem.priority(stack));
                nextSlots.add(i);
            }
        }
        if (!next.equals(patterns) || !nextPriorities.equals(priorities)) {
            if (!next.equals(patterns)) {
                ticks.clear();
            }
            patterns = List.copyOf(next);
            priorities = IntLists.unmodifiable(nextPriorities);
            patternSlots = IntLists.unmodifiable(nextSlots);
            link.patternsChanged();
        } else if (!nextSlots.equals(patternSlots)) {
            // The same patterns moved to other slots.
            patternSlots = IntLists.unmodifiable(nextSlots);
        }
    }

    @Override
    public PatternProvider provider() {
        return this;
    }

    @Override
    public long nodePos() {
        return device.nodePos();
    }

    @Override
    public List<CraftingPattern> patterns() {
        return patterns;
    }

    @Override
    public int priority(int index) {
        return priorities.getInt(index);
    }

    /** The slot {@code patterns().get(index)} sits in. */
    public int slotOf(int index) {
        return patternSlots.getInt(index);
    }

    /**
     * Sets the priority of the pattern in {@code slot} (M7C item 2) on its item, clamped to the pattern item's range,
     * and tells the network. False for a slot without a pattern. Server thread.
     */
    public boolean setPriority(int slot, int priority) {
        if (slot < 0 || slot >= MAX_SLOTS) {
            return false;
        }
        ItemStack stack = slots.getItem(slot);
        if (PatternItem.pattern(stack) == null) {
            return false;
        }
        ItemStack changed = stack.copy();
        PatternItem.setPriority(changed, priority);
        slots.setItem(slot, changed);
        return true;
    }

    /** The provider this logic belongs to. */
    public ProviderDevice device() {
        return device;
    }

    /** The side the target machine is on. */
    public Direction facing() {
        return facing.get();
    }

    /** The provider's block position (a part's cable). */
    public BlockPos pos() {
        return pos.get();
    }

    private BlockPos target() {
        return pos.get().relative(facing.get());
    }

    /** The face of the machine that touches the provider. */
    private Direction providerFace() {
        return facing.get().getOpposite();
    }

    /**
     * Whether the provider can work now: on the server thread, still there, and no land claim keeps its owner from the
     * target ({@link FacingAccess}). A refused provider accepts no batch and pulls nothing until a later check allows
     * it.
     */
    private boolean ready() {
        ServerLevel current = level;
        return current != null && current.getServer().isSameThread() && !device.isRemoved()
                && (own != null || access.allowed(current, pos.get(), facing.get(), device.ownerRecord()));
    }

    /** Whether a land claim keeps the provider from the block it faces, at the last check. */
    public boolean claimBlocked() {
        return access.blocked();
    }

    /** The provider's claim check; tests read it. */
    public FacingAccess access() {
        return access;
    }

    /** The crafter's own core, else that of the assembler the provider faces, or null; cached until it changes. */
    private @Nullable AssemblerCore assembler() {
        if (own != null) {
            return own;
        }
        ServerLevel current = level;
        if (current == null) {
            return null;
        }
        CraftingAssemblerBlockEntity cached = assembler;
        if (!assemblerKnown || (cached != null && cached.isRemoved())) {
            BlockPos target = target();
            cached = current.isLoaded(target)
                    && current.getBlockEntity(target) instanceof CraftingAssemblerBlockEntity found ? found : null;
            assembler = cached;
            assemblerKnown = true;
        }
        return cached == null ? null : cached.core();
    }

    private PatternFaces.@Nullable Rule rule() {
        ServerLevel current = level;
        if (own != null || current == null || !current.isLoaded(target())) {
            return null;
        }
        Block block = current.getBlockState(target()).getBlock();
        return PatternFaces.forBlock(block);
    }

    private @Nullable ResourceHandler<ItemResource> items(Direction face) {
        ServerLevel current = level;
        if (current == null) {
            return null;
        }
        CachedBlockLookup<ResourceHandler<ItemResource>> cache = itemFaces[face.get3DDataValue()];
        if (cache == null) {
            cache = Platform.get().transfer().items().cache(current, target(), face, () -> !device.isRemoved(),
                    this::targetChanged);
            itemFaces[face.get3DDataValue()] = cache;
        }
        return cache.find();
    }

    private @Nullable ResourceHandler<FluidResource> fluids(Direction face) {
        ServerLevel current = level;
        if (current == null) {
            return null;
        }
        CachedBlockLookup<ResourceHandler<FluidResource>> cache = fluidFaces[face.get3DDataValue()];
        if (cache == null) {
            cache = Platform.get().transfer().fluids().cache(current, target(), face, () -> !device.isRemoved(),
                    this::targetChanged);
            fluidFaces[face.get3DDataValue()] = cache;
        }
        return cache.find();
    }

    /**
     * Whether a batch of {@code pattern} can go now: the provider has it and faces something that takes it (an
     * assembler with room for crafting patterns, any inventory or tank for processing ones), and blocking mode does not
     * hold it.
     */
    @Override
    public boolean canAccept(CraftingPattern pattern) {
        // Turned off by logic (M7L): no dispatch reaches the provider until it is on again.
        if (link.stopped() || !ready() || !patterns.contains(pattern)) {
            return false;
        }
        if (locked() != null) {
            // The lock names itself as the stuck reason (stuck()); dispatch picks another provider meanwhile.
            return false;
        }
        if (pattern.kind() == CraftingPattern.Kind.CRAFTING) {
            AssemblerCore target = assembler();
            if (target != null && !target.hasRoom()) {
                dispatchStuck = StuckReason.MACHINE_FULL;
            }
            return target != null && target.hasRoom();
        }
        if (own != null) {
            // A crafter has no machine for processing patterns; its slots never hold one.
            return false;
        }
        CraftingPattern filled = PatternFaces.fill(pattern, rule());
        boolean any = false;
        for (PatternInput input : filled.inputs()) {
            Direction face = input.face().orElse(providerFace());
            ResourceHandler<ItemResource> items = items(face);
            ResourceHandler<FluidResource> fluids = fluids(face);
            any |= items != null || fluids != null;
            if (blocking && (holdsInput(items, filled) || holdsInput(fluids, filled))) {
                // With nothing in flight, inputs left in the machine mean it is not working them off: it is full.
                if (inFlight == 0) {
                    dispatchStuck = StuckReason.MACHINE_FULL;
                }
                return false;
            }
        }
        return any && !(blocking && inFlight > 0);
    }

    private static <T extends Resource> boolean holdsInput(@Nullable ResourceHandler<T> handler,
            CraftingPattern pattern) {
        if (handler == null) {
            return false;
        }
        for (int i = 0; i < handler.size(); i++) {
            T resource = handler.getResource(i);
            if (resource.isEmpty() || handler.getAmountAsLong(i) <= 0) {
                continue;
            }
            StackKey key = StackKeys.of(resource);
            for (PatternInput input : pattern.inputs()) {
                if (input.ingredient().matches(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Puts {@code crafts} crafts' inputs into the machine (or the assembler), all or nothing. Must be called outside
     * any open transaction: it commits its own.
     */
    @Override
    public boolean push(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs) {
        if (crafts < 1 || !canAccept(pattern)) {
            return false;
        }
        ServerLevel current = level;
        if (current == null) {
            return false;
        }
        boolean pushed;
        if (pattern.kind() == CraftingPattern.Kind.CRAFTING) {
            AssemblerCore target = assembler();
            NetworkState state = link.network();
            // A batch the network cannot pay for is refused whole; the dispatcher halves it until one fits.
            pushed = target != null && state != null && target.craft(current, pattern, crafts, inputs, state, link);
            if (pushed) {
                pushed(pattern, crafts, current.getGameTime());
            } else if (target != null && target.recipeChanged()) {
                dispatchStuck = StuckReason.RECIPE_CHANGED;
            }
        } else {
            CraftingPattern filled = PatternFaces.fill(pattern, rule());
            StackKey[] keys = insertAll(filled, crafts, inputs);
            pushed = keys != null;
            if (keys != null) {
                pushed(pattern, crafts, current.getGameTime());
                expectReturns(filled, crafts, keys);
            } else {
                // Every input had somewhere to go, yet the machine refused some of the batch.
                dispatchStuck = StuckReason.MACHINE_FULL;
            }
        }
        if (pushed) {
            dispatchStuck = null;
        }
        return pushed;
    }

    /** Puts the batch's inputs into their faces in one transaction; the key used for each input, or null for none. */
    private StackKey @Nullable [] insertAll(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs) {
        StackKey[] keys = InputAssignment.assign(pattern, crafts, inputs);
        if (keys == null) {
            return null;
        }
        try (Transaction root = Transaction.openRoot()) {
            for (int i = 0; i < keys.length; i++) {
                PatternInput input = pattern.inputs().get(i);
                long need = InputAssignment.need(pattern, i, crafts);
                if (need > Integer.MAX_VALUE) {
                    return null;
                }
                Direction face = input.face().orElse(providerFace());
                int put = switch (keys[i]) {
                    case ItemKey item -> {
                        ResourceHandler<ItemResource> handler = items(face);
                        yield handler == null ? 0 : handler.insert(StackKeys.toResource(item), (int) need, root);
                    }
                    case FluidKey fluid -> {
                        ResourceHandler<FluidResource> handler = fluids(face);
                        yield handler == null ? 0 : handler.insert(StackKeys.toResource(fluid), (int) need, root);
                    }
                };
                if (put != need) {
                    return null;
                }
            }
            root.commit();
            return keys;
        }
    }

    /**
     * Remembers the batch's returned inputs (M7C item 3) to take back once no batch is in flight. A catalyst the
     * pattern lists as an output too is taken back the same way and no longer expected as an output, so the provider
     * never pulls it out of the machine while the craft still needs it.
     */
    private void expectReturns(CraftingPattern pattern, int crafts, StackKey[] keys) {
        for (int i = 0; i < keys.length; i++) {
            if (!PatternReturns.returned(pattern, i)) {
                continue;
            }
            long amount = InputAssignment.need(pattern, i, crafts);
            int catalyst = PatternReturns.catalystOutput(pattern, i);
            if (catalyst >= 0) {
                StackKey output = pattern.outputs().get(catalyst).key();
                long left = expected.getLong(output) - amount;
                if (left > 0) {
                    expected.put(output, left);
                } else {
                    expected.removeLong(output);
                }
            }
            returns.add(new Returned(keys[i], amount, pattern.inputs().get(i).face().orElse(providerFace())));
        }
        if (!returns.isEmpty()) {
            saved.run();
        }
    }

    private void pushed(CraftingPattern pattern, int crafts, long now) {
        PatternOutput main = pattern.mainOutput();
        batches.add(new Batch(pattern, crafts, main.guaranteed() ? main.amount() * crafts : 0));
        for (PatternOutput output : pattern.outputs()) {
            // Chance outputs are pulled only while a batch is in flight; they never linger after it.
            (output.guaranteed() ? expected : chanceExpected).addTo(output.key(), output.amount() * crafts);
        }
        lingerUntil = -1;
        leftoverDelay = 0;
        if (lockMode == LockMode.UNTIL_PULSE) {
            awaitingPulse = true;
        } else if (lockMode == LockMode.UNTIL_RESULT) {
            // A main output that is only a chance unlocks with the first one that comes.
            resultKey = main.key();
            resultOwed = main.guaranteed() ? main.amount() * crafts : 1;
        }
        inFlight += crafts;
        lastProgress = now;
        lastDispatch = now;
        // A batch whose main output is only a chance cannot be waited for; it counts until its time runs out.
        saved.run();
        link.wake(task);
    }

    /** Ticks one craft of {@code pattern} takes by its recipe, or {@link #DEFAULT_TICKS} when unknown. */
    public int ticksPerCraft(CraftingPattern pattern) {
        int known = ticks.getInt(pattern);
        if (known >= 0) {
            return known;
        }
        int found = pattern.kind() == CraftingPattern.Kind.CRAFTING ? 1 : DEFAULT_TICKS;
        ServerLevel current = level;
        if (current != null && pattern.recipe().isPresent()) {
            Optional<RecipeHolder<?>> holder = current.recipeAccess()
                    .byKey(ResourceKey.create(Registries.RECIPE, pattern.recipe().get()));
            if (holder.isPresent()) {
                Recipe<?> recipe = holder.get().value();
                found = switch (recipe) {
                    case AbstractCookingRecipe cooking -> cooking.cookingTime();
                    case StorageMachineRecipe machine -> machine.duration();
                    case CraftingRecipe crafting -> 1;
                    default -> found;
                };
            }
        }
        found = Math.max(1, found);
        ticks.put(pattern, found);
        return found;
    }

    /** One run of the output task: pulls what the machine has of the expected outputs. */
    int pull(StorageTransfer transfer, long now) {
        if (idle()) {
            return StorageTask.WAIT;
        }
        if (!ready()) {
            return StorageTask.IDLE;
        }
        if (!transfer.canOperate()) {
            return Math.max(2, transfer.retryTicks());
        }
        boolean everything = assembler() != null;
        PatternFaces.@Nullable Rule rule = rule();
        Direction face = rule == null ? providerFace() : rule.outputFace().orElse(providerFace());
        refused = false;
        AssemblerCore self = own;
        boolean moved = pullFrom(self != null ? self.output() : items(face), everything, transfer);
        if (self == null) {
            moved |= pullFrom(fluids(face), everything, transfer);
        }
        if (batches.isEmpty() && !returns.isEmpty()) {
            // Only once no craft is running: a returned input may sit in the machine's input until the craft is done.
            moved |= pullReturns(face, transfer);
        }
        if (batches.isEmpty()) {
            // Byproducts of the finished batches may come a moment after the main output: pulled for a short while,
            // never more than they declared, never a chance output; then forgotten, so items the machine gets from
            // elsewhere are left alone.
            chanceExpected.clear();
            if (lingerUntil < 0) {
                lingerUntil = now + LINGER_TICKS;
            } else if (now >= lingerUntil) {
                expected.clear();
            }
        }
        outputStuck = moved
                ? null
                : refused ? StuckReason.OUTPUT_BLOCKED : machineUnpowered() ? StuckReason.NO_POWER : null;
        if (moved) {
            lastProgress = now;
            leftoverDelay = 0;
            saved.run();
            if (idle()) {
                // Nothing left in the machine or the buffer: an assembler or a crafter has room again.
                freed();
                return StorageTask.WAIT;
            }
            return StorageTask.NEXT_TICK;
        }
        if (now - lastProgress > giveUpTicks()) {
            clearPending();
            if (idle()) {
                return StorageTask.WAIT;
            }
        }
        int pullTicks = Math.max(1, StorageServerConfig.CRAFTING_PROVIDER_PULL_TICKS.getAsInt());
        if (batches.isEmpty() && expected.isEmpty() && returns.isEmpty()) {
            // Only results left in a buffer the network has no room for: retry less often while nothing moves.
            leftoverDelay = leftoverDelay == 0 ? pullTicks : Math.min(MAX_LEFTOVER_TICKS, leftoverDelay * 2);
            return Math.max(pullTicks, leftoverDelay);
        }
        return pullTicks;
    }

    /** The delay before the next retry for results left in a buffer, 0 while none is due; for tests. */
    public int leftoverDelay() {
        return leftoverDelay;
    }

    /**
     * Nothing left to take from the machine: no batch in flight, no byproduct still expected, no returned input to take
     * back and nothing in the result buffer of the crafter or the assembler it faces. Byproducts and returned inputs
     * that come out after the main output keep the task running until they are in or the give-up time ran out; results
     * in a buffer keep it running until they are in (B09 item 29: a buffer the network had no room for, or more than
     * one operation moves, never waits for the next batch).
     */
    private boolean idle() {
        if (!batches.isEmpty() || !expected.isEmpty() || !returns.isEmpty()) {
            return false;
        }
        AssemblerCore core = assembler();
        return core == null || core.isEmpty();
    }

    /** How much of {@code key} the provider still means to take back as a returned input; for tests. */
    public long returnOwed(StackKey key) {
        long total = 0;
        for (int i = 0; i < returns.size(); i++) {
            if (returns.get(i).key().equals(key)) {
                total += returns.get(i).amount();
            }
        }
        return total;
    }

    /** Takes returned inputs back: from {@code outputFace}, else from the face each went into. */
    private boolean pullReturns(Direction outputFace, StorageTransfer transfer) {
        boolean moved = false;
        for (int i = returns.size() - 1; i >= 0 && transfer.canOperate(); i--) {
            Returned owed = returns.get(i);
            long got = pullKey(owed.key(), owed.amount(), outputFace, transfer);
            if (got < owed.amount() && owed.face() != outputFace) {
                got += pullKey(owed.key(), owed.amount() - got, owed.face(), transfer);
            }
            if (got > 0) {
                moved = true;
                CraftingDeliveries.delivered(transfer.network(), owed.key(), got);
                if (got >= owed.amount()) {
                    returns.remove(i);
                } else {
                    returns.set(i, new Returned(owed.key(), owed.amount() - got, owed.face()));
                }
            }
        }
        return moved;
    }

    /** Moves up to {@code amount} of {@code key} from {@code face} of the machine into the network; returns it. */
    private long pullKey(StackKey key, long amount, Direction face, StorageTransfer transfer) {
        return switch (key) {
            case ItemKey item -> pullKey(items(face), key, amount, transfer);
            case FluidKey fluid -> pullKey(fluids(face), key, amount, transfer);
        };
    }

    private static <T extends Resource> long pullKey(@Nullable ResourceHandler<T> handler, StackKey key, long amount,
            StorageTransfer transfer) {
        if (handler == null) {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < handler.size() && total < amount; i++) {
            T resource = handler.getResource(i);
            if (resource.isEmpty() || handler.getAmountAsLong(i) <= 0 || !StackKeys.of(resource).equals(key)) {
                continue;
            }
            long want = Math.min(Math.min(handler.getAmountAsLong(i), amount - total),
                    Math.min(transfer.batchLimit(key.kind()), Integer.MAX_VALUE));
            if (want <= 0) {
                break;
            }
            long got = move(handler, i, resource, key, (int) want, transfer);
            if (got <= 0) {
                break;
            }
            total += got;
        }
        return total;
    }

    /**
     * Why the provider holds a craft up now (M7C item 9), or null: its network has no power, the last output pass found
     * outputs that storage would not take or a machine without FE, or the machine refused the last batch. Server
     * thread; allocation-free.
     */
    public @Nullable StuckReason stuck() {
        NetworkState network = link.network();
        if (network != null && !network.isPowered()) {
            return StuckReason.NO_POWER;
        }
        StuckReason output = outputStuck;
        if (output != null) {
            return output;
        }
        StuckReason lock = locked();
        return lock != null ? lock : dispatchStuck;
    }

    /** Whether the machine has an FE store and it is empty; looked up through a cache, only while outputs are due. */
    private boolean machineUnpowered() {
        ServerLevel current = level;
        if (current == null || own != null) {
            return false;
        }
        CachedBlockLookup<EnergyHandler> cache = machineEnergy;
        if (cache == null) {
            cache = Platform.get().capabilities()
                    .sided(Identifier.fromNamespaceAndPath("neoforge", "energy_handler"), EnergyHandler.class)
                    .cache(current, target(), null, () -> !device.isRemoved(), () -> {
                    });
            machineEnergy = cache;
        }
        EnergyHandler energy = cache.find();
        return energy != null && energy.getCapacityAsLong() > 0 && energy.getAmountAsLong() <= 0;
    }

    /** Twice the in-flight crafts' estimated time, at least {@link #MIN_GIVE_UP_TICKS}. */
    long giveUpTicks() {
        long total = 0;
        for (int i = 0; i < batches.size(); i++) {
            Batch batch = batches.get(i);
            total += (long) batch.crafts() * ticksPerCraft(batch.pattern());
        }
        return Math.max(MIN_GIVE_UP_TICKS, 2 * total);
    }

    /**
     * Forgets every batch in flight: what comes out later stays in the machine. A result lock goes with them, since the
     * provider no longer waits for the output.
     */
    public void clearPending() {
        outputStuck = null;
        boolean wasBusy = resultKey != null || inFlight > 0;
        resultKey = null;
        resultOwed = 0;
        batches.clear();
        expected.clear();
        chanceExpected.clear();
        lingerUntil = -1;
        returns.clear();
        inFlight = 0;
        saved.run();
        if (wasBusy) {
            freed();
        }
    }

    private <T extends Resource> boolean pullFrom(@Nullable ResourceHandler<T> handler, boolean everything,
            StorageTransfer transfer) {
        if (handler == null) {
            return false;
        }
        if (handler.size() > 0 && handler.getResource(0) instanceof ItemResource) {
            return pullItems(handler, everything, transfer);
        }
        boolean moved = false;
        for (int i = 0; i < handler.size(); i++) {
            T resource = handler.getResource(i);
            long amount = handler.getAmountAsLong(i);
            if (resource.isEmpty() || amount <= 0) {
                continue;
            }
            StackKey key = StackKeys.of(resource);
            long want = everything ? amount : Math.min(amount, expected.getLong(key) + chanceExpected.getLong(key));
            want = Math.min(Math.min(want, transfer.batchLimit(key.kind())), Integer.MAX_VALUE);
            if (want <= 0) {
                continue;
            }
            long got = move(handler, i, resource, key, (int) want, transfer);
            if (got > 0) {
                moved = true;
                credit(key, got);
                CraftingDeliveries.delivered(transfer.network(), key, got);
            } else if (!transfer.canOperate()) {
                break;
            } else {
                // The network could operate but took none of an output it waits for: storage is full for it.
                refused = true;
            }
        }
        return moved;
    }

    /**
     * The machine's item outputs in one operation per batch, whatever their kinds (M7E follow-up): each slot moves in
     * its own nested transaction (into the network first, then exactly that much out of the machine), the energy is
     * paid once at the end, and the outputs are credited only after the root transaction commits. Runs at most one
     * operation per slot, as the per-slot pass did, and stops once one moves less than a full batch. Returns whether
     * anything moved.
     */
    private <T extends Resource> boolean pullItems(ResourceHandler<T> handler, boolean everything,
            StorageTransfer transfer) {
        boolean movedAny = false;
        boolean missed = false;
        for (int run = 0; run < handler.size(); run++) {
            outputMoves.clear();
            long budget = transfer.beginOperation(StackKey.Kind.ITEM);
            if (budget <= 0) {
                break;
            }
            boolean paid;
            long moved = 0;
            try (Transaction root = Transaction.openRoot()) {
                try {
                    for (int i = 0; i < handler.size() && moved < budget; i++) {
                        T resource = handler.getResource(i);
                        long amount = handler.getAmountAsLong(i);
                        if (resource.isEmpty() || amount <= 0) {
                            continue;
                        }
                        StackKey key = StackKeys.of(resource);
                        // What this operation took of the key already counts against what is expected of it.
                        long want = everything
                                ? amount
                                : Math.min(amount, expected.getLong(key) + chanceExpected.getLong(key)
                                        - outputMoves.amountOf(key));
                        want = Math.min(Math.min(want, budget - moved), Integer.MAX_VALUE);
                        if (want <= 0) {
                            continue;
                        }
                        long got = movePart(handler, i, resource, key, (int) want, transfer, root);
                        if (got > 0) {
                            moved += got;
                            outputMoves.add(key, got, i);
                        } else {
                            missed = true;
                        }
                    }
                } finally {
                    paid = transfer.finishOperation(StackKey.Kind.ITEM, moved, root);
                }
                if (paid) {
                    root.commit();
                }
            }
            if (!paid) {
                break;
            }
            movedAny = true;
            for (int i = 0; i < outputMoves.size(); i++) {
                credit(outputMoves.key(i), outputMoves.amount(i));
                CraftingDeliveries.delivered(transfer.network(), outputMoves.key(i), outputMoves.amount(i));
            }
            if (moved < budget) {
                break;
            }
        }
        if (missed && transfer.canOperate()) {
            // The network could operate but took none of an output it waits for: storage is full for it.
            refused = true;
        }
        return movedAny;
    }

    /** One slot's share of {@link #pullItems} in a transaction nested in {@code root}; what it moved. */
    private static <T extends Resource> long movePart(ResourceHandler<T> handler, int index, T resource, StackKey key,
            int amount, StorageTransfer transfer, Transaction root) {
        try (Transaction part = Transaction.open(root)) {
            long stored = transfer.insert(key, amount, part);
            if (stored <= 0) {
                return 0;
            }
            int taken = handler.extract(index, resource, (int) stored, part);
            if (taken != stored) {
                return 0;
            }
            part.commit();
            return stored;
        }
    }

    /** One operation: into the network first, then exactly that much out of the machine, or nothing. */
    private static <T extends Resource> long move(ResourceHandler<T> handler, int index, T resource, StackKey key,
            int amount, StorageTransfer transfer) {
        try (Transaction root = Transaction.openRoot()) {
            long stored = transfer.insert(key, amount, root);
            if (stored <= 0) {
                return 0;
            }
            int taken = handler.extract(index, resource, (int) stored, root);
            if (taken != stored) {
                return 0;
            }
            root.commit();
            return stored;
        }
    }

    /**
     * Counts {@code amount} of {@code key} against the expected outputs and the batches' main outputs, oldest first.
     */
    private void credit(StackKey key, long amount) {
        long fromGuaranteed = Math.min(amount, expected.getLong(key));
        reduce(expected, key, fromGuaranteed);
        reduce(chanceExpected, key, amount - fromGuaranteed);
        long rest = amount;
        for (int i = 0; i < batches.size() && rest > 0; i++) {
            Batch batch = batches.get(i);
            if (batch.remaining() > 0 && batch.pattern().mainOutput().key().equals(key)) {
                long take = Math.min(rest, batch.remaining());
                rest -= take;
                batches.set(i, new Batch(batch.pattern(), batch.crafts(), batch.remaining() - take));
            }
        }
        for (int i = batches.size() - 1; i >= 0; i--) {
            Batch batch = batches.get(i);
            if (batch.remaining() <= 0 && batch.pattern().mainOutput().guaranteed()) {
                batches.remove(i);
                inFlight = Math.max(0, inFlight - batch.crafts());
            }
        }
        if (batches.isEmpty()) {
            // Byproducts may still be on their way (a later slot, a later tick): see pull (M7C item 3).
            boolean wasBusy = inFlight > 0;
            inFlight = 0;
            if (wasBusy) {
                freed();
            }
        }
    }

    private static void reduce(Object2LongOpenHashMap<StackKey> map, StackKey key, long amount) {
        if (amount <= 0) {
            return;
        }
        long left = map.getLong(key) - amount;
        if (left > 0) {
            map.put(key, left);
        } else {
            map.removeLong(key);
        }
    }

    /** Writes the patterns, the blocking setting, the lock and the batches in flight. */
    public void save(ValueOutput output) {
        ContainerHelper.saveAllItems(output.child(SLOTS), slots.getItems());
        output.putBoolean(BLOCKING, blocking);
        if (lockMode != LockMode.NONE) {
            output.putInt(LOCK, lockMode.id());
        }
        if (lockPowered) {
            output.putBoolean(LOCK_POWERED, true);
        }
        if (awaitingPulse) {
            output.putBoolean(LOCK_PULSE, true);
        }
        StackKey waiting = resultKey;
        if (waiting != null) {
            output.store(LOCK_RESULT, Expected.CODEC, new Expected(waiting, resultOwed));
        }
        output.putLong(LAST_DISPATCH, lastDispatch);
        if (!batches.isEmpty() || !keptBatches.isEmpty()) {
            output.store(BATCHES, BATCHES_CODEC, KeptList.of(List.copyOf(batches), keptBatches));
        }
        store(output, EXPECTED, expected, keptExpected);
        store(output, CHANCE_EXPECTED, chanceExpected, keptChanceExpected);
        if (!returns.isEmpty() || !keptReturns.isEmpty()) {
            output.store(RETURNS, RETURNS_CODEC, KeptList.of(List.copyOf(returns), keptReturns));
        }
    }

    private static void store(ValueOutput output, String name, Object2LongOpenHashMap<StackKey> map, List<Tag> kept) {
        if (!map.isEmpty() || !kept.isEmpty()) {
            List<Expected> keys = new ArrayList<>(map.size());
            for (Object2LongMap.Entry<StackKey> entry : map.object2LongEntrySet()) {
                keys.add(new Expected(entry.getKey(), entry.getLongValue()));
            }
            output.store(name, EXPECTED_CODEC, KeptList.of(keys, kept));
        }
    }

    /** Reads what {@link #save} wrote, without saving or telling the network. */
    public void load(ValueInput input) {
        slots.getItems().clear();
        input.child(SLOTS).ifPresent(child -> ContainerHelper.loadAllItems(child, slots.getItems()));
        blocking = input.getBooleanOr(BLOCKING, true);
        lockMode = LockMode.byId(input.getIntOr(LOCK, LockMode.NONE.id()));
        lockPowered = input.getBooleanOr(LOCK_POWERED, false);
        lockStale = !lockMode.redstone();
        awaitingPulse = lockMode == LockMode.UNTIL_PULSE && input.getBooleanOr(LOCK_PULSE, false);
        // A result whose key no longer loads (a removed mod) cannot come back: the lock is dropped.
        Expected result = lockMode == LockMode.UNTIL_RESULT
                ? input.read(LOCK_RESULT, Expected.CODEC).orElse(null)
                : null;
        resultKey = result == null ? null : result.key();
        resultOwed = result == null ? 0 : result.amount();
        lastDispatch = input.getLongOr(LAST_DISPATCH, 0L);
        batches.clear();
        expected.clear();
        inFlight = 0;
        KeptList<Batch> savedBatches = input.read(BATCHES, BATCHES_CODEC).orElse(KeptList.of(List.of()));
        keptBatches = savedBatches.kept();
        for (Batch batch : savedBatches.known()) {
            batches.add(batch);
            inFlight += batch.crafts();
        }
        KeptList<Expected> savedExpected = input.read(EXPECTED, EXPECTED_CODEC).orElse(KeptList.of(List.of()));
        keptExpected = savedExpected.kept();
        for (Expected entry : savedExpected.known()) {
            expected.put(entry.key(), entry.amount());
        }
        chanceExpected.clear();
        KeptList<Expected> savedChance = input.read(CHANCE_EXPECTED, EXPECTED_CODEC).orElse(KeptList.of(List.of()));
        keptChanceExpected = savedChance.kept();
        for (Expected entry : savedChance.known()) {
            chanceExpected.put(entry.key(), entry.amount());
        }
        lingerUntil = -1;
        returns.clear();
        KeptList<Returned> savedReturns = input.read(RETURNS, RETURNS_CODEC).orElse(KeptList.of(List.of()));
        keptReturns = savedReturns.kept();
        returns.addAll(savedReturns.known());
    }

    /**
     * Writes the patterns (vanilla's container component, listed by its tooltip), a non-default blocking setting and
     * lock mode into an item; a lock that holds is not kept. Batches in flight are not kept: what is in the machine
     * stays there.
     */
    public void write(DataComponentMap.Builder components) {
        if (!slots.isEmpty()) {
            List<ItemStack> held = new ArrayList<>(MAX_SLOTS);
            for (int i = 0; i < MAX_SLOTS; i++) {
                held.add(slots.getItem(i).copy());
            }
            components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(held));
        }
        if (!blocking) {
            components.set(ProviderContent.nonBlockingType(), true);
        }
        if (lockMode != LockMode.NONE) {
            components.set(ProviderContent.lockType(), lockMode);
        }
    }

    /** Reads {@link #write}'s components (a placed item), replacing the patterns and the setting. */
    public void read(DataComponentGetter components) {
        NonNullList<ItemStack> held = NonNullList.withSize(MAX_SLOTS, ItemStack.EMPTY);
        ItemContainerContents contents = components.get(DataComponents.CONTAINER);
        if (contents != null) {
            contents.copyInto(held);
        }
        slots.getItems().clear();
        for (int i = 0; i < MAX_SLOTS; i++) {
            slots.getItems().set(i, held.get(i));
        }
        blocking = !Boolean.TRUE.equals(components.get(ProviderContent.nonBlockingType()));
        lockMode = components.getOrDefault(ProviderContent.lockType(), LockMode.NONE);
        lockStale = true;
        awaitingPulse = false;
        resultKey = null;
        resultOwed = 0;
    }

    /** Encoded patterns only, one per slot; a change re-reads the patterns. */
    private final class Slots extends SimpleContainer {
        Slots() {
            super(MAX_SLOTS);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return accepts(stack, own != null);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            saved.run();
            if (level != null) {
                refresh();
            }
        }
    }

    /** The provider's output work on the network's scheduler; one per provider, scheduled when it starts. */
    private final class Task implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            return pull(transfer, gameTime);
        }
    }
}
