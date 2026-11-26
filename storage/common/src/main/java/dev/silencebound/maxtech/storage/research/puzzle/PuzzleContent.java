package dev.silencebound.maxtech.storage.research.puzzle;

/** The four research puzzles and their payloads; loaded from {@code ModContent.init()}. */
public final class PuzzleContent {
    /** Network nodes. */
    public static final PuzzleType<SignalRouting.Puzzle> SIGNAL_ROUTING = PuzzleTypes.register(new SignalRouting());
    /** Processor nodes. */
    public static final PuzzleType<MaskAlignment.Puzzle> MASK_ALIGNMENT = PuzzleTypes.register(new MaskAlignment());
    /** Memory nodes. */
    public static final PuzzleType<BytePacking.Puzzle> BYTE_PACKING = PuzzleTypes.register(new BytePacking());
    /** Signal and wireless nodes. */
    public static final PuzzleType<WaveformTuning.Puzzle> WAVEFORM_TUNING = PuzzleTypes.register(new WaveformTuning());

    private PuzzleContent() {
    }

    public static void init() {
        PuzzleSessions.register();
        PuzzleTexts.init();
    }
}
