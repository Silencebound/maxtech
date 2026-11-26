package dev.silencebound.maxtech.storage.logic.controller;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Lets an optional mod watch the logic controllers: each rule run and each notice. KubeJS installs a listener
 * ({@code compat.kubejs}) that hands them to scripts as read-only events; tests install a recorder. Nothing is
 * allocated for a run unless a listener says it {@link Listener#wantsRuns() wants runs}. Server thread only.
 */
public final class LogicEvents {
    /** What a listener hears. Both calls come after the fact; a listener cannot change or cancel anything. */
    public interface Listener {
        /** Whether {@link #ruleRan} should be called; checked before every run, so it must be cheap. */
        boolean wantsRuns();

        /**
         * Rule {@code rule} (row {@code index}) of the controller at {@code pos} ran at {@code gameTime}, costing
         * {@code cost} checks and asking for {@code actions} actions; {@code simulated} when it only recorded them.
         */
        void ruleRan(ServerLevel level, BlockPos pos, String rule, int index, int cost, int actions, boolean simulated,
                long gameTime);

        /**
         * A notify node of rule {@code rule} at {@code pos} posted {@code text} to {@code channels}
         * ({@code LogicContext.CHANNEL_*} bits), for the team {@code team} ({@code TeamBudget#UNOWNED} for none).
         */
        void notice(ServerLevel level, BlockPos pos, String rule, String text, int channels, UUID team);
    }

    private static @Nullable Listener listener;

    private LogicEvents() {
    }

    /** Installs the listener; null removes it. During mod construction, or on the server thread in tests. */
    public static void setListener(@Nullable Listener replacement) {
        listener = replacement;
    }

    /** Whether a listener is installed; tests check that none is left over. */
    public static boolean listening() {
        return listener != null;
    }

    /** The listener, or null. */
    static @Nullable Listener listener() {
        return listener;
    }
}
