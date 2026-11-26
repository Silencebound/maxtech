package dev.silencebound.maxtech.storage.research.puzzle;

import dev.silencebound.maxtech.lib.platform.NetworkHelper;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Server side of puzzle play. Research (area C) calls {@link #start} with the node, puzzle and seed it picked; this
 * remembers the issued puzzle per player and asks the client to show it. The client sends only its final configuration;
 * the server generates the puzzle again from the remembered seed, validates the configuration, answers with a result,
 * and on success tells every {@link SolvedListener}. Wrong answers keep the session open (free retries); submissions
 * are accepted at most once every {@link #SUBMIT_COOLDOWN_TICKS} ticks per player. Server thread only.
 *
 * <p>
 * The client is never trusted with the seed or the puzzle id: a submission names only the session number, and a stale
 * or forged session number is refused.
 */
public final class PuzzleSessions {
    /**
     * Ticks between two submissions a player may make. A performance and spam bound, not a balance value: validation
     * regenerates the puzzle, which for mask alignment searches its orientation sets.
     */
    public static final int SUBMIT_COOLDOWN_TICKS = 10;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final List<SolvedListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static int nextSession = 1;
    private static @Nullable ClientHandler clientHandler;
    private static @Nullable Consumer<PuzzlePayloads.Result> resultObserver;

    private PuzzleSessions() {
    }

    /** Registers the payloads and clears sessions when the server stops; call once during mod construction. */
    public static void register() {
        NetworkHelper network = Platform.get().network();
        network.registerToClient(MaxtechStorage.MOD_ID, PuzzlePayloads.Open.TYPE, PuzzlePayloads.Open.CODEC,
                PuzzleSessions::receiveOpen);
        network.registerToServer(MaxtechStorage.MOD_ID, PuzzlePayloads.Submit.TYPE, PuzzlePayloads.Submit.CODEC,
                (payload, player) -> submit(payload, player));
        network.registerToClient(MaxtechStorage.MOD_ID, PuzzlePayloads.Result.TYPE, PuzzlePayloads.Result.CODEC,
                PuzzleSessions::receiveResult);
        Platform.get().lifecycle().onServerStopped(server -> {
            SESSIONS.clear();
            nextSession = 1;
        });
    }

    /**
     * Issues puzzle {@code puzzleId} at {@code difficulty} (clamped to {@link PuzzleType#EASY} to
     * {@link PuzzleType#HARD}) with {@code seed} for research node {@code nodeId}, replacing the player's previous
     * session, and asks the player's client to show it. Empty when no puzzle type has that id, so the caller can fall
     * back to tasks or points.
     */
    public static Optional<Session> start(ServerPlayer player, Identifier nodeId, Identifier puzzleId, long seed,
            int difficulty) {
        if (PuzzleTypes.get(puzzleId).isEmpty()) {
            return Optional.empty();
        }
        Session session = new Session(nextSession++, nodeId, puzzleId, seed, PuzzleType.clampDifficulty(difficulty));
        SESSIONS.put(player.getUUID(), session);
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player,
                    new PuzzlePayloads.Open(session.id(), nodeId, puzzleId, seed, session.difficulty()));
        }
        return Optional.of(session);
    }

    /** The player's open session, if any. */
    public static Optional<Session> current(Player player) {
        return Optional.ofNullable(SESSIONS.get(player.getUUID()));
    }

    /** Drops the player's session, e.g. when research completes the node another way. */
    public static void cancel(Player player) {
        SESSIONS.remove(player.getUUID());
    }

    /** Adds a listener told about every accepted solution; call during mod construction. */
    public static void onSolved(SolvedListener listener) {
        LISTENERS.add(listener);
    }

    /**
     * Handles a submission: refused for a client-side player, an unknown or stale session, a submission inside the
     * cooldown or a wrong configuration. Answers the client with a {@link PuzzlePayloads.Result} unless the session was
     * unknown or the cooldown applied. Returns what it answered, or empty when it dropped the payload.
     */
    public static Optional<Boolean> submit(PuzzlePayloads.Submit payload, Player sender) {
        if (!(sender instanceof ServerPlayer player)) {
            return Optional.empty();
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.id() != payload.session()) {
            return Optional.empty();
        }
        long now = player.level().getGameTime();
        if (now - session.lastSubmit < SUBMIT_COOLDOWN_TICKS) {
            return Optional.empty();
        }
        session.lastSubmit = now;
        Optional<PuzzleType<?>> type = PuzzleTypes.get(session.puzzleId());
        boolean accepted = type.isPresent()
                && type.get().check(session.seed(), session.difficulty(), payload.solution().toIntArray());
        if (accepted) {
            SESSIONS.remove(player.getUUID());
        }
        PuzzlePayloads.Result result = new PuzzlePayloads.Result(session.id(), accepted);
        Consumer<PuzzlePayloads.Result> observer = resultObserver;
        if (observer != null) {
            observer.accept(result);
        }
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, result);
        }
        if (accepted) {
            for (SolvedListener listener : LISTENERS) {
                listener.solved(player, session.nodeId(), session.puzzleId(), session.difficulty());
            }
        }
        return Optional.of(accepted);
    }

    /** Set by Storage's client entrypoint; the handler opens screens and shows results. */
    public static void setClientHandler(ClientHandler handler) {
        clientHandler = handler;
    }

    /** Sees every result {@link #submit} sends; GameTest players have no connection. */
    public static void setResultObserver(@Nullable Consumer<PuzzlePayloads.Result> observer) {
        resultObserver = observer;
    }

    private static void receiveOpen(PuzzlePayloads.Open payload, Player player) {
        ClientHandler handler = clientHandler;
        if (player.level().isClientSide() && handler != null) {
            handler.open(payload);
        }
    }

    private static void receiveResult(PuzzlePayloads.Result payload, Player player) {
        ClientHandler handler = clientHandler;
        if (player.level().isClientSide() && handler != null) {
            handler.result(payload);
        }
    }

    /** One issued puzzle. {@code difficulty} is already clamped. */
    public static final class Session {
        private final int id;
        private final Identifier nodeId;
        private final Identifier puzzleId;
        private final long seed;
        private final int difficulty;
        private long lastSubmit = Long.MIN_VALUE / 2;

        Session(int id, Identifier nodeId, Identifier puzzleId, long seed, int difficulty) {
            this.id = id;
            this.nodeId = nodeId;
            this.puzzleId = puzzleId;
            this.seed = seed;
            this.difficulty = difficulty;
        }

        public int id() {
            return id;
        }

        public Identifier nodeId() {
            return nodeId;
        }

        public Identifier puzzleId() {
            return puzzleId;
        }

        public long seed() {
            return seed;
        }

        public int difficulty() {
            return difficulty;
        }
    }

    /** Told on the server thread when a player solves a puzzle; research completes the node here. */
    @FunctionalInterface
    public interface SolvedListener {
        void solved(ServerPlayer player, Identifier nodeId, Identifier puzzleId, int difficulty);
    }

    /** Client-side reactions to the server's payloads, installed by the client entrypoint. */
    public interface ClientHandler {
        /** Shows the puzzle; client thread. */
        void open(PuzzlePayloads.Open payload);

        /** Shows whether the last submission was accepted; client thread. */
        void result(PuzzlePayloads.Result payload);
    }
}
