package dev.silencebound.maxtech.storage.cable;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitState;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.Vec3;

/**
 * A storage cable (PRD 7.2, Network): a conduit on the storage network type whose tier sets the channels it carries
 * ({@code network.channels.tier<n>}, tiers 1 to 4; {@link dev.silencebound.maxtech.storage.network.Channels}), drawn in
 * one of the {@link CableLook}s. All tiers, looks and colours of one network type connect by Lib's conduit rule; a face
 * towards a device is only linked or disabled. Its info lines come from {@link NetworkInfo}.
 */
public final class StorageCableConduitType extends ConduitType implements BlockInfoProvider {
    private static final Set<ConduitFaceMode> FACE_MODES = Collections
            .unmodifiableSet(EnumSet.of(ConduitFaceMode.BOTH, ConduitFaceMode.DISABLED));

    /** The highest channel tier. */
    public static final int MAX_TIER = 4;

    private final int level;
    private final CableLook look;

    /**
     * A plain cable.
     *
     * @param networkType
     *            the storage network type it joins; {@code StorageNetworks.ID} in game
     * @param level
     *            channel tier, 1 to {@link #MAX_TIER}
     * @param thickness
     *            width as a fraction of a block
     */
    public StorageCableConduitType(Identifier networkType, int level, int baseColour, float thickness,
            Supplier<? extends ItemLike> item) {
        this(networkType, level, CableLook.PLAIN, baseColour, thickness, item);
    }

    /** As {@link #StorageCableConduitType(Identifier, int, int, float, Supplier)}, drawn in {@code look}. */
    public StorageCableConduitType(Identifier networkType, int level, CableLook look, int baseColour, float thickness,
            Supplier<? extends ItemLike> item) {
        super(networkType, "tier" + level, baseColour, thickness, item);
        if (level < 1 || level > MAX_TIER) {
            throw new IllegalArgumentException("Storage cable tier must be 1 to " + MAX_TIER + ": " + level);
        }
        this.level = level;
        this.look = look;
    }

    /** The channel tier, 1 to {@link #MAX_TIER}. */
    public int level() {
        return level;
    }

    /** How the cable is drawn; every look of a tier carries the same channels. */
    public CableLook look() {
        return look;
    }

    @Override
    public Set<ConduitFaceMode> supportedFaceModes() {
        return FACE_MODES;
    }

    /** Every storage cable carries its parts in its state; empty until a part goes on. */
    @Override
    public Optional<ConduitState> createState() {
        return Optional.of(new CableParts());
    }

    /** The part the player looks at, if the request knows the hit and there is one there; else the cable. */
    @Override
    public void addInfo(BlockInfoRequest request, BlockInfoSink sink) {
        Vec3 hit = request.hit();
        Direction side = request.side();
        CableParts parts = StorageCableBlock.parts(request.level(), request.pos());
        CablePart part = hit == null || side == null || parts == null ? null : parts.partAt(hit, side);
        if (part != null) {
            part.addInfo(request, sink);
            return;
        }
        NetworkInfo.cable(this, request, sink);
    }
}
