package dev.silencebound.maxtech.storage.dev;

import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplayText;
import dev.silencebound.maxtech.storage.dev.ShowcaseSteps.Check;
import dev.silencebound.maxtech.storage.dev.ShowcaseSteps.Step;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The step walls of the showcase: one board per built station, listing {@link ShowcaseSteps} on a display wall with a
 * box per step that turns green once the step's detector sees it done (or, for a step without one, once someone ticks
 * it). Boards live in memory only while their build stands: {@code showcase clear} and a rebuild over them remove them,
 * and so does losing the wall's panel. Detectors run once a second ({@link #CHECK_INTERVAL}) for the steps still open,
 * and the wall's text is set only when a step changed. Nothing here exists unless a dev run built a showcase. Server
 * thread.
 */
public final class ShowcaseBoards {
    /** Ticks between detector checks. */
    static final int CHECK_INTERVAL = 20;
    /** Wall size in panels, along x and y, and its pad-local corner (west end, bottom) on the north fence line. */
    static final int WALL_X = 5;
    static final int WALL_WIDTH = 4;
    static final int WALL_HEIGHT = 3;

    static final String OPEN_BOX = "☐";
    static final String DONE_MARK = "✔";
    static final String PROBLEM_MARK = "✘";

    private static final List<Board> BOARDS = new ArrayList<>();
    private static int clock;

    private ShowcaseBoards() {
    }

    /** One station's wall and the state of its steps. */
    static final class Board {
        final ServerLevel level;
        final ShowcaseStation station;
        final ShowcaseSteps.Spot spot;
        /** A panel of the wall (its west bottom one). */
        final BlockPos panel;
        List<Step> steps;
        Check[] states;

        Board(ServerLevel level, ShowcaseStation station, BlockPos origin, BlockPos hub) {
            this.level = level;
            this.station = station;
            this.spot = new ShowcaseSteps.Spot(level, origin, hub);
            this.panel = origin.offset(WALL_X, 0, 0);
            this.steps = ShowcaseSteps.steps(station);
            this.states = open(steps.size());
        }

        boolean wallContains(BlockPos pos) {
            int dx = pos.getX() - panel.getX();
            int dy = pos.getY() - panel.getY();
            return pos.getZ() == panel.getZ() && dx >= 0 && dx < WALL_WIDTH && dy >= 0 && dy < WALL_HEIGHT;
        }

        void reset() {
            steps = ShowcaseSteps.steps(station);
            states = open(steps.size());
        }

        /** Shows the current text on the wall; false when the wall is gone. */
        boolean show() {
            if (!(level.getBlockEntity(panel) instanceof DisplayPanelBlockEntity display)) {
                return false;
            }
            display.showText(markup(station, steps, states));
            return true;
        }

        /** Runs the detectors of the steps not yet done; true when one changed. */
        boolean check() {
            boolean changed = false;
            for (int i = 0; i < steps.size(); i++) {
                ShowcaseSteps.Detector detector = steps.get(i).detector();
                if (detector == null || states[i] == Check.DONE) {
                    continue;
                }
                Check now = detector.check(spot);
                if (now != states[i]) {
                    states[i] = now;
                    changed = true;
                }
            }
            return changed;
        }
    }

    private static Check[] open(int size) {
        Check[] states = new Check[size];
        Arrays.fill(states, Check.OPEN);
        return states;
    }

    /**
     * Starts a board for {@code station} built at {@code origin} with its hub at {@code hub}, after removing any board
     * whose wall the new build covers, and shows its steps. Returns false when the wall is missing.
     */
    static boolean start(ServerLevel level, ShowcaseStation station, BlockPos origin, BlockPos hub) {
        Board board = new Board(level, station, origin, hub);
        BOARDS.removeIf(old -> old.level.equals(level) && old.panel.equals(board.panel));
        if (!board.show()) {
            return false;
        }
        BOARDS.add(board);
        return true;
    }

    /** Removes the boards whose wall lies in {@code bounds}. */
    static void remove(ServerLevel level, AABB bounds) {
        BOARDS.removeIf(board -> board.level.equals(level) && bounds.contains(board.panel.getCenter()));
    }

    /** How many boards have their wall in {@code bounds}; for tests. */
    public static int count(ServerLevel level, AABB bounds) {
        int count = 0;
        for (Board board : BOARDS) {
            if (board.level.equals(level) && bounds.contains(board.panel.getCenter())) {
                count++;
            }
        }
        return count;
    }

    /** Whether step {@code index} of the board on the pad at {@code origin} is done; for tests. */
    public static boolean done(ServerLevel level, BlockPos origin, int index) {
        Board board = board(level, origin.offset(WALL_X, 0, 0));
        return board != null && index >= 0 && index < board.states.length && board.states[index] == Check.DONE;
    }

    /**
     * Checks the open steps about once a second and updates the walls whose steps changed; drops boards whose wall is
     * gone. Called every server tick by the NeoForge glue; costs nothing without a board. Server thread.
     */
    static void tick() {
        if (BOARDS.isEmpty() || ++clock % CHECK_INTERVAL != 0) {
            return;
        }
        for (int i = BOARDS.size() - 1; i >= 0; i--) {
            Board board = BOARDS.get(i);
            if (!board.level.isLoaded(board.panel)) {
                continue;
            }
            if (!(board.level.getBlockEntity(board.panel) instanceof DisplayPanelBlockEntity)) {
                BOARDS.remove(i);
                continue;
            }
            if (board.check()) {
                board.show();
            }
        }
    }

    /** Opens every step of every board in {@code level} again and runs their detectors afresh. Returns the boards. */
    public static int reset(ServerLevel level) {
        int count = 0;
        for (Board board : BOARDS) {
            if (board.level.equals(level)) {
                board.reset();
                board.show();
                count++;
            }
        }
        return count;
    }

    /**
     * Ticks or unticks the hand-ticked step {@code step} (from 1) of the wall at {@code wall}. Returns the step's text
     * when it changed, empty when there is no such wall or the step has a detector.
     */
    public static Optional<Component> toggle(ServerLevel level, BlockPos wall, int step) {
        Board board = board(level, wall);
        int index = step - 1;
        if (board == null || index < 0 || index >= board.steps.size() || !board.steps.get(index).manual()) {
            return Optional.empty();
        }
        board.states[index] = board.states[index] == Check.DONE ? Check.OPEN : Check.DONE;
        board.show();
        return Optional.of(DisplayText.component(line(board.steps.get(index), board.states[index])));
    }

    /**
     * The chat lines for a player who used the wall at {@code pos}: the station's name and each step, the hand-ticked
     * ones with a clickable tick or untick; empty when {@code pos} is no showcase wall.
     */
    public static Optional<List<Component>> describe(ServerLevel level, BlockPos pos) {
        Board board = board(level, pos);
        if (board == null) {
            return Optional.empty();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(board.station.nameKey()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        for (int i = 0; i < board.steps.size(); i++) {
            Step step = board.steps.get(i);
            MutableComponent line = DisplayText.component(line(step, board.states[i]));
            if (step.manual()) {
                boolean done = board.states[i] == Check.DONE;
                String command = "/maxtech_storage steps tick " + board.panel.getX() + " " + board.panel.getY() + " "
                        + board.panel.getZ() + " " + (i + 1);
                line = Component.empty()
                        .append(Component.translatable(done ? Showcase.Messages.UNTICK : Showcase.Messages.TICK)
                                .withStyle(style -> style.withColor(ChatFormatting.GOLD)
                                        .withClickEvent(new ClickEvent.RunCommand(command))
                                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(command)))))
                        .append(" ").append(line);
            }
            lines.add(line);
        }
        return Optional.of(lines);
    }

    /** Forgets every board; the server is stopping. */
    static void forget() {
        BOARDS.clear();
        clock = 0;
    }

    private static @Nullable Board board(ServerLevel level, BlockPos pos) {
        for (Board board : BOARDS) {
            if (board.level.equals(level) && board.wallContains(pos)) {
                return board;
            }
        }
        return null;
    }

    /** The wall's markup: the station's name, a legend and a line per step. */
    static String markup(ShowcaseStation station, List<Step> steps, Check[] states) {
        StringBuilder out = new StringBuilder();
        out.append("<center><big><aqua>").append(station.english()).append('\n');
        out.append("<center><small><gray>").append(OPEN_BOX).append(" checked by the showcase, <gold>").append(OPEN_BOX)
                .append("</> ticked by hand: use the wall");
        for (int i = 0; i < steps.size(); i++) {
            out.append('\n').append(line(steps.get(i), states[i]));
        }
        return out.toString();
    }

    /**
     * One step's line: a box (gold when ticked by hand), the action in white and the result in grey; all green done.
     */
    static String line(Step step, Check state) {
        return switch (state) {
            case DONE -> "<green>" + DONE_MARK + " " + step.action() + ": " + step.expected();
            case PROBLEM -> "<red>" + PROBLEM_MARK + " " + step.action() + "<gray>: " + step.expected();
            case OPEN -> (step.manual() ? "<gold>" : "<white>") + OPEN_BOX + "</> <white>" + step.action() + "<gray>: "
                    + step.expected();
        };
    }
}
