package dev.silencebound.maxtech.storage.part;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.SyncedConduitState;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.DeviceHost;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The parts on a storage cable's faces, at most one per face: the cable's saved state (Lib's
 * {@link SyncedConduitState}), so they live in the cable's own block entity, save with it, reach clients with its
 * update packet only when they change, join its hit shape and its model data. As a {@link DeviceHost} it hands its
 * parts to the storage network as devices on the cable's node. On a smart cable it also carries the load bucket the
 * cable draws ({@link #setLoad}), which travels the same way but is never saved as a change.
 *
 * <p>
 * A face with a part is set to Lib's {@code DISABLED} face mode, so the cable links on it to neither a neighbouring
 * cable nor a device; the network's connectivity also skips it, whatever a wrench later does to the mode. Taking the
 * part off sets the face back to {@code BOTH}. A part that sits in the cable's line ({@link PartType#isInLine()}, the
 * toggle bus) keeps its face on {@code BOTH} instead, and the connectivity links past it while it says so
 * ({@link #cutsLink}). Server changes go through {@link #place} and {@link #remove}; both sides read. Never ticks; a
 * part that must look again later asks for the cable block's scheduled tick ({@link #scheduleTick}).
 */
public final class CableParts implements SyncedConduitState, DeviceHost {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PARTS = "parts";
    private static final String SIDE = "side";
    private static final String TYPE = "type";
    private static final String VARIANT = "variant";
    private static final String DATA = "data";
    private static final String LOAD = "load";
    private static final String LINK = "link";
    // Well below a pixel; moves a hit on a surface into the box that was hit.
    private static final double HIT_INSET = 1.0E-4;
    private static final Direction[] SIDES = Direction.values();
    private static final long NO_TICK = Long.MAX_VALUE;

    private final @Nullable CablePart[] parts = new CablePart[6];
    /** The parts in face order, for the network's device list. */
    private final ObjectArrayList<CablePart> list = new ObjectArrayList<>(2);
    /** The look variants the server last sent; the client draws from these. */
    private final int[] syncedVariants = new int[6];
    /** The front line looks the server last sent ({@link LinkLook}); the client draws from these. */
    private final LinkLook[] syncedLinks = {LinkLook.OFFLINE, LinkLook.OFFLINE, LinkLook.OFFLINE, LinkLook.OFFLINE,
            LinkLook.OFFLINE, LinkLook.OFFLINE};
    private @Nullable ConduitBlockEntity host;
    private VoxelShape shape = Shapes.empty();
    private int load;
    /**
     * Game time the cable's pending scheduled tick is due, as {@link #scheduleTick} asked; {@link #NO_TICK} if unknown.
     */
    private long tickDue = NO_TICK;

    @Override
    public void bind(ConduitBlockEntity host) {
        this.host = host;
        rebuild();
    }

    private ConduitBlockEntity host() {
        ConduitBlockEntity current = host;
        if (current == null) {
            throw new IllegalStateException("Cable parts not bound to a cable");
        }
        return current;
    }

    /** The cable's position. */
    public BlockPos pos() {
        return host().getBlockPos();
    }

    /** The cable's level, or null before it is placed. */
    public @Nullable Level level() {
        return host().getLevel();
    }

    /** Whether the cable is gone (broken or unloaded). */
    public boolean isRemoved() {
        ConduitBlockEntity current = host;
        return current == null || current.isRemoved();
    }

    /** The part on {@code side}, or null. */
    public @Nullable CablePart part(Direction side) {
        return parts[side.get3DDataValue()];
    }

    /** Every part, in face order. */
    public List<CablePart> all() {
        return List.copyOf(list);
    }

    @Override
    public int deviceCount() {
        return list.size();
    }

    @Override
    public NetworkDevice device(int index) {
        return list.get(index);
    }

    @Override
    public boolean occupies(Direction side) {
        return parts[side.get3DDataValue()] != null;
    }

    @Override
    public boolean cutsLink(Direction side) {
        CablePart part = parts[side.get3DDataValue()];
        return part != null && !(part.type().isInLine() && part.linksThrough());
    }

    /**
     * Asks for the cable block's scheduled tick in {@code delay} ticks (at least 1); it reaches every part through
     * {@link CablePart#scheduledTick()}, and each part checks there whether it is due and asks again if not. Vanilla
     * keeps one scheduled tick per block and position and drops a second request, so the cable remembers when its
     * pending tick is due: a request that comes later than that is served by the pending tick, and an earlier one moves
     * the pending tick to the earlier time, so no part is served late. After a reload that time is unknown; a tick
     * still pending then is kept and serves every request (once possibly late). Server side.
     */
    public void scheduleTick(int delay) {
        ConduitBlockEntity current = host;
        if (current == null || !(current.getLevel() instanceof ServerLevel level) || current.isRemoved()) {
            return;
        }
        BlockPos pos = current.getBlockPos();
        Block block = current.getBlockState().getBlock();
        int wait = Math.max(1, delay);
        long due = level.getGameTime() + wait;
        if (level.getBlockTicks().hasScheduledTick(pos, block)) {
            if (tickDue == NO_TICK || tickDue <= due) {
                return;
            }
            level.getBlockTicks().clearArea(new BoundingBox(pos));
        }
        level.scheduleTick(pos, block, wait);
        tickDue = due;
    }

    /** The cable block's scheduled tick came due: every part hears of it. Server side. */
    public void scheduledTick() {
        tickDue = NO_TICK;
        for (int i = 0; i < list.size(); i++) {
            list.get(i).scheduledTick();
        }
    }

    /**
     * Puts a part of {@code type} on {@code side} on the server, owned by {@code placer} and with the data of
     * {@code stack} (the item it was placed from; the caller consumes it). Cuts the cable's link on that side and adds
     * the part to the cable's network. Returns false, changing nothing, if the side has a part already or this is the
     * client.
     */
    public boolean place(Direction side, PartType type, @Nullable Player placer, ItemStack stack) {
        ConduitBlockEntity cable = host();
        if (!(cable.getLevel() instanceof ServerLevel level) || occupies(side)) {
            return false;
        }
        CablePart part = type.create(this, side);
        if (placer != null) {
            part.setOwner(OwnerRecord.of(placer));
        }
        part.readLabel(stack);
        part.readItem(stack);
        put(side, part);
        rebuild();
        part.startOnce(level);
        cable.setFaceMode(side, type.isInLine() ? ConduitFaceMode.BOTH : ConduitFaceMode.DISABLED);
        cable.syncedStateChanged();
        StorageNetworkLogic logic = StorageNetworks.logic(level, cable.type().networkType());
        if (logic != null) {
            logic.deviceAdded(cable.getBlockPos().asLong(), this, part);
        }
        return true;
    }

    /**
     * Takes the part off {@code side} on the server and returns it (its item is {@link CablePart#toItem()}; dropping it
     * is the caller's business), or null if there is none or this is the client. The part leaves its network and the
     * side links again.
     */
    public @Nullable CablePart remove(Direction side) {
        ConduitBlockEntity cable = host();
        CablePart part = part(side);
        if (part == null || !(cable.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        parts[side.get3DDataValue()] = null;
        list.remove(part);
        part.markRemoved();
        StorageNetworkLogic logic = StorageNetworks.logic(level, cable.type().networkType());
        if (logic != null) {
            logic.deviceGone(part);
        }
        rebuild();
        cable.setFaceMode(side, ConduitFaceMode.BOTH);
        cable.syncedStateChanged();
        return part;
    }

    /**
     * The part a hit on the cable at {@code location} (world coordinates, on the surface of the face {@code hitSide})
     * landed on, the arm behind it included; null for a hit on the bare cable.
     */
    public @Nullable CablePart partAt(Vec3 location, Direction hitSide) {
        if (list.isEmpty()) {
            return null;
        }
        BlockPos pos = pos();
        Vec3 pixels = location.relative(hitSide, -HIT_INSET).subtract(pos.getX(), pos.getY(), pos.getZ()).scale(16.0);
        for (int i = 0; i < list.size(); i++) {
            CablePart part = list.get(i);
            AABB[] boxes = part.type().boxes(part.face());
            for (AABB box : boxes) {
                if (PartBoxes.contains(box, pixels)) {
                    return part;
                }
            }
            AABB arm = PartBoxes.arm(host().type().thickness(), part.face(), part.type().depth());
            if (arm != null && PartBoxes.contains(arm, pixels)) {
                return part;
            }
        }
        return null;
    }

    /** A part's saved data changed: the cable saves. Server side. */
    public void changed() {
        ConduitBlockEntity current = host;
        if (current != null) {
            current.setChanged();
        }
    }

    /** A part's {@link CablePart#lookVariant()} changed: clients redraw it. Server side. */
    public void lookChanged() {
        host().syncedStateChanged();
    }

    /**
     * A part's front line look changed ({@link CablePart#linkLookChanged}): clients redraw the cable. Like
     * {@link #setLoad} it leaves the network node alone and does not mark the chunk for saving; the network works the
     * look out again after a reload. Server side.
     */
    void linkChanged() {
        ConduitBlockEntity current = host;
        if (current != null && current.getLevel() instanceof ServerLevel level && !current.isRemoved()) {
            BlockState state = current.getBlockState();
            level.sendBlockUpdated(current.getBlockPos(), state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** A smart cable's load bucket ({@code SmartLoad}); 0 on every other cable. Both sides. */
    public int load() {
        return load;
    }

    /**
     * Sets the smart cable's load bucket on the server and, if it changed, sends the cable to clients so it redraws.
     * Unlike {@link #lookChanged} it leaves the network node alone and does not mark the chunk for saving: the load is
     * re-read from the network's channel counts at its next cadence.
     */
    public void setLoad(int bucket) {
        ConduitBlockEntity current = host;
        if (bucket == load || current == null || !(current.getLevel() instanceof ServerLevel level)) {
            return;
        }
        load = bucket;
        BlockState state = current.getBlockState();
        level.sendBlockUpdated(current.getBlockPos(), state, state, Block.UPDATE_CLIENTS);
    }

    /** A neighbour of the cable changed ({@code from}, or null when unknown); every part hears of it. Server side. */
    public void neighbourChanged(@Nullable BlockPos from) {
        for (int i = 0; i < list.size(); i++) {
            CablePart part = list.get(i);
            part.neighbourChanged(from);
            NetworkState.neighbourChanged(part);
        }
    }

    @Override
    public void nodeAdded(ServerLevel level) {
        for (int i = 0; i < list.size(); i++) {
            list.get(i).startOnce(level);
        }
    }

    private void put(Direction side, CablePart part) {
        CablePart old = parts[side.get3DDataValue()];
        if (old != null) {
            list.remove(old);
            old.markRemoved();
        }
        parts[side.get3DDataValue()] = part;
        list.clear();
        for (CablePart each : parts) {
            if (each != null) {
                list.add(each);
            }
        }
    }

    private void rebuild() {
        ConduitBlockEntity current = host;
        VoxelShape built = Shapes.empty();
        for (int i = 0; i < list.size(); i++) {
            CablePart part = list.get(i);
            built = Shapes.or(built, part.type().shape(part.face()));
            if (current != null) {
                AABB arm = PartBoxes.arm(current.type().thickness(), part.face(), part.type().depth());
                if (arm != null) {
                    built = Shapes.or(built, PartBoxes.toShape(arm));
                }
            }
        }
        shape = built.optimize();
    }

    @Override
    public void save(ValueOutput output) {
        if (list.isEmpty()) {
            return;
        }
        ValueOutput.ValueOutputList saved = output.childrenList(PARTS);
        for (int i = 0; i < list.size(); i++) {
            CablePart part = list.get(i);
            ValueOutput child = saved.addChild();
            child.putByte(SIDE, (byte) part.face().get3DDataValue());
            child.store(TYPE, Identifier.CODEC, part.type().id());
            ValueOutput data = child.child(DATA);
            StorageSaveFormats.CABLE_PARTS.write(data, part);
            part.save(data);
        }
    }

    @Override
    public void load(ValueInput input) {
        for (ValueInput child : input.childrenListOrEmpty(PARTS)) {
            PartType type = readType(child);
            int side = child.getByteOr(SIDE, (byte) -1);
            if (type == null || side < 0 || side >= SIDES.length) {
                continue;
            }
            CablePart part = type.create(this, SIDES[side]);
            ValueInput data = child.childOrEmpty(DATA);
            StorageSaveFormats.CABLE_PARTS.noteLoaded(part, data);
            part.load(StorageSaveFormats.CABLE_PARTS.migrate(type.id(), data));
            put(SIDES[side], part);
        }
        rebuild();
    }

    private static @Nullable PartType readType(ValueInput child) {
        Identifier id = child.read(TYPE, Identifier.CODEC).orElse(null);
        PartType type = id == null ? null : PartTypes.byId(id);
        if (id != null && type == null) {
            LOGGER.warn("Dropping a cable part of unknown type {}", id);
        }
        return type;
    }

    @Override
    public void saveSynced(ValueOutput output) {
        if (load != 0) {
            output.putByte(LOAD, (byte) load);
        }
        if (list.isEmpty()) {
            return;
        }
        ValueOutput.ValueOutputList synced = output.childrenList(PARTS);
        for (int i = 0; i < list.size(); i++) {
            CablePart part = list.get(i);
            ValueOutput child = synced.addChild();
            child.putByte(SIDE, (byte) part.face().get3DDataValue());
            child.store(TYPE, Identifier.CODEC, part.type().id());
            child.putInt(VARIANT, part.lookVariant());
            child.putString(LINK, part.deviceLink().linkLook().getSerializedName());
            part.saveSynced(child.child(DATA));
        }
    }

    @Override
    public void loadSynced(ValueInput input) {
        load = input.getByteOr(LOAD, (byte) 0);
        int seen = 0;
        for (ValueInput child : input.childrenListOrEmpty(PARTS)) {
            PartType type = readType(child);
            int side = child.getByteOr(SIDE, (byte) -1);
            if (type == null || side < 0 || side >= SIDES.length) {
                continue;
            }
            CablePart part = parts[side];
            if (part == null || part.type() != type) {
                // The client learns of new parts here; the server has them from load already.
                part = type.create(this, SIDES[side]);
                put(SIDES[side], part);
            }
            part.loadSynced(child.childOrEmpty(DATA));
            syncedVariants[side] = child.getIntOr(VARIANT, 0);
            syncedLinks[side] = LinkLook.byName(child.getStringOr(LINK, ""));
            seen |= 1 << side;
        }
        for (int side = 0; side < parts.length; side++) {
            CablePart part = parts[side];
            if (part != null && (seen & 1 << side) == 0) {
                parts[side] = null;
                list.remove(part);
                part.markRemoved();
            }
        }
        rebuild();
    }

    /**
     * The cable is gone for good: every part leaves its network and drops its item, as a chest drops its contents.
     */
    @Override
    public void onRemoved(ServerLevel level, BlockPos pos) {
        StorageNetworkLogic logic = host == null ? null : StorageNetworks.logic(level, host().type().networkType());
        for (int i = 0; i < list.size(); i++) {
            CablePart part = list.get(i);
            part.markRemoved();
            if (logic != null) {
                logic.deviceGone(part);
            }
            Block.popResource(level, pos, part.toItem());
            for (ItemStack content : part.takeContents()) {
                Block.popResource(level, pos, content);
            }
        }
        list.clear();
        for (int side = 0; side < parts.length; side++) {
            parts[side] = null;
        }
    }

    @Override
    public @Nullable Object renderKey() {
        if (list.isEmpty()) {
            return load == 0 ? null : new PartLook(List.of(), load);
        }
        Level level = host == null ? null : host.getLevel();
        boolean client = level != null && level.isClientSide();
        List<PartLook.Face> faces = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CablePart part = list.get(i);
            int side = part.face().get3DDataValue();
            faces.add(
                    new PartLook.Face(part.face(), part.type().id(), client ? syncedVariants[side] : part.lookVariant(),
                            client ? syncedLinks[side] : part.deviceLink().linkLook()));
        }
        return new PartLook(faces, load);
    }

    @Override
    public VoxelShape shape() {
        return shape;
    }
}
