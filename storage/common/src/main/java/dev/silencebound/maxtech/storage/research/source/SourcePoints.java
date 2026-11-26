package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchPreset;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * How many points a source gives at the current preset (owner decision 2026-10-03: relaxed twice, expert half the
 * standard points). Area C's preset scale multiplies targets and costs, not points earned, so the sources have their
 * own multipliers ({@code research.sources.relaxedPointScale} / {@code expertPointScale}). Discovery and advancement
 * points are area C's and stay unscaled.
 */
public final class SourcePoints {
    private SourcePoints() {
    }

    /** The point multiplier of {@code preset}: {@code relaxed} or {@code expert}, 1 otherwise. Pure. */
    static double scale(ResearchPreset preset, double relaxed, double expert) {
        return switch (preset) {
            case RELAXED -> relaxed;
            case EXPERT -> expert;
            case OFF, STANDARD -> 1.0;
        };
    }

    /** The configured multiplier at the current preset. Server thread. */
    public static double scale() {
        return scale(Research.preset(), StorageServerConfig.SOURCE_RELAXED_POINT_SCALE.getAsDouble(),
                StorageServerConfig.SOURCE_EXPERT_POINT_SCALE.getAsDouble());
    }

    /** {@code base} points scaled and rounded half up; a positive base gives at least 1, 0 stays 0. Pure. */
    static long points(long base, double scale) {
        if (base <= 0) {
            return 0;
        }
        double scaled = base * scale;
        return scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(scaled));
    }

    /**
     * The observer's runs per point at {@code scale}: a scale of 2 halves the runs, so an observer gives twice the
     * points over time while each award stays one whole point. At least 1. Pure.
     */
    static int runsPerPoint(int runs, double scale) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, (long) Math.ceil(runs / scale)));
    }

    /** The observer's daily cap at {@code scale}, rounded half up. Pure. */
    static int dailyCap(int cap, double scale) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(cap * scale));
    }

    /** Adds {@code base} points, scaled, to {@code team}; returns the points added (0 while research is off). */
    static long award(MinecraftServer server, ResearchTeam team, long base) {
        if (!Research.active()) {
            return 0;
        }
        long points = points(base, scale());
        Research.addPoints(server, team, points);
        return points;
    }

    /** Shows {@code message} above the hotbar; a player a GameTest built has no connection to send it to. */
    static void tell(ServerPlayer player, Component message) {
        if (player.connection != null) {
            player.sendOverlayMessage(message);
        }
    }
}
