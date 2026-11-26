package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTypes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The puzzle payloads survive their codecs, and the server validates a submission against the puzzle it issued: a stale
 * session, a wrong answer and a second submission inside the cooldown are refused, and the right answer reaches the
 * solved listeners once. A test puzzle type keeps this independent of the real puzzles.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PuzzleSessionGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final String[] NAMES = {"puzzle_payload_round_trip", "puzzle_server_validates",
            "puzzle_real_types_validate"};
    private static final Identifier NODE = MaxtechStorage.id("test_node");
    private static final List<Solved> SOLVED = new ArrayList<>();
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final PuzzleType<Integer> GUESS = PuzzleTypes.register(new GuessPuzzle());

    static {
        FUNCTIONS.register(NAMES[0], () -> PuzzleSessionGameTests::payloadRoundTrip);
        FUNCTIONS.register(NAMES[1], () -> PuzzleSessionGameTests::serverValidates);
        FUNCTIONS.register(NAMES[2], () -> PuzzleSessionGameTests::realTypesValidate);
        PuzzleSessions.onSolved(
                (player, node, puzzle, difficulty) -> SOLVED.add(new Solved(player, node, puzzle, difficulty)));
    }

    private PuzzleSessionGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("puzzle"));
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    private static void payloadRoundTrip(GameTestHelper helper) {
        PuzzlePayloads.Open open = new PuzzlePayloads.Open(7, NODE, GUESS.id(), -1234567890123L, PuzzleType.HARD);
        helper.assertValueEqual(open, roundTrip(PuzzlePayloads.Open.CODEC, open), "open payload");
        PuzzlePayloads.Submit submit = new PuzzlePayloads.Submit(7, IntArrayList.of(0, 3, -1, 1 << 20));
        helper.assertValueEqual(submit, roundTrip(PuzzlePayloads.Submit.CODEC, submit), "submit payload");
        PuzzlePayloads.Result result = new PuzzlePayloads.Result(7, true);
        helper.assertValueEqual(result, roundTrip(PuzzlePayloads.Result.CODEC, result), "result payload");

        ByteBuf forged = Unpooled.buffer();
        VarInt.write(forged, 7);
        VarInt.write(forged, PuzzlePayloads.MAX_SOLUTION_LENGTH + 1);
        boolean refused = false;
        try {
            PuzzlePayloads.Submit.CODEC.decode(forged);
        } catch (RuntimeException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "a solution longer than the cap must not decode");
        helper.succeed();
    }

    private static void serverValidates(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "puzzler");
        List<PuzzlePayloads.Result> results = new ArrayList<>();
        PuzzleSessions.setResultObserver(results::add);
        helper.assertTrue(PuzzleSessions.start(player, NODE, MaxtechStorage.id("no_such_puzzle"), 1L, 0).isEmpty(),
                "an unknown puzzle id starts nothing");
        long seed = 41L;
        PuzzleSessions.Session session = PuzzleSessions.start(player, NODE, GUESS.id(), seed, 5).orElseThrow();
        helper.assertValueEqual(PuzzleType.HARD, session.difficulty(), "clamped difficulty");
        int answer = GUESS.knownSolution(GUESS.generate(seed, PuzzleType.HARD))[0];

        helper.assertValueEqual(Optional.empty(), submit(player, session.id() + 1, answer),
                "a stale session number is dropped");
        helper.assertValueEqual(Optional.of(false), submit(player, session.id(), answer + 1), "a wrong answer");
        helper.assertValueEqual(Optional.empty(), submit(player, session.id(), answer),
                "a second submission inside the cooldown is dropped");
        helper.assertTrue(SOLVED.stream().noneMatch(solved -> solved.player().equals(player)), "nothing solved yet");

        helper.runAfterDelay(PuzzleSessions.SUBMIT_COOLDOWN_TICKS, () -> {
            helper.assertValueEqual(Optional.of(true), submit(player, session.id(), answer), "the right answer");
            helper.assertTrue(PuzzleSessions.current(player).isEmpty(), "a solved session closes");
            helper.assertValueEqual(Optional.empty(), submit(player, session.id(), answer),
                    "a solved session takes no more submissions");
            List<Solved> mine = SOLVED.stream().filter(solved -> solved.player().equals(player)).toList();
            helper.assertValueEqual(List.of(new Solved(player, NODE, GUESS.id(), PuzzleType.HARD)), mine,
                    "listeners told once");
            helper.assertValueEqual(
                    List.of(new PuzzlePayloads.Result(session.id(), false),
                            new PuzzlePayloads.Result(session.id(), true)),
                    results.stream().filter(result -> result.session() == session.id()).toList(), "results answered");
            PuzzleSessions.setResultObserver(null);

            PuzzleSessions.Session next = PuzzleSessions.start(player, NODE, GUESS.id(), seed + 1, 0).orElseThrow();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            helper.assertTrue(PuzzleSessions.current(player).isEmpty(), "logging out drops the session");
            helper.assertValueEqual(Optional.empty(), submit(player, next.id(), 0),
                    "a dropped session takes no submissions");
            helper.succeed();
        });
    }

    /**
     * Each of the four puzzles at each difficulty, through the submit codec: the scrambled start is refused, the known
     * solution accepted after the cooldown.
     */
    private static void realTypesValidate(GameTestHelper helper) {
        List<PuzzleType<?>> types = List.of(PuzzleContent.SIGNAL_ROUTING, PuzzleContent.MASK_ALIGNMENT,
                PuzzleContent.BYTE_PACKING, PuzzleContent.WAVEFORM_TUNING);
        List<Runnable> afterCooldown = new ArrayList<>();
        for (PuzzleType<?> type : types) {
            helper.assertTrue(PuzzleTypes.get(type.id()).isPresent(), type.id() + " registered");
            for (int difficulty = PuzzleType.EASY; difficulty <= PuzzleType.HARD; difficulty++) {
                afterCooldown.add(startAndRefuse(helper, type, difficulty));
            }
        }
        helper.runAfterDelay(PuzzleSessions.SUBMIT_COOLDOWN_TICKS, () -> {
            afterCooldown.forEach(Runnable::run);
            helper.succeed();
        });
    }

    private static <S> Runnable startAndRefuse(GameTestHelper helper, PuzzleType<S> type, int difficulty) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "puzzler");
        long seed = type.id().hashCode() * 31L + difficulty;
        PuzzleSessions.Session session = PuzzleSessions.start(player, NODE, type.id(), seed, difficulty).orElseThrow();
        S puzzle = type.generate(seed, difficulty);
        String where = type.id() + " difficulty " + difficulty;
        helper.assertValueEqual(Optional.of(false), submitThroughCodec(player, session.id(), type.start(puzzle)),
                where + ": the scrambled start");
        return () -> helper.assertValueEqual(Optional.of(true),
                submitThroughCodec(player, session.id(), type.knownSolution(puzzle)), where + ": the solution");
    }

    private static Optional<Boolean> submitThroughCodec(ServerPlayer player, int session, int[] solution) {
        PuzzlePayloads.Submit sent = new PuzzlePayloads.Submit(session, IntArrayList.wrap(solution));
        return PuzzleSessions.submit(roundTrip(PuzzlePayloads.Submit.CODEC, sent), player);
    }

    private static Optional<Boolean> submit(ServerPlayer player, int session, int answer) {
        return PuzzleSessions.submit(new PuzzlePayloads.Submit(session, IntArrayList.of(answer)), player);
    }

    private static <T> T roundTrip(StreamCodec<ByteBuf, T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, value);
        return codec.decode(buf);
    }

    private record Solved(ServerPlayer player, Identifier node, Identifier puzzle, int difficulty) {
    }

    /** Guess the number derived from the seed; the answer differs per difficulty. */
    private static final class GuessPuzzle implements PuzzleType<Integer> {
        @Override
        public Identifier id() {
            return MaxtechStorage.id("test_guess");
        }

        @Override
        public Integer generate(long seed, int difficulty) {
            return (int) Math.floorMod(seed * 31 + PuzzleType.clampDifficulty(difficulty), 97L);
        }

        @Override
        public boolean validate(Integer puzzle, int[] solution) {
            return solution.length == 1 && solution[0] == puzzle;
        }

        @Override
        public int[] knownSolution(Integer puzzle) {
            return new int[]{puzzle};
        }

        @Override
        public int[] start(Integer puzzle) {
            return new int[]{puzzle + 1};
        }
    }
}
