package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
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
 */
public record ConduitRenderData(ConduitType type, @Nullable DyeColor colour, int arms, int rings,
        List<ConduitFaceKey> faces, @Nullable BlockState facade) {
    public ConduitRenderData {
        faces = List.copyOf(faces);
    }

    /** The same conduit without its facade: what it draws while the conduits show through facades. */
    public ConduitRenderData withoutFacade() {
        return facade == null ? this : new ConduitRenderData(type, colour, arms, rings, faces, null);
    }

    static ConduitRenderData of(ConduitData conduit, @Nullable BlockState facade) {
        List<ConduitFaceKey> faces = new ArrayList<>();
        int decorated = conduit.ringMask() | conduit.stubMask();
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(decorated, side)) {
                faces.add(new ConduitFaceKey(conduit.type(), side, conduit.faceMode(side)));
            }
        }
        return new ConduitRenderData(conduit.type(), conduit.colour(), conduit.armMask(), conduit.ringMask(), faces,
                facade);
    }
}
