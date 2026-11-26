package dev.silencebound.maxtech.storage.research.puzzle.client;

import dev.silencebound.maxtech.storage.research.puzzle.BytePacking;
import dev.silencebound.maxtech.storage.research.puzzle.MaskAlignment;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.research.puzzle.SignalRouting;
import dev.silencebound.maxtech.storage.research.puzzle.WaveformTuning;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * The client side of puzzle play: the screen factory per puzzle id, opened when the server sends
 * {@link PuzzlePayloads.Open}, and the routing of {@link PuzzlePayloads.Result} to the open puzzle screen. Installed by
 * Storage's client entrypoint; nothing here loads on a dedicated server. Client thread.
 */
public final class PuzzleScreens implements PuzzleSessions.ClientHandler {
    private static final Map<Identifier, Function<PuzzlePayloads.Open, Screen>> FACTORIES = new HashMap<>();

    private PuzzleScreens() {
    }

    /** Registers the four puzzles' screens and hooks the payloads up; call once from the client entrypoint. */
    public static void install() {
        register(SignalRouting.ID, SignalRoutingScreen::new);
        register(MaskAlignment.ID, MaskAlignmentScreen::new);
        register(BytePacking.ID, BytePackingScreen::new);
        register(WaveformTuning.ID, WaveformTuningScreen::new);
        PuzzleSessions.setClientHandler(new PuzzleScreens());
    }

    /** Adds the screen for puzzle {@code id}; another puzzle type registers its screen here. */
    public static void register(Identifier id, Function<PuzzlePayloads.Open, Screen> factory) {
        FACTORIES.put(id, factory);
    }

    @Override
    public void open(PuzzlePayloads.Open payload) {
        Function<PuzzlePayloads.Open, Screen> factory = FACTORIES.get(payload.puzzleId());
        if (factory != null) {
            Minecraft.getInstance().setScreen(factory.apply(payload));
        }
    }

    @Override
    public void result(PuzzlePayloads.Result payload) {
        if (Minecraft.getInstance().screen instanceof PuzzleScreen<?> screen && screen.session() == payload.session()) {
            screen.showResult(payload.accepted());
        }
    }
}
