package dev.silencebound.maxtech.storage.display;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.world.phys.AABB;

/**
 * The display parts a client has received a value for, so the part renderer can draw them without a block entity
 * renderer on the cable (CLAUDE.md: conduits never get one). A part joins with its first update; the renderer drops
 * parts that are gone ({@link #prune}). Client main thread.
 */
public final class DisplayClientParts {
    /** Each part with its block's box, made once for the frustum check. */
    private static final Reference2ObjectLinkedOpenHashMap<DisplayPart, AABB> PARTS = new Reference2ObjectLinkedOpenHashMap<>();

    private DisplayClientParts() {
    }

    static void add(DisplayPart part) {
        if (!PARTS.containsKey(part)) {
            PARTS.put(part, new AABB(part.pos()));
        }
    }

    /** The known parts with their boxes; do not change the map. */
    public static Reference2ObjectMap<DisplayPart, AABB> parts() {
        return PARTS;
    }

    /** Drops removed parts (broken, or their chunk unloaded) and, with {@code all}, everything (leaving a world). */
    public static void prune(boolean all) {
        if (all) {
            PARTS.clear();
            return;
        }
        ObjectIterator<DisplayPart> it = PARTS.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().isRemoved()) {
                it.remove();
            }
        }
    }
}
