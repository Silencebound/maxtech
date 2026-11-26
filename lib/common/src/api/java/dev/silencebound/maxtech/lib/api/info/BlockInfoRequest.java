package dev.silencebound.maxtech.lib.api.info;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a player asked about. Made on the server thread and handed to {@link BlockInfoProvider#addInfo}; use it on that
 * thread only.
 *
 * @param level
 *            the level, on the server thread
 * @param pos
 *            the block
 * @param side
 *            the face the player targeted, or {@code null} if unknown
 * @param hit
 *            the exact hit location, or {@code null} if unknown; lets a multi-part block describe the targeted part
 * @param detail
 *            how much to say
 */
public record BlockInfoRequest(ServerLevel level, BlockPos pos, @Nullable Direction side, @Nullable Vec3 hit,
        Detail detail) {

    /** How much a provider should say. */
    public enum Detail {
        /** At most three entries for an always-on overlay: status and the most important number. */
        BRIEF,
        /**
         * Everything useful: the brief entries, then the details. For an explicit scan and for an overlay's "show
         * details" state (sneaking, by default).
         */
        FULL
    }
}
