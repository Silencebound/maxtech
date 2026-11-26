package dev.silencebound.maxtech.storage.logic.controller;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * A team mod's answer to "which team is this player in", for the logic budget, team notices ({@link TeamBudget}) and
 * the server caps. FTB Teams installs one when it is loaded ({@code compat.ftbteams}); without one, a player's team is
 * their research team, else the player alone. GameTests install a fake. Server thread; called on every graph run, so an
 * answer must be a cheap lookup that never blocks.
 */
@FunctionalInterface
public interface TeamSource {
    /**
     * The id of the shared team {@code player} is in, or null when they are in none (only their own personal team) or
     * the team mod has not loaded its data yet; null falls back to the research team. A team id must never equal a
     * player's UUID, so a team and a lone player never share a budget by accident.
     */
    @Nullable
    UUID team(MinecraftServer server, UUID player);
}
