package dev.silencebound.maxtech.lib.client.ghost;

import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

/**
 * An immutable set of ghost cells: block positions, each with an ARGB colour and a style. A {@link Style#FILLED} cell
 * is a translucent cube (the colour's alpha, which the caller keeps low) with an opaque outline, slightly inset so it
 * does not flicker against the blocks around it; an {@link Style#OUTLINE} cell is only the opaque outline of a whole
 * block, slightly outset, to mark blocks that are there. Held as primitive arrays so the renderer allocates nothing.
 * Built with {@link #builder()}; any thread, shown only on the client.
 */
public final class GhostSet {
    /** The empty set. */
    public static final GhostSet EMPTY = new GhostSet(new long[0], new int[0], new boolean[0]);

    /** How a cell is drawn. */
    public enum Style {
        /** A translucent inset cube plus its outline: a block that could go here. */
        FILLED,
        /** The outline of a whole block: a block that is there. */
        OUTLINE
    }

    private final long[] positions;
    private final int[] colours;
    private final boolean[] filled;

    private GhostSet(long[] positions, int[] colours, boolean[] filled) {
        this.positions = positions;
        this.colours = colours;
        this.filled = filled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int size() {
        return positions.length;
    }

    public boolean isEmpty() {
        return positions.length == 0;
    }

    /** Cell {@code index}'s block position as {@link BlockPos#asLong()}. */
    public long position(int index) {
        return positions[index];
    }

    /** Cell {@code index}'s ARGB colour. */
    public int colour(int index) {
        return colours[index];
    }

    /** Whether cell {@code index} is {@link Style#FILLED}. */
    public boolean filled(int index) {
        return filled[index];
    }

    /** Collects cells; {@link #build()} copies them, so a builder can be reused after {@link #clear()}. */
    public static final class Builder {
        private final LongArrayList positions = new LongArrayList();
        private final IntArrayList colours = new IntArrayList();
        private final BooleanArrayList filled = new BooleanArrayList();

        private Builder() {
        }

        /** Adds the cell at {@code pos} ({@link BlockPos#asLong()}) in {@code argb}, drawn as {@code style}. */
        public Builder add(long pos, int argb, Style style) {
            positions.add(pos);
            colours.add(argb);
            filled.add(style == Style.FILLED);
            return this;
        }

        public Builder add(BlockPos pos, int argb, Style style) {
            return add(pos.asLong(), argb, style);
        }

        public Builder clear() {
            positions.clear();
            colours.clear();
            filled.clear();
            return this;
        }

        public GhostSet build() {
            return positions.isEmpty()
                    ? EMPTY
                    : new GhostSet(positions.toLongArray(), colours.toIntArray(), filled.toBooleanArray());
        }
    }
}
