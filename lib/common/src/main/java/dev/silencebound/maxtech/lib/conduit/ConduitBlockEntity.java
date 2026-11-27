package dev.silencebound.maxtech.lib.conduit;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceModes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitState;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.lib.api.network.NodeConnectivity;
import dev.silencebound.maxtech.lib.registry.ModBlockEntities;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// PLATFORM-LEAK: getModelData() is NeoForge's thread-safe way to hand the conduit's look to chunk meshing.
import net.neoforged.neoforge.model.data.ModelData;
// PLATFORM-LEAK: the model data key for getModelData().
import net.neoforged.neoforge.model.data.ModelProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The block entity of every conduit block (ADR-0012): one conduit, whose type comes from its {@link ConduitBlock}, its
 * colour, connection masks and face modes, its optional {@link ConduitState} and the facade covering it. Never ticks
 * (PRD 8.1): connection masks are recomputed when a neighbour changes or the conduit itself is edited, and clients get
 * the new state through the block update packet only when something changed.
 */
// Name clash: vanilla has ...level.block.entity.ConduitBlockEntity (the underwater beacon); this is not it.
public final class ConduitBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STATE = "state";
    private static final String COLOUR = "colour";
    private static final String CONNECTIONS = "connections";
    private static final String CONDUIT_FACES = "conduit_faces";
    private static final String BLOCK_FACES = "block_faces";
    private static final String FACE_MODES = "face_modes";
    private static final String FACADE = "facade";
    private static final String SYNCED = "synced";
    private static final Set<ConduitFaceMode> LINK_MODES = Set
            .copyOf(EnumSet.of(ConduitFaceMode.BOTH, ConduitFaceMode.DISABLED));
    // Well below a pixel (1/16).
    private static final double HIT_INSET = 1.0E-4;

    /** Model data key the conduit model reads its look from. */
    public static final ModelProperty<ConduitRenderData> RENDER_DATA = new ModelProperty<>();
    /** Model data key of a {@link SyncedConduitState}'s {@link SyncedConduitState#renderKey()}; absent for none. */
    public static final ModelProperty<Object> STATE_RENDER_DATA = new ModelProperty<>();

    private ConduitData conduit;
    /** The block this conduit is disguised as; render-only (CLAUDE.md, Scope). Saved and synced. */
    private @Nullable BlockState facade;
    // Last render data seen by refreshModel, so updates that change nothing visible skip the re-mesh.
    private @Nullable ConduitRenderData renderData;
    private @Nullable Object stateRenderKey;
    private VoxelShape shape;
    // Set by a load from saved data; a conduit without it was just placed and works out its connections in onLoad.
    private boolean loaded;

    public ConduitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONDUIT.get(), pos, state);
        ConduitType type = typeOf(state);
        this.conduit = new ConduitData(type, null, 0);
        this.shape = ConduitShapes.shape(type.thickness(), 0, 0);
        bindState();
    }

    private void bindState() {
        if (conduit.state() instanceof SyncedConduitState synced) {
            synced.bind(this);
        }
    }

    private static ConduitType typeOf(BlockState state) {
        if (!(state.getBlock() instanceof ConduitBlock block)) {
            throw new IllegalArgumentException("Not a conduit block: " + state);
        }
        return block.type();
    }

    /** The conduit type, fixed by the block. */
    public ConduitType type() {
        return conduit.type();
    }

    /** The conduit's state: colour, masks, face modes and decor. */
    public ConduitData conduit() {
        return conduit;
    }

    /** The conduit if it is of {@code type}, else empty. */
    public Optional<ConduitData> find(ConduitType type) {
        return conduit.type().equals(type) ? Optional.of(conduit) : Optional.empty();
    }

    /** The conduit if it joins {@code networkType}, else {@code null}. */
    public @Nullable ConduitData findNetwork(Identifier networkType) {
        return conduit.type().networkType().equals(networkType) ? conduit : null;
    }

    /** Sets the conduit's colour on the server; {@code null} makes it uncoloured. Returns false if nothing changed. */
    public boolean setColour(@Nullable DyeColor colour) {
        if (conduit.colour() == colour || level == null || level.isClientSide()) {
            return false;
        }
        conduit.setColour(colour);
        // A colour change can make or break links on every side, here and at the neighbours.
        updateConnections();
        updateNeighbours();
        sync();
        return true;
    }

    /**
     * Recomputes the connection mask from the neighbouring conduits. Called on neighbour changes and after edits, never
     * per tick. Sides whose chunk isn't present keep their previous state so a conduit at a chunk border doesn't lose
     * its connections while the other side is unloaded; the network graph relies on that to keep a cut edge there.
     */
    public void updateConnections() {
        if (level == null || level.isClientSide()) {
            return;
        }
        int mask = conduit.connections();
        int conduitFaces = conduit.conduitFaces();
        int modes = conduit.faceModes();
        for (Direction side : Direction.values()) {
            int bit = ConduitConnections.bit(side);
            ConduitFaceMode own = conduit.faceMode(side);
            BlockPos neighbourPos = worldPosition.relative(side);
            if (!isChunkPresent(level, neighbourPos.getX(), neighbourPos.getZ())) {
                // Our own disabled face cuts the link even while the other side is unloaded.
                if (own == ConduitFaceMode.DISABLED) {
                    mask &= ~bit;
                }
                continue;
            }
            ConduitData other = linkable(conduit, level.getBlockEntity(neighbourPos));
            if (other == null) {
                conduitFaces &= ~bit;
                mask &= ~bit;
                // A link's BOTH may be a mode the type does not offer towards blocks.
                if (!ConduitFaceModes.allowed(conduit.type(), own, false)) {
                    conduit.setFaceMode(side, conduit.type().defaultFaceMode());
                }
                continue;
            }
            conduitFaces |= bit;
            if (!ConduitFaceModes.allowed(conduit.type(), own, true)) {
                // Between conduits a face is only linked or disabled (ADR-0005).
                conduit.setFaceMode(side, ConduitFaceMode.BOTH);
                own = ConduitFaceMode.BOTH;
            }
            if (ConduitConnections.linked(own, other.faceMode(side.getOpposite()))) {
                mask |= bit;
            } else {
                mask &= ~bit;
            }
        }
        boolean changed = false;
        if (conduitFaces != conduit.conduitFaces()) {
            conduit.setConduitFaces(conduitFaces);
            changed = true;
        }
        if (mask != conduit.connections() || modes != conduit.faceModes()) {
            conduit.setConnections(mask);
            changed = true;
            updateNode(NodeChange.INVALIDATE);
        }
        if (changed) {
            rebuildShape();
            sync();
        }
    }

    /** The conduit in {@code neighbour} that {@code conduit} could link with, ignoring face modes. */
    private static @Nullable ConduitData linkable(ConduitData conduit, @Nullable BlockEntity neighbour) {
        if (neighbour instanceof ConduitBlockEntity other && ConduitConnections.canConnect(conduit.type(),
                conduit.colour(), other.conduit.type(), other.conduit.colour())) {
            return other.conduit;
        }
        return null;
    }

    /** Recomputes the connections of the loaded neighbouring conduits. Server only. */
    private void updateNeighbours() {
        if (level == null) {
            return;
        }
        for (Direction side : Direction.values()) {
            BlockPos neighbourPos = worldPosition.relative(side);
            if (isChunkPresent(level, neighbourPos.getX(), neighbourPos.getZ())
                    && level.getBlockEntity(neighbourPos) instanceof ConduitBlockEntity neighbour) {
                neighbour.updateConnections();
            }
        }
    }

    /**
     * Sets one face on the server; the wrench and tests go through here. Towards a conduit it could link with, a face
     * takes only {@code BOTH} or {@code DISABLED}, whatever the type supports; towards anything else only the type's
     * supported modes ({@link ConduitFaceModes#allowed}). Cutting or restoring a link is a topology change; any other
     * change only makes the network re-read the node. Returns whether the mode changed.
     */
    public boolean setFaceMode(Direction side, ConduitFaceMode mode) {
        if (level == null || level.isClientSide()) {
            return false;
        }
        boolean towardsConduit = ConduitConnections.isConnected(conduit.conduitFaces(), side);
        if (conduit.faceMode(side) == mode || !ConduitFaceModes.allowed(conduit.type(), mode, towardsConduit)) {
            return false;
        }
        conduit.setFaceMode(side, mode);
        if (towardsConduit) {
            updateConnections();
            BlockPos neighbourPos = worldPosition.relative(side);
            if (isChunkPresent(level, neighbourPos.getX(), neighbourPos.getZ())
                    && level.getBlockEntity(neighbourPos) instanceof ConduitBlockEntity neighbour) {
                neighbour.updateConnections();
            }
        } else {
            updateNode(NodeChange.INVALIDATE);
        }
        rebuildShape();
        sync();
        return true;
    }

    /**
     * Sets the sides that face a block the conduit can transfer with, if the conduit joins {@code networkType}. Only
     * that network's logic calls this, on the server and outside capability invalidation. Syncs only on a change.
     */
    public void setBlockFaces(Identifier networkType, int blockFaces) {
        if (findNetwork(networkType) == null || conduit.blockFaces() == blockFaces || level == null
                || level.isClientSide()) {
            return;
        }
        conduit.setBlockFaces(blockFaces);
        rebuildShape();
        sync();
    }

    /**
     * The conduit's {@link SyncedConduitState} changed something clients see (its look, its shape or what links on its
     * faces): rebuilds the hit shape, saves, has the network re-read the node and sends the block entity to clients.
     * Server thread; does nothing on the client.
     */
    public void syncedStateChanged() {
        if (level == null || level.isClientSide()) {
            return;
        }
        rebuildShape();
        updateNode(NodeChange.INVALIDATE);
        sync();
    }

    /** The block this conduit is disguised as, or {@code null}. Render-only. */
    public @Nullable BlockState facade() {
        return facade;
    }

    /**
     * Sets or clears the facade on the server and syncs it. The facade item and its drop are the caller's business.
     * Returns whether anything changed.
     */
    public boolean setFacade(@Nullable BlockState facade) {
        if (level == null || level.isClientSide() || Objects.equals(this.facade, facade)) {
            return false;
        }
        this.facade = facade;
        sync();
        return true;
    }

    /**
     * Hit shape of the conduit: core, arms, stubs and rings at the type's thickness. Cached, safe from
     * {@code getShape}.
     */
    public VoxelShape shape() {
        return shape;
    }

    /**
     * The face a use-item hit targets (ADR-0005): the side of the arm or stub that was hit, or, for a hit on the core,
     * the clicked side of the core. That keeps a disabled face reachable through its stub or the core.
     */
    public Direction faceAt(BlockHitResult hit) {
        Vec3 pixels = hit.getLocation().relative(hit.getDirection(), -HIT_INSET)
                .subtract(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ()).scale(16.0);
        return ConduitShapes.faceAt(conduit.type().thickness(), conduit.armMask(), conduit.stubMask(),
                conduit.ringMask(), pixels, hit.getDirection());
    }

    /**
     * Moves one face to the next mode in the wrench's cycle ({@link ConduitFaceMode#next}) on the server: over
     * {@code BOTH} and {@code DISABLED} towards a conduit it could link with, otherwise over the type's supported
     * modes. Returns the new mode, or {@code null} if the face could not change.
     */
    public @Nullable ConduitFaceMode cycleFaceMode(Direction side) {
        Set<ConduitFaceMode> modes = ConduitConnections.isConnected(conduit.conduitFaces(), side)
                ? LINK_MODES
                : conduit.type().supportedFaceModes();
        ConduitFaceMode next = conduit.faceMode(side).next(modes);
        return setFaceMode(side, next) ? next : null;
    }

    /**
     * Whether the chunk holding block column {@code (x, z)} is fully present, as {@link NodeConnectivity#isLoaded}
     * defines it. Server thread only on a server level ({@code getChunkNow} answers null from other threads).
     */
    public static boolean isChunkPresent(Level level, int x, int z) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(z)) != null;
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the block entity is in a loaded chunk, for
    // placement and chunk load alike, which vanilla has no single callback for.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            // A saved conduit kept its masks; a placed one learns them here, since Block#onPlace runs before the
            // block entity exists. Its neighbours already updated theirs from the placement's neighbour updates.
            if (!loaded) {
                updateConnections();
            }
            updateNode(NodeChange.ADD);
        }
    }

    /**
     * Runs when the conduit is broken or replaced, but not when its chunk unloads (that saves the conduit's state
     * instead), so this is where the conduit drops what it holds, like a chest's contents.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        ConduitState conduitState = conduit.state();
        if (conduitState != null && level instanceof ServerLevel serverLevel) {
            conduitState.onRemoved(serverLevel, worldPosition);
        }
    }

    /** Covers both breaking and chunk unload; the graph tells the two apart by whether the chunk is still there. */
    @Override
    public void setRemoved() {
        super.setRemoved();
        updateNode(NodeChange.REMOVE);
    }

    private enum NodeChange {
        ADD, REMOVE, INVALIDATE
    }

    /** Reports the conduit's node to its network, if its kind has network logic. */
    private void updateNode(NodeChange change) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        NetworkType networkType = NetworkType.byId(conduit.type().networkType()).orElse(null);
        if (networkType == null) {
            return;
        }
        NetworkManager manager = NetworkManager.get(serverLevel);
        switch (change) {
            case ADD -> manager.addNode(networkType, worldPosition);
            case REMOVE -> manager.removeNode(networkType, worldPosition);
            case INVALIDATE -> manager.invalidateNode(networkType, worldPosition);
        }
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private void rebuildShape() {
        VoxelShape built = ConduitShapes.shape(conduit.type().thickness(), conduit.armMask(), conduit.stubMask(),
                conduit.ringMask());
        if (conduit.state() instanceof SyncedConduitState synced && !synced.shape().isEmpty()) {
            built = Shapes.or(built, synced.shape());
        }
        shape = built;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        write(output, true);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // Data loaded into a conduit that is already in a level (e.g. /data merge block) replaces its state; the
        // network must drop the old node and read the new one, or it keeps moving stacks that no longer exist here. A
        // chunk load reads data before the level is set, and onLoad adds the node then.
        boolean live = level instanceof ServerLevel && !isRemoved();
        if (live) {
            updateNode(NodeChange.REMOVE);
        }
        loaded = true;
        ConduitType type = conduit.type();
        int connections = input.getByteOr(CONNECTIONS, (byte) 0);
        ConduitData read = new ConduitData(type, input.read(COLOUR, DyeColor.CODEC).orElse(null), connections,
                input.getByteOr(CONDUIT_FACES, (byte) connections), input.getByteOr(BLOCK_FACES, (byte) 0),
                input.getIntOr(FACE_MODES, ConduitFaceModes.all(type.defaultFaceMode())));
        ConduitState state = read.state();
        if (state != null) {
            state.load(input.childOrEmpty(STATE));
        }
        conduit = read;
        bindState();
        if (state instanceof SyncedConduitState synced) {
            synced.loadSynced(input.childOrEmpty(SYNCED));
        }
        facade = input.read(FACADE, BlockState.CODEC).orElse(null);
        rebuildShape();
        refreshModel();
        if (live) {
            updateNode(NodeChange.ADD);
            // PLATFORM-LEAK: NeoForge's IBlockEntityExtension; capability handlers may wrap the replaced state.
            invalidateCapabilities();
        }
    }

    /**
     * On the client, hands the new look to the conduit model and re-meshes the section, but only if something visible
     * changed. The state doesn't change, so without the explicit block update the section would keep its old mesh.
     */
    private void refreshModel() {
        ConduitRenderData updated = ConduitRenderData.of(conduit, facade);
        Object key = conduit.state() instanceof SyncedConduitState synced ? synced.renderKey() : null;
        boolean changed = !updated.equals(renderData) || !Objects.equals(key, stateRenderKey);
        renderData = updated;
        stateRenderKey = key;
        if (changed && level != null && level.isClientSide()) {
            requestModelDataUpdate();
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Called on the client's main thread only when a refresh was requested; chunk meshing reads the snapshot. */
    @Override
    public ModelData getModelData() {
        ConduitRenderData data = ConduitRenderData.of(conduit, facade);
        Object key = conduit.state() instanceof SyncedConduitState synced ? synced.renderKey() : null;
        return key == null
                ? ModelData.of(RENDER_DATA, data)
                : ModelData.builder().with(RENDER_DATA, data).with(STATE_RENDER_DATA, key).build();
    }

    /**
     * Colour, masks, the facade and a {@link SyncedConduitState}'s synced part, which is everything the client renders;
     * with {@code withState}, also the conduit's {@link ConduitState}, which stays on the server.
     */
    private void write(ValueOutput output, boolean withState) {
        output.storeNullable(FACADE, BlockState.CODEC, facade);
        output.storeNullable(COLOUR, DyeColor.CODEC, conduit.colour());
        output.putByte(CONNECTIONS, (byte) conduit.connections());
        output.putByte(CONDUIT_FACES, (byte) conduit.conduitFaces());
        output.putByte(BLOCK_FACES, (byte) conduit.blockFaces());
        output.putInt(FACE_MODES, conduit.faceModes());
        ConduitState state = conduit.state();
        if (withState && state != null) {
            state.save(output.child(STATE));
        }
        if (state instanceof SyncedConduitState synced) {
            synced.saveSynced(output.child(SYNCED));
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, registries);
            write(output, false);
            return output.buildResult();
        }
    }
}
