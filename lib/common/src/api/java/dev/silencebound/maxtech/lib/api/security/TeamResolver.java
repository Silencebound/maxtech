package dev.silencebound.maxtech.lib.api.security;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;

/**
 * Answers whether a player is on an owner's team. Lib asks vanilla scoreboard teams itself and, with FTB Teams
 * installed, FTB Teams; another team mod adds a resolver with {@link OwnerSecurity#registerTeamResolver}. A resolver
 * that does not know the owner returns false.
 */
@FunctionalInterface
public interface TeamResolver {
    /**
     * Whether {@code player} shares a team with the owner {@code owner}, whose last known name is {@code ownerName}
     * (possibly empty). Called on the thread of the access check, usually the server thread; must not block.
     */
    boolean sameTeam(Player player, UUID owner, String ownerName);
}
