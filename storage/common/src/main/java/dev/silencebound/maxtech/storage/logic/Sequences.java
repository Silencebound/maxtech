package dev.silencebound.maxtech.storage.logic;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Names and rules of the step sequence node (M7L2 row 16): a few named steps run one after another, such as fill, wait,
 * empty and clean. A sequence of {@code n} steps has the inputs "reset" then, per step, "go on when" ({@code
 * next_k}) and "go to this step while" ({@code go_k}); the outputs "step", "time ran out" and, per step, "entered"
 * ({@code entered_k}); and the settings {@code steps}, then per step its name ({@code name_k}) and its time limit in
 * seconds ({@code timeout_k}). The type declares ports for {@link #MAX_STEPS} steps; a node shows those of its own step
 * count ({@link NodeType#inputs(NodeSettings)}), always a prefix, so port positions never move.
 *
 * <p>
 * Moving on, checked whenever an input changes, the time limit is due or the step changed in the run before (at most
 * one move per run, so a chain of yes conditions steps once per tick):
 * <ol>
 * <li>"reset" fired: back to step 1, entering it again even when it is there;</li>
 * <li>a "go to" input is yes: to the lowest such step; while it stays yes the sequence stays there, and neither its "go
 * on" input nor its time limit moves it;</li>
 * <li>the current step's "go on" input turned yes since the last look, or is yes when the step is looked at right after
 * entering it: to the next step, after the last back to the first;</li>
 * <li>the current step's time limit passed: to the next step, and "time ran out" fires.</li>
 * </ol>
 * A "go on" input that stays yes counts once, not on every run (PRD 8.1: work on events). Steps entered one after
 * another through "go on" inputs that were already yes stop after one full round of the steps, until an input changes,
 * so inputs that are all yes never keep the sequence going round every tick. Pure; any thread.
 */
public final class Sequences {
    /** Fewest steps. */
    public static final int MIN_STEPS = 2;
    /** Most steps; the type declares ports and settings for this many. */
    public static final int MAX_STEPS = 16;
    /** Steps of a new sequence. */
    public static final int DEFAULT_STEPS = 3;
    /** Most characters of a step name shown or matched; longer names are cut. */
    public static final int MAX_NAME = 24;
    /** Most seconds a step's time limit can be (a day). */
    public static final long MAX_SECONDS = NodeTypes.MAX_SECONDS;

    static final String STEPS = "steps";
    static final String RESET = "reset";
    /** The "step" output and the step-is node's input. */
    public static final String STEP = "step";
    /** The "time ran out" event (not "timed_out", which Wait until's "gave up" output is called). */
    public static final String TIME_UP = "time_up";
    /** The step-is node's setting: a step name or number. */
    static final String WHICH = "which";

    // Memory of a sequence node: the current step (0 before the first run), the tick its time limit is due (0: none),
    // which "go on" inputs were yes at the last look (bit k - 1 for step k) with ENTERING set while the step entered
    // last
    // run is still to be looked at, and how many steps in a row it entered through "go on" inputs already yes.
    static final int MEM_STEP = 0;
    static final int MEM_DUE = 1;
    static final int MEM_SEEN = 2;
    static final int MEM_CHAIN = 3;
    static final int MEMORY = 4;
    /** In {@link #MEM_SEEN}: the step was just entered and its "go on" input counts once even if it was yes already. */
    static final long ENTERING = 1L << MAX_STEPS;

    private Sequences() {
    }

    /** The "go on when" input of step {@code k} (1 based). */
    public static String next(int k) {
        return "next_" + k;
    }

    /** The "go to this step while" input of step {@code k}. */
    public static String go(int k) {
        return "go_" + k;
    }

    /** The "entered" event output of step {@code k}. */
    public static String entered(int k) {
        return "entered_" + k;
    }

    /** The name setting of step {@code k}. */
    public static String name(int k) {
        return "name_" + k;
    }

    /** The time limit setting of step {@code k}, in seconds; 0 is none. */
    public static String timeout(int k) {
        return "timeout_" + k;
    }

    /** The input index of step {@code k}'s "go on when" input. */
    static int nextIndex(int k) {
        return 2 * k - 1;
    }

    /** The input index of step {@code k}'s "go to" input. */
    static int goIndex(int k) {
        return 2 * k;
    }

    /** The output index of step {@code k}'s "entered" event. */
    static int enteredIndex(int k) {
        return 1 + k;
    }

    /** How many steps a sequence with {@code settings} has, within {@link #MIN_STEPS} and {@link #MAX_STEPS}. */
    public static int steps(NodeSettings<?> settings) {
        return (int) Math.clamp(settings.number(STEPS).orElse(DEFAULT_STEPS), MIN_STEPS, MAX_STEPS);
    }

    /** Inputs a sequence of {@code steps} steps shows: reset, then two per step. */
    static int inputCount(int steps) {
        return 1 + 2 * steps;
    }

    /** Outputs a sequence of {@code steps} steps shows: step, time ran out, then one per step. */
    static int outputCount(int steps) {
        return 2 + steps;
    }

    /** The name the player gave step {@code k}, cleaned; empty when none. */
    public static String typedName(NodeSettings<?> settings, int k) {
        return clean(settings.text(name(k)).orElse(""));
    }

    /** Step {@code k}'s name as shown: the typed one, else "Step k" through {@code defaultName}. */
    public static String shownName(NodeSettings<?> settings, int k,
            java.util.function.IntFunction<String> defaultName) {
        String typed = typedName(settings, k);
        return typed.isEmpty() ? defaultName.apply(k) : typed;
    }

    /** {@code typed} as a step name: trimmed and cut to {@link #MAX_NAME} characters. */
    public static String clean(String typed) {
        String name = typed.strip();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).strip() : name;
    }

    /** Step {@code k}'s time limit in seconds; 0 for none. */
    public static long timeoutSeconds(NodeSettings<?> settings, int k) {
        return Math.clamp(settings.number(timeout(k)).orElse(0), 0, MAX_SECONDS);
    }

    /**
     * The step {@code which} names in a sequence with {@code settings}: a step whose typed name equals it ignoring case
     * (the first such), else a step number written in digits within the step count. Empty when none.
     */
    public static OptionalInt find(NodeSettings<?> settings, String which) {
        String wanted = clean(which).toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return OptionalInt.empty();
        }
        int steps = steps(settings);
        for (int k = 1; k <= steps; k++) {
            if (typedName(settings, k).toLowerCase(Locale.ROOT).equals(wanted)) {
                return OptionalInt.of(k);
            }
        }
        return number(wanted, steps);
    }

    /** {@code which} as a step number from 1 to {@code steps}, when it is digits only. */
    static OptionalInt number(String which, int steps) {
        String digits = which.strip();
        if (digits.isEmpty() || digits.length() > 3) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return OptionalInt.empty();
            }
        }
        int k = Integer.parseInt(digits);
        return k >= 1 && k <= steps ? OptionalInt.of(k) : OptionalInt.empty();
    }
}
