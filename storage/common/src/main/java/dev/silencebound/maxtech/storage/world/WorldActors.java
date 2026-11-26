package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The loader's part of a device acting on the world as its owner: a fake player for the owner, the break event that
 * protection mods cancel for the placer and breaker, and the right-click event that claim mods cancel for every device
 * that uses the block it faces ({@code network.FacingAccess}). Placing needs nothing here, since a placement through
 * {@code ItemStack.useOn} fires the loader's place event by itself. Storage's NeoForge entrypoint installs the
 * implementation during construction; it is a Storage-internal seam rather than a {@code Platform} method because only
 * Storage's devices need it. Server thread.
 */
public interface WorldActors {
    /**
     * A fake player for {@code owner}: the owner's id when owned, so claims and protection mods judge the device as its
     * owner. The same instance may come back for the same owner; callers set its position, rotation and hand.
     */
    ServerPlayer player(ServerLevel level, OwnerRecord owner);

    /** Whether {@code player} may break {@code state} at {@code pos}: the loader's break event was not cancelled. */
    boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player);

    /**
     * Whether {@code player} may use the block at {@code pos} through its face {@code side}: the loader's right-click
     * event for that face, with an empty main hand, was neither cancelled nor told not to use the block. Only posts the
     * event; nothing is used.
     */
    boolean mayUse(ServerLevel level, BlockPos pos, Direction side, ServerPlayer player);

    /** Installs the loader's implementation; called once during mod construction. */
    static void install(WorldActors actors) {
        Holder.installed = actors;
    }

    /** The installed implementation; throws when none is (outside the game). */
    static WorldActors get() {
        WorldActors actors = Holder.installed;
        if (actors == null) {
            throw new IllegalStateException("No world actors installed");
        }
        return actors;
    }

    /** Where the implementation is kept. */
    final class Holder {
        private static volatile @Nullable WorldActors installed;

        private Holder() {
        }
    }
}
