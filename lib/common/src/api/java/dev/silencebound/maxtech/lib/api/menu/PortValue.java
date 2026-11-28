package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/**
 * Handle of a host's {@link TransferPort} in a menu, declared with {@link MenuDefinition.Builder#port}: per declared
 * kind the packed face modes ({@link PortFaces}, two bits per face) and the modes the kind allows, plus the host's
 * facing for the side config screen. {@link Layout#faces} and {@link MenuDefinition.Builder#sideConfig} show it; a
 * click on a face asks the server for one mode of that face and kind, which the server sets on the port only when the
 * port allows that mode for the kind (see {@link MenuDefinition.Builder#port}). Every part travels as a plain int
 * value, so reads never allocate. Immutable; any thread.
 */
@ApiStatus.Experimental
public final class PortValue {
    /** The most kinds one port value may show; the button id carries the kind's position below it. */
    public static final int MAX_KINDS = 16;
    /** The facing value of a host without a facing. */
    static final int NO_FACING = -1;

    private static final Set<PortFaceMode>[] ALLOWED = allowedSets();

    private final int index;
    private final List<ResourceKind<?>> kinds;
    private final IntValue facing;
    private final IntValue[] faces;
    private final IntValue[] allowed;

    PortValue(int index, List<ResourceKind<?>> kinds, IntValue facing, IntValue[] faces, IntValue[] allowed) {
        this.index = index;
        this.kinds = List.copyOf(kinds);
        this.facing = facing;
        this.faces = faces.clone();
        this.allowed = allowed.clone();
    }

    /** The kinds the value shows, in declaration order. */
    public List<ResourceKind<?>> kinds() {
        return kinds;
    }

    /** The position of {@code kind} in {@link #kinds()}, or -1 for a kind the value does not show. */
    public int kindIndex(ResourceKind<?> kind) {
        return kinds.indexOf(kind);
    }

    /** The host's facing in {@code values}; empty for a host without one (screens then name faces absolutely). */
    public Optional<Direction> facing(MenuValueView values) {
        int id = values.get(facing);
        return id < 0 || id > 5 ? Optional.empty() : Optional.of(Direction.from3DDataValue(id));
    }

    /** The packed face modes of {@code kind} ({@link PortFaces}); {@link PortFaces#NONE} for a kind not shown. */
    public int faces(MenuValueView values, ResourceKind<?> kind) {
        int at = kindIndex(kind);
        return at < 0 ? PortFaces.NONE : values.get(faces[at]);
    }

    /** The mode of {@code face} for {@code kind}. */
    public PortFaceMode mode(MenuValueView values, ResourceKind<?> kind, Direction face) {
        return PortFaces.get(faces(values, kind), face);
    }

    /**
     * The modes faces of {@code kind} may take ({@link TransferPort#allowed}); empty for a kind not shown or one the
     * host's port does not move.
     */
    public Set<PortFaceMode> allowed(MenuValueView values, ResourceKind<?> kind) {
        int at = kindIndex(kind);
        return at < 0 ? Set.of() : ALLOWED[values.get(allowed[at]) & 7];
    }

    /** Every set of modes by its mask (bit {@link PortFaceMode#id()} per mode), so reads never allocate. */
    private static Set<PortFaceMode>[] allowedSets() {
        @SuppressWarnings("unchecked") // an array of sets of one type
        Set<PortFaceMode>[] sets = new Set[8];
        for (int mask = 0; mask < sets.length; mask++) {
            Set<PortFaceMode> modes = EnumSet.noneOf(PortFaceMode.class);
            for (PortFaceMode mode : PortFaceMode.values()) {
                if ((mask & (1 << mode.id())) != 0) {
                    modes.add(mode);
                }
            }
            sets[mask] = Collections.unmodifiableSet(modes);
        }
        return sets;
    }

    /** The value's position among its definition's port values, for the button id. */
    @ApiStatus.Internal
    public int index() {
        return index;
    }
}
