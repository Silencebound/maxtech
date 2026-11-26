package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import net.minecraft.resources.Identifier;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * The one research check other Storage code calls (PRD 7.2, Research). {@code unlock} is a node id or an unlock id a
 * node lists (the ones Storage checks are in {@link ResearchGates}); an id no loaded node names is unlocked. Research
 * is shared per team: the player's or owner's team as Lib's owner security resolves it. At the {@code off} preset
 * everything is unlocked.
 */
public final class ResearchUnlocks {
    private ResearchUnlocks() {
    }

    /**
     * Whether {@code player}'s team has {@code unlock}. Authoritative on the server; on the client it answers true, so
     * client-side predictions (slot placement) never refuse what the server allows.
     */
    public static boolean isUnlocked(Player player, Identifier unlock) {
        if (!(player instanceof ServerPlayer serverPlayer) || !Research.active()) {
            return true;
        }
        return isUnlocked(serverPlayer.level().getServer(), Research.team(serverPlayer, false), unlock);
    }

    /**
     * Whether the team of {@code owner} (a machine's or network's owner) has {@code unlock}. Unowned things are treated
     * as unlocked only when research is off, so a machine nobody placed cannot bypass research. Server thread.
     */
    public static boolean isUnlocked(ServerLevel level, OwnerRecord owner, Identifier unlock) {
        if (!owner.isOwned() || !Research.active()) {
            return true;
        }
        MinecraftServer server = level.getServer();
        ResearchTeam team = Research.data(server).team(server, owner);
        return team == null || isUnlocked(server, team, unlock);
    }

    /** Whether {@code team} has {@code unlock}: the node itself, or any node listing it, is complete. */
    public static boolean isUnlocked(MinecraftServer server, ResearchTeam team, Identifier unlock) {
        if (!Research.active()) {
            return true;
        }
        ResearchTree tree = Research.tree(server);
        ResearchTree.Entry node = tree.node(unlock);
        if (node != null) {
            return Research.isComplete(team, node);
        }
        List<ResearchTree.Entry> granting = tree.granting(unlock);
        if (granting.isEmpty()) {
            return true;
        }
        for (int i = 0; i < granting.size(); i++) {
            if (Research.isComplete(team, granting.get(i))) {
                return true;
            }
        }
        return false;
    }
}
