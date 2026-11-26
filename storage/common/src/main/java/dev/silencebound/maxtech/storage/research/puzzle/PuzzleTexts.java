package dev.silencebound.maxtech.storage.research.puzzle;

import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.resources.Identifier;

/** Lang keys of the puzzle screens and their English text, declared through Storage's content builder. */
public final class PuzzleTexts {
    public static final String SUBMIT = "gui.maxtech_storage.puzzle.submit";
    public static final String WAITING = "gui.maxtech_storage.puzzle.waiting";
    public static final String ACCEPTED = "gui.maxtech_storage.puzzle.accepted";
    public static final String REJECTED = "gui.maxtech_storage.puzzle.rejected";
    public static final String MOVES = "gui.maxtech_storage.puzzle.moves";
    public static final String DIFFICULTY = "gui.maxtech_storage.puzzle.difficulty";
    public static final String ROUTING_STATUS = "gui.maxtech_storage.puzzle.signal_routing.status";
    public static final String ROUTING_HELP = "gui.maxtech_storage.puzzle.signal_routing.help";
    public static final String MASK_STATUS = "gui.maxtech_storage.puzzle.mask_alignment.status";
    public static final String MASK_TARGET = "gui.maxtech_storage.puzzle.mask_alignment.target";
    public static final String MASK_CURRENT = "gui.maxtech_storage.puzzle.mask_alignment.current";
    public static final String MASK_LABEL = "gui.maxtech_storage.puzzle.mask_alignment.mask";
    public static final String MASK_ROTATE = "gui.maxtech_storage.puzzle.mask_alignment.rotate";
    public static final String MASK_FLIP = "gui.maxtech_storage.puzzle.mask_alignment.flip";
    public static final String MASK_HELP = "gui.maxtech_storage.puzzle.mask_alignment.help";
    public static final String MASK_HELP_FLIP = "gui.maxtech_storage.puzzle.mask_alignment.help_flip";
    public static final String PACKING_STATUS = "gui.maxtech_storage.puzzle.byte_packing.status";
    public static final String PACKING_BANK = "gui.maxtech_storage.puzzle.byte_packing.bank";
    public static final String PACKING_HELP = "gui.maxtech_storage.puzzle.byte_packing.help";
    public static final String WAVE_STATUS = "gui.maxtech_storage.puzzle.waveform_tuning.status";
    public static final String WAVE_FREQUENCY = "gui.maxtech_storage.puzzle.waveform_tuning.frequency";
    public static final String WAVE_AMPLITUDE = "gui.maxtech_storage.puzzle.waveform_tuning.amplitude";
    public static final String WAVE_PHASE = "gui.maxtech_storage.puzzle.waveform_tuning.phase";
    public static final String WAVE_HELP = "gui.maxtech_storage.puzzle.waveform_tuning.help";
    private static final String[] DIFFICULTIES = {"easy", "medium", "hard"};

    private PuzzleTexts() {
    }

    /** The screen title of puzzle {@code id}. */
    public static String title(Identifier id) {
        return "gui." + id.getNamespace() + ".puzzle." + id.getPath();
    }

    /** The name of difficulty {@code difficulty}, clamped. */
    public static String difficulty(int difficulty) {
        return "gui.maxtech_storage.puzzle.difficulty." + DIFFICULTIES[PuzzleType.clampDifficulty(difficulty)];
    }

    static void init() {
        ModContent.CONTENT.translation(title(SignalRouting.ID), "Signal Routing");
        ModContent.CONTENT.translation(title(MaskAlignment.ID), "Mask Alignment");
        ModContent.CONTENT.translation(title(BytePacking.ID), "Byte Packing");
        ModContent.CONTENT.translation(title(WaveformTuning.ID), "Waveform Tuning");
        ModContent.CONTENT.translation(difficulty(PuzzleType.EASY), "Easy");
        ModContent.CONTENT.translation(difficulty(PuzzleType.MEDIUM), "Medium");
        ModContent.CONTENT.translation(difficulty(PuzzleType.HARD), "Hard");
        ModContent.CONTENT.translation(SUBMIT, "Submit");
        ModContent.CONTENT.translation(WAITING, "Checking...");
        ModContent.CONTENT.translation(ACCEPTED, "Solved: the research node is complete");
        ModContent.CONTENT.translation(REJECTED, "Not accepted; keep trying");
        ModContent.CONTENT.translation(MOVES, "%s moves");
        ModContent.CONTENT.translation(DIFFICULTY, "Difficulty: %s");
        ModContent.CONTENT.translation(ROUTING_STATUS, "%s of %s outputs connected");
        ModContent.CONTENT.translation(ROUTING_HELP,
                "Click to turn a tile, right-click to turn it back. Arrows and Enter work too.");
        ModContent.CONTENT.translation(MASK_STATUS, "%s cells differ");
        ModContent.CONTENT.translation(MASK_TARGET, "Target");
        ModContent.CONTENT.translation(MASK_CURRENT, "Exposed");
        ModContent.CONTENT.translation(MASK_LABEL, "Mask %s");
        ModContent.CONTENT.translation(MASK_ROTATE, "Rotate");
        ModContent.CONTENT.translation(MASK_FLIP, "Flip");
        ModContent.CONTENT.translation(MASK_HELP,
                "Turn the masks until the exposed cells match. Keys 1 to 3 turn them too.");
        ModContent.CONTENT.translation(MASK_HELP_FLIP,
                "Turn and flip masks until the exposed cells match. Keys 1 to 3 turn, Shift and a key flips.");
        ModContent.CONTENT.translation(PACKING_STATUS, "%s kinds left");
        ModContent.CONTENT.translation(PACKING_BANK, "Bank %s: %s/%s");
        ModContent.CONTENT.translation(PACKING_HELP,
                "Pick a kind, then click a bank. Click a stored kind to take it out.");
        ModContent.CONTENT.translation(WAVE_STATUS, "Match %s%%");
        ModContent.CONTENT.translation(WAVE_FREQUENCY, "Frequency %s");
        ModContent.CONTENT.translation(WAVE_AMPLITUDE, "Amplitude %s");
        ModContent.CONTENT.translation(WAVE_PHASE, "Phase %s°");
        ModContent.CONTENT.translation(WAVE_HELP,
                "Click or scroll a dial. Up and down pick one, left and right turn it.");
    }
}
