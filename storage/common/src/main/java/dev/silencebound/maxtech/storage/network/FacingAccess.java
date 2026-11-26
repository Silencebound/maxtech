package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.world.WorldActors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Whether a device's owner may use the block the device faces, as land claim and protection mods see it. There is no
 * shared claim api, but claim mods cancel the loader's right-click event for players who may not use a block, so the
 * owner's fake player (an anonymous one for unowned devices) posts that event for the faced block's face and a refusal
 * counts as "blocked" ({@link WorldActors#mayUse}). Only blocks with a block entity or a handler are asked about, since
 * the posted event also reaches mods that act on right-clicks (harvesting crops); plain blocks are always allowed. A
 * refused device leaves the block alone: ports move nothing, sensors read nothing, pattern providers push and pull
 * nothing, P2P outputs hand nothing on. Placers and breakers ask through the place and break events instead.
 *
 * <p>
 * One instance per device and target. The answer is kept and costs two comparisons per use while it holds: it is asked
 * again only on the first use after {@link #targetChanged} (the block there was replaced; the next use compares the
 * block and asks only when it differs), after {@link #forget}, when the device's position or facing differs from the
 * last check, or once {@code claims.cacheSeconds} have passed. A device that is refused waits that long before it tries
 * again, so a claim lifted or granted is picked up without polling. With {@code claims.enabled} off every use is
 * allowed; unowned devices are allowed unless {@code claims.checkUnowned} is on. Server thread.
 */
public final class FacingAccess {
    private boolean known;
    private boolean allowed = true;
    private boolean replaced;
    private long devicePos;
    private @Nullable Direction facing;
    private @Nullable Block block;
    private long expires;
    private int checks;

    /**
     * Whether {@code owner} may use the block next to {@code device} on its {@code facing} side now. Asks the claim
     * mods only when the kept answer does not hold (see the class); otherwise allocates nothing.
     */
    public boolean allowed(ServerLevel level, BlockPos device, Direction facing, OwnerRecord owner) {
        if (!StorageServerConfig.CLAIMS_ENABLED.getAsBoolean()
                || (!owner.isOwned() && !StorageServerConfig.CLAIMS_CHECK_UNOWNED.getAsBoolean())) {
            known = false;
            allowed = true;
            return true;
        }
        long now = level.getGameTime();
        if (known && now < expires && devicePos == device.asLong() && this.facing == facing) {
            if (!replaced) {
                return allowed;
            }
            BlockPos target = device.relative(facing);
            if (!loaded(level, target)) {
                return allowed;
            }
            replaced = false;
            if (level.getBlockState(target).getBlock().equals(block)) {
                return allowed;
            }
        }
        BlockPos target = device.relative(facing);
        if (!loaded(level, target)) {
            // Never load a chunk to ask; a device finds no handlers there anyway, and asks once it is loaded.
            return allowed;
        }
        Direction side = facing.getOpposite();
        if (protectable(level, target, side)) {
            ServerPlayer actor = WorldActors.get().player(level, owner);
            actor.setPos(device.getX() + 0.5, device.getY() + 0.5 - actor.getEyeHeight(), device.getZ() + 0.5);
            actor.setYRot(facing.getAxis().isHorizontal() ? facing.toYRot() : 0);
            actor.setXRot(facing == Direction.UP ? -90 : facing == Direction.DOWN ? 90 : 0);
            allowed = WorldActors.get().mayUse(level, target, side, actor);
            checks++;
        } else {
            allowed = true;
        }
        known = true;
        replaced = false;
        devicePos = device.asLong();
        this.facing = facing;
        block = level.getBlockState(target).getBlock();
        expires = now + StorageServerConfig.CLAIMS_CACHE_SECONDS.getAsInt() * 20L;
        return allowed;
    }

    /**
     * The block at the target may have been replaced (a block update or a capability change from that side): the next
     * use asks again if the block differs from the one last asked about. Costs nothing until then.
     */
    public void targetChanged() {
        replaced = true;
    }

    /** Drops the kept answer: the next use asks again. For a device that starts (placed or loaded). */
    public void forget() {
        known = false;
        allowed = true;
    }

    /** Whether the last answer was a refusal and still holds; what screens and Jade show. */
    public boolean blocked() {
        return known && !allowed;
    }

    /**
     * Ticks until a refused device should try again: what is left of the kept answer, at least 1. For a device that
     * sleeps while refused.
     */
    public int retryTicks(ServerLevel level) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, expires - level.getGameTime()));
    }

    /**
     * Whether the block at {@code pos} is something claim mods guard against use: it has a block entity or offers an
     * item, fluid or FE handler on {@code side} (containers and machines). The event is posted only for those, since a
     * real right-click event also reaches mods that act on it (right-click harvesting of crops, levers); a plain
     * block's state, comparator output and redstone are read without asking. Looked up only when an answer is made.
     */
    private static boolean protectable(ServerLevel level, BlockPos pos, Direction side) {
        return level.getBlockEntity(pos) != null || Platform.get().transfer().items().find(level, pos, side) != null
                || Platform.get().transfer().fluids().find(level, pos, side) != null
                || FeLookups.BLOCK.find(level, pos, side) != null;
    }

    /** Whether the chunk at {@code pos} is fully loaded; never loads it ({@code isLoaded} is true while it loads). */
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())) != null;
    }

    /** How often this device asked the claim mods; tests use it to see that a kept answer asks nothing. */
    public int checks() {
        return checks;
    }
}
