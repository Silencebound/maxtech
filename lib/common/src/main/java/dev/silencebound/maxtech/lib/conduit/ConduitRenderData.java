package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What a conduit block looks like, handed from the block entity to the conduit model as model data: the body (type,
 * colour, the sides that draw a full arm and the arms that end in a ring), one ring or stub per decorated face, and the
 * facade covering the conduit, if any. Immutable, because chunk meshing reads it off the main thread; compared by
 * value, so an update that changes nothing visible can be skipped and equal bodies can share a baked part.
 *
 * @param arms
 *            sides that draw a full arm ({@link ConduitData#armMask()})
 * @param rings
 *            arms that stop where their ring starts ({@link ConduitData#ringMask()}); the rings themselves are
 *            {@link ConduitFaceKey}s, so the body does not depend on which mode a ringed face has
 * @param renderValue
 *            the addon's {@link ConduitBlockEntity#renderValue()}, part of the body
 * @param contents
 *            the conduit's {@link ConduitBlockEntity#contents()}, {@linkplain ConduitContents#packed() packed} with
 *            this session's fluid ids; part of the body, 0 for none
 */
public record ConduitRenderData(ConduitType type, @Nullable DyeColor colour, int arms, int rings, int renderValue,
        int contents, List<ConduitFaceKey> faces, @Nullable BlockState facade) {
    public ConduitRenderData {
        faces = List.copyOf(faces);
    }

    /** Render data with render value 0 and no contents. */
    public ConduitRenderData(ConduitType type, @Nullable DyeColor colour, int arms, int rings,
            List<ConduitFaceKey> faces, @Nullable BlockState facade) {
        this(type, colour, arms, rings, 0, 0, faces, facade);
    }

    /** Render data with no contents. */
    public ConduitRenderData(ConduitType type, @Nullable DyeColor colour, int arms, int rings, int renderValue,
            List<ConduitFaceKey> faces, @Nullable BlockState facade) {
        this(type, colour, arms, rings, renderValue, 0, faces, facade);
    }

    /** The same conduit without its facade: what it draws while the conduits show through facades. */
    public ConduitRenderData withoutFacade() {
        return facade == null
                ? this
                : new ConduitRenderData(type, colour, arms, rings, renderValue, contents, faces, null);
    }

    /** The key of this conduit's body. */
    public ConduitBodyKey bodyKey() {
        return new ConduitBodyKey(type, colour, arms, rings, renderValue, contents);
    }

    static ConduitRenderData of(ConduitData conduit, @Nullable BlockState facade) {
        return of(conduit, 0, 0, facade);
    }

    static ConduitRenderData of(ConduitData conduit, int renderValue, int contents, @Nullable BlockState facade) {
        List<ConduitFaceKey> faces = new ArrayList<>();
        int decorated = conduit.ringMask() | conduit.stubMask();
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(decorated, side)) {
                faces.add(new ConduitFaceKey(conduit.type(), side, conduit.faceMode(side)));
            }
        }
        return new ConduitRenderData(conduit.type(), conduit.colour(), conduit.armMask(), conduit.ringMask(),
                renderValue, contents, faces, facade);
    }
}
