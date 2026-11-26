package dev.silencebound.maxtech.storage.port;

import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * The port parts' shapes (MTX item 4, shape A): three square steps, each 2 px deep, 10, 6 and 2 px wide, reaching from
 * the block face to the cable's arm. The exporter's steps shrink towards the inventory, a pyramid pointing at it; the
 * importer's widen towards it, a funnel; the storage bus holds the tip between two wide plates, a spool. Only the
 * smallest step (the tip) is cyan. The port blocks are plain full cubes (owner, 2026-10-17); their fronts show the
 * direction with arrows instead.
 *
 * <p>
 * Every box is in pixels for a part whose front faces north, lying against {@code z = 0}, the side the part's face
 * turns to the inventory. Lists run from the block face inwards. Shared by the item models (datagen) and the cable
 * model, so they cannot drift apart. Immutable; any thread.
 */
public final class PortShapes {
    /** How deep each step is, in pixels. */
    public static final int STEP_DEPTH = 2;

    private static final int[] PART_SIZES = {10, 6, 2};
    private static final List<AABB> PART_EXPORT = steps(PART_SIZES, false);
    private static final List<AABB> PART_IMPORT = steps(PART_SIZES, true);
    /**
     * The storage bus (B09 item 8): two 10 px plates with the cyan 2 px tip between them, a spool, so it reads as
     * neither direction.
     */
    private static final List<AABB> PART_STORAGE = List.of(box(10, 0), box(PART_SIZES[PART_SIZES.length - 1], 1),
            box(10, 2));

    private PortShapes() {
    }

    /** A part's three steps in {@code mode}, from the block face inwards. */
    public static List<AABB> partSteps(PortMode mode) {
        return switch (mode) {
            case IMPORT -> PART_IMPORT;
            case EXPORT -> PART_EXPORT;
            case STORAGE -> PART_STORAGE;
        };
    }

    /** A centred square step {@code size} px wide, the {@code index}th from the block face. */
    private static AABB box(int size, int index) {
        double lo = (16 - size) / 2.0;
        return new AABB(lo, lo, index * STEP_DEPTH, lo + size, lo + size, (index + 1) * STEP_DEPTH);
    }

    /** Whether {@code step}, one of the boxes above, is the smallest of its set, the cyan tip. */
    public static boolean tip(AABB step) {
        return step.getXsize() == PART_SIZES[PART_SIZES.length - 1];
    }

    /**
     * Whether a face of {@code steps.get(index)} lies wholly against a step at least as wide, so no model draws it: its
     * front face (towards the block face) against the step before it, or its back face against the step after it.
     */
    public static boolean hidden(List<AABB> steps, int index, boolean front) {
        int next = front ? index - 1 : index + 1;
        if (next < 0 || next >= steps.size()) {
            return false;
        }
        return steps.get(next).getXsize() >= steps.get(index).getXsize();
    }

    /**
     * The steps of {@code sizes} (largest first), centred, from {@code z = 0} inwards: largest at the block face when
     * {@code widening} (the importer's funnel), smallest there otherwise (the exporter's pyramid).
     */
    private static List<AABB> steps(int[] sizes, boolean widening) {
        AABB[] out = new AABB[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            int size = widening ? sizes[i] : sizes[sizes.length - 1 - i];
            double lo = (16 - size) / 2.0;
            out[i] = new AABB(lo, lo, i * STEP_DEPTH, lo + size, lo + size, (i + 1) * STEP_DEPTH);
        }
        return List.of(out);
    }
}
