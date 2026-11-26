package dev.silencebound.maxtech.storage.research.puzzle.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTexts;
import dev.silencebound.maxtech.storage.research.puzzle.WaveformTuning;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Waveform tuning: the target wave dashed in amber and the tuned wave in cyan over one period, a match meter, and one
 * stepped dial per setting (phase only from medium up). Click a dial's track to set it or scroll over it; up and down
 * pick a dial, left and right step it. Client only.
 */
final class WaveformTuningScreen extends PuzzleScreen<WaveformTuning.Puzzle> {
    private static final int PLOT_WIDTH = 220;
    private static final int PLOT_HEIGHT = 72;
    private static final int METER = 6;
    private static final int DIAL_ROW = 16;
    private static final int LABEL_WIDTH = 96;
    private static final int TRACK_HEIGHT = 8;
    /** The error at which the meter reads zero, as in the prototype. */
    private static final double METER_RANGE = 0.9;
    private final int[] target;
    private int dial;

    WaveformTuningScreen(PuzzlePayloads.Open open) {
        super(PuzzleContent.WAVEFORM_TUNING, open);
        target = puzzle.target();
    }

    private int dialCount() {
        return puzzle.steps(WaveformTuning.PHASE) > 1 ? WaveformTuning.DIALS : WaveformTuning.DIALS - 1;
    }

    private int dialsY() {
        return PLOT_HEIGHT + METER + 8;
    }

    @Override
    protected int boardWidth() {
        return PLOT_WIDTH;
    }

    @Override
    protected int boardHeight() {
        return dialsY() + dialCount() * DIAL_ROW;
    }

    @Override
    protected void extractBoard(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        graphics.fill(x - 1, y - 1, x + PLOT_WIDTH + 1, y + PLOT_HEIGHT + 1, AccentFrame.SLOT_EDGE);
        graphics.fill(x, y, x + PLOT_WIDTH, y + PLOT_HEIGHT, AccentFrame.SLOT);
        for (int i = 1; i < 8; i++) {
            int gx = x + i * PLOT_WIDTH / 8;
            graphics.fill(gx, y, gx + 1, y + PLOT_HEIGHT, AccentFrame.BEVEL);
        }
        for (int i = 1; i < 4; i++) {
            int gy = y + i * PLOT_HEIGHT / 4;
            graphics.fill(x, gy, x + PLOT_WIDTH, gy + 1, AccentFrame.BEVEL);
        }
        plot(graphics, x, y, target, AMBER, true);
        plot(graphics, x, y, config, LIT, false);

        double error = puzzle.error(config, target);
        AccentFrame.horizontalBar(graphics, x, y + PLOT_HEIGHT + 4, PLOT_WIDTH, METER, (float) match(error));

        for (int d = 0; d < dialCount(); d++) {
            int rowY = y + dialsY() + d * DIAL_ROW;
            TextScale.text(graphics, font, dialLabel(d), x, rowY + 1, d == dial ? AccentFrame.TEXT : AccentFrame.LABEL,
                    false);
            int trackX = x + LABEL_WIDTH;
            int trackWidth = PLOT_WIDTH - LABEL_WIDTH;
            graphics.fill(trackX - 1, rowY - 1, trackX + trackWidth + 1, rowY + TRACK_HEIGHT + 1,
                    d == dial ? AccentFrame.ACCENT_DIM : AccentFrame.SLOT_EDGE);
            graphics.fill(trackX, rowY, trackX + trackWidth, rowY + TRACK_HEIGHT, AccentFrame.SLOT);
            int steps = puzzle.steps(d);
            int knob = trackX + (trackWidth - 4) * config[d] / Math.max(1, steps - 1);
            graphics.fill(knob, rowY - 1, knob + 4, rowY + TRACK_HEIGHT + 1, LIT);
        }
    }

    /** The wave as a two pixel line, one column at a time, joining neighbouring columns; dashed every 6 columns. */
    private void plot(GuiGraphicsExtractor graphics, int x, int y, int[] dials, int colour, boolean dashed) {
        int mid = y + PLOT_HEIGHT / 2;
        double scale = PLOT_HEIGHT / 2.0 - 4;
        int previous = mid - (int) Math.round(puzzle.sample(dials, 0) * scale);
        for (int column = 0; column < PLOT_WIDTH; column++) {
            double angle = column * 2 * Math.PI / PLOT_WIDTH;
            int current = mid - (int) Math.round(puzzle.sample(dials, angle) * scale);
            if (!dashed || column / 6 % 2 == 0) {
                int from = Math.min(previous, current);
                int to = Math.max(previous, current);
                graphics.fill(x + column, from - 1, x + column + 1, to + 1, colour);
            }
            previous = current;
        }
    }

    private static double match(double error) {
        return Math.clamp(1 - error / METER_RANGE, 0.0, 1.0);
    }

    private Component dialLabel(int d) {
        return switch (d) {
            case WaveformTuning.FREQUENCY -> Component.translatable(PuzzleTexts.WAVE_FREQUENCY,
                    String.format(Locale.ROOT, "%.1f", puzzle.frequency(config[d])));
            case WaveformTuning.AMPLITUDE -> Component.translatable(PuzzleTexts.WAVE_AMPLITUDE,
                    String.format(Locale.ROOT, "%.1f", puzzle.amplitude(config[d])));
            default ->
                Component.translatable(PuzzleTexts.WAVE_PHASE, Math.round(Math.toDegrees(puzzle.phase(config[d]))));
        };
    }

    @Override
    protected boolean clickBoard(int x, int y, MouseButtonEvent event) {
        int d = (y - dialsY()) / DIAL_ROW;
        int trackX = x - LABEL_WIDTH;
        if (y < dialsY() || d >= dialCount() || trackX < 0) {
            return false;
        }
        dial = d;
        int steps = puzzle.steps(d);
        int trackWidth = PLOT_WIDTH - LABEL_WIDTH;
        set(d, (int) Math.round((double) trackX * (steps - 1) / trackWidth));
        return true;
    }

    @Override
    protected boolean scrollBoard(int x, int y, double amount) {
        int d = (y - dialsY()) / DIAL_ROW;
        if (y < dialsY() || d >= dialCount() || amount == 0) {
            return false;
        }
        dial = d;
        set(d, config[d] + (amount > 0 ? 1 : -1));
        return true;
    }

    @Override
    protected boolean keyBoard(KeyEvent event) {
        if (event.isUp()) {
            dial = Math.floorMod(dial - 1, dialCount());
        } else if (event.isDown()) {
            dial = Math.floorMod(dial + 1, dialCount());
        } else if (event.isLeft() || event.isRight()) {
            set(dial, config[dial] + (event.isRight() ? 1 : -1));
        } else {
            return false;
        }
        return true;
    }

    private void set(int d, int step) {
        int clamped = Math.clamp(step, 0, puzzle.steps(d) - 1);
        if (clamped != config[d]) {
            config[d] = clamped;
            changed();
        }
    }

    @Override
    protected Component progress() {
        return Component.translatable(PuzzleTexts.WAVE_STATUS, Math.round(match(puzzle.error(config, target)) * 100));
    }

    @Override
    protected Component help() {
        return Component.translatable(PuzzleTexts.WAVE_HELP);
    }
}
