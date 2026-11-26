package dev.silencebound.maxtech.lib.highlight;

import dev.silencebound.maxtech.lib.MaxtechLib;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A set of coloured boxes and line segments to draw in the world for a while, under a {@code channel}: a newer request
 * on the same channel replaces the older one, and a request with no shapes or no duration clears it. Shapes are in
 * world coordinates; {@code throughWalls} draws them over blocks (the network analyzer's overlay, a locate), otherwise
 * blocks hide them. Colours are ARGB. Built with {@link #builder}; sent by {@link Highlights#send} or shown on the
 * client directly through {@link HighlightStore}. A final class rather than a record because it holds arrays.
 */
public final class HighlightRequest implements CustomPacketPayload {
    public static final Type<HighlightRequest> TYPE = new Type<>(MaxtechLib.id("highlight"));
    /** Most boxes plus lines one request may hold; the builder and the codec refuse more. */
    public static final int MAX_SHAPES = 8192;
    /** Longest a request may last, in ticks (ten minutes). */
    public static final int MAX_DURATION_TICKS = 12_000;
    public static final StreamCodec<FriendlyByteBuf, HighlightRequest> CODEC = StreamCodec
            .of((buf, request) -> request.write(buf), HighlightRequest::read);

    private final Identifier channel;
    private final int durationTicks;
    private final boolean throughWalls;
    private final double[] boxes;
    private final int[] boxColours;
    private final double[] lines;
    private final int[] lineColours;

    private HighlightRequest(Identifier channel, int durationTicks, boolean throughWalls, double[] boxes,
            int[] boxColours, double[] lines, int[] lineColours) {
        this.channel = channel;
        this.durationTicks = durationTicks;
        this.throughWalls = throughWalls;
        this.boxes = boxes;
        this.boxColours = boxColours;
        this.lines = lines;
        this.lineColours = lineColours;
    }

    public static Builder builder(Identifier channel) {
        return new Builder(channel);
    }

    /** A request that removes whatever {@code channel} shows. */
    public static HighlightRequest clear(Identifier channel) {
        return new HighlightRequest(channel, 0, false, new double[0], new int[0], new double[0], new int[0]);
    }

    public Identifier channel() {
        return channel;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public boolean throughWalls() {
        return throughWalls;
    }

    public int boxCount() {
        return boxColours.length;
    }

    /** Coordinate {@code axis} (0 to 5: min x, y, z, max x, y, z) of box {@code index}. */
    public double box(int index, int axis) {
        return boxes[index * 6 + axis];
    }

    public int boxColour(int index) {
        return boxColours[index];
    }

    public int lineCount() {
        return lineColours.length;
    }

    /** Coordinate {@code axis} (0 to 5: from x, y, z, to x, y, z) of line {@code index}. */
    public double line(int index, int axis) {
        return lines[index * 6 + axis];
    }

    public int lineColour(int index) {
        return lineColours[index];
    }

    /** Whether showing this request removes its channel instead. */
    public boolean clears() {
        return durationTicks <= 0 || (boxColours.length == 0 && lineColours.length == 0);
    }

    @Override
    public Type<HighlightRequest> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeIdentifier(channel);
        VarInt.write(buf, durationTicks);
        buf.writeBoolean(throughWalls);
        writeShapes(buf, boxes, boxColours);
        writeShapes(buf, lines, lineColours);
    }

    private static void writeShapes(FriendlyByteBuf buf, double[] coordinates, int[] colours) {
        VarInt.write(buf, colours.length);
        for (int i = 0; i < colours.length; i++) {
            for (int axis = 0; axis < 6; axis++) {
                buf.writeDouble(coordinates[i * 6 + axis]);
            }
            buf.writeInt(colours[i]);
        }
    }

    private static HighlightRequest read(FriendlyByteBuf buf) {
        Identifier channel = buf.readIdentifier();
        int duration = VarInt.read(buf);
        if (duration < 0 || duration > MAX_DURATION_TICKS) {
            throw new DecoderException("Highlight duration " + duration);
        }
        boolean throughWalls = buf.readBoolean();
        int boxCount = shapeCount(VarInt.read(buf), 0);
        double[] boxes = new double[boxCount * 6];
        int[] boxColours = new int[boxCount];
        readShapes(buf, boxes, boxColours);
        int lineCount = shapeCount(VarInt.read(buf), boxCount);
        double[] lines = new double[lineCount * 6];
        int[] lineColours = new int[lineCount];
        readShapes(buf, lines, lineColours);
        return new HighlightRequest(channel, duration, throughWalls, boxes, boxColours, lines, lineColours);
    }

    private static int shapeCount(int count, int already) {
        if (count < 0 || count + already > MAX_SHAPES) {
            throw new DecoderException("Highlight with " + (count + already) + " shapes");
        }
        return count;
    }

    private static void readShapes(FriendlyByteBuf buf, double[] coordinates, int[] colours) {
        for (int i = 0; i < colours.length; i++) {
            for (int axis = 0; axis < 6; axis++) {
                double value = buf.readDouble();
                if (!Double.isFinite(value)) {
                    throw new DecoderException("Highlight coordinate " + value);
                }
                coordinates[i * 6 + axis] = value;
            }
            colours[i] = buf.readInt();
        }
    }

    /** Collects shapes; {@link #build} fails past {@link HighlightRequest#MAX_SHAPES}. */
    public static final class Builder {
        private final Identifier channel;
        private final DoubleArrayList boxes = new DoubleArrayList();
        private final IntArrayList boxColours = new IntArrayList();
        private final DoubleArrayList lines = new DoubleArrayList();
        private final IntArrayList lineColours = new IntArrayList();
        private int durationTicks = 200;
        private boolean throughWalls;

        private Builder(Identifier channel) {
            this.channel = channel;
        }

        /** How long the shapes stay, in ticks; 200 (ten seconds) unless set. Clamped to the allowed range. */
        public Builder duration(int ticks) {
            durationTicks = Math.clamp(ticks, 0, MAX_DURATION_TICKS);
            return this;
        }

        /** Draw over blocks instead of being hidden by them. */
        public Builder throughWalls(boolean value) {
            throughWalls = value;
            return this;
        }

        /** The outline of the block at {@code pos}, slightly larger so the block's faces do not hide it. */
        public Builder block(BlockPos pos, int argb) {
            return box(pos.getX() - 0.005, pos.getY() - 0.005, pos.getZ() - 0.005, pos.getX() + 1.005,
                    pos.getY() + 1.005, pos.getZ() + 1.005, argb);
        }

        public Builder box(AABB box, int argb) {
            return box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, argb);
        }

        public Builder box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb) {
            boxes.add(Math.min(minX, maxX));
            boxes.add(Math.min(minY, maxY));
            boxes.add(Math.min(minZ, maxZ));
            boxes.add(Math.max(minX, maxX));
            boxes.add(Math.max(minY, maxY));
            boxes.add(Math.max(minZ, maxZ));
            boxColours.add(argb);
            return this;
        }

        /** A segment between two points, such as a route between the centres of two blocks. */
        public Builder line(Vec3 from, Vec3 to, int argb) {
            lines.add(from.x);
            lines.add(from.y);
            lines.add(from.z);
            lines.add(to.x);
            lines.add(to.y);
            lines.add(to.z);
            lineColours.add(argb);
            return this;
        }

        /** Shapes added so far. */
        public int size() {
            return boxColours.size() + lineColours.size();
        }

        public HighlightRequest build() {
            if (size() > MAX_SHAPES) {
                throw new IllegalStateException("A highlight holds at most " + MAX_SHAPES + " shapes, not " + size());
            }
            return new HighlightRequest(channel, durationTicks, throughWalls, boxes.toDoubleArray(),
                    boxColours.toIntArray(), lines.toDoubleArray(), lineColours.toIntArray());
        }
    }
}
