package dev.silencebound.maxtech.storage.controller;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.energy.EndpointBuffer;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import dev.silencebound.maxtech.storage.save.KeptList;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.alarm.Alarm;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmCenter;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.card.ControllerCards;
import dev.silencebound.maxtech.storage.controller.module.AnchorChunks;
import dev.silencebound.maxtech.storage.controller.module.ExpansionBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.controller.module.TelemetryRecorder;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.module.LedgerBook;
import dev.silencebound.maxtech.storage.controller.recorder.ControllerRecorders;
import dev.silencebound.maxtech.storage.controller.recorder.RecordTexts;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryBin;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.display.task.TaskBoards;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.ClusterNode;
import dev.silencebound.maxtech.storage.network.NetworkController;
import dev.silencebound.maxtech.storage.network.NetworkHistory;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessCoverage;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage runs on it (PRD 7.2).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
// PLATFORM-LEAK: the face's glow phase goes to the baked model through getModelData (ADR-0002 allows it there).
import net.neoforged.neoforge.model.data.ModelData;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.model.data.ModelProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A block of a storage controller (PRD 7.2, Controller; M7R item 1): identical controller blocks placed face to face in
 * any shape form one multiblock controller. One block, the <em>primary</em>, holds the network's state: the FE buffer,
 * the settings, the name, the alarms and the recovery bin; the others are members that point to it. Every face of an
 * active block that touches a storage cable or device is a root link ({@link #exitNodes()}, counted by the network at a
 * rebuild). Each active block adds {@code controller.bufferFe} and {@code controller.inputPerTick}
 * ({@link ClusterStats}). Blocks over the size cap (research steps, {@code controller.size.*}) are dark: they belong to
 * the multiblock but add nothing and carry no routes.
 *
 * <p>
 * The multiblock is found by a breadth-first fill from the primary only when a controller block is placed or broken, a
 * module is placed, or research raises the cap (a block tick, so several changes in one tick cost one fill); the fill
 * stops at {@code controller.size.max} blocks. Its result is saved, so nothing is searched when chunks load; a member
 * that loads checks once that its primary still claims it. Two multiblocks that touch merge: the one placed first keeps
 * its state and takes the other's FE and recovery rows. Breaking the primary hands the whole state to another active
 * block; breaking a member keeps the FE in the primary (a lower capacity only drains it). Nothing ticks.
 *
 * <p>
 * The upgrade cards (M7R item 2) sit in the primary's {@link #cards()}: {@code controller.cards.*} slots by active
 * blocks, none without the Upgrade Cards research. A fill that shrinks the slots moves cards into free ones or drops
 * them where the block was broken; a handover moves them with the state; a fill turns the old batch amplifier,
 * efficiency core and surge regulator blocks it meets into cards. The telemetry history, the ledger and the redstone
 * alarm rules (M7R item 3) are the primary's {@link #recorders()}; they move with the state like the cards, and a fill
 * moves the rows and settings of the old ledger and alarm module blocks it meets into them, once. The security module,
 * network anchor, antenna mount, wireless access mount and dimension bridge are network devices since M7R item 4: the
 * primary keeps the security rules and the anchor's chunks, and applies them while the network has such a device
 * anywhere ({@link #secured()}, {@link #anchored()}); nothing attaches to the multiblock any more. Of several separate
 * controllers in one network the one placed first runs it and the others are on standby (M7N item 3,
 * {@link NetworkState#isStandby}). Never ticks; the block's {@link ControllerBlock#STATUS} changes only when the
 * network's state or power does.
 */
public final class ControllerBlockEntity extends StorageDeviceBlockEntity implements NetworkController, ClusterNode {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Set by the save migration of an old cluster core (M7R item 1, {@code StorageSaveFormats}): the block finds its
     * multiblock once after it loads, which also lets the retired expansions go.
     */
    public static final String REDETECT = "redetect";
    private static final String ENERGY = "energy";
    private static final String SHEDDING_ARMED = "shedding_armed";
    private static final String MEMBERS = "members";
    private static final String KINDS = "kinds";
    private static final String BLOCKS = "blocks";
    private static final String DARK = "dark";
    private static final String PRIMARY = "primary";
    private static final String DARK_PART = "dark_part";
    private static final String OVER = "over";
    private static final String CAP = "cap";
    private static final String SECURITY = "security";
    private static final String NETWORK_NAME = "network_name";
    private static final String CRAFT_LIMITS = "craft_limits";
    private static final String ALARMS = "alarms";
    private static final String RECOVERY = "recovery";
    private static final Codec<KeptList<RecoveryBin.Row>> RECOVERY_CODEC = KeptList.codec(RecoveryBin.Row.CODEC);
    private static final String PLACED_AT = "placed_at";
    private static final String CARD_SLOTS = "card_slots";
    private static final String GLOW_CENTRE_KEY = "glow_centre";
    /**
     * Where the multiblock's centre lies from this block ({@link ControllerFace#packCentre}), for the face model's glow
     * phase (M7R item 7). Synced to clients; changes only when the multiblock does.
     */
    public static final ModelProperty<Integer> GLOW_CENTRE = new ModelProperty<>();
    /** {@link #placedAt} before the block first loaded in a level. */
    private static final long UNPLACED = Long.MIN_VALUE;
    /** {@link #primaryPos} of a primary block. */
    private static final long NO_PRIMARY = Long.MIN_VALUE;
    /**
     * Ticks after loading in which the anchor keeps every chunk it held, while the chunks its tickets load bring the
     * network back together. A correctness bound, not a balance value.
     */
    static final int ANCHOR_GRACE_TICKS = 100;
    private static final Direction[] SIDES = Direction.values();
    /** Loaded controller blocks, so research can find them; weak, since a server that stops does not unload them. */
    private static final Set<ControllerBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private final TickLimitedBuffer buffer = new TickLimitedBuffer(this);
    /** Load shedding armed on this network (M7L2): charged past the highest resume level once; saved. */
    private boolean sheddingArmed;
    /** The capability's handler; it picks its target on each call, since capabilities cache the instance. */
    private final EnergyHandler input = new CoreInput();
    /** The primary block's position, or {@link #NO_PRIMARY} when this block is the primary. Saved. */
    private long primaryPos = NO_PRIMARY;
    /**
     * The primary found by the last {@link #primary()} lookup; checked on each use, so FE fed into a member every tick
     * costs no block entity lookup. Not saved.
     */
    private @Nullable ControllerBlockEntity primaryCache;
    /** A member over the size cap: it belongs to the multiblock but adds nothing. Saved. */
    private boolean darkPart;
    /** Primary only: the active member blocks in fill order, and the dark ones. Saved. */
    private final LongArrayList blocks = new LongArrayList();
    private final LongArrayList darkBlocks = new LongArrayList();
    /** Primary only: the attached modules in fill order, and their kinds' indices. Saved. */
    private final LongArrayList members = new LongArrayList();
    private final IntArrayList kinds = new IntArrayList();
    /**
     * Primary only: where the capacitor banks of an old save stood (M7R item 5, {@link StorageSaveFormats#BANK_CELLS}),
     * now tier-1 energy cells that take the FE above the buffer's capacity at the next fill. Saved; empty once done.
     */
    private final LongArrayList bankCells = new LongArrayList();
    private LongList exits = LongLists.EMPTY_LIST;
    private int[] counts = new int[ModuleKind.count()];
    private @Nullable ClusterStats stats;
    /** Whether the last fill found more blocks than the size cap allows (the rest stay dark). */
    private boolean over;
    /** The size cap at the last fill. */
    private int cap;
    /** Find the multiblock once after loading (an old save, {@link #REDETECT}). */
    private boolean redetect;
    /** Game time the block was placed at; saved, so which controller is in charge survives reloads. */
    private long placedAt = UNPLACED;
    /** On standby as of the last {@link #refreshStatus()}: another controller runs this network. Not saved. */
    private boolean standby;
    private SecuritySettings security = SecuritySettings.DEFAULT;
    /** The network's name (M7L2 row 13), claimed in {@link NetworkNames} when the primary loads; empty before. */
    private String networkName = "";
    private CraftLimits craftLimits = CraftLimits.DEFAULT;
    /** The anchored chunk set may have changed (network or cluster change); worked out on the next tick. */
    private boolean anchorDirty;
    /** Chunks the anchor wanted and holds after its last update; for the info lines. */
    private int anchorWanted;
    private int anchorHeld;
    /** Whether this block's graph node carries Lib's anchor flag (keeps a paused network ticking). */
    private boolean graphAnchored;
    /** The built-in recorders (M7R item 3); a primary's only, the ledger and rules saved with it. */
    private final ControllerRecorders recorders = new ControllerRecorders(this);
    /** The network's alarm panel (M7C item 16); saved with the primary. */
    private final AlarmCenter alarms = new AlarmCenter(this);
    private final RecoveryBin recovery = new RecoveryBin();
    /** Recovery rows whose keys no longer load (a removed mod), saved back unchanged until the mod returns. */
    private List<Tag> keptRecovery = List.of();
    /** The upgrade cards (M7R item 2); a primary's only, saved with it. */
    private final ControllerCards cards = new ControllerCards(this::cardsChanged);
    /**
     * Where cards that no longer fit drop at the next fill ({@link BlockPos#asLong()}): the member broken last, or
     * {@link #NO_PRIMARY} for this block. Not saved.
     */
    private long cardDrop = NO_PRIMARY;
    /**
     * Set while the block is being broken ({@link #preRemoveSideEffects}): handing over makes the network refresh its
     * controllers before this block is gone, and setting this block's status then would put the block back.
     */
    private boolean breaking;
    /** Game time the block loaded at; the anchor only adds chunks for {@link #ANCHOR_GRACE_TICKS} after it. */
    private long loadedAt = Long.MIN_VALUE / 2;
    /** {@link #GLOW_CENTRE}: set by the primary's fill on every block it takes; 0 on a lone block. Saved. */
    private int glowCentre;

    public ControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ControllerContent.CONTROLLER_BLOCK_ENTITY.get(), pos, state);
    }

    /**
     * The buffer's FE changed: marks the chunk unsaved without {@code setChanged}'s neighbour updates. Every network
     * operation pays FE, and those updates were about an eighth of a port operation's time (M7E item D); the block has
     * no comparator output, so its neighbours have nothing to read from them.
     */
    private void energyChanged() {
        Level current = level;
        if (current != null) {
            current.blockEntityChanged(worldPosition);
        }
    }

    /** The primary is the network's controller; a member is a cluster block like a module. */
    @Override
    public Role role() {
        return isPrimary() ? Role.CONTROLLER : Role.OTHER;
    }

    /** Every controller block links to cables and devices; only active ones carry root links. */
    @Override
    public boolean linksOutside() {
        return true;
    }

    @Override
    public boolean sheddingArmed() {
        return sheddingArmed;
    }

    @Override
    public void armShedding() {
        if (!sheddingArmed) {
            sheddingArmed = true;
            setChanged();
        }
    }

    @Override
    public FeBuffer buffer() {
        return buffer;
    }

    /** The primary's buffer endpoint, which is in the network's energy network while the controller is in charge. */
    @Override
    public EnergyEndpoint energyEndpoint() {
        ControllerBlockEntity p = primary();
        return p != null ? p.buffer.endpoint() : buffer.endpoint();
    }

    @Override
    public long placedAt() {
        return placedAt;
    }

    /** Whether this block is its multiblock's primary, which holds the network's state. */
    public boolean isPrimary() {
        return primaryPos == NO_PRIMARY;
    }

    /** The primary block's position ({@link BlockPos#asLong()}); this block's own when it is the primary. */
    public long primaryPos() {
        return isPrimary() ? worldPosition.asLong() : primaryPos;
    }

    /** Whether this member is over its multiblock's size cap and adds nothing. */
    public boolean isDark() {
        return !isPrimary() && darkPart;
    }

    /**
     * The loaded primary of this block's multiblock: this block when it is the primary; null when the primary's chunk
     * is not loaded or the block there is no primary any more. Server side.
     */
    public @Nullable ControllerBlockEntity primary() {
        if (isPrimary()) {
            return this;
        }
        ControllerBlockEntity cached = primaryCache;
        if (cached != null && !cached.isRemoved() && cached.isPrimary()
                && cached.worldPosition.asLong() == primaryPos) {
            return cached;
        }
        primaryCache = null;
        Level current = level;
        BlockPos at = BlockPos.of(primaryPos);
        if (current != null && current.isLoaded(at) && current.getBlockEntity(at) instanceof ControllerBlockEntity p
                && p != this && p.isPrimary() && !p.isRemoved()) {
            primaryCache = p;
            return p;
        }
        return null;
    }

    /** Whether this primary counts the block at {@code pos} as its member, active or dark. */
    boolean claims(long pos) {
        return blocks.contains(pos) || darkBlocks.contains(pos);
    }

    /**
     * FE fed into any block of the multiblock, dark ones included, goes where the network's FE goes
     * ({@link NetworkState#energyStore()}): the primary's buffer first, then whatever stores the network adds behind
     * it, like the input of any other device. A block in no network or a controller on standby fills only its own
     * buffer.
     */
    @Override
    public EnergyHandler energyInput() {
        return input;
    }

    /** The store {@link #energyInput()} hands FE to now. */
    private EnergyHandler inputTarget() {
        if (!isPrimary()) {
            // A dark block adds no capacity or links, but its FE input still feeds the controller.
            ControllerBlockEntity p = primary();
            return p != null ? p.inputTarget() : buffer.inputOnly();
        }
        NetworkState current = network();
        if (!standby && current != null) {
            EnergyHandler store = current.energyStore();
            if (store != null) {
                return store;
            }
        }
        return buffer.inputOnly();
    }

    /**
     * The multiblock's stats as of the last fill, card change or load display update; recomputed when config may have
     * changed. The card kinds count the cards in the usable slots, not attached blocks.
     */
    public ClusterStats stats() {
        ClusterStats current = stats;
        if (current == null) {
            if (isPrimary()) {
                int[] all = counts.clone();
                for (CardKind kind : CardKind.values()) {
                    all[kind.module().index()] = cards.count(kind, -1);
                }
                current = ClusterStats.of(1 + blocks.size(), all, ClusterStats.Numbers.fromConfig());
            } else {
                current = ClusterStats.single(ClusterStats.Numbers.fromConfig());
            }
            stats = current;
        }
        return current;
    }

    /**
     * The upgrade card slots (M7R item 2): the primary's hold the network's cards, a member's stay empty. Server side;
     * change them through a menu or {@link ControllerCards#insert}, which recount the stats.
     */
    public ControllerCards cards() {
        return cards;
    }

    /** Installed cards of {@code kind} in the usable slots; more than the config's limit count only up to it. */
    public int cards(CardKind kind) {
        return cards.count(kind, -1);
    }

    /**
     * A card went in or out: the stats are worked out again, the block is saved, and the network looks again at what
     * the cards change (a surge regulator gone lets held-back automation go). Only on a change, never per tick.
     */
    private void cardsChanged() {
        stats = null;
        if (!(level instanceof ServerLevel) || !isPrimary()) {
            return;
        }
        setChanged();
        NetworkState current = network();
        if (current != null && !current.isStandby(this)) {
            current.clusterChanged();
        }
    }

    /**
     * The card slots the owner's team allows for {@code blocks} active blocks: none without the Upgrade Cards research,
     * else {@code controller.cards.*}.
     */
    private int cardSlots(ServerLevel server, int blocks) {
        return ResearchUnlocks.isUnlocked(server, ownerRecord(), ResearchGates.CONTROLLER_CARDS)
                ? ClusterStats.Cards.fromConfig().slots(blocks)
                : 0;
    }

    /** Drops {@code stack} at the block {@code pos} as an item; gamerules do not apply, so no card is lost. */
    private static void drop(ServerLevel server, long pos, ItemStack stack) {
        BlockPos at = BlockPos.of(pos);
        Containers.dropItemStack(server, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, stack);
    }

    /**
     * Turns the retired card-kind expansion blocks at {@code positions} (a batch amplifier, efficiency core or surge
     * regulator the fill met, from an old cluster or placed from an old item) into cards: each block goes, and its card
     * goes into a usable slot that takes it, or drops where the block was (M7R item 2, no loss).
     */
    private void convertExpansions(ServerLevel server, LongList positions) {
        if (positions.isEmpty()) {
            return;
        }
        ClusterStats.Cards limits = ClusterStats.Cards.fromConfig();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos at = BlockPos.of(positions.getLong(i));
            if (!(server.getBlockEntity(at) instanceof ExpansionBlockEntity expansion)) {
                continue;
            }
            CardKind kind = CardKind.of(expansion.kind());
            if (kind == null) {
                continue;
            }
            server.removeBlock(at, false);
            ItemStack left = cards.insert(new ItemStack(CardContent.item(kind)), limits);
            if (!left.isEmpty()) {
                drop(server, at.asLong(), left);
            }
        }
    }

    /**
     * Moves what the old ledger and alarm module blocks at {@code positions} hold into this controller's recorders (M7R
     * item 3): the ledger rows into the ledger, each alarm's settings as a rule while there is room. Each block gives
     * them up as it hands them over, so a reload or a second fill moves nothing twice; an alarm that finds no free rule
     * keeps its settings until a later fill finds room. The blocks stay, inert.
     */
    private void absorbRecords(ServerLevel server, LongList positions) {
        for (int i = 0; i < positions.size(); i++) {
            if (!(server.getBlockEntity(BlockPos.of(positions.getLong(i))) instanceof ExpansionBlockEntity expansion)) {
                continue;
            }
            LedgerBook<StackKey> book = expansion.takeOldLedger();
            if (book != null) {
                recorders.ledger().merge(book);
            }
            // Given up only once a rule took it: at the rule cap the module keeps its settings for a later fill.
            AlarmSettings alarm = expansion.oldAlarm();
            if (alarm != null && recorders.addRule(alarm)) {
                expansion.takeOldAlarm();
            }
            // Inert from now on, like every retired module.
            expansion.leave();
        }
    }

    /** No fixed number: every face of an active block that touches a cable or device is a root link (M7R item 1). */
    @Override
    public int rootLinks() {
        return Integer.MAX_VALUE;
    }

    @Override
    public LongList exitNodes() {
        if (exits.isEmpty()) {
            exits = LongLists.singleton(worldPosition.asLong());
        }
        return exits;
    }

    @Override
    public int batchPercent() {
        return stats().batchPercent();
    }

    @Override
    public int energyPercent() {
        return stats().energyPercent();
    }

    @Override
    public int surgeReservePercent() {
        return stats().surgeReservePercent();
    }

    /** Active controller blocks of the multiblock, the primary included; 1 for a member. */
    public int blockCount() {
        return isPrimary() ? 1 + blocks.size() : 1;
    }

    /** The active member blocks as of the last fill, in fill order ({@link BlockPos#asLong()}); do not change it. */
    public LongList blocks() {
        return LongLists.unmodifiable(blocks);
    }

    /** The dark member blocks (over the size cap) as of the last fill; do not change it. */
    public LongList darkBlocks() {
        return LongLists.unmodifiable(darkBlocks);
    }

    /**
     * Modules of {@code kind} an old save still lists (M7R): nothing attaches any more, so the next fill drops them.
     * For migration tests.
     */
    public int expansions(ModuleKind kind) {
        return kind.index() < counts.length ? counts[kind.index()] : 0;
    }

    /** The size cap the owner's research allowed at the last fill. */
    public int cap() {
        return cap;
    }

    /** Whether the last fill found more controller blocks than the size cap allows (the rest stay dark). */
    public boolean overCapacity() {
        return over;
    }

    /** Whether this block is a member of another block's multiblock, so it runs nothing itself. */
    public boolean isIdle() {
        return !isPrimary();
    }

    /**
     * Whether another controller runs this primary's network, so this one does nothing (M7N item 3), as of the last
     * {@link #refreshStatus()}.
     */
    public boolean isStandby() {
        return standby;
    }

    /** Whether the network says another controller is in charge of it now. */
    private boolean standbyNow() {
        NetworkState current = network();
        return isPrimary() && current != null && current.isStandby(this);
    }

    /** What the block should show for the network it is in now. */
    ControllerStatus desiredStatus() {
        if (!isPrimary()) {
            ControllerBlockEntity p = darkPart ? null : primary();
            return p == null ? ControllerStatus.OFF : p.desiredStatus();
        }
        if (standby) {
            return ControllerStatus.ERROR;
        }
        StorageNetwork current = network();
        if (current == null) {
            return ControllerStatus.OFF;
        }
        return switch (current.status()) {
            case PAUSED -> ControllerStatus.OFF;
            case ONLINE -> current.isPowered() ? ControllerStatus.ONLINE : ControllerStatus.OFF;
        };
    }

    @Override
    public void refreshStatus() {
        Level current = level;
        if (current == null || current.isClientSide() || isRemoved() || breaking || !isPrimary()) {
            return;
        }
        boolean nowStandby = standbyNow();
        if (nowStandby != standby) {
            standby = nowStandby;
            // The modules go dark or light up again on the next tick; the alarms stop or look again.
            requestDetect();
            alarms.networkChanged(network());
            recorders.refresh(network());
        }
        BlockState state = getBlockState();
        ControllerStatus desired = desiredStatus();
        if (state.getValue(ControllerBlock.STATUS) != desired) {
            current.setBlock(worldPosition, state.setValue(ControllerBlock.STATUS, desired), Block.UPDATE_CLIENTS);
            showMemberStatus(desired);
        }
        // Joins, leaves and rebuilds all pass here: an anchored network's chunks may have changed.
        if (anchorHeld > 0 || anchored()) {
            markAnchorDirty();
        }
    }

    /** The network this primary runs counts members giving {@code service}; 0 for a member block or without one. */
    public int services(NetworkService service) {
        NetworkState current = network();
        return isPrimary() && current != null ? current.serviceCount(service) : 0;
    }

    /** Whether a network anchor is on this primary's network (M7R item 4), so the anchor keeps its chunks. */
    public boolean anchored() {
        return services(NetworkService.ANCHOR) > 0;
    }

    /** Whether a security module is on this primary's network (M7R item 4), so its access rules apply. */
    public boolean secured() {
        return services(NetworkService.SECURITY) > 0;
    }

    /** Shows {@code status} on every loaded active member and OFF on every dark one; each only when it differs. */
    private void showMemberStatus(ControllerStatus status) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            showStatus(server, BlockPos.of(blocks.getLong(i)), status);
        }
        for (int i = 0; i < darkBlocks.size(); i++) {
            showStatus(server, BlockPos.of(darkBlocks.getLong(i)), ControllerStatus.OFF);
        }
    }

    private static void showStatus(ServerLevel server, BlockPos pos, ControllerStatus status) {
        if (!server.isLoaded(pos)) {
            return;
        }
        BlockState state = server.getBlockState(pos);
        if (state.getBlock() instanceof ControllerBlock && state.getValue(ControllerBlock.STATUS) != status) {
            server.setBlock(pos, state.setValue(ControllerBlock.STATUS, status), Block.UPDATE_CLIENTS);
        }
    }

    /** Asks for the anchored chunk set to be worked out again on the next tick; several asks cost one. */
    private void markAnchorDirty() {
        if (!anchorDirty) {
            anchorDirty = true;
            requestDetect();
        }
    }

    /**
     * Makes the anchor hold the chunks it should (PRD 7.2, network anchor): with a network anchor on a working
     * controller's network (M7R item 4), the chunks of its blocks and attached modules, then the chunks on the routes
     * of the network's storage devices, energy cells and anchors ({@link NetworkState#anchorChunks}), up to the owner's
     * team's cap; otherwise none. Right after loading with chunks the loader kept, the anchors may not have joined the
     * network yet, so for {@link #ANCHOR_GRACE_TICKS} nothing is released. Also sets Lib's anchor flag on the primary's
     * node, so a network touching an unloaded chunk in {@code pause} mode keeps running. Called from the block's
     * scheduled tick after a change; nothing runs per tick.
     */
    void updateAnchor(ServerLevel server) {
        if (!anchorDirty) {
            return;
        }
        anchorDirty = false;
        NetworkState current = network();
        if (current == null && !isRemoved()) {
            // Between networks (just loaded, or a rebuild): joining one asks again; dropping chunks now could unload
            // the very drives the network is about to take back.
            return;
        }
        boolean active = isPrimary() && !standby && !isRemoved() && current != null && anchored()
                && ServerConfig.ALLOW_ANCHORS.getAsBoolean() && AnchorChunks.cap() > 0
                && StorageModules.NETWORK_ANCHOR.isEnabled();
        int before = anchorHeld;
        long settled = loadedAt + ANCHOR_GRACE_TICKS - server.getGameTime();
        if (!active && settled > 0 && !isRemoved() && isPrimary()) {
            // Just loaded with chunks the loader kept: the network's anchors may still be joining. Look again later.
            anchorDirty = true;
            server.scheduleTick(worldPosition, getBlockState().getBlock(), (int) settled);
            return;
        }
        if (!active || current == null) {
            AnchorChunks.release(server, worldPosition);
            anchorWanted = 0;
            anchorHeld = 0;
            setGraphAnchored(server, false);
            wakeCappedAnchors(before);
            return;
        }
        LongLinkedOpenHashSet wanted = new LongLinkedOpenHashSet();
        wanted.add(ChunkPos.pack(worldPosition));
        addChunks(blocks, wanted);
        addChunks(darkBlocks, wanted);
        addChunks(members, wanted);
        current.anchorChunks(wanted);
        anchorWanted = wanted.size();
        // Right after loading, the network is still gathering the chunks the anchor keeps: only add then, and look
        // again once the grace period is over, so a half-built network does not release its own drives.
        boolean addOnly = settled > 0;
        anchorHeld = AnchorChunks.update(server, worldPosition, anchorTeam(server), wanted, addOnly);
        setGraphAnchored(server, anchorHeld > 0);
        wakeCappedAnchors(before);
        if (addOnly) {
            anchorDirty = true;
            server.scheduleTick(worldPosition, getBlockState().getBlock(), (int) settled);
        }
    }

    private static void addChunks(LongList positions, LongLinkedOpenHashSet out) {
        for (int i = 0; i < positions.size(); i++) {
            out.add(ChunkPos.pack(BlockPos.of(positions.getLong(i))));
        }
    }

    /**
     * When this primary now holds fewer chunks than {@code before}, its team may have room again: every loaded primary
     * whose anchor holds less than it wants looks again. Only on a decrease, so a steady network costs nothing.
     */
    private void wakeCappedAnchors(int before) {
        if (anchorHeld >= before) {
            return;
        }
        ControllerBlockEntity[] cores = LOADED.toArray(new ControllerBlockEntity[0]);
        for (ControllerBlockEntity core : cores) {
            if (core != this && !core.isRemoved() && core.anchorHeld < core.anchorWanted) {
                core.markAnchorDirty();
            }
        }
    }

    /** The team whose chunk cap the anchor counts against: the owner's research team, else the owner alone. */
    private UUID anchorTeam(ServerLevel server) {
        ResearchTeam team = Research.data(server.getServer()).team(server.getServer(), ownerRecord());
        return team != null ? team.anchor() : ownerRecord().owner();
    }

    private void setGraphAnchored(ServerLevel server, boolean anchored) {
        NetworkType type = NetworkType.byId(networkId()).orElse(null);
        if (anchored != graphAnchored && type != null) {
            graphAnchored = anchored;
            NetworkManager.get(server).setAnchored(type, worldPosition, anchored);
        }
    }

    /** Chunks the anchor holds now; 0 without an active anchor. */
    public int anchoredChunks() {
        return anchorHeld;
    }

    /** Chunks the anchor would hold without the team's cap; 0 without an active anchor. */
    public int anchorWantedChunks() {
        return anchorWanted;
    }

    /**
     * The access rules a security module on the network applies; the defaults until someone edits them. They stay with
     * the controller (M7R item 4): any security module on the network turns them on and opens them.
     */
    public SecuritySettings security() {
        return security;
    }

    /** Replaces the access rules. Server side; the caller checks {@link SecuritySettings#canEdit}. */
    public void setSecurity(SecuritySettings settings) {
        if (!settings.equals(security)) {
            security = settings;
            setChanged();
            // Rules on other networks that this one's access decides check again (M7L2 row 13).
            NetworkNames.changed();
        }
    }

    @Override
    public String networkName() {
        return networkName;
    }

    /**
     * Renames the network to {@code wanted} (cleaned, {@link NetworkNames#clean}); refused when the name is empty or
     * another controller holds it. Server side; the caller checks the player may configure the controller.
     */
    public NetworkNames.Renamed rename(String wanted) {
        if (!(level instanceof ServerLevel server) || !isPrimary()) {
            return NetworkNames.Renamed.SAME;
        }
        NetworkNames names = NetworkNames.get(server.getServer());
        NetworkNames.Renamed result = names.rename(networkName, wanted, server.dimension(), worldPosition.asLong(),
                ownerUuid());
        if (result == NetworkNames.Renamed.DONE) {
            networkName = NetworkNames.clean(wanted);
            setChanged();
            NetworkNames.changed();
        }
        return result;
    }

    private @Nullable UUID ownerUuid() {
        return ownerRecord().isOwned() ? ownerRecord().owner() : null;
    }

    /** Claims {@code wanted} (or a fresh name) for this primary in {@link NetworkNames}. */
    private String claimName(ServerLevel server, String wanted) {
        return NetworkNames.get(server.getServer()).claim(wanted, server.dimension(), worldPosition.asLong(),
                ownerUuid());
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        // A rule on another network that names this one finds its network graph again.
        NetworkNames.changed();
        if (level != null && !level.isClientSide() && isPrimary()) {
            NetworkState state = network instanceof NetworkState joined ? joined : null;
            alarms.networkChanged(state);
            recorders.networkChanged(state);
        }
    }

    /** The built-in telemetry history, ledger and redstone alarm rules (M7R item 3); a member's stay empty. */
    public ControllerRecorders recorders() {
        return recorders;
    }

    /**
     * The recorders' saved state changed (a ledger row, a rule): marks the chunk unsaved without {@code setChanged}'s
     * neighbour updates, since the ledger changes with every recorded move.
     */
    public void recordsChanged() {
        Level current = level;
        if (current != null) {
            current.blockEntityChanged(worldPosition);
        }
    }

    /**
     * Whether the network's redstone alarm rules hold one; the controller's blocks give 15 on every side then (the
     * primary and its active members; a dark block gives none).
     */
    public int signal() {
        if (darkPart && !isPrimary()) {
            return 0;
        }
        ControllerBlockEntity p = primary();
        return p != null && p.recorders.output() ? 15 : 0;
    }

    /** The rules' signal changed: the neighbours of the primary and its loaded active members read it again. */
    public void signalChanged() {
        if (!(level instanceof ServerLevel server) || breaking) {
            return;
        }
        Block block = getBlockState().getBlock();
        server.updateNeighborsAt(worldPosition, block);
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos at = BlockPos.of(blocks.getLong(i));
            if (server.isLoaded(at)) {
                server.updateNeighborsAt(at, block);
            }
        }
    }

    /** The network's alarm panel (M7C item 16). Server side; empty on the client. */
    public AlarmCenter alarms() {
        return alarms;
    }

    /** The network's recovery bin (M7C item 20). Server side; empty on the client. */
    public RecoveryBin recovery() {
        return recovery;
    }

    /**
     * Puts the recovery bin's row {@code id} back into {@code network} for {@code player} (M7C item 20); the caller has
     * checked the player may. Returns what moved.
     */
    public long recover(int id, NetworkState network, Player player) {
        if (!(level instanceof ServerLevel server)) {
            return 0;
        }
        long moved = recovery.recover(id, network, player, server.getGameTime());
        if (moved > 0) {
            setChanged();
        }
        return moved;
    }

    /** Keeps what void overflow or a destroyed cell lost in the recovery bin; marks the block changed once per save. */
    @Override
    public void voided(StackKey key, long amount) {
        if (level instanceof ServerLevel server && recovery.add(key, amount, server.getGameTime())) {
            setChanged();
        }
    }

    @Override
    public void setOwner(OwnerRecord owner) {
        super.setOwner(owner);
        if (level instanceof ServerLevel server && !networkName.isEmpty() && isPrimary()) {
            networkName = claimName(server, networkName);
            NetworkNames.changed();
        }
    }

    @Override
    public CraftLimits craftLimits() {
        return craftLimits;
    }

    /**
     * Replaces the network's limits for automated crafting jobs; a lower number of CPUs kept free may let a waiting job
     * start at once, and logic reading the free CPUs for automation hears it. Server side; the caller checks the player
     * may configure the controller.
     */
    public void setCraftLimits(CraftLimits limits) {
        if (limits.equals(craftLimits)) {
            return;
        }
        craftLimits = limits;
        setChanged();
        if (level instanceof ServerLevel server) {
            JobQueue.limitsChanged(server);
        }
    }

    @Override
    public @Nullable AccessLevel securityLevel(Player player) {
        if (!isPrimary() || !secured()) {
            return null;
        }
        return security.levelFor(player, ownerRecord());
    }

    /**
     * Every few seconds while the network ticks: picks up config changes and refreshes the recorders.
     */
    @Override
    public void refreshLoad(NetworkState network) {
        stats = null;
        if (!(level instanceof ServerLevel server) || !isPrimary() || isRemoved()) {
            return;
        }
        alarms.loadRefresh(server.getGameTime());
        buffer.refreshCapacity();
        recorders.refresh(network);
    }

    /**
     * Finds the multiblock now rather than on the next tick (tests and diagnostics). Server side; does nothing on a
     * client.
     */
    public void detectNow() {
        if (level instanceof ServerLevel server) {
            detectCluster(server);
        }
    }

    /**
     * Asks the primary for a fill on its next tick; several asks in one tick cost one fill. A member whose primary is
     * not loaded checks itself on its own next tick. Server side.
     */
    public void requestDetect() {
        if (!(level instanceof ServerLevel server) || isRemoved()) {
            return;
        }
        ControllerBlockEntity target = isPrimary() ? this : primary();
        if (target == null) {
            target = this;
        }
        Block block = target.getBlockState().getBlock();
        if (!server.getBlockTicks().hasScheduledTick(target.worldPosition, block)) {
            server.scheduleTick(target.worldPosition, block, 1);
        }
    }

    /** Schedules this block's own tick (a member checking its primary). */
    private void scheduleOwnTick(ServerLevel server) {
        Block block = getBlockState().getBlock();
        if (!server.getBlockTicks().hasScheduledTick(worldPosition, block)) {
            server.scheduleTick(worldPosition, block, 1);
        }
    }

    /**
     * The block's scheduled tick. A primary finds its multiblock: a breadth-first fill over controller blocks touching
     * it or each other, face to face, in loaded chunks, nearest first, up to {@code controller.size.max} blocks; the
     * first blocks up to the size cap are active, the rest dark. A known member in an unloaded chunk stays a member.
     * Meeting a block of another multiblock placed later (or a lone block) takes that one over; meeting one placed
     * earlier leaves it to that one's fill. Nothing else attaches: network devices (M7R item 4) are network members, so
     * a fill drops those an old save still lists. A member checks that its primary still claims it.
     */
    void detectCluster(ServerLevel server) {
        if (isRemoved()) {
            return;
        }
        if (!isPrimary()) {
            checkPrimary(server);
            return;
        }
        redetect = false;
        long self = worldPosition.asLong();
        ClusterStats.Size size = ClusterStats.Size.fromConfig();
        int max = Math.max(1, size.max());
        int newCap = researchCap(server, size);
        LongArrayList found = new LongArrayList();
        LongArrayList foundDark = new LongArrayList();
        LongArrayList modules = new LongArrayList();
        IntArrayList moduleKinds = new IntArrayList();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongOpenHashSet controllers = new LongOpenHashSet();
        LongOpenHashSet absorbed = new LongOpenHashSet();
        // Card-kind expansion blocks met on the way: they become cards once the slots are known.
        LongArrayList toCards = new LongArrayList();
        // Old ledger and alarm modules met on the way that still hold rows or settings (M7R item 3).
        LongArrayList toRecords = new LongArrayList();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(self);
        controllers.add(self);
        queue.enqueue(self);
        boolean overCap = false;
        boolean keepModules = !standbyNow();
        int blockCount = 1;
        ControllerBlockEntity yieldTo = null;
        while (!queue.isEmpty()) {
            long pos = queue.dequeueLong();
            boolean fromController = controllers.contains(pos);
            for (Direction side : SIDES) {
                long next = BlockPos.offset(pos, side);
                if (seen.contains(next)) {
                    continue;
                }
                BlockPos at = BlockPos.of(next);
                if (!server.isLoaded(at)) {
                    // A known member whose chunk is not loaded stays where it was.
                    if (fromController && claims(next) && blockCount < max) {
                        seen.add(next);
                        blockCount++;
                        (darkBlocks.contains(next) ? foundDark : found).add(next);
                    }
                    continue;
                }
                BlockEntity entity = server.getBlockEntity(at);
                if (entity instanceof ControllerBlockEntity block) {
                    if (!fromController) {
                        // Controller blocks join face to face only.
                        continue;
                    }
                    ControllerBlockEntity owner = block.primary();
                    if (block.isPrimary() || owner != null) {
                        ControllerBlockEntity other = block.isPrimary() ? block : owner;
                        if (other != null && other != this && !absorbed.contains(other.worldPosition.asLong())) {
                            if (other.placedBefore(this)) {
                                // The other multiblock is older: it takes this one over in its own fill.
                                if (yieldTo == null || other.placedBefore(yieldTo)) {
                                    yieldTo = other;
                                }
                                continue;
                            }
                            absorbed.add(other.worldPosition.asLong());
                        }
                    } else if (!server.isLoaded(BlockPos.of(block.primaryPos))) {
                        // A member of a multiblock whose primary is not loaded: not this fill's to take.
                        continue;
                    }
                    seen.add(next);
                    if (blockCount >= max) {
                        overCap = true;
                        continue;
                    }
                    blockCount++;
                    controllers.add(next);
                    if (blockCount <= newCap) {
                        found.add(next);
                    } else {
                        overCap = true;
                        foundDark.add(next);
                    }
                    queue.enqueue(next);
                } else if (entity instanceof ExpansionBlockEntity expansion) {
                    ModuleKind kind = expansion.kind();
                    if (kind.card() && !foreign(server, entity, absorbed)) {
                        seen.add(next);
                        toCards.add(next);
                        continue;
                    }
                    if (kind.builtIn() && keepModules && expansion.holdsRecords()
                            && !foreign(server, entity, absorbed)) {
                        seen.add(next);
                        toRecords.add(next);
                    }
                    // Nothing else attaches (M7R items 1 to 5): retired kinds stay inert, and the network devices
                    // are network members with block entities of their own, so a fill drops whatever an old save
                    // still lists.
                }
            }
        }
        apply(server, found, foundDark, modules, moduleKinds, newCap, overCap);
        convertExpansions(server, toCards);
        absorbRecords(server, toRecords);
        if (yieldTo != null) {
            yieldTo.requestDetect();
        }
    }

    /**
     * Whether this block was placed before {@code other} (then the lower position): the older one keeps its state. A
     * block whose onLoad has not run yet counts as the newest.
     */
    private boolean placedBefore(ControllerBlockEntity other) {
        long mine = placedAt == UNPLACED ? Long.MAX_VALUE : placedAt;
        long theirs = other.placedAt == UNPLACED ? Long.MAX_VALUE : other.placedAt;
        if (mine != theirs) {
            return mine < theirs;
        }
        return worldPosition.asLong() < other.worldPosition.asLong();
    }

    /**
     * A member's tick: when its primary's chunk is loaded and the primary does not claim it (yet), the primary looks
     * again; when there is no primary there any more, this block becomes a controller of its own and finds its
     * multiblock.
     */
    private void checkPrimary(ServerLevel server) {
        BlockPos at = BlockPos.of(primaryPos);
        if (!server.isLoaded(at)) {
            return;
        }
        ControllerBlockEntity p = primary();
        if (p != null) {
            if (!p.claims(worldPosition.asLong())) {
                p.requestDetect();
            }
            return;
        }
        becomePrimary(server);
        detectCluster(server);
    }

    /**
     * Whether the fill meeting the module {@code entity} finds it attached to another working controller: one that is
     * not on standby and not being taken over in this fill. Next to the multiblock such a module is passed over; the
     * cluster preview (M7C item 22) uses it to leave out cells where a new module would not attach. Server thread.
     */
    public boolean foreign(ServerLevel server, @Nullable BlockEntity entity) {
        return foreign(server, entity, new LongOpenHashSet());
    }

    private boolean foreign(ServerLevel server, @Nullable BlockEntity entity, LongOpenHashSet absorbed) {
        if (entity instanceof ControllerBlockEntity other) {
            ControllerBlockEntity p = other.primary();
            return p != null && p != this && !p.standby && !absorbed.contains(p.worldPosition.asLong());
        }
        if (entity instanceof ExpansionBlockEntity expansion) {
            long owner = expansion.core();
            return owner != ExpansionBlockEntity.NO_CORE && owner != worldPosition.asLong() && !absorbed.contains(owner)
                    && runsCluster(server, owner);
        }
        return false;
    }

    /** The attached modules as of the last fill, in fill order ({@link BlockPos#asLong()}); do not change it. */
    public LongList members() {
        return LongLists.unmodifiable(members);
    }

    /** The loaded primaries in {@code level} (a new list). Server thread. */
    public static List<ControllerBlockEntity> loadedIn(ServerLevel level) {
        List<ControllerBlockEntity> found = new ArrayList<>();
        for (ControllerBlockEntity core : LOADED) {
            if (level.equals(core.level) && !core.isRemoved() && core.isPrimary()) {
                found.add(core);
            }
        }
        return found;
    }

    /** Whether a working primary sits at {@code pos}; one in an unloaded chunk counts as working. */
    private boolean runsCluster(ServerLevel server, long pos) {
        BlockPos at = BlockPos.of(pos);
        if (!server.isLoaded(at)) {
            return true;
        }
        return server.getBlockEntity(at) instanceof ControllerBlockEntity other && other != this && other.isPrimary()
                && !other.standby;
    }

    private void apply(ServerLevel server, LongArrayList found, LongArrayList foundDark, LongArrayList modules,
            IntArrayList moduleKinds, int newCap, boolean overCap) {
        long self = worldPosition.asLong();
        LongOpenHashSet taken = new LongOpenHashSet(found);
        taken.addAll(foundDark);
        // Blocks that left (cut off, or over the size maximum) become controllers of their own.
        List<ControllerBlockEntity> released = new ArrayList<>();
        collectReleased(server, blocks, taken, self, released);
        collectReleased(server, darkBlocks, taken, self, released);
        // Blocks that joined; primaries among them (a younger multiblock or a lone block) hand over afterwards.
        List<ControllerBlockEntity> merged = new ArrayList<>();
        join(server, found, false, merged);
        join(server, foundDark, true, merged);
        spreadGlowCentre(server, found, foundDark);
        // Cards of merged controllers, and where each came from; they go in once the slots are known.
        List<ItemStack> incoming = new ArrayList<>();
        LongArrayList incomingAt = new LongArrayList();
        for (ControllerBlockEntity other : merged) {
            other.mergeInto(server, this, taken, incoming, incomingAt);
        }
        // Modules: those that left go dark, the rest light up and remember this primary.
        LongOpenHashSet keptModules = new LongOpenHashSet(modules);
        for (int i = 0; i < members.size(); i++) {
            long pos = members.getLong(i);
            if (!keptModules.contains(pos)) {
                release(server, pos, self);
            }
        }
        for (int i = 0; i < modules.size(); i++) {
            BlockPos pos = BlockPos.of(modules.getLong(i));
            if (server.getBlockEntity(pos) instanceof ExpansionBlockEntity expansion) {
                expansion.join(self);
            }
        }
        int newSlots = cardSlots(server, 1 + found.size());
        boolean changed = !blocks.equals(found) || !darkBlocks.equals(foundDark) || !members.equals(modules)
                || !kinds.equals(moduleKinds) || cap != newCap || over != overCap || !merged.isEmpty()
                || cards.usable() != newSlots;
        blocks.clear();
        blocks.addAll(found);
        darkBlocks.clear();
        darkBlocks.addAll(foundDark);
        members.clear();
        members.addAll(modules);
        kinds.clear();
        kinds.addAll(moduleKinds);
        cap = newCap;
        over = overCap;
        cards.setUsable(newSlots);
        long dropAt = cardDrop == NO_PRIMARY ? self : cardDrop;
        cardDrop = NO_PRIMARY;
        // Cards in slots the smaller multiblock no longer has move to free slots or drop where it lost the block.
        ClusterStats.Cards limits = ClusterStats.Cards.fromConfig();
        cards.rehome(limits, stack -> drop(server, dropAt, stack));
        // A merged controller's cards take free slots within each kind's limit; the rest drop where it was.
        for (int i = 0; i < incoming.size(); i++) {
            ItemStack left = cards.insert(incoming.get(i), limits);
            if (!left.isEmpty()) {
                drop(server, incomingAt.getLong(i), left);
            }
        }
        recount();
        pullEnergy(server);
        moveBankEnergy(server);
        if (changed) {
            setChanged();
            NetworkState current = network();
            // A standby controller gives the network nothing, so it needs no new routes.
            if (current != null && !current.isStandby(this)) {
                current.clusterChanged();
            }
            // A security module joined or left: rules on other networks that this one's access decides check again.
            NetworkNames.changed();
        }
        for (ControllerBlockEntity block : released) {
            block.requestDetect();
        }
        refreshStatus();
        showMemberStatus(desiredStatus());
        if (changed && recorders.output()) {
            // Blocks that joined give the rules' signal too.
            signalChanged();
        }
    }

    /** Adds to {@code out} the loaded blocks of {@code old} that are not {@code taken} and leaves them on their own. */
    private static void collectReleased(ServerLevel server, LongList old, LongOpenHashSet taken, long self,
            List<ControllerBlockEntity> out) {
        for (int i = 0; i < old.size(); i++) {
            long pos = old.getLong(i);
            BlockPos at = BlockPos.of(pos);
            // Still this primary's, or pointing at a primary that is really gone (its position loaded, no primary
            // there): either way it is on its own now. A primary that is only unloaded is unknown, not gone
            // (networks.unloadedChunkMode), so its member stays one.
            if (!taken.contains(pos) && server.isLoaded(at)
                    && server.getBlockEntity(at) instanceof ControllerBlockEntity block && !block.isRemoved()
                    && !block.isPrimary() && (block.primaryPos == self
                            || (server.isLoaded(BlockPos.of(block.primaryPos)) && block.primary() == null))) {
                block.becomePrimary(server);
                out.add(block);
            }
        }
    }

    /**
     * Makes the loaded blocks at {@code positions} members of this primary; primaries among them go to {@code merged}.
     */
    private void join(ServerLevel server, LongList positions, boolean dark, List<ControllerBlockEntity> merged) {
        long self = worldPosition.asLong();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos at = BlockPos.of(positions.getLong(i));
            if (!server.isLoaded(at) || !(server.getBlockEntity(at) instanceof ControllerBlockEntity block)
                    || block == this) {
                continue;
            }
            if (block.isPrimary()) {
                merged.add(block);
                NetworkState current = block.network();
                if (current != null) {
                    // It joins again as a cluster block at the next rebuild.
                    current.roleChanging(block);
                }
                block.primaryPos = self;
                block.darkPart = dark;
                block.setChanged();
            } else if (block.primaryPos != self || block.darkPart != dark) {
                block.primaryPos = self;
                block.darkPart = dark;
                block.setChanged();
            }
        }
    }

    /**
     * Tells this primary and every loaded block of its multiblock (active and dark) where the multiblock's centre lies,
     * for the face's glow phase; a block whose offset changed re-sends its data so the client re-meshes it. Runs only
     * with a fill.
     */
    private void spreadGlowCentre(ServerLevel server, LongList active, LongList dark) {
        int count = active.size() + dark.size() + 1;
        long x = worldPosition.getX();
        long y = worldPosition.getY();
        long z = worldPosition.getZ();
        for (int i = 0; i < count - 1; i++) {
            long pos = i < active.size() ? active.getLong(i) : dark.getLong(i - active.size());
            x += BlockPos.getX(pos);
            y += BlockPos.getY(pos);
            z += BlockPos.getZ(pos);
        }
        setGlowCentre(glowCentreOf(x, y, z, count, worldPosition));
        for (int i = 0; i < count - 1; i++) {
            long pos = i < active.size() ? active.getLong(i) : dark.getLong(i - active.size());
            BlockPos at = BlockPos.of(pos);
            if (server.isLoaded(at) && server.getBlockEntity(at) instanceof ControllerBlockEntity block) {
                block.setGlowCentre(glowCentreOf(x, y, z, count, at));
            }
        }
    }

    private static int glowCentreOf(long x, long y, long z, int count, BlockPos at) {
        return ControllerFace.packCentre(halfBlocks(x, count, at.getX()), halfBlocks(y, count, at.getY()),
                halfBlocks(z, count, at.getZ()));
    }

    /** The mean of {@code sum / count} minus {@code own}, in half blocks, rounded. */
    private static int halfBlocks(long sum, int count, int own) {
        return (int) Math.round(2.0 * ((double) sum / count - own));
    }

    private void setGlowCentre(int packed) {
        if (glowCentre != packed) {
            glowCentre = packed;
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** The packed offset to the multiblock's centre ({@link ControllerFace#packCentre}); for the model and tests. */
    public int glowCentre() {
        return glowCentre;
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, read by the controller's face model on the client.
    @Override
    public ModelData getModelData() {
        return ModelData.of(GLOW_CENTRE, glowCentre);
    }

    /** Clients get only the glow centre; everything else stays on the server. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(GLOW_CENTRE_KEY, glowCentre);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hooks; the default would run loadSaved on the partial tag.
    @Override
    public void handleUpdateTag(ValueInput input) {
        readGlowCentre(input);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        readGlowCentre(input);
    }

    private void readGlowCentre(ValueInput input) {
        int packed = input.getIntOr(GLOW_CENTRE_KEY, 0);
        if (packed != glowCentre) {
            glowCentre = packed;
            if (level != null && level.isClientSide()) {
                requestModelDataUpdate();
                // The server's block update carries an unchanged state, which re-meshes nothing on its own.
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
            }
        }
    }

    /**
     * This former primary was taken over by {@code winner} in a merge: its FE stays in its own buffer until the winner
     * pulls it in, its recovery rows go to the winner, its modules go dark (the winner's fill takes those it reaches)
     * and its blocks that the winner did not take become controllers of their own. Its name, settings, alarms and
     * network boards go, like a broken controller's.
     */
    private void mergeInto(ServerLevel server, ControllerBlockEntity winner, LongOpenHashSet taken,
            List<ItemStack> incoming, LongArrayList incomingAt) {
        long self = worldPosition.asLong();
        // Its cards go to the winner's fill, which installs them within the limits or drops them here.
        cards.takeAll(stack -> {
            incoming.add(stack);
            incomingAt.add(self);
        });
        // Its ledger rows and alarm rules go to the winner; its history ends.
        recorders.mergeInto(winner.recorders);
        List<RecoveryBin.Row> rows = recovery.saved();
        if (!rows.isEmpty()) {
            List<RecoveryBin.Row> all = new ArrayList<>(winner.recovery.saved());
            all.addAll(rows);
            all.sort(Comparator.comparingLong(RecoveryBin.Row::created));
            winner.recovery.load(all);
            winner.setChanged();
            recovery.load(List.of());
        }
        if (!keptRecovery.isEmpty()) {
            List<Tag> kept = new ArrayList<>(winner.keptRecovery);
            kept.addAll(keptRecovery);
            winner.keptRecovery = kept;
            winner.setChanged();
            keptRecovery = List.of();
        }
        for (int i = 0; i < members.size(); i++) {
            long pos = members.getLong(i);
            if (!taken.contains(pos)) {
                release(server, pos, self);
            }
        }
        List<ControllerBlockEntity> released = new ArrayList<>();
        collectReleased(server, blocks, taken, self, released);
        collectReleased(server, darkBlocks, taken, self, released);
        for (ControllerBlockEntity block : released) {
            block.requestDetect();
        }
        dropNetworkState(server);
        blocks.clear();
        darkBlocks.clear();
        members.clear();
        kinds.clear();
        over = false;
        recount();
        setChanged();
    }

    /** Releases the name, task boards, anchor and settings this block held as a primary. */
    private void dropNetworkState(ServerLevel server) {
        long self = worldPosition.asLong();
        if (!networkName.isEmpty()) {
            NetworkNames.get(server.getServer()).release(networkName, server.dimension(), self);
            NetworkNames.changed();
        }
        TaskBoards.controllerBroken(server.getServer(), server.dimension().identifier().toString(), self);
        networkName = "";
        security = SecuritySettings.DEFAULT;
        craftLimits = CraftLimits.DEFAULT;
        alarms.load(List.of());
        int before = anchorHeld;
        AnchorChunks.release(server, worldPosition);
        anchorHeld = 0;
        anchorWanted = 0;
        wakeCappedAnchors(before);
    }

    /**
     * This member becomes a controller of its own, with nothing but its own FE and a fresh name: its primary let it go
     * (cut off or over the size maximum) or is gone. Its next fill finds what it touches.
     */
    private void becomePrimary(ServerLevel server) {
        if (isPrimary()) {
            return;
        }
        NetworkState current = network();
        if (current != null) {
            // It joins again as a controller at the next rebuild.
            current.roleChanging(this);
        }
        primaryPos = NO_PRIMARY;
        darkPart = false;
        blocks.clear();
        darkBlocks.clear();
        members.clear();
        kinds.clear();
        over = false;
        networkName = claimName(server, "");
        NetworkNames.changed();
        recorders.clear();
        recount();
        setChanged();
        alarms.start();
        recorders.start();
    }

    /**
     * Moves the FE in the members' own buffers, active or dark (from an item they were placed from, or a merged
     * controller's buffer), into this primary's, as far as it has room; what does not fit stays in the member.
     */
    private void pullEnergy(ServerLevel server) {
        pullEnergy(server, blocks);
        // Dark blocks add no capacity, but the FE they hold is not frozen.
        pullEnergy(server, darkBlocks);
    }

    private void pullEnergy(ServerLevel server, LongList from) {
        for (int i = 0; i < from.size(); i++) {
            BlockPos at = BlockPos.of(from.getLong(i));
            if (!server.isLoaded(at) || !(server.getBlockEntity(at) instanceof ControllerBlockEntity block)) {
                continue;
            }
            long held = block.buffer.stored();
            long room = Math.max(0, buffer.capacity() - buffer.stored());
            long moved = Math.min(held, room);
            if (moved > 0) {
                block.buffer.load(held - moved);
                block.setChanged();
                buffer.load(buffer.stored() + moved);
                setChanged();
            }
        }
    }

    /**
     * Moves the FE this primary holds above its capacity into the energy cells that replaced its capacitor banks (M7R
     * item 5), each up to its room; the rest stays here, above the capacity, and is used first. A position whose chunk
     * is not loaded is tried at the next fill; one that holds no energy cell any more is dropped. Runs once per
     * migrated save, so the FE moves exactly once.
     */
    private void moveBankEnergy(ServerLevel server) {
        if (bankCells.isEmpty()) {
            return;
        }
        for (int i = bankCells.size() - 1; i >= 0; i--) {
            BlockPos at = BlockPos.of(bankCells.getLong(i));
            if (!server.isLoaded(at)) {
                continue;
            }
            if (server.getBlockEntity(at) instanceof EnergyCellBlockEntity cell && !cell.isRemoved()) {
                long over = buffer.stored() - buffer.capacity();
                long moved = over > 0 ? cell.cellStore().receive(over) : 0;
                if (moved > 0) {
                    buffer.load(buffer.stored() - moved);
                }
            }
            bankCells.removeLong(i);
            setChanged();
        }
    }

    /** The module at {@code pos} leaves this controller and goes dark, if it is still this controller's. */
    private static void release(ServerLevel server, long pos, long self) {
        BlockPos at = BlockPos.of(pos);
        if (server.isLoaded(at) && server.getBlockEntity(at) instanceof ExpansionBlockEntity expansion
                && expansion.core() == self) {
            expansion.leave();
        }
    }

    /** Exits and stats from the blocks (and the modules an old save still lists, which add nothing). */
    private void recount() {
        int[] newCounts = new int[ModuleKind.count()];
        LongArrayList newExits = new LongArrayList();
        newExits.add(worldPosition.asLong());
        newExits.addAll(blocks);
        for (int i = 0; i < kinds.size(); i++) {
            ModuleKind kind = ModuleKind.byIndex(kinds.getInt(i));
            if (kind != null) {
                newCounts[kind.index()]++;
            }
        }
        counts = newCounts;
        exits = LongLists.unmodifiable(newExits);
        stats = null;
        buffer.refreshCapacity();
        recorders.refreshTelemetry();
    }

    @Override
    public @Nullable NetworkHistory history() {
        return isPrimary() ? recorders.telemetry() : null;
    }

    /** The built-in telemetry history, or null while none is recorded; for tests and info lines. */
    public @Nullable TelemetryRecorder telemetry() {
        return recorders.telemetry();
    }

    /** The size cap the owner's research allows (unowned: every step, as for research checks). */
    private int researchCap(ServerLevel server, ClusterStats.Size size) {
        return size.cap(ResearchUnlocks.isUnlocked(server, ownerRecord(), ResearchGates.CONTROLLER_SIZE_1),
                ResearchUnlocks.isUnlocked(server, ownerRecord(), ResearchGates.CONTROLLER_SIZE_2),
                ResearchUnlocks.isUnlocked(server, ownerRecord(), ResearchGates.CONTROLLER_SIZE_3));
    }

    /**
     * Has every loaded primary whose anchor holds or wants chunks, and for which {@code test} holds, work its chunks
     * out again on its next tick (a config reload, an owner moving to another team). Server thread.
     */
    static void recheckAnchors(Predicate<ControllerBlockEntity> test) {
        ControllerBlockEntity[] cores = LOADED.toArray(new ControllerBlockEntity[0]);
        for (ControllerBlockEntity core : cores) {
            if (!core.isRemoved() && core.isPrimary() && (core.anchorHeld > 0 || core.anchored()) && test.test(core)) {
                core.markAnchorDirty();
            }
        }
    }

    /** Asks every loaded primary for which {@code test} holds to find its multiblock again (research changed). */
    static void redetectLoaded(Predicate<ControllerBlockEntity> test) {
        ControllerBlockEntity[] cores = LOADED.toArray(new ControllerBlockEntity[0]);
        for (ControllerBlockEntity core : cores) {
            if (!core.isRemoved() && core.isPrimary() && test.test(core)) {
                core.requestDetect();
            }
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        if (level instanceof ServerLevel server) {
            buffer.refreshCapacity();
            LOADED.add(this);
            if (placedAt == UNPLACED) {
                placedAt = server.getGameTime();
                setChanged();
                // Placed next to a multiblock: a member from the start (dark until the primary's fill counts it), so
                // it never joins the network as a second controller. Not when an older fill already took it.
                if (isPrimary()) {
                    joinNeighbour(server);
                }
            }
            if (isPrimary()) {
                String claimed = claimName(server, networkName);
                if (!claimed.equals(networkName)) {
                    networkName = claimed;
                    setChanged();
                }
                NetworkNames.changed();
                alarms.start();
                recorders.start();
                graphAnchored = false;
                if (!AnchorChunks.held(server, worldPosition).isEmpty()) {
                    // Loaded with chunks the loader kept for its anchor (a restart): hold on to every chunk for a
                    // while, then settle. The anchors join the network only once their chunks load.
                    loadedAt = server.getGameTime();
                    anchorDirty = false;
                    markAnchorDirty();
                }
                if (redetect) {
                    requestDetect();
                }
            } else {
                // A member checks once that its primary still claims it.
                scheduleOwnTick(server);
            }
        }
        super.onLoad();
    }

    /**
     * A block placed next to controller blocks becomes a dark member of the oldest loaded multiblock it touches, when
     * that one is older than this block, and asks that one's primary to find its blocks again. Blocks placed in the
     * same tick sort by position; the merge in the fill settles them.
     */
    private void joinNeighbour(ServerLevel server) {
        ControllerBlockEntity best = null;
        for (Direction side : SIDES) {
            BlockPos next = worldPosition.relative(side);
            if (server.isLoaded(next) && server.getBlockEntity(next) instanceof ControllerBlockEntity block
                    && !block.isRemoved()) {
                ControllerBlockEntity p = block.primary();
                if (p != null && p != this && (best == null || p.placedBefore(best))) {
                    best = p;
                }
            }
        }
        if (best != null && best.placedBefore(this)) {
            primaryPos = best.worldPosition.asLong();
            darkPart = true;
            stats = null;
            best.requestDetect();
        }
    }

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        super.setRemoved();
        if (level instanceof ServerLevel) {
            // Unloaded or broken: rules naming this network see it as unavailable (or gone) from now on.
            NetworkNames.changed();
        }
    }

    /**
     * Broken (not unloaded). A member asks its primary to find the multiblock again; its FE stays in the primary. A
     * primary hands its state to the nearest loaded member, which then finds the multiblock; without one, the item
     * keeps the FE, the name and the settings, and the modules go dark.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        breaking = true;
        if (!isPrimary()) {
            ControllerBlockEntity p = primary();
            if (p != null) {
                // Cards that lose their slot with this block drop where it was.
                p.cardDrop = worldPosition.asLong();
                p.requestDetect();
            }
            return;
        }
        long self = worldPosition.asLong();
        for (int i = 0; i < members.size(); i++) {
            release(server, members.getLong(i), self);
        }
        members.clear();
        kinds.clear();
        ControllerBlockEntity successor = successor(server, blocks);
        if (successor == null) {
            successor = successor(server, darkBlocks);
        }
        if (successor != null) {
            handOver(server, successor);
        } else {
            // Broken, not unloaded: the name is free again (the dropped item keeps it for when it is placed again).
            NetworkNames.get(server.getServer()).release(networkName, server.dimension(), self);
            // The network's own task boards go with it (M7C item 15); a controller placed elsewhere starts without.
            TaskBoards.controllerBroken(server.getServer(), server.dimension().identifier().toString(), self);
            // The last block: its cards drop with it.
            cards.takeAll(stack -> drop(server, self, stack));
            recorders.stop();
        }
        blocks.clear();
        darkBlocks.clear();
        // A broken controller keeps nothing loaded.
        int before = anchorHeld;
        AnchorChunks.release(server, worldPosition);
        anchorHeld = 0;
        wakeCappedAnchors(before);
    }

    /**
     * Whether breaking this block hands the network's state to another block of the multiblock (a primary with a loaded
     * member), so its item must carry none of it. Vanilla computes drops before the removal on some paths
     * ({@code Level.destroyBlock}) and after it on others (a player's break), so the item data asks this instead of
     * relying on {@link #preRemoveSideEffects} having run. Server side.
     */
    public boolean handsOver() {
        return isPrimary() && level instanceof ServerLevel server
                && (successor(server, blocks) != null || successor(server, darkBlocks) != null);
    }

    /** The first loaded block of {@code positions} that is still this primary's member, or null. */
    private @Nullable ControllerBlockEntity successor(ServerLevel server, LongList positions) {
        long self = worldPosition.asLong();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos at = BlockPos.of(positions.getLong(i));
            if (server.isLoaded(at) && server.getBlockEntity(at) instanceof ControllerBlockEntity block && block != this
                    && block.primaryPos == self && !block.isRemoved()) {
                return block;
            }
        }
        return null;
    }

    /**
     * Hands this broken primary's whole state to {@code successor}: FE, placement time (so the network keeps its
     * controller), owner, name, settings, alarms, recovery rows and network boards. This block keeps only nothing, so
     * its item is a plain one. The successor then finds the multiblock; the other members point to this position until
     * then, which its fill takes as a primary that is gone.
     */
    private void handOver(ServerLevel server, ControllerBlockEntity successor) {
        long self = worldPosition.asLong();
        long to = successor.worldPosition.asLong();
        NetworkState current = successor.network();
        if (current != null) {
            current.roleChanging(successor);
        }
        successor.primaryPos = NO_PRIMARY;
        successor.darkPart = false;
        successor.buffer.load(successor.buffer.stored() + buffer.stored());
        buffer.load(0);
        successor.sheddingArmed = sheddingArmed;
        successor.placedAt = placedAt;
        successor.setOwner(ownerRecord());
        successor.security = security;
        successor.craftLimits = craftLimits;
        successor.alarms.load(List.copyOf(alarms.alarms()));
        successor.recovery.load(recovery.saved());
        successor.keptRecovery = keptRecovery;
        // The history, the ledger and the alarm rules go on in the successor.
        recorders.handOver(successor.recorders);
        // The cards move with the state, in the same slots while they work; the successor's fill finds slots for the
        // smaller multiblock and drops what does not fit where this block was.
        successor.cards.setUsable(cards.usable());
        for (int i = 0; i < cards.getContainerSize(); i++) {
            successor.cards.getItems().set(i, cards.getItem(i));
            cards.getItems().set(i, ItemStack.EMPTY);
        }
        successor.cardDrop = self;
        successor.blocks.clear();
        successor.darkBlocks.clear();
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.getLong(i) != to) {
                successor.blocks.add(blocks.getLong(i));
            }
        }
        for (int i = 0; i < darkBlocks.size(); i++) {
            if (darkBlocks.getLong(i) != to) {
                successor.darkBlocks.add(darkBlocks.getLong(i));
            }
        }
        // Every loaded member points to the successor now, so its fill keeps those it reaches and lets the others (a
        // group the break cut off) become a controller of their own; none is left pointing at this broken block.
        repoint(server, successor.blocks, self, to);
        repoint(server, successor.darkBlocks, self, to);
        NetworkNames names = NetworkNames.get(server.getServer());
        names.release(networkName, server.dimension(), self);
        successor.networkName = successor.claimName(server, networkName);
        NetworkNames.changed();
        String dimension = server.dimension().identifier().toString();
        TaskBoards.controllerMoved(server.getServer(), dimension, self, to);
        networkName = "";
        security = SecuritySettings.DEFAULT;
        craftLimits = CraftLimits.DEFAULT;
        recovery.load(List.of());
        keptRecovery = List.of();
        alarms.load(List.of());
        successor.recount();
        successor.setChanged();
        successor.alarms.start();
        successor.recorders.start();
        successor.requestDetect();
    }

    private static void repoint(ServerLevel server, LongList positions, long from, long to) {
        for (int i = 0; i < positions.size(); i++) {
            BlockPos at = BlockPos.of(positions.getLong(i));
            if (server.isLoaded(at) && server.getBlockEntity(at) instanceof ControllerBlockEntity block
                    && block.primaryPos == from) {
                block.primaryPos = to;
                block.setChanged();
            }
        }
    }

    /**
     * At a glance: the channels in use against what the root links carry, as a bar; a dark block or a standby
     * controller says so instead. The energy bar comes from Lib's entries for the controller's buffer.
     */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        if (!isPrimary() && darkPart) {
            ControllerBlockEntity p = primary();
            sink.line(Component.translatable(ControllerContent.DARK_KEY, InfoFormat.number(p == null ? 0 : p.cap)));
            return;
        }
        ControllerBlockEntity p = isPrimary() ? this : primary();
        if (p == null) {
            return;
        }
        if (p.standby) {
            sink.line(Component.translatable(ControllerContent.STANDBY_KEY));
            return;
        }
        NetworkState current = p.network();
        if (current != null) {
            NetworkInfo.channels(current.channelsHeld(), current.rootCapacity(), current.channelsInfinite(), sink);
        }
    }

    /** With details: which block is primary, then the primary's size, modules, anchor, recorders and security. */
    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        if (!isPrimary()) {
            ControllerBlockEntity p = primary();
            BlockPos at = BlockPos.of(primaryPos);
            if (!darkPart) {
                sink.line(Component.translatable(ControllerContent.MEMBER_KEY,
                        at.getX() + " " + at.getY() + " " + at.getZ()));
            }
            if (p != null && !darkPart) {
                p.addControllerInfo(request, sink);
            }
        } else if (!standby) {
            addControllerInfo(request, sink);
        }
    }

    /** The primary's own lines: size, modules, anchor, recorder, wireless range and security. */
    private void addControllerInfo(BlockInfoRequest request, BlockInfoSink sink) {
        sink.line(Component.translatable(ControllerContent.CLUSTER_KEY, InfoFormat.number(blockCount()),
                InfoFormat.number(cap)));
        if (over) {
            sink.line(Component.translatable(ControllerContent.OVER_CAP_KEY));
        }
        if (cards.usable() > 0) {
            sink.line(Component.translatable(CardContent.INFO_KEY, InfoFormat.number(cards.installed()),
                    InfoFormat.number(cards.usable())));
        }
        if (request.detail() == BlockInfoRequest.Detail.FULL) {
            ExpansionContent.addModuleInfo(stats(), sink);
        }
        if (anchored()) {
            sink.line(Component.translatable(
                    anchorHeld < anchorWanted ? ExpansionContent.ANCHOR_CAPPED_KEY : ExpansionContent.ANCHOR_KEY,
                    InfoFormat.number(anchorHeld), InfoFormat.number(anchorWanted),
                    InfoFormat.number(AnchorChunks.cap())));
        }
        TelemetryRecorder recording = recorders.telemetry();
        if (recording != null && request.detail() == BlockInfoRequest.Detail.FULL) {
            sink.line(Component.translatable(ExpansionContent.TELEMETRY_KEY, InfoFormat.number(recording.size()),
                    InfoFormat.number(recording.capacity()), InfoFormat.number(recording.sampleTicks() / 20)));
        }
        if (request.detail() == BlockInfoRequest.Detail.FULL && StorageServerConfig.RECORD_LEDGER.getAsBoolean()) {
            LedgerBook<StackKey> book = recorders.ledger().book();
            sink.line(Component.translatable(RecordTexts.LEDGER_INFO, InfoFormat.number(book.rows()),
                    InfoFormat.number(StorageServerConfig.LEDGER_MAX_ROWS.getAsInt()), InfoFormat.number(book.days())));
        }
        if (!recorders.rules().isEmpty()) {
            sink.line(Component.translatable(RecordTexts.ALARM_INFO, InfoFormat.number(recorders.rules().size()),
                    InfoFormat.number(recorders.holding())));
        }
        int wireless = WirelessCoverage.range(this);
        if (wireless > 0) {
            sink.line(Component.translatable(SignalContent.WIRELESS_RANGE_KEY, InfoFormat.number(wireless)));
        }
        if (secured()) {
            sink.line(Component.translatable(ExpansionContent.SECURITY_KEY,
                    InfoFormat.number(security.players().size() + security.teams().size())));
        }
    }

    /**
     * The block's item data ({@link dev.silencebound.maxtech.storage.network.StorageDeviceBlock} drops it): the FE in
     * this block's buffer, and the name and settings of a controller's only block. A primary that hands its state to
     * another block of the multiblock gives none of it, whether the drops are worked out before the removal
     * ({@code Level.destroyBlock}, explosions) or after it (a player's break, when {@link #handOver} has run).
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (handsOver()) {
            return;
        }
        if (buffer.stored() > 0) {
            components.set(ModDataComponents.CONTROLLER_ENERGY.get(), buffer.stored());
            components.set(ModDataComponents.ENERGY_IN_MU.get(), true);
        }
        if (!security.equals(SecuritySettings.DEFAULT)) {
            components.set(ModDataComponents.NETWORK_SECURITY.get(), security);
        }
        if (!craftLimits.equals(CraftLimits.DEFAULT)) {
            components.set(ModDataComponents.CRAFT_LIMITS.get(), craftLimits);
        }
        if (!networkName.isEmpty()) {
            components.set(ModDataComponents.NETWORK_NAME.get(), networkName);
        }
        if (!recorders.rules().isEmpty()) {
            components.set(ModDataComponents.ALARM_RULES.get(), recorders.ruleSettings());
        }
    }

    /**
     * Placed from an item: its FE goes into this block's buffer (a member's primary pulls it in at its next fill); the
     * name and settings apply only to a block that is its own primary.
     */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        if (components.get(ModDataComponents.CONTROLLER_ENERGY.get()) != null) {
            buffer.load(ItemEnergy.get(components, ModDataComponents.CONTROLLER_ENERGY.get()));
        }
        SecuritySettings rules = components.get(ModDataComponents.NETWORK_SECURITY.get());
        CraftLimits limits = components.get(ModDataComponents.CRAFT_LIMITS.get());
        String name = components.get(ModDataComponents.NETWORK_NAME.get());
        List<AlarmSettings> alarmRules = components.get(ModDataComponents.ALARM_RULES.get());
        if (!isPrimary()) {
            return;
        }
        if (alarmRules != null) {
            recorders.setRules(alarmRules);
        }
        if (rules != null) {
            security = rules;
        }
        if (limits != null) {
            craftLimits = limits;
        }
        if (name != null) {
            networkName = NetworkNames.clean(name);
            if (level instanceof ServerLevel server && !isRemoved()) {
                // Placed from a named item after onLoad claimed a fresh name: take the item's name if it is free.
                networkName = claimName(server, networkName);
                NetworkNames.changed();
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong(ENERGY, buffer.stored());
        output.putBoolean(SHEDDING_ARMED, sheddingArmed);
        if (!isPrimary()) {
            output.putLong(PRIMARY, primaryPos);
            output.putBoolean(DARK_PART, darkPart);
        }
        // ValueOutput has no long arrays; the codec writes a long array tag.
        output.store(BLOCKS, Codec.LONG_STREAM, LongStream.of(blocks.toLongArray()));
        output.store(DARK, Codec.LONG_STREAM, LongStream.of(darkBlocks.toLongArray()));
        output.store(MEMBERS, Codec.LONG_STREAM, LongStream.of(members.toLongArray()));
        output.putIntArray(KINDS, kinds.toIntArray());
        if (!bankCells.isEmpty()) {
            output.store(StorageSaveFormats.BANK_CELLS, Codec.LONG_STREAM, LongStream.of(bankCells.toLongArray()));
        }
        output.putBoolean(OVER, over);
        output.putInt(CAP, cap);
        if (redetect) {
            output.putBoolean(REDETECT, true);
        }
        if (placedAt != UNPLACED) {
            output.putLong(PLACED_AT, placedAt);
        }
        if (!security.equals(SecuritySettings.DEFAULT)) {
            output.store(SECURITY, SecuritySettings.CODEC, security);
        }
        if (!craftLimits.equals(CraftLimits.DEFAULT)) {
            output.store(CRAFT_LIMITS, CraftLimits.CODEC, craftLimits);
        }
        if (!networkName.isEmpty()) {
            output.putString(NETWORK_NAME, networkName);
        }
        if (!alarms.alarms().isEmpty()) {
            output.store(ALARMS, Alarm.CODEC.listOf(), List.copyOf(alarms.alarms()));
        }
        if (!recovery.isEmpty() || !keptRecovery.isEmpty()) {
            output.store(RECOVERY, RECOVERY_CODEC, KeptList.of(recovery.saved(), keptRecovery));
        }
        cards.save(output);
        recorders.save(output);
        if (cards.usable() > 0) {
            output.putInt(CARD_SLOTS, cards.usable());
        }
        if (glowCentre != 0) {
            output.putInt(GLOW_CENTRE_KEY, glowCentre);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        buffer.load(input.getLongOr(ENERGY, 0L));
        sheddingArmed = input.getBooleanOr(SHEDDING_ARMED, false);
        if (level == null) {
            // Only a chunk load keeps the place in a multiblock and the placement time; block data put on a placed
            // block (an item) does not.
            primaryPos = input.getLongOr(PRIMARY, NO_PRIMARY);
            darkPart = input.getBooleanOr(DARK_PART, false);
            placedAt = input.getLongOr(PLACED_AT, UNPLACED);
            glowCentre = input.getIntOr(GLOW_CENTRE_KEY, 0);
        }
        blocks.clear();
        darkBlocks.clear();
        members.clear();
        kinds.clear();
        bankCells.clear();
        if (isPrimary()) {
            bankCells.addElements(0, readLongs(input, StorageSaveFormats.BANK_CELLS));
            blocks.addElements(0, readLongs(input, BLOCKS));
            darkBlocks.addElements(0, readLongs(input, DARK));
            long[] savedMembers = readLongs(input, MEMBERS);
            int[] savedKinds = input.getIntArray(KINDS).orElse(new int[0]);
            for (int i = 0; i < savedMembers.length && i < savedKinds.length; i++) {
                // A deleted kind (the priority router and burst buffer, the status crown and light strip) or one
                // from a later version: drop the module, keep the rest. It held no items, so nothing is lost.
                // Retired kinds stay listed until the next fill lets them go.
                if (ModuleKind.byIndex(savedKinds[i]) == null) {
                    LOGGER.debug("Controller at {} drops a saved module of unknown kind {}", worldPosition,
                            savedKinds[i]);
                    continue;
                }
                members.add(savedMembers[i]);
                kinds.add(savedKinds[i]);
            }
        }
        over = input.getBooleanOr(OVER, false);
        cap = input.getIntOr(CAP, 0);
        redetect = input.getBooleanOr(REDETECT, false);
        security = input.read(SECURITY, SecuritySettings.CODEC).orElse(SecuritySettings.DEFAULT);
        craftLimits = input.read(CRAFT_LIMITS, CraftLimits.CODEC).orElse(CraftLimits.DEFAULT);
        networkName = NetworkNames.clean(input.getStringOr(NETWORK_NAME, ""));
        alarms.load(input.read(ALARMS, Alarm.CODEC.listOf()).orElse(List.of()));
        KeptList<RecoveryBin.Row> rows = input.read(RECOVERY, RECOVERY_CODEC).orElse(KeptList.of(List.of()));
        keptRecovery = rows.kept();
        recovery.load(rows.known());
        cards.load(input);
        cards.setUsable(input.getIntOr(CARD_SLOTS, 0));
        recorders.load(input);
        recount();
    }

    private static long[] readLongs(ValueInput input, String key) {
        return input.read(key, Codec.LONG_STREAM).map(LongStream::toArray).orElse(new long[0]);
    }

    /** Takes FE into {@link #inputTarget()}; gives none back. */
    private final class CoreInput implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return inputTarget().getAmountAsLong();
        }

        @Override
        public long getCapacityAsLong() {
            return inputTarget().getCapacityAsLong();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            int inserted = inputTarget().insert(amount, transaction);
            NetworkState network = network();
            if (network != null) {
                // The network's received FE (display panels), whichever store took it.
                network.countReceived(inserted, transaction);
            }
            return inserted;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return 0;
        }
    }

    /**
     * The controller's MU store, an endpoint of its network's energy network while the controller is in charge:
     * capacity and per-tick input from the multiblock's stats, the capacity refreshed on load, on each fill and on each
     * insert. The input per tick is shared by what the energy network's pass puts in (generators) and what FE inputs
     * insert: inserts get what the pass's flow leaves of it. The insert allowance counts every insert of the current
     * game tick, committed or not, so a rolled-back insert still uses it up; that only errs towards taking less.
     */
    private static final class TickLimitedBuffer extends EndpointBuffer {
        private final ControllerBlockEntity owner;
        private long tick = Long.MIN_VALUE;
        private long takenThisTick;

        TickLimitedBuffer(ControllerBlockEntity owner) {
            super(0, 0, 0, 0, 0, EnergyPriority.DEFAULT, owner::energyChanged);
            this.owner = owner;
        }

        private long now() {
            Level current = owner.level;
            return current == null ? 0 : current.getGameTime();
        }

        /** Takes the capacity from the stats, which follow config (not loaded at construction) and the multiblock. */
        void refreshCapacity() {
            ClusterStats stats = owner.stats();
            setCapacity(stats.bufferMu());
            setRates(stats.inputPerTick(), 0);
        }

        @Override
        public long getCapacityAsLong() {
            // Room checks (inserts and the owner's own receive) go through here, so a buffer used before onLoad
            // still has its capacity.
            refreshCapacity();
            return super.getCapacityAsLong();
        }

        @Override
        protected long insertLimit() {
            long now = now();
            if (now != tick) {
                tick = now;
                takenThisTick = 0;
            }
            return Math.max(0, owner.stats().inputPerTick() - takenThisTick - endpoint().received());
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            int inserted = super.insert(amount, transaction);
            takenThisTick += inserted;
            return inserted;
        }

        @Override
        protected long extractLimit() {
            return 0;
        }
    }
}
