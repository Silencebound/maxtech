package dev.silencebound.maxtech.storage.part;

import dev.silencebound.maxtech.storage.network.LinkLook;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * What a cable's parts look like, handed to the cable model as model data: one {@link Face} per part, plus a smart
 * cable's load bucket ({@code SmartLoad}, 0 on every other cable). Immutable and compared by value, so a sync that
 * changes nothing visible re-meshes nothing.
 */
public record PartLook(List<Face> faces, int load) {
    public PartLook {
        faces = List.copyOf(faces);
    }

    /** The look of {@code faces} without load. */
    public PartLook(List<Face> faces) {
        this(faces, 0);
    }

    /**
     * The part on {@code side}: its type, the type's look variant ({@link CablePart#lookVariant()}) and the look of the
     * line on its front ({@link LinkLook}).
     */
    public record Face(Direction side, Identifier type, int variant, LinkLook link) {
    }

    /** Sides that carry a part, one bit per {@link Direction#get3DDataValue()}. */
    public int mask() {
        int mask = 0;
        for (int i = 0; i < faces.size(); i++) {
            mask |= 1 << faces.get(i).side().get3DDataValue();
        }
        return mask;
    }
}
