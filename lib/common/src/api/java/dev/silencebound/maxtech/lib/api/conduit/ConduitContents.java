package dev.silencebound.maxtech.lib.api.conduit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * What a conduit shows inside it, in a meaning shared by every addon: the fluid it carries, how full it looks, the side
 * its flow comes from and whether a second lane flows the other way. The addon sets it on the server with
 * {@link ConduitHost#setContents}; Lib saves it (the fluid by registry name, so it survives registry id changes between
 * sessions), syncs it on change and hands it to the client model. It says only what to draw; it holds no amount and
 * moves nothing.
 * <p>
 * An immutable value, safe to share between threads. Its accessors return the stored components and never allocate.
 * <p>
 * Experimental (for Farms, FM1) until Farms, its first addon user, settles it.
 *
 * @param fluid
 *            the fluid shown, drawn with that fluid's own textures and tint; empty for none. Never the empty fluid
 *            itself ({@code Fluids.EMPTY}): that is {@code Optional.empty()}
 * @param fill
 *            how full the conduit looks, from {@code 0} (empty) over quarter steps to {@link #FULL}; {@code 0} without
 *            a fluid
 * @param upstream
 *            the side the flow enters from, or empty when it has none (a dead end, a source, or no flow)
 * @param lane
 *            whether a second lane is drawn, flowing opposite to the main one
 */
@ApiStatus.Experimental
public record ConduitContents(Optional<Fluid> fluid, int fill, Optional<Direction> upstream, boolean lane) {
    /** The highest fill step: full. */
    public static final int FULL = 4;
    /** Nothing inside: no fluid, fill 0, no upstream side, no second lane. What every conduit has until set. */
    public static final ConduitContents EMPTY = new ConduitContents(Optional.empty(), 0, Optional.empty(), false);

    private static final int FLUID_BITS = 16;
    private static final int FLUID_MASK = (1 << FLUID_BITS) - 1;
    private static final int FILL_SHIFT = 16;
    private static final int FILL_MASK = 0b111;
    private static final int UPSTREAM_SHIFT = 19;
    private static final int UPSTREAM_MASK = 0b111;
    private static final int LANE_BIT = 1 << 22;
    // Index 0 is none, then Direction.get3DDataValue() + 1; shared so packedUpstream never allocates.
    private static final List<Optional<Direction>> UPSTREAM = upstreamOptions();

    /**
     * @throws IllegalArgumentException
     *             if {@code fill} is outside {@code 0..}{@link #FULL}, a fill is given without a fluid, or the fluid is
     *             the empty fluid
     */
    public ConduitContents {
        Objects.requireNonNull(fluid, "fluid");
        Objects.requireNonNull(upstream, "upstream");
        if (fill < 0 || fill > FULL) {
            throw new IllegalArgumentException("fill must be 0.." + FULL + ": " + fill);
        }
        if (fluid.isPresent() && fluid.get().defaultFluidState().isEmpty()) {
            throw new IllegalArgumentException("the empty fluid is Optional.empty()");
        }
        if (fluid.isEmpty() && fill != 0) {
            throw new IllegalArgumentException("fill without a fluid: " + fill);
        }
    }

    /** Whether this is {@link #EMPTY}. */
    public boolean isEmpty() {
        return equals(EMPTY);
    }

    /**
     * These contents packed into one int for a client render key, using the fluid's registry id in this session (see
     * {@link #pack(int, int, Direction, boolean)}). Only meaningful within the session that packed it: never save or
     * send it. {@link #EMPTY} packs to 0.
     *
     * @throws IllegalStateException
     *             if the fluid's registry id does not fit in the 16 bits of the layout
     */
    public int packed() {
        int fluidId = fluid.isPresent() ? BuiltInRegistries.FLUID.getId(fluid.get()) : -1;
        if (fluidId >= FLUID_MASK) {
            throw new IllegalStateException("fluid registry id too large to pack: " + fluidId);
        }
        Direction side = upstream.isPresent() ? upstream.get() : null;
        return pack(fluidId, fill, side, lane);
    }

    /**
     * Packs contents into one int; pure, so the layout can be pinned. Bits 0-15 hold the fluid (0 for none, else its
     * registry id + 1), bits 16-18 the fill step, bits 19-21 the upstream side (0 for none, else
     * {@link Direction#get3DDataValue()} + 1), bit 22 the second lane; bits 23-31 are 0. All-empty packs to 0.
     *
     * @param fluidId
     *            the fluid's registry id, or -1 for none; at most 65534
     * @param upstream
     *            the upstream side, or null for none
     * @throws IllegalArgumentException
     *             if {@code fluidId} or {@code fill} is out of range
     */
    public static int pack(int fluidId, int fill, @Nullable Direction upstream, boolean lane) {
        if (fluidId < -1 || fluidId >= FLUID_MASK) {
            throw new IllegalArgumentException("fluid id out of range: " + fluidId);
        }
        if (fill < 0 || fill > FULL) {
            throw new IllegalArgumentException("fill must be 0.." + FULL + ": " + fill);
        }
        int side = upstream == null ? 0 : upstream.get3DDataValue() + 1;
        return (fluidId + 1) | fill << FILL_SHIFT | side << UPSTREAM_SHIFT | (lane ? LANE_BIT : 0);
    }

    /** The fluid's registry id in packed contents, or -1 for none. */
    public static int packedFluidId(int packed) {
        return (packed & FLUID_MASK) - 1;
    }

    /** The fill step in packed contents, {@code 0..}{@link #FULL}. */
    public static int packedFill(int packed) {
        return packed >>> FILL_SHIFT & FILL_MASK;
    }

    /** The upstream side in packed contents, or empty for none. Allocation-free, for render code. */
    public static Optional<Direction> packedUpstream(int packed) {
        int side = packed >>> UPSTREAM_SHIFT & UPSTREAM_MASK;
        return side < UPSTREAM.size() ? UPSTREAM.get(side) : Optional.empty();
    }

    /** Whether packed contents have the second lane. */
    public static boolean packedLane(int packed) {
        return (packed & LANE_BIT) != 0;
    }

    private static List<Optional<Direction>> upstreamOptions() {
        List<Optional<Direction>> options = new ArrayList<>();
        options.add(Optional.empty());
        for (int i = 0; i < Direction.values().length; i++) {
            options.add(Optional.of(Direction.from3DDataValue(i)));
        }
        return List.copyOf(options);
    }
}
