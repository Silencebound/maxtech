package dev.silencebound.maxtech.lib.shim.client.render;

/**
 * 26.1's {@code com.mojang.math.Quadrant}, the part Lib uses: a face's UV turn in quarter turns. 1.21.1 keeps the turn
 * as degrees on {@code BlockFaceUV}; {@link #degrees()} gives them.
 */
public enum Quadrant {
    R0(0),
    R90(1),
    R180(2),
    R270(3);

    public final int shift;

    Quadrant(int shift) {
        this.shift = shift;
    }

    public int rotateVertexIndex(int index) {
        return (index + this.shift) % 4;
    }

    /** The turn in degrees, as {@code BlockFaceUV#rotation} holds it. */
    public int degrees() {
        return shift * 90;
    }
}
