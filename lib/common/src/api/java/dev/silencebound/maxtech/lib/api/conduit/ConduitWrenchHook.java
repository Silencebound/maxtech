package dev.silencebound.maxtech.lib.api.conduit;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

/**
 * A conduit type's own handling of a wrench, set with {@link Conduits.Builder#onWrench}. Lib asks it before doing its
 * own thing (cycling the hit face, or with {@code dismantle} taking off the facade, then the conduit), so it can take a
 * wrench on something mounted in the conduit.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface ConduitWrenchHook {
    /** Leaves every wrench to Lib. */
    ConduitWrenchHook NONE = (level, conduit, player, hit, dismantle) -> InteractionResult.PASS;

    /**
     * A wrench was used on {@code conduit}. Return {@link InteractionResult#PASS} to leave it to Lib, or the result
     * (normally {@code SUCCESS}, so the arm swings) to stop there. Called on both sides; change things only on the
     * server. Mutators on {@code conduit} are no-ops on the client.
     */
    InteractionResult onWrench(Level level, ConduitHost conduit, Player player, BlockHitResult hit, boolean dismantle);
}
