package dev.silencebound.maxtech.storage.logic;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Reads a graph as a {@link RuleCard} (M7L2 chunk 11): one saved graph, two views. Every line comes from a node's
 * sentence ({@link NodeType#sentenceKey()}) with its settings filled in and its wired inputs replaced by what feeds
 * them, so a value used once reads inline ("craft 32 Stick"), while a value several nodes read gets a name in LET and
 * memory nodes get one in REMEMBER. The sections:
 *
 * <ul>
 * <li>WHEN: the event nodes that run an action, joined by OR. For an action with an event input, its event wire is
 * followed back through If, Delay, Cooldown and Wait until (which become checks and "after 5 s"-style steps) to the
 * event node; an event from Compare or "Turns yes or no" becomes a "turns yes" check. An action without an event (a
 * device switch, redstone out) runs when its inputs change, so every event node upstream of it wakes it.</li>
 * <li>IF: the checks every action shares, as lines; an And at the top splits into lines, nested And / Or become ALL OF
 * / ANY OF with their parts indented, Not flips a part. A switch or redstone action whose on/off input is a shared
 * check reads "switch X on, otherwise off".</li>
 * <li>THEN: the actions in graph order; a check only one action has reads "if ...:" in its line, and a trigger set that
 * differs from the others' marks the card custom and reads "on ...:".</li>
 * </ul>
 *
 * Nodes the card cannot place show as "Custom step: name" lines in {@link RuleCard.Section#OTHER}, and the card is then
 * marked custom ("Custom graph · N nodes"), as it is for graphs with no action or a loop. Pure; any thread; it
 * allocates freely, so callers build a card only when the graph changes.
 */
public final class RuleCards {
    /** Longest line text kept. */
    public static final int MAX_TEXT = 512;
    /** How deep the card follows wires and nests ALL OF / ANY OF lines before it reads the rest inline. */
    private static final int MAX_DEPTH = 24;
    private static final int MAX_NEST = 6;
    private static final int MAX_CHAIN = 64;
    // Settings in a line's text are marked while it is built: OPEN, a character for the field's number, the text,
    // CLOSE. line() takes the marks out and keeps where each part sits. Private-use characters, which typed text
    // loses (clean), so a label cannot fake a mark.
    private static final char OPEN = '';
    private static final char CLOSE = '';
    private static final char FIELD_BASE = '';
    private static final char MARK_LAST = '';
    private static final int MAX_FIELDS = MARK_LAST - FIELD_BASE;

    // Section words and the words between lines.
    public static final String SECTION_REMEMBER = LogicLang.add("card.section.remember", "REMEMBER");
    public static final String SECTION_LET = LogicLang.add("card.section.let", "LET");
    public static final String SECTION_WHEN = LogicLang.add("card.section.when", "WHEN");
    public static final String SECTION_IF = LogicLang.add("card.section.if", "IF");
    public static final String SECTION_THEN = LogicLang.add("card.section.then", "THEN");
    public static final String SECTION_OTHER = LogicLang.add("card.section.other", "ALSO");
    public static final String WORD_OR = LogicLang.add("card.word.or", "OR");
    public static final String WORD_AND = LogicLang.add("card.word.and", "AND");

    /** The header of a custom card; one argument, the node count. */
    public static final String CUSTOM = LogicLang.add("card.custom", "Custom graph · %s nodes");
    /** A node the card cannot place; one argument, the node's name. */
    public static final String CUSTOM_STEP = LogicLang.add("card.custom_step", "Custom step: %s");
    public static final String EMPTY = LogicLang.add("card.empty", "This rule has no nodes yet");

    // Pieces of lines.
    static final String NAMED = LogicLang.add("card.named", "%s: %s");
    /** A name the player typed (a label, a shared value's name); generated names show bare. */
    static final String REF = LogicLang.add("card.ref", "\"%s\"");
    /**
     * "yes" when the language lower-cases the first letter of choice and port names inside a sentence ("Above" reads
     * "above"); any other text keeps them as the lang file writes them.
     */
    static final String LOWER_WORDS = LogicLang.add("card.lower_words", "yes");
    // Memory nodes in REMEMBER.
    static final String MEM_LATCH = LogicLang.add("card.memory.latch", "on once %s");
    static final String MEM_LATCH_RESET = LogicLang.add("card.memory.latch_reset", "on once %s, until %s");
    static final String MEM_COUNTER = LogicLang.add("card.memory.counter", "counts each time %s");
    static final String MEM_TOGGLE = LogicLang.add("card.memory.toggle", "flips on or off each time %s");
    static final String MEM_HOLD = LogicLang.add("card.memory.hold", "%s as it was when %s");
    static final String MEM_DOWN = LogicLang.add("card.memory.down", "%s, down each time %s");
    static final String MEM_STEP = LogicLang.add("card.memory.step", "%s, in steps of %s");
    static final String MEM_RESET = LogicLang.add("card.memory.reset", "%s, back to 0 when %s");
    // Checks read from a node's yes/no output, and values of event nodes, with their subject.
    static final String CHECK_DEVICE_ON = LogicLang.add("card.check.device_on", "%s is on");
    static final String CHECK_DAY = LogicLang.add("card.check.day", "it is day");
    static final String CHECK_RAINING = LogicLang.add("card.check.raining", "it is raining");
    static final String CHECK_CRAFTING = LogicLang.add("card.check.crafting", "%s is being crafted");
    // Stock status yes/no outputs (M7L2 row 15); the arguments are the item and the keeper's label.
    static final String CHECK_STOCK_BELOW = LogicLang.add("card.check.stock_below",
            "%s in %s is below its restart amount");
    static final String CHECK_STOCK_CRAFTING = LogicLang.add("card.check.stock_crafting",
            "stock keeper %2$s is crafting %1$s");
    static final String CHECK_STOCK_ON = LogicLang.add("card.check.stock_on", "the stock rule for %s in %s is on");
    static final String STOCK_SWITCH_WHILE = LogicLang.add("card.stock_switch_while",
            "switch the stock rule for %s in %s on while %s, otherwise off");
    static final String STOCK_SWITCH_LIFTED = LogicLang.add("card.stock_switch_lifted",
            "switch the stock rule for %s in %s on, otherwise off");
    // Pool status (M7L2 row 17): "any idle" as a check of its own; the argument is the pool's label.
    static final String CHECK_POOL_IDLE = LogicLang.add("card.check.pool_idle", "a member of %s is idle");
    static final String CHECK_PAST = LogicLang.add("card.check.past", "%s is %s %s");
    static final String CHECK_SENSOR_PAST = LogicLang.add("card.check.sensor_past", "%s of %s is %s %s");
    static final String CHECK_OVERLOADED = LogicLang.add("card.check.overloaded",
            "the link to %s uses over %s%% of its channels");
    static final String CHECK_FULL = LogicLang.add("card.check.full", "storage in %s is full");
    static final String CHECK_POWERED = LogicLang.add("card.check.powered", "the network has power");
    static final String CHECK_PLAYER_NEAR = LogicLang.add("card.check.player_near", "a player is near %s");
    static final String VALUE_COUNT = LogicLang.add("card.value.count", "the count of %s");
    static final String VALUE_SENSOR = LogicLang.add("card.value.sensor", "%s of %s");
    // Events of event nodes inside another line ("counts each time the redstone changes").
    static final String EVENT_REDSTONE = LogicLang.add("card.event.redstone", "the redstone changes");
    static final String EVENT_COUNT = LogicLang.add("card.event.count", "the count of %s crosses %s");
    static final String EVENT_SENSOR = LogicLang.add("card.event.sensor", "the sensor crosses %s");
    static final String EVENT_TIMER = LogicLang.add("card.event.timer", "the timer fires");
    static final String EVENT_START = LogicLang.add("card.event.start", "the graph starts");
    static final String EVENT_OTHER = LogicLang.add("card.event.other", "%s happens");
    static final String SWITCH_WHILE = LogicLang.add("card.switch_while", "switch %s on while %s, otherwise off");
    static final String REDSTONE_WHILE = LogicLang.add("card.redstone_while",
            "send redstone %s from %s while %s, otherwise off");
    static final String IS_ON = LogicLang.add("card.is_on", "%s is on");
    static final String PART = LogicLang.add("card.part", "%s (%s)");
    static final String WITH = LogicLang.add("card.with", "%s (%s: %s)");
    static final String NOT = LogicLang.add("card.not", "not %s");
    static final String ALL_OF = LogicLang.add("card.all_of", "all of:");
    static final String ANY_OF = LogicLang.add("card.any_of", "any of:");
    static final String NONE_OF = LogicLang.add("card.none_of", "none of:");
    static final String NOT_ALL_OF = LogicLang.add("card.not_all_of", "not all of:");
    static final String IF_EVENT = LogicLang.add("card.if_event", "%s if %s");
    static final String IF_NOT_EVENT = LogicLang.add("card.if_not_event", "%s if not %s");
    static final String GAVE_UP_EVENT = LogicLang.add("card.gave_up_event", "%s, given up");
    static final String AFTER_SECONDS = LogicLang.add("card.after_seconds", "after %s s");
    static final String AFTER_TICKS = LogicLang.add("card.after_ticks", "after %s ticks");
    static final String AT_MOST = LogicLang.add("card.at_most", "at most every %s s");
    static final String WAIT_UNTIL = LogicLang.add("card.wait_until", "wait until %s");
    static final String GAVE_UP = LogicLang.add("card.gave_up", "if %s stays no for %s s");
    static final String STEP = LogicLang.add("card.step", "%s, %s");
    static final String ONLY_IF = LogicLang.add("card.only_if", "if %s: %s");
    static final String ONLY_ON = LogicLang.add("card.only_on", "on %s: %s");
    static final String JOIN_AND = LogicLang.add("card.join_and", "%s and %s");
    static final String JOIN_OR = LogicLang.add("card.join_or", "%s or %s");
    static final String SWITCH_LIFTED = LogicLang.add("card.switch_lifted", "switch %s on, otherwise off");
    static final String REDSTONE_LIFTED = LogicLang.add("card.redstone_lifted",
            "send redstone %s from %s, otherwise off");
    // Network redstone channels (M7L2): "set the red channel to 15 ...", "the red channel changes".
    static final String CHANNEL_WHILE = LogicLang.add("card.channel_while", "set %s to %s while %s, otherwise 0");
    static final String CHANNEL_LIFTED = LogicLang.add("card.channel_lifted", "set %s to %s, otherwise 0");
    static final String EVENT_CHANNEL = LogicLang.add("card.event.channel", "%s changes");
    static final String COMPARE_ABOVE = LogicLang.add("card.compare.above", "%s is above %s");
    static final String COMPARE_BELOW = LogicLang.add("card.compare.below", "%s is below %s");
    static final String COMPARE_BETWEEN = LogicLang.add("card.compare.between", "%s is between %s and %s");
    static final String COMPARE_EQUAL = LogicLang.add("card.compare.equal", "%s is %s");
    static final String COMPARE_NOT_ABOVE = LogicLang.add("card.compare.not_above", "%s is at most %s");
    static final String COMPARE_NOT_BELOW = LogicLang.add("card.compare.not_below", "%s is at least %s");
    static final String COMPARE_NOT_BETWEEN = LogicLang.add("card.compare.not_between", "%s is not between %s and %s");
    static final String COMPARE_NOT_EQUAL = LogicLang.add("card.compare.not_equal", "%s is not %s");
    static final String IS_OFF = LogicLang.add("card.is_off", "%s is off");
    static final String IS_NO = LogicLang.add("card.is_no", "%s is no");
    static final String CHECK_DEVICE_OFF = LogicLang.add("card.check.device_off", "%s is off");
    static final String CHECK_NIGHT = LogicLang.add("card.check.night", "it is night");
    static final String CHECK_NOT_RAINING = LogicLang.add("card.check.not_raining", "it is not raining");
    static final String MATH_ADD = LogicLang.add("card.math.add", "%s + %s");
    static final String MATH_SUBTRACT = LogicLang.add("card.math.subtract", "%s − %s");
    static final String MATH_MULTIPLY = LogicLang.add("card.math.multiply", "%s × %s");
    static final String MATH_DIVIDE = LogicLang.add("card.math.divide", "%s ÷ %s");
    static final String MATH_MIN = LogicLang.add("card.math.min", "the smaller of %s and %s");
    static final String MATH_MAX = LogicLang.add("card.math.max", "the larger of %s and %s");
    static final String MATH_MODULO = LogicLang.add("card.math.modulo", "the remainder of %s ÷ %s");
    static final String THE_NETWORK = LogicLang.add("card.the_network", "the network");
    /** A label on another network (M7L2 row 13): "intake on North Base". */
    static final String LABEL_ON = LogicLang.add("card.label_on", "%s on %s");
    static final String THE_CONTROLLER = LogicLang.add("card.the_controller", "the controller");
    static final String ANY_DEVICE = LogicLang.add("card.any_device", "any device");
    static final String ANY = LogicLang.add("card.any", "any");
    static final String ANY_ITEM = LogicLang.add("card.any_item", "any item");
    static final String UNSET = LogicLang.add("card.unset", "(%s)");
    static final String NEVER = LogicLang.add("card.never", "never");
    /** What event sentences start with; WHEN lines leave it out, since their keyword says it. */
    static final String WHEN_PREFIX = LogicLang.add("card.when_prefix", "when ");
    static final String LOOP = LogicLang.add("card.loop", "…");
    // Step sequences (M7L2 row 16): the STEPS block. Arguments: the sequence's name and step count; a step's number,
    // name, condition and seconds.
    public static final String SECTION_STEPS = LogicLang.add("card.section.steps", "STEPS");
    static final String SEQ_HEADER = LogicLang.add("card.sequence.header", "%s: %s steps in turn");
    static final String STEP_WHEN = LogicLang.add("card.sequence.step_when", "%s. %s: go on when %s");
    static final String STEP_WHEN_OR_AFTER = LogicLang.add("card.sequence.step_when_or_after",
            "%s. %s: go on when %s, or after %s s");
    static final String STEP_AFTER = LogicLang.add("card.sequence.step_after", "%s. %s: go on after %s s");
    static final String STEP_STAYS = LogicLang.add("card.sequence.step_stays", "%s. %s: stays until moved");
    static final String STEP_GO_HERE = LogicLang.add("card.sequence.go_here", "go here from any step while %s");
    static final String STEP_ON_ENTER = LogicLang.add("card.sequence.on_enter", "on entering: %s");
    static final String STEP_WHILE_HERE = LogicLang.add("card.sequence.while_here", "while here: %s");
    static final String SEQ_RESET = LogicLang.add("card.sequence.reset", "back to step 1 when %s");
    static final String SEQ_TIME_UP = LogicLang.add("card.sequence.time_up", "when a step's time runs out: %s");
    static final String WHILE_SWITCH = LogicLang.add("card.sequence.switch", "switch %s on");
    static final String WHILE_REDSTONE = LogicLang.add("card.sequence.redstone", "send redstone %s from %s");
    static final String WHILE_CHANNEL = LogicLang.add("card.sequence.channel", "set %s to %s");
    static final String WHILE_STOCK = LogicLang.add("card.sequence.stock", "switch the stock rule for %s in %s on");
    static final String CHECK_STEP_NOT = LogicLang.add("card.check.step_not", "%s is not at %s");
    static final String EVENT_ENTERED = LogicLang.add("card.event.entered", "%s enters %s");
    static final String EVENT_TIME_UP = LogicLang.add("card.event.time_up", "a step of %s runs out of time");
    /** The mark beside the current step's line. */
    public static final String STEP_NOW = LogicLang.add("card.sequence.now", "◀ now");

    // For each (M7L2 row 18).
    public static final String SECTION_EACH = LogicLang.add("card.section.each", "FOR EACH");
    static final String EACH_TAG = LogicLang.add("card.each.tag", "item in %s");
    static final String EACH_LIST = LogicLang.add("card.each.list", "item in the list %s");
    static final String EACH_STOCK = LogicLang.add("card.each.stock", "item stock keeper %s has a rule for");
    /** A list of more items than the card names: the first ones, then how many more. */
    static final String EACH_MORE = LogicLang.add("card.each.more", "%s and %s more");
    static final String EACH_IT = LogicLang.add("card.each.it", "it");
    /** A failed crafting job's "missing item" where another node takes it: "craft 16 of the missing item". */
    static final String MISSING_ITEM = LogicLang.add("card.missing_item", "the missing item");
    static final String EACH_CHECKED = LogicLang.add("card.each.checked", "the number of items checked");
    static final String EACH_DONE = LogicLang.add("card.each.done", "once every item is checked");
    /** How many list items a FOR EACH line names before "and n more". */
    private static final int EACH_SHOWN = 3;

    private RuleCards() {
    }

    /** Turns a lang key into text in the player's language; the screen passes the client's language. */
    @FunctionalInterface
    public interface Lang {
        /** The text of {@code key} as written, without format arguments. */
        String text(String key);

        /** The text of {@code key} with its {@code %s} filled by {@code args}. */
        default String format(String key, Object... args) {
            return String.format(Locale.ROOT, text(key), args);
        }
    }

    /** Loads the class so its lang lines are declared; {@link LogicLang#english()} calls it. */
    static void init() {
    }

    /** The lang key of {@code section}'s word. */
    static String sectionKey(RuleCard.Section section) {
        return switch (section) {
            case REMEMBER -> SECTION_REMEMBER;
            case LET -> SECTION_LET;
            case STEPS -> SECTION_STEPS;
            case WHEN -> SECTION_WHEN;
            case EACH -> SECTION_EACH;
            case IF -> SECTION_IF;
            case THEN -> SECTION_THEN;
            case OTHER -> SECTION_OTHER;
        };
    }

    /**
     * {@code graph} as a card. {@code lang} gives the texts (sentences are read with {@link Lang#text}, so a literal
     * {@code %} in one is safe); {@code item} names an item setting's value.
     */
    public static <K> RuleCard build(LogicGraph<K> graph, Lang lang, Function<K, String> item) {
        return new Builder<>(graph, lang, item).build();
    }

    /** A check an action passes through: a yes/no value (negated for an If's Otherwise) or a "turns" event. */
    private enum CondKind {
        CHECK, TURNS_YES, TURNS_NO
    }

    private record Cond(CondKind kind, int node, String output, boolean negated) {
    }

    /** What leads to one action. */
    private static final class Path {
        final Set<Integer> triggers = new LinkedHashSet<>();
        /** Checks from the trigger towards the action. */
        final List<Cond> conds = new ArrayList<>();
        /** Steps such as "after 5 s", from the trigger towards the action. */
        final List<String> steps = new ArrayList<>();
        /** The If, Delay, Cooldown and Wait until nodes on the way, and what their steps read. */
        final Set<Integer> chain = new LinkedHashSet<>();
        /** For an action without an event: its on/off input as a check; null when it has none. */
        @Nullable
        Cond level;
        /**
         * The step sequence the action belongs to, so its line goes under the sequence's steps: its event comes from
         * the sequence (an "entered" event, or "time ran out"), or its on/off is "step is" of the sequence. -1 for
         * none.
         */
        int sequence = -1;
        /** Which step of {@link #sequence}: 1 based; 0 for "time ran out". */
        int step;
        /** Whether the action runs while the sequence is at the step (on/off) rather than once on entering. */
        boolean whileHere;
        /** The For each the action runs under, once per item or once after them (M7L2 row 18); -1 for none. */
        int each = -1;
    }

    private static final class Builder<K> {
        private final Lang lang;
        private final Function<K, String> item;
        private final int count;
        private final Map<Integer, LogicNode<K>> nodes = new LinkedHashMap<>();
        private final Map<Integer, Map<String, Wire>> into = new HashMap<>();
        private final Map<Integer, List<Wire>> out = new HashMap<>();
        private final Map<Integer, String> names = new HashMap<>();
        /** Named nodes whose name the player typed (a label, an item, a shared value's name), shown in quotes. */
        private final Set<Integer> typed = new HashSet<>();
        private final Set<Integer> rendering = new HashSet<>();
        private final List<RuleCard.Line> lines = new ArrayList<>();
        /** The settings marked so far, by field number: node id and setting name. */
        private final List<Integer> fieldNodes = new ArrayList<>();
        private final List<String> fieldSettings = new ArrayList<>();
        private boolean custom;

        Builder(LogicGraph<K> graph, Lang lang, Function<K, String> item) {
            this.lang = lang;
            this.item = item;
            this.count = graph.nodes().size();
            for (LogicNode<K> node : graph.nodes()) {
                if (nodes.putIfAbsent(node.id(), node) != null) {
                    custom = true;
                }
            }
            for (Wire wire : graph.wires()) {
                if (!nodes.containsKey(wire.from()) || !nodes.containsKey(wire.to())) {
                    custom = true;
                    continue;
                }
                into.computeIfAbsent(wire.to(), id -> new HashMap<>()).putIfAbsent(wire.input(), wire);
                out.computeIfAbsent(wire.from(), id -> new ArrayList<>()).add(wire);
            }
        }

        RuleCard build() {
            List<LogicNode<K>> actions = new ArrayList<>();
            for (LogicNode<K> node : nodes.values()) {
                if (action(node)) {
                    actions.add(node);
                }
            }
            nameNodes();
            // Actions a step sequence runs go under its steps; the rest are the THEN lines.
            List<LogicNode<K>> stepActions = new ArrayList<>();
            List<Path> stepPaths = new ArrayList<>();
            List<Path> paths = new ArrayList<>(actions.size());
            Set<Integer> fired = new LinkedHashSet<>();
            for (Iterator<LogicNode<K>> it = actions.iterator(); it.hasNext();) {
                LogicNode<K> action = it.next();
                Path path = path(action);
                if (path.sequence >= 0) {
                    stepActions.add(action);
                    stepPaths.add(path);
                    it.remove();
                    continue;
                }
                paths.add(path);
                fired.addAll(path.triggers);
            }
            List<Cond> common = common(paths);

            // REMEMBER and LET.
            for (LogicNode<K> node : nodes.values()) {
                if (memory(node)) {
                    Set<Integer> reads = reads(node.id());
                    String text = lang.format(NAMED, ref(node.id()), memoryText(node, reads));
                    line(RuleCard.Section.REMEMBER, 0, text, reads, live(node));
                }
            }
            for (LogicNode<K> node : nodes.values()) {
                if (!memory(node) && !sequence(node) && names.containsKey(node.id())) {
                    Set<Integer> reads = reads(node.id());
                    String text = lang.format(NAMED, ref(node.id()), own(node, mainOutput(node), reads, 0));
                    line(RuleCard.Section.LET, 0, text, reads, live(node));
                }
            }
            // Step sequences, each step with how it goes on and what runs there.
            for (LogicNode<K> node : nodes.values()) {
                if (sequence(node)) {
                    stepLines(node, stepActions, stepPaths);
                }
            }
            // Event nodes that run no action but feed values: named by the outputs read, like LET.
            List<LogicNode<K>> when = new ArrayList<>();
            for (LogicNode<K> node : nodes.values()) {
                if (!trigger(node) || onlyIntoSequences(node)) {
                    // Read where a sequence's step lines use it.
                    continue;
                }
                if (fired.isEmpty() || fired.contains(node.id()) || !feedsValues(node)) {
                    when.add(node);
                } else {
                    Set<Integer> reads = reads(node.id());
                    String text = lang.format(NAMED, outputsRead(node), sentence(node, reads, 0));
                    line(RuleCard.Section.LET, 0, text, reads, live(node));
                }
            }
            for (LogicNode<K> node : when) {
                Set<Integer> reads = reads(node.id());
                line(RuleCard.Section.WHEN, 0, withoutWhen(sentence(node, reads, 0)), reads, live(node));
            }
            // The items each For each goes through, under the triggers that start it.
            Set<Integer> eaches = new LinkedHashSet<>();
            for (Path path : paths) {
                if (path.each >= 0) {
                    eaches.add(path.each);
                }
            }
            for (int id : eaches) {
                LogicNode<K> node = nodes.get(id);
                if (node != null) {
                    Set<Integer> reads = reads(id);
                    line(RuleCard.Section.EACH, 0, eachText(node), reads, live(node));
                }
            }
            for (Cond cond : common) {
                condLines(cond, 0, List.of());
            }
            for (int i = 0; i < actions.size(); i++) {
                LogicNode<K> action = actions.get(i);
                Path path = paths.get(i);
                Set<Integer> reads = reads(action.id());
                reads.addAll(path.chain);
                String text = actionText(action, path, common, fired, reads);
                line(RuleCard.Section.THEN, 0, text, reads, live(action));
            }
            if (actions.isEmpty() && stepActions.isEmpty() && !nodes.isEmpty()) {
                custom = true;
            }

            // Whatever no line read.
            Set<Integer> covered = new HashSet<>();
            for (RuleCard.Line line : lines) {
                covered.addAll(line.nodes());
            }
            for (LogicNode<K> node : nodes.values()) {
                if (!covered.contains(node.id())) {
                    custom = true;
                    line(RuleCard.Section.OTHER, 0, lang.format(CUSTOM_STEP, lang.text(node.type().nameKey())),
                            reads(node.id()), new Live(-1, "", RuleCard.Live.NONE));
                }
            }
            return new RuleCard(custom, count, lines);
        }

        // ---- roles

        private static boolean trigger(LogicNode<?> node) {
            return node.type().category() == NodeCategory.EVENTS;
        }

        private static boolean memory(LogicNode<?> node) {
            return node.type().category() == NodeCategory.REMEMBER && node.type() != NodeTypes.EDGE
                    && node.type() != NodeTypes.SEQUENCE;
        }

        private static boolean sequence(LogicNode<?> node) {
            return node.type() == NodeTypes.SEQUENCE;
        }

        /** Whether every wire out of {@code node} goes into a step sequence, whose step lines read it. */
        private boolean onlyIntoSequences(LogicNode<K> node) {
            List<Wire> wires = out.getOrDefault(node.id(), List.of());
            for (Wire wire : wires) {
                LogicNode<K> target = nodes.get(wire.to());
                if (target == null || !sequence(target)) {
                    return false;
                }
            }
            return !wires.isEmpty();
        }

        private static boolean action(LogicNode<?> node) {
            return node.type().category() == NodeCategory.ACT;
        }

        /** Whether {@code node} has a wire out of an output that is not an event. */
        private boolean feedsValues(LogicNode<K> node) {
            for (Wire wire : out.getOrDefault(node.id(), List.of())) {
                Port port = output(node, wire.output());
                if (port != null && port.type() != PortType.EVENT) {
                    return true;
                }
            }
            return false;
        }

        /** Names memory nodes and the values more than one input reads. */
        private void nameNodes() {
            Set<String> taken = new HashSet<>();
            for (LogicNode<K> node : nodes.values()) {
                if (trigger(node) || action(node) || node.type() == NodeTypes.FOR_EACH
                        || node.type() == NodeTypes.PARAMETER) {
                    // A For each's item reads as "it" wherever it goes, and its items on the FOR EACH line; a
                    // parameter reads as its value wherever it goes, so a copy's card shows its own values.
                    continue;
                }
                int readers = 0;
                for (Wire wire : out.getOrDefault(node.id(), List.of())) {
                    Port port = output(node, wire.output());
                    if (port != null && port.type() != PortType.EVENT) {
                        readers++;
                    }
                }
                if (memory(node) || readers > 1 || sequence(node)) {
                    String base = baseName(node);
                    if (!node.settings().text(NodeTypes.LABEL_NAME).orElse("").isBlank()
                            || (node.type() == NodeTypes.SHARED_VARIABLE
                                    && !node.settings().text(NodeTypes.NAME).orElse("").isBlank())) {
                        typed.add(node.id());
                    }
                    String name = base;
                    for (int n = 2; !taken.add(name.toLowerCase(Locale.ROOT)); n++) {
                        name = base + " " + n;
                    }
                    names.put(node.id(), name);
                }
            }
        }

        /** A shared variable's name, else the node's label, item or type name. */
        private String baseName(LogicNode<K> node) {
            NodeSettings<K> settings = node.settings();
            if (node.type() == NodeTypes.SHARED_VARIABLE) {
                String name = settings.text(NodeTypes.NAME).orElse("");
                if (!name.isBlank()) {
                    return name;
                }
            }
            String label = settings.text(NodeTypes.LABEL_NAME).orElse("");
            if (!label.isBlank() && node.type().setting(NodeTypes.LABEL_NAME).isPresent()) {
                return label;
            }
            for (SettingSpec spec : node.type().settings()) {
                if (spec.kind().holdsKey() && settings.item(spec.name()).isPresent()) {
                    return item.apply(settings.item(spec.name()).get());
                }
            }
            return lang.text(node.type().nameKey());
        }

        // ---- paths to actions

        private Path path(LogicNode<K> action) {
            Path path = new Path();
            Port doPort = input(action.type(), "do");
            Wire doWire = wire(action.id(), "do");
            if (doPort != null && doPort.type() == PortType.EVENT && (doPort.required() || doWire != null)) {
                if (doWire != null) {
                    follow(doWire, path);
                }
                return path;
            }
            // Runs whenever its inputs change: every event node upstream wakes it.
            path.triggers.addAll(upstreamTriggers(action));
            Port on = input(action.type(), "on");
            Wire onWire = wire(action.id(), "on");
            LogicNode<K> onSource = onWire == null ? null : nodes.get(onWire.from());
            // An event node's own yes/no (a crossing's "past the limit") reads best in the action's line.
            if (on != null && on.type() == PortType.YES_NO && onWire != null && onSource != null
                    && !trigger(onSource)) {
                path.level = new Cond(CondKind.CHECK, onWire.from(), onWire.output(), false);
                placeWhileStep(path, onSource);
            }
            return path;
        }

        /** An on/off from "step is" of a sequence: the action runs while the sequence is at that step. */
        private void placeWhileStep(Path path, LogicNode<K> check) {
            if (check.type() != NodeTypes.STEP_IS) {
                return;
            }
            Wire stepWire = wire(check.id(), Sequences.STEP);
            if (stepWire == null) {
                return;
            }
            LogicNode<K> source = nodes.get(stepWire.from());
            if (source == null || !sequence(source) || !Sequences.STEP.equals(stepWire.output())) {
                return;
            }
            int step = Sequences.find(source.settings(), check.settings().text(Sequences.WHICH).orElse("")).orElse(0);
            if (step > 0) {
                path.sequence = source.id();
                path.step = step;
                path.whileHere = true;
                path.triggers.clear();
                path.chain.add(check.id());
            }
        }

        /** Follows an action's event wire back to the event node, collecting checks and steps. */
        private void follow(Wire start, Path path) {
            Wire wire = start;
            for (int guard = 0; guard < MAX_CHAIN; guard++) {
                LogicNode<K> source = nodes.get(wire.from());
                if (source == null) {
                    custom = true;
                    return;
                }
                if (trigger(source)) {
                    path.triggers.add(source.id());
                    return;
                }
                if (sequence(source)) {
                    // Entering a step, or a step's time running out: the line goes under the sequence's steps.
                    int step = enteredStep(wire.output());
                    if (step > 0 || Sequences.TIME_UP.equals(wire.output())) {
                        path.sequence = source.id();
                        path.step = step;
                    } else {
                        custom = true;
                    }
                    return;
                }
                if (source.type() == NodeTypes.FOR_EACH) {
                    // Once per item ("each"), or once after every item ("done"): the FOR EACH line says which items.
                    path.chain.add(source.id());
                    path.each = source.id();
                    if (ForEach.DONE.equals(wire.output())) {
                        path.steps.addFirst(lang.text(EACH_DONE));
                    } else if (!ForEach.EACH.equals(wire.output())) {
                        custom = true;
                        return;
                    }
                    Wire into = wire(source.id(), "do");
                    if (into == null) {
                        return;
                    }
                    wire = into;
                    continue;
                }
                path.chain.add(source.id());
                NodeType type = source.type();
                Wire next;
                if (type == NodeTypes.IF) {
                    Wire condition = wire(source.id(), "condition");
                    if (condition != null) {
                        path.conds.addFirst(new Cond(CondKind.CHECK, condition.from(), condition.output(),
                                "otherwise".equals(wire.output())));
                    }
                    next = wire(source.id(), "when");
                } else if (type == NodeTypes.DELAY) {
                    long ticks = number(source, NodeTypes.TICKS);
                    path.steps.addFirst(ticks % NodeTypes.TICKS_PER_SECOND == 0
                            ? lang.format(AFTER_SECONDS,
                                    mark(source, NodeTypes.TICKS,
                                            InfoFormat.number(ticks / NodeTypes.TICKS_PER_SECOND)))
                            : lang.format(AFTER_TICKS, mark(source, NodeTypes.TICKS, InfoFormat.number(ticks))));
                    next = wire(source.id(), "start");
                } else if (type == NodeTypes.COOLDOWN) {
                    path.steps.addFirst(lang.format(AT_MOST,
                            mark(source, NodeTypes.SECONDS, InfoFormat.number(number(source, NodeTypes.SECONDS)))));
                    next = wire(source.id(), "in");
                } else if (type == NodeTypes.WAIT_UNTIL) {
                    String condition = placeholder(source, "condition", path.chain, 0);
                    path.steps.addFirst("timed_out".equals(wire.output())
                            ? lang.format(GAVE_UP, condition,
                                    mark(source, NodeTypes.TIMEOUT,
                                            InfoFormat.number(number(source, NodeTypes.TIMEOUT))))
                            : lang.format(WAIT_UNTIL, condition));
                    next = wire(source.id(), "in");
                } else if ((type == NodeTypes.COMPARE || type == NodeTypes.EDGE)
                        && ("turned_yes".equals(wire.output()) || "turned_no".equals(wire.output()))) {
                    path.chain.remove(source.id());
                    path.conds.addFirst(
                            new Cond("turned_yes".equals(wire.output()) ? CondKind.TURNS_YES : CondKind.TURNS_NO,
                                    source.id(), wire.output(), false));
                    path.triggers.addAll(upstreamTriggers(source));
                    return;
                } else {
                    // An event from a node the card cannot read as a step.
                    custom = true;
                    return;
                }
                if (next == null) {
                    return;
                }
                wire = next;
            }
            custom = true;
        }

        /** The event nodes {@code start} reads through any wires, in graph order. */
        private Set<Integer> upstreamTriggers(LogicNode<K> start) {
            Set<Integer> seen = new HashSet<>();
            List<Integer> todo = new ArrayList<>();
            todo.add(start.id());
            seen.add(start.id());
            Set<Integer> found = new HashSet<>();
            while (!todo.isEmpty()) {
                int id = todo.removeLast();
                for (Wire wire : into.getOrDefault(id, Map.of()).values()) {
                    LogicNode<K> source = nodes.get(wire.from());
                    if (source == null || !seen.add(source.id())) {
                        continue;
                    }
                    if (trigger(source)) {
                        found.add(source.id());
                    }
                    todo.add(source.id());
                }
            }
            Set<Integer> ordered = new LinkedHashSet<>();
            for (Integer id : nodes.keySet()) {
                if (found.contains(id)) {
                    ordered.add(id);
                }
            }
            return ordered;
        }

        /** The checks every action has, in the first action's order; none without actions. */
        private static List<Cond> common(List<Path> paths) {
            List<Cond> common = new ArrayList<>();
            if (paths.isEmpty()) {
                return common;
            }
            for (Cond cond : conds(paths.getFirst())) {
                boolean everywhere = true;
                for (Path path : paths) {
                    everywhere &= conds(path).contains(cond);
                }
                if (everywhere && !common.contains(cond)) {
                    common.add(cond);
                }
            }
            return common;
        }

        private static List<Cond> conds(Path path) {
            Cond level = path.level;
            if (level == null) {
                return path.conds;
            }
            List<Cond> all = new ArrayList<>(path.conds);
            all.add(level);
            return all;
        }

        // ---- lines

        private record Live(int node, String entry, RuleCard.Live kind) {
        }

        private void line(RuleCard.Section section, int depth, String text, Set<Integer> reads, Live live) {
            // Take the marks out, keeping where each marked part sits.
            StringBuilder plain = new StringBuilder(text.length());
            List<RuleCard.Field> fields = new ArrayList<>();
            int start = -1;
            int field = -1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == OPEN && i + 1 < text.length()) {
                    field = text.charAt(i + 1) - FIELD_BASE;
                    start = plain.length();
                    i++;
                } else if (c == CLOSE) {
                    if (start >= 0 && field >= 0 && field < fieldNodes.size() && plain.length() > start) {
                        fields.add(new RuleCard.Field(start, plain.length(), fieldNodes.get(field),
                                fieldSettings.get(field)));
                    }
                    start = -1;
                } else if (c < OPEN || c > MARK_LAST) {
                    plain.append(c);
                }
            }
            String cut = plain.length() > MAX_TEXT ? plain.substring(0, MAX_TEXT) : plain.toString();
            List<RuleCard.Field> kept = new ArrayList<>(fields.size());
            for (RuleCard.Field part : fields) {
                if (part.start() < cut.length()) {
                    kept.add(new RuleCard.Field(part.start(), Math.min(part.end(), cut.length()), part.node(),
                            part.setting()));
                }
            }
            lines.add(new RuleCard.Line(section, depth, cut, List.copyOf(reads), live.node(), live.entry(), live.kind(),
                    kept));
        }

        /** {@code text} marked as showing setting {@code setting} of {@code node}, so the card view can edit it. */
        private String mark(LogicNode<K> node, String setting, String text) {
            int field = fieldNodes.size();
            if (field >= MAX_FIELDS || text.isEmpty()) {
                return clean(text);
            }
            fieldNodes.add(node.id());
            fieldSettings.add(setting);
            return OPEN + String.valueOf((char) (FIELD_BASE + field)) + clean(text) + CLOSE;
        }

        /**
         * {@code key}'s text with {@code args}, its longest piece of plain words between the arguments ("is above" in
         * "%s is above %s", "the smaller of") marked as showing {@code setting} of {@code node}. A text with other
         * format codes than {@code %s} and {@code %%} is formatted unmarked.
         */
        private String markedFormat(String key, LogicNode<K> node, String setting, Object... args) {
            String template = lang.text(key);
            String[] pieces = template.split("%s", -1);
            if (pieces.length != args.length + 1 || template.replace("%%", "").replace("%s", "").indexOf('%') >= 0) {
                return lang.format(key, args);
            }
            int longest = -1;
            for (int i = 0; i < pieces.length; i++) {
                if (!pieces[i].isBlank()
                        && (longest < 0 || pieces[i].strip().length() > pieces[longest].strip().length())) {
                    longest = i;
                }
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < pieces.length; i++) {
                String piece = pieces[i].replace("%%", "%");
                if (i == longest) {
                    String core = piece.strip();
                    int at = piece.indexOf(core);
                    out.append(piece, 0, at).append(mark(node, setting, core))
                            .append(piece.substring(at + core.length()));
                } else {
                    out.append(piece);
                }
                if (i < args.length) {
                    out.append(args[i]);
                }
            }
            return out.toString();
        }

        /** A set of read node ids starting with {@code id}. */
        private static Set<Integer> reads(int id) {
            Set<Integer> reads = new LinkedHashSet<>();
            reads.add(id);
            return reads;
        }

        /** The live value beside a node's own line: its live line in the editor, or a LET value's main output. */
        private Live live(LogicNode<K> node) {
            LiveLine.Spec spec = LiveLine.of(node.type()).orElse(null);
            if (spec != null) {
                RuleCard.Live kind = switch (spec.format()) {
                    case NUMBER -> RuleCard.Live.NUMBER;
                    case YES_NO -> RuleCard.Live.YES_NO;
                    case CROSSING -> RuleCard.Live.CROSSING;
                    case DUE -> RuleCard.Live.DUE;
                };
                return new Live(node.id(), spec.entry(), kind);
            }
            if (names.containsKey(node.id())) {
                Port port = output(node, mainOutput(node));
                if (port != null && (port.type() == PortType.NUMBER || port.type() == PortType.YES_NO)) {
                    return new Live(node.id(), port.name(),
                            port.type() == PortType.NUMBER ? RuleCard.Live.NUMBER : RuleCard.Live.YES_NO);
                }
            }
            return new Live(-1, "", RuleCard.Live.NONE);
        }

        /** IF lines for {@code cond}: one line, or a group with its parts indented. */
        private void condLines(Cond cond, int depth, List<Integer> via) {
            if (cond.kind() != CondKind.CHECK) {
                Set<Integer> reads = reads(cond.node());
                line(RuleCard.Section.IF, depth, condText(cond, reads), reads, new Live(-1, "", RuleCard.Live.NONE));
                return;
            }
            tree(cond.node(), cond.output(), cond.negated(), depth, via);
        }

        private void tree(int id, String output, boolean negated, int depth, List<Integer> via) {
            LogicNode<K> node = nodes.get(id);
            if (node != null && !names.containsKey(id) && depth < MAX_NEST && !rendering.contains(id)) {
                NodeType type = node.type();
                if (type == NodeTypes.NOT) {
                    Wire inner = wire(id, NodeTypes.VALUE);
                    if (inner != null) {
                        List<Integer> through = new ArrayList<>(via);
                        through.add(id);
                        rendering.add(id);
                        tree(inner.from(), inner.output(), !negated, depth, through);
                        rendering.remove(id);
                        return;
                    }
                } else if ((type == NodeTypes.AND || type == NodeTypes.OR) && wiredParts(node)) {
                    List<Wire> parts = new ArrayList<>();
                    List<Integer> group = new ArrayList<>(via);
                    rendering.add(id);
                    flatten(node, type, parts, group);
                    boolean and = type == NodeTypes.AND;
                    if (and && !negated && depth == 0) {
                        for (Wire part : parts) {
                            tree(part.from(), part.output(), false, 0, group);
                        }
                    } else {
                        String header = negated ? (and ? NOT_ALL_OF : NONE_OF) : (and ? ALL_OF : ANY_OF);
                        Set<Integer> reads = reads(id);
                        reads.addAll(group);
                        line(RuleCard.Section.IF, depth, lang.text(header), reads,
                                negated
                                        ? new Live(-1, "", RuleCard.Live.NONE)
                                        : new Live(id, "result", RuleCard.Live.YES_NO));
                        for (Wire part : parts) {
                            tree(part.from(), part.output(), false, depth + 1, List.of());
                        }
                    }
                    rendering.remove(id);
                    return;
                }
            }
            Set<Integer> reads = reads(id);
            reads.addAll(via);
            String text = check(id, output, negated, reads);
            line(RuleCard.Section.IF, depth, text, reads,
                    negated ? new Live(-1, "", RuleCard.Live.NONE) : new Live(id, output, RuleCard.Live.YES_NO));
        }

        /** Whether both inputs of an And or Or are wired, so it can show as a group. */
        private boolean wiredParts(LogicNode<K> node) {
            return wire(node.id(), "a") != null && wire(node.id(), "b") != null;
        }

        /** The inputs of an And or Or, with unnamed nested nodes of the same type merged in. */
        private void flatten(LogicNode<K> node, NodeType type, List<Wire> parts, List<Integer> group) {
            group.add(node.id());
            for (String input : List.of("a", "b")) {
                Wire wire = wire(node.id(), input);
                if (wire == null) {
                    continue;
                }
                LogicNode<K> source = nodes.get(wire.from());
                if (source != null && source.type() == type && "result".equals(wire.output())
                        && !names.containsKey(source.id()) && !group.contains(source.id())) {
                    flatten(source, type, parts, group);
                } else {
                    parts.add(wire);
                }
            }
        }

        /** A check as inline text. */
        private String condText(Cond cond, Set<Integer> reads) {
            return switch (cond.kind()) {
                case TURNS_YES, TURNS_NO -> event(cond.node(), cond.output(), reads, 0);
                case CHECK -> check(cond.node(), cond.output(), cond.negated(), reads);
            };
        }

        /** A yes/no output as a check, said the other way round when {@code negated} ("lamp is off"). */
        private String check(int id, String output, boolean negated, Set<Integer> reads) {
            LogicNode<K> node = nodes.get(id);
            if (!negated || node == null) {
                return value(id, output, reads, 0);
            }
            NodeType type = node.type();
            if (names.containsKey(id)) {
                reads.add(id);
                return type == NodeTypes.LATCH || type == NodeTypes.TOGGLE
                        ? lang.format(IS_OFF, ref(id))
                        : lang.format(IS_NO, ref(id));
            }
            if (type == NodeTypes.DEVICE_STATE) {
                reads.add(id);
                return lang.format(CHECK_DEVICE_OFF, placeholder(node, NodeTypes.LABEL_NAME, reads, 0));
            }
            if (type == NodeTypes.STEP_IS) {
                reads.add(id);
                return lang.format(CHECK_STEP_NOT, placeholder(node, Sequences.STEP, reads, 0),
                        placeholder(node, Sequences.WHICH, reads, 0));
            }
            if (type == NodeTypes.COMPARE && !rendering.contains(id)) {
                reads.add(id);
                rendering.add(id);
                try {
                    return compare(node, reads, 0, true);
                } finally {
                    rendering.remove(id);
                }
            }
            if ((type == NodeTypes.DAYTIME || type == NodeTypes.DAYTIME_CHANGES) && "day".equals(output)) {
                reads.add(id);
                return lang.text(CHECK_NIGHT);
            }
            if (type == NodeTypes.WEATHER && "raining".equals(output)) {
                reads.add(id);
                return lang.text(CHECK_NOT_RAINING);
            }
            return lang.format(NOT, value(id, output, reads, 0));
        }

        private String actionText(LogicNode<K> action, Path path, List<Cond> common, Set<Integer> fired,
                Set<Integer> reads) {
            Cond level = path.level;
            String text = level != null && common.contains(level) ? lifted(action, reads) : inline(action, reads);
            for (int i = path.steps.size() - 1; i >= 0; i--) {
                text = lang.format(STEP, path.steps.get(i), text);
            }
            List<String> only = new ArrayList<>();
            for (Cond cond : path.conds) {
                if (!common.contains(cond)) {
                    only.add(condText(cond, reads));
                }
            }
            if (!only.isEmpty()) {
                text = lang.format(ONLY_IF, join(only, JOIN_AND), text);
            }
            if (fired.size() > 1 && !path.triggers.isEmpty() && !path.triggers.equals(fired)) {
                custom = true;
                List<String> on = new ArrayList<>();
                for (int id : path.triggers) {
                    LogicNode<K> trigger = nodes.get(id);
                    on.add(trigger == null ? "?" : lower(lang.text(trigger.type().nameKey())));
                }
                text = lang.format(ONLY_ON, join(on, JOIN_OR), text);
            }
            return text;
        }

        /** An action with its checks in its own line: "switch X on while Y, otherwise off". */
        private String inline(LogicNode<K> action, Set<Integer> reads) {
            if (wire(action.id(), "on") != null) {
                if (action.type() == NodeTypes.REDSTONE_OUT
                        && NodeTypes.channel(action.type(), action.settings()) >= 0) {
                    return lang.format(CHANNEL_WHILE, placeholder(action, NodeTypes.LABEL_NAME, reads, 0),
                            placeholder(action, NodeTypes.STRENGTH, reads, 0), placeholder(action, "on", reads, 0));
                }
                if (action.type() == NodeTypes.REDSTONE_OUT) {
                    return lang.format(REDSTONE_WHILE, placeholder(action, NodeTypes.STRENGTH, reads, 0),
                            placeholder(action, NodeTypes.LABEL_NAME, reads, 0), placeholder(action, "on", reads, 0));
                }
                if (action.type() == NodeTypes.DEVICE_SWITCH) {
                    return lang.format(SWITCH_WHILE, placeholder(action, NodeTypes.LABEL_NAME, reads, 0),
                            placeholder(action, "on", reads, 0));
                }
                if (action.type() == NodeTypes.STOCK_RULE_SWITCH) {
                    return lang.format(STOCK_SWITCH_WHILE, placeholder(action, NodeTypes.ITEM_NAME, reads, 0),
                            placeholder(action, NodeTypes.LABEL_NAME, reads, 0), placeholder(action, "on", reads, 0));
                }
            }
            return sentence(action, reads, 0);
        }

        /** A switch or redstone action whose on/off input is a shared check: "switch X on, otherwise off". */
        private String lifted(LogicNode<K> action, Set<Integer> reads) {
            if (action.type() == NodeTypes.REDSTONE_OUT && NodeTypes.channel(action.type(), action.settings()) >= 0) {
                return lang.format(CHANNEL_LIFTED, placeholder(action, NodeTypes.LABEL_NAME, reads, 0),
                        placeholder(action, NodeTypes.STRENGTH, reads, 0));
            }
            if (action.type() == NodeTypes.REDSTONE_OUT) {
                return lang.format(REDSTONE_LIFTED, placeholder(action, NodeTypes.STRENGTH, reads, 0),
                        placeholder(action, NodeTypes.LABEL_NAME, reads, 0));
            }
            if (action.type() == NodeTypes.DEVICE_SWITCH) {
                return lang.format(SWITCH_LIFTED, placeholder(action, NodeTypes.LABEL_NAME, reads, 0));
            }
            if (action.type() == NodeTypes.STOCK_RULE_SWITCH) {
                return lang.format(STOCK_SWITCH_LIFTED, placeholder(action, NodeTypes.ITEM_NAME, reads, 0),
                        placeholder(action, NodeTypes.LABEL_NAME, reads, 0));
            }
            return sentence(action, reads, 0);
        }

        // ---- step sequences

        /** The step an "entered" output of a sequence names; 0 for other outputs. */
        private static int enteredStep(String output) {
            for (int k = 1; k <= Sequences.MAX_STEPS; k++) {
                if (Sequences.entered(k).equals(output)) {
                    return k;
                }
            }
            return 0;
        }

        /**
         * A sequence's STEPS lines: a header with the step count, then per step how it goes on ("go on when ...", "or
         * after 30 s") with, under it, the "go to" input that pulls it there and the actions that run on entering it or
         * while it is there; then what resets it and what runs when a step's time runs out.
         */
        private void stepLines(LogicNode<K> seq, List<LogicNode<K>> actions, List<Path> paths) {
            NodeSettings<K> settings = seq.settings();
            int steps = Sequences.steps(settings);
            line(RuleCard.Section.STEPS, 0,
                    lang.format(SEQ_HEADER, ref(seq.id()), mark(seq, Sequences.STEPS, InfoFormat.number(steps))),
                    reads(seq.id()), new Live(seq.id(), Sequences.STEP, RuleCard.Live.STEP));
            for (int k = 1; k <= steps; k++) {
                Set<Integer> reads = reads(seq.id());
                String name = mark(seq, Sequences.name(k),
                        Sequences.shownName(settings, k, number -> lang.format(LogicLang.STEP_DEFAULT_NAME, number)));
                String number = Integer.toString(k);
                long seconds = Sequences.timeoutSeconds(settings, k);
                boolean when = wire(seq.id(), Sequences.next(k)) != null;
                String text;
                if (when && seconds > 0) {
                    text = lang.format(STEP_WHEN_OR_AFTER, number, name, placeholder(seq, Sequences.next(k), reads, 0),
                            mark(seq, Sequences.timeout(k), InfoFormat.number(seconds)));
                } else if (when) {
                    text = lang.format(STEP_WHEN, number, name, placeholder(seq, Sequences.next(k), reads, 0));
                } else if (seconds > 0) {
                    text = lang.format(STEP_AFTER, number, name,
                            mark(seq, Sequences.timeout(k), InfoFormat.number(seconds)));
                } else {
                    text = lang.format(STEP_STAYS, number, name);
                }
                line(RuleCard.Section.STEPS, 0, text, reads,
                        new Live(seq.id(), Sequences.STEP + ":" + k, RuleCard.Live.STEP));
                if (wire(seq.id(), Sequences.go(k)) != null) {
                    Set<Integer> goReads = reads(seq.id());
                    line(RuleCard.Section.STEPS, 1,
                            lang.format(STEP_GO_HERE, placeholder(seq, Sequences.go(k), goReads, 0)), goReads,
                            new Live(-1, "", RuleCard.Live.NONE));
                }
                stepActionLines(seq, k, actions, paths);
            }
            if (wire(seq.id(), Sequences.RESET) != null) {
                Set<Integer> reads = reads(seq.id());
                line(RuleCard.Section.STEPS, 0, lang.format(SEQ_RESET, placeholder(seq, Sequences.RESET, reads, 0)),
                        reads, new Live(-1, "", RuleCard.Live.NONE));
            }
            stepActionLines(seq, 0, actions, paths);
        }

        /** The lines of the actions sequence {@code seq} runs at step {@code step} (0: when a step's time runs out). */
        private void stepActionLines(LogicNode<K> seq, int step, List<LogicNode<K>> actions, List<Path> paths) {
            for (int i = 0; i < actions.size(); i++) {
                Path path = paths.get(i);
                if (path.sequence != seq.id() || path.step != step) {
                    continue;
                }
                LogicNode<K> action = actions.get(i);
                Set<Integer> reads = reads(action.id());
                reads.addAll(path.chain);
                String text;
                if (path.whileHere) {
                    text = lang.format(STEP_WHILE_HERE, whileText(action, reads));
                } else {
                    String done = actionText(action, path, List.of(), Set.of(), reads);
                    text = lang.format(step == 0 ? SEQ_TIME_UP : STEP_ON_ENTER, done);
                }
                line(RuleCard.Section.STEPS, step == 0 ? 0 : 1, text, reads, live(action));
            }
        }

        /** An action that runs while a step lasts, without its on/off: "switch inlet on". */
        private String whileText(LogicNode<K> action, Set<Integer> reads) {
            NodeType type = action.type();
            if (type == NodeTypes.DEVICE_SWITCH) {
                return lang.format(WHILE_SWITCH, placeholder(action, NodeTypes.LABEL_NAME, reads, 0));
            }
            if (type == NodeTypes.REDSTONE_OUT && NodeTypes.channel(type, action.settings()) >= 0) {
                return lang.format(WHILE_CHANNEL, placeholder(action, NodeTypes.LABEL_NAME, reads, 0),
                        placeholder(action, NodeTypes.STRENGTH, reads, 0));
            }
            if (type == NodeTypes.REDSTONE_OUT) {
                return lang.format(WHILE_REDSTONE, placeholder(action, NodeTypes.STRENGTH, reads, 0),
                        placeholder(action, NodeTypes.LABEL_NAME, reads, 0));
            }
            if (type == NodeTypes.STOCK_RULE_SWITCH) {
                return lang.format(WHILE_STOCK, placeholder(action, NodeTypes.ITEM_NAME, reads, 0),
                        placeholder(action, NodeTypes.LABEL_NAME, reads, 0));
            }
            return sentence(action, reads, 0);
        }

        /** The name of step {@code step} of sequence {@code seq} as a card shows it. */
        private String stepName(LogicNode<K> seq, int step) {
            return Sequences.shownName(seq.settings(), step,
                    number -> lang.format(LogicLang.STEP_DEFAULT_NAME, number));
        }

        // ---- text

        /** What the output {@code output} of node {@code id} reads as inside another line. */
        private String value(int id, String output, Set<Integer> reads, int depth) {
            LogicNode<K> node = nodes.get(id);
            if (node == null) {
                custom = true;
                return lang.text(LOOP);
            }
            reads.add(id);
            if (names.containsKey(id)) {
                String ref = ref(id);
                if (node.type() == NodeTypes.LATCH || node.type() == NodeTypes.TOGGLE) {
                    return lang.format(IS_ON, ref);
                }
                Port port = output(node, output);
                return port == null || output.equals(mainOutput(node)) ? ref : lang.format(PART, ref, portName(port));
            }
            if (trigger(node)) {
                return triggerValue(node, output, reads, depth);
            }
            return own(node, output, reads, depth);
        }

        /** A node's own text for {@code output}, ignoring its name. */
        private String own(LogicNode<K> node, String output, Set<Integer> reads, int depth) {
            if (depth > MAX_DEPTH || !rendering.add(node.id())) {
                custom = true;
                return lang.text(LOOP);
            }
            try {
                if (node.type() == NodeTypes.FOR_EACH) {
                    return ForEach.ITEM.equals(output)
                            ? lang.text(EACH_IT)
                            : ForEach.CHECKED.equals(output) ? lang.text(EACH_CHECKED) : eachText(node);
                }
                String text = switch (node.type().id()) {
                    case "constant", "parameter" -> constant(node);
                    case "math" -> math(node, reads, depth);
                    case "compare" -> compare(node, reads, depth);
                    case "device_state" ->
                        lang.format(CHECK_DEVICE_ON, placeholder(node, NodeTypes.LABEL_NAME, reads, depth));
                    case "daytime" -> "day".equals(output) ? lang.text(CHECK_DAY) : sentence(node, reads, depth);
                    case "weather" ->
                        "raining".equals(output) ? lang.text(CHECK_RAINING) : sentence(node, reads, depth);
                    case "craft_status" -> "running".equals(output)
                            ? lang.format(CHECK_CRAFTING, placeholder(node, NodeTypes.ITEM_NAME, reads, depth))
                            : sentence(node, reads, depth);
                    case "stock_status" -> stockCheck(node, output, reads, depth);
                    case "pool_status" -> "any_idle".equals(output)
                            ? lang.format(CHECK_POOL_IDLE, placeholder(node, NodeTypes.LABEL_NAME, reads, depth))
                            : sentence(node, reads, depth);
                    default -> sentence(node, reads, depth);
                };
                if (node.type() == NodeTypes.POOL_STATUS && "any_idle".equals(output)) {
                    return text;
                }
                if (node.type() == NodeTypes.STOCK_STATUS
                        && ("below".equals(output) || "crafting".equals(output) || "on".equals(output))) {
                    return text;
                }
                if (node.type() == NodeTypes.DAYTIME || node.type() == NodeTypes.WEATHER
                        || node.type() == NodeTypes.CRAFT_STATUS) {
                    // These phrase each output on its own.
                    Port part = output(node, output);
                    return "day".equals(output) || "raining".equals(output) || "running".equals(output) || part == null
                            ? text
                            : lang.format(PART, text, portName(part));
                }
                Port port = output(node, output);
                if (port != null && port.type() != PortType.EVENT && !output.equals(mainOutput(node))) {
                    text = lang.format(PART, text, portName(port));
                }
                return text;
            } finally {
                rendering.remove(node.id());
            }
        }

        /** A stock status node's yes/no outputs as a check of their own; its numbers as its sentence. */
        private String stockCheck(LogicNode<K> node, String output, Set<Integer> reads, int depth) {
            String key = switch (output) {
                case "below" -> CHECK_STOCK_BELOW;
                case "crafting" -> CHECK_STOCK_CRAFTING;
                case "on" -> CHECK_STOCK_ON;
                default -> "";
            };
            if (key.isEmpty()) {
                return sentence(node, reads, depth);
            }
            return lang.format(key, placeholder(node, NodeTypes.ITEM_NAME, reads, depth),
                    placeholder(node, NodeTypes.LABEL_NAME, reads, depth));
        }

        /** What an event out of node {@code id} reads as inside another line. */
        private String event(int id, String output, Set<Integer> reads, int depth) {
            LogicNode<K> node = nodes.get(id);
            if (node == null) {
                custom = true;
                return lang.text(LOOP);
            }
            reads.add(id);
            NodeType type = node.type();
            if (trigger(node)) {
                return triggerEvent(node, output, reads, depth);
            }
            if (sequence(node)) {
                int step = enteredStep(output);
                return step > 0
                        ? lang.format(EVENT_ENTERED, ref(id), stepName(node, step))
                        : lang.format(EVENT_TIME_UP, ref(id));
            }
            boolean yes = "turned_yes".equals(output);
            if ((type == NodeTypes.COMPARE || type == NodeTypes.EDGE) && (yes || "turned_no".equals(output))) {
                // "Once X" or "until not X": the check itself, which the event follows.
                String subject = names.containsKey(id)
                        ? ref(id)
                        : type == NodeTypes.EDGE
                                ? placeholder(node, NodeTypes.VALUE, reads, depth + 1)
                                : own(node, "result", reads, depth + 1);
                return yes ? subject : lang.format(NOT, subject);
            }
            if (type == NodeTypes.IF) {
                return lang.format("otherwise".equals(output) ? IF_NOT_EVENT : IF_EVENT,
                        placeholder(node, "when", reads, depth + 1), placeholder(node, "condition", reads, depth + 1));
            }
            String text = own(node, output, reads, depth);
            return "timed_out".equals(output) ? lang.format(GAVE_UP_EVENT, text) : text;
        }

        /** The node's sentence with settings filled and wired inputs replaced by what feeds them. */
        private String sentence(LogicNode<K> node, Set<Integer> reads, int depth) {
            String key = NodeTypes.sentenceKey(node.type(), node.settings());
            Wire itemWire = wire(node.id(), NodeTypes.ITEM_NAME);
            if (itemWire != null && fromEach(itemWire)) {
                // The item of a For each's pass reads "it": "its count", "craft 64 of it".
                key = NodeTypes.eachSentenceKey(node.type(), node.settings()).orElse(key);
                reads.add(itemWire.from());
            } else if (itemWire != null && fromMissing(itemWire)) {
                // A failed job's missing item: "craft 16 of the missing item".
                key = NodeTypes.missingSentenceKey(node.type(), node.settings()).orElse(key);
                reads.add(itemWire.from());
            }
            String template = lang.text(key);
            List<String> named = NodeSentence.placeholders(template);
            String text = NodeSentence.fill(template, name -> placeholder(node, name, reads, depth));
            for (Port input : node.type().inputs()) {
                Wire wire = wire(node.id(), input.name());
                if (wire == null || input.type() == PortType.EVENT || named.contains(input.name()) || fromEach(wire)
                        || fromMissing(wire)) {
                    // A For each's item is the "it" of the sentence.
                    continue;
                }
                text = lang.format(WITH, text, portName(input), value(wire.from(), wire.output(), reads, depth + 1));
            }
            return text;
        }

        /** One placeholder: a wired input, a setting, or an unwired input's fallback. */
        private String placeholder(LogicNode<K> node, String name, Set<Integer> reads, int depth) {
            int channel = NodeTypes.channel(node.type(), node.settings());
            if (channel >= 0 && name.equals(NodeTypes.LABEL_NAME)) {
                // A network redstone channel replaces the label, wired or not (M7L2).
                return lang.text(NodeTypes.channelPhraseKey(channel));
            }
            Port port = input(node.type(), name);
            Wire wire = wire(node.id(), name);
            if (port != null && wire != null) {
                return port.type() == PortType.EVENT
                        ? event(wire.from(), wire.output(), reads, depth + 1)
                        : value(wire.from(), wire.output(), reads, depth + 1);
            }
            SettingSpec spec = node.type().setting(name).orElse(null);
            if (spec != null) {
                return setting(node, spec);
            }
            if (port != null) {
                return switch (port.type()) {
                    case EVENT -> lang.text(NEVER);
                    case NUMBER -> InfoFormat.number(port.fallback());
                    case YES_NO -> lang.text(port.fallback() != 0 ? LogicLang.WORD_YES : LogicLang.WORD_NO);
                    case ITEM, LABEL -> portName(port);
                };
            }
            return name;
        }

        private String setting(LogicNode<K> node, SettingSpec spec) {
            return mark(node, spec.name(), settingText(node, spec));
        }

        private String settingText(LogicNode<K> node, SettingSpec spec) {
            NodeSettings<K> settings = node.settings();
            String name = spec.name();
            return switch (spec.kind()) {
                case NUMBER -> InfoFormat.number(number(node, name));
                case FLAG -> lang.text(
                        settings.flag(name).orElse(spec.defaultValue() != 0) ? LogicLang.WORD_YES : LogicLang.WORD_NO);
                case CHOICE -> lower(lang.text(SettingSpec.choiceKey(choice(node, spec))));
                case TEXT -> settings.text(name).filter(text -> !text.isBlank()).orElseGet(() -> unset(spec));
                case LABEL -> settings.text(name).filter(text -> !text.isBlank()).map(this::labelText)
                        .orElseGet(() -> spec.required() ? unset(spec) : lang.text(emptyLabel(node.type())));
                case ITEM, FLUID, TAG ->
                    settings.item(name).map(item).orElseGet(() -> spec.required() ? unset(spec) : lang.text(ANY_ITEM));
            };
        }

        /**
         * A label as a sentence shows it: "@North Base" as the network's name, "intake@North Base" as "intake on North
         * Base" (M7L2 row 13), any other label as typed.
         */
        private String labelText(String label) {
            String network = NetworkRef.network(label);
            if (network.isEmpty()) {
                return label;
            }
            String device = NetworkRef.device(label);
            return device.isEmpty() ? network : lang.format(LABEL_ON, device, network);
        }

        private String unset(SettingSpec spec) {
            return lang.format(UNSET, lower(lang.text(spec.nameKey())));
        }

        /** What an empty label means for nodes of {@code type}. */
        private static String emptyLabel(NodeType type) {
            return switch (type.id()) {
                case "redstone_changes", "player_nearby", "link_overloaded" -> THE_CONTROLLER;
                case "item_moves" -> ANY_DEVICE;
                case "player_uses_terminal" -> ANY;
                default -> THE_NETWORK;
            };
        }

        private String constant(LogicNode<K> node) {
            SettingSpec kind = node.type().setting(NodeTypes.KIND).orElse(null);
            String id = kind == null ? "number" : choice(node, kind);
            NodeSettings<K> settings = node.settings();
            return switch (id) {
                case "yes_no" -> mark(node, NodeTypes.YES,
                        lang.text(settings.flag(NodeTypes.YES).orElse(true) ? LogicLang.WORD_YES : LogicLang.WORD_NO));
                case "item" -> mark(node, NodeTypes.ITEM_NAME,
                        settings.item(NodeTypes.ITEM_NAME).map(item).orElseGet(() -> lang.text(ANY_ITEM)));
                case "label" -> mark(node, NodeTypes.LABEL_NAME, settings.text(NodeTypes.LABEL_NAME)
                        .filter(text -> !text.isBlank()).map(this::labelText).orElseGet(() -> lang.text(THE_NETWORK)));
                default -> mark(node, NodeTypes.VALUE, InfoFormat.number(number(node, NodeTypes.VALUE)));
            };
        }

        private String math(LogicNode<K> node, Set<Integer> reads, int depth) {
            SettingSpec operation = node.type().setting("operation").orElse(null);
            String key = switch (operation == null ? "add" : choice(node, operation)) {
                case "subtract" -> MATH_SUBTRACT;
                case "multiply" -> MATH_MULTIPLY;
                case "divide" -> MATH_DIVIDE;
                case "min" -> MATH_MIN;
                case "max" -> MATH_MAX;
                case "modulo" -> MATH_MODULO;
                default -> MATH_ADD;
            };
            return markedFormat(key, node, "operation", placeholder(node, "a", reads, depth),
                    placeholder(node, "b", reads, depth));
        }

        private String compare(LogicNode<K> node, Set<Integer> reads, int depth) {
            return compare(node, reads, depth, false);
        }

        /** A Compare as a check, or (when {@code negated}) the check it fails ("is at most 32"). */
        private String compare(LogicNode<K> node, Set<Integer> reads, int depth, boolean negated) {
            SettingSpec mode = node.type().setting(NodeTypes.MODE).orElse(null);
            String value = placeholder(node, NodeTypes.VALUE, reads, depth);
            // The limit: the setting, or what is wired into it (a parameter, M7L2 row 19).
            String limit = placeholder(node, NodeTypes.THRESHOLD, reads, depth);
            String m = NodeTypes.MODE;
            return switch (mode == null ? "above" : choice(node, mode)) {
                case "below" -> markedFormat(negated ? COMPARE_NOT_BELOW : COMPARE_BELOW, node, m, value, limit);
                case "between" -> markedFormat(negated ? COMPARE_NOT_BETWEEN : COMPARE_BETWEEN, node, m, value, limit,
                        mark(node, NodeTypes.UPPER, InfoFormat.number(number(node, NodeTypes.UPPER))));
                case "equal" -> markedFormat(negated ? COMPARE_NOT_EQUAL : COMPARE_EQUAL, node, m, value, limit);
                default -> markedFormat(negated ? COMPARE_NOT_ABOVE : COMPARE_ABOVE, node, m, value, limit);
            };
        }

        /** A For each's FOR EACH line: "item in #c:ores", "item in the list a, b and c", "item stock keeper ... ". */
        private String eachText(LogicNode<K> node) {
            NodeSettings<K> settings = node.settings();
            return switch (ForEach.source(settings)) {
                case ForEach.SOURCE_TAG -> lang.format(EACH_TAG, setting(node, spec(node, NodeTypes.TAG)));
                case ForEach.SOURCE_LIST -> lang.format(EACH_LIST, listText(node));
                default -> lang.format(EACH_STOCK, lang.format(REF, setting(node, spec(node, NodeTypes.LABEL_NAME))));
            };
        }

        /** The list a For each goes through: its first items by name, then how many more. */
        private String listText(LogicNode<K> node) {
            NodeSettings<K> settings = node.settings();
            List<String> named = new ArrayList<>();
            int count = 0;
            int size = ForEach.listSize(settings);
            for (int k = 1; k <= size; k++) {
                K entry = settings.item(ForEach.item(k)).orElse(null);
                if (entry == null) {
                    continue;
                }
                count++;
                if (named.size() < EACH_SHOWN) {
                    named.add(mark(node, ForEach.item(k), item.apply(entry)));
                }
            }
            if (named.isEmpty()) {
                return lang.format(UNSET, lower(lang.text(spec(node, ForEach.item(1)).nameKey())));
            }
            return count > named.size()
                    ? lang.format(EACH_MORE, String.join(", ", named), count - named.size())
                    : join(named, JOIN_AND);
        }

        /** Whether {@code wire} carries a failed crafting job's missing item. */
        private boolean fromMissing(Wire wire) {
            LogicNode<K> source = nodes.get(wire.from());
            return source != null && source.type() == NodeTypes.CRAFT_FAILED
                    && NodeTypes.MISSING_ITEM.equals(wire.output());
        }

        /** Whether {@code wire} carries a For each's item. */
        private boolean fromEach(Wire wire) {
            LogicNode<K> source = nodes.get(wire.from());
            return source != null && source.type() == NodeTypes.FOR_EACH && ForEach.ITEM.equals(wire.output());
        }

        private static SettingSpec spec(LogicNode<?> node, String name) {
            return node.type().setting(name).orElseThrow();
        }

        // ---- small helpers

        /** An event sentence without its leading "when ", which the WHEN keyword already says. */
        private String withoutWhen(String sentence) {
            String prefix = lang.text(WHEN_PREFIX);
            return !prefix.isBlank() && sentence.startsWith(prefix) && sentence.length() > prefix.length()
                    ? sentence.substring(prefix.length())
                    : sentence;
        }

        /** {@code parts} joined pairwise with {@code key} ("a and b and c"); {@code parts} is not empty. */
        private String join(List<String> parts, String key) {
            String joined = parts.getFirst();
            for (int i = 1; i < parts.size(); i++) {
                joined = lang.format(key, joined, parts.get(i));
            }
            return joined;
        }

        private String ref(int id) {
            String name = names.getOrDefault(id, "?");
            return typed.contains(id) ? lang.format(REF, name) : name;
        }

        /** A memory node's REMEMBER text: what sets it and what resets it, in words. */
        private String memoryText(LogicNode<K> node, Set<Integer> reads) {
            NodeType type = node.type();
            if (type == NodeTypes.LATCH) {
                Wire reset = wire(node.id(), "reset");
                String set = placeholder(node, "set", reads, 0);
                return reset == null
                        ? lang.format(MEM_LATCH, set)
                        : lang.format(MEM_LATCH_RESET, set, placeholder(node, "reset", reads, 0));
            }
            String text;
            if (type == NodeTypes.COUNTER) {
                text = lang.format(MEM_COUNTER, placeholder(node, "up", reads, 0));
                if (wire(node.id(), "down") != null) {
                    text = lang.format(MEM_DOWN, text, placeholder(node, "down", reads, 0));
                }
                if (number(node, NodeTypes.STEP) != 1) {
                    text = lang.format(MEM_STEP, text,
                            mark(node, NodeTypes.STEP, InfoFormat.number(number(node, NodeTypes.STEP))));
                }
            } else if (type == NodeTypes.TOGGLE) {
                text = lang.format(MEM_TOGGLE, placeholder(node, "flip", reads, 0));
            } else if (type == NodeTypes.HOLD) {
                return lang.format(MEM_HOLD, placeholder(node, NodeTypes.VALUE, reads, 0),
                        placeholder(node, "store", reads, 0));
            } else {
                return sentence(node, reads, 0);
            }
            return wire(node.id(), "reset") == null
                    ? text
                    : lang.format(MEM_RESET, text, placeholder(node, "reset", reads, 0));
        }

        /** What a yes/no or number output of an event node reads as, with its subject where it has one. */
        private String triggerValue(LogicNode<K> node, String output, Set<Integer> reads, int depth) {
            String key = node.type().id() + "/" + output;
            return switch (key) {
                case "count_crosses/past" -> lang.format(CHECK_PAST, placeholder(node, "item", reads, depth),
                        placeholder(node, NodeTypes.MODE, reads, depth),
                        placeholder(node, NodeTypes.THRESHOLD, reads, depth));
                case "count_crosses/count" -> lang.format(VALUE_COUNT, placeholder(node, "item", reads, depth));
                case "sensor_crosses/past" ->
                    lang.format(CHECK_SENSOR_PAST, placeholder(node, NodeTypes.VALUE, reads, depth),
                            placeholder(node, NodeTypes.LABEL_NAME, reads, depth),
                            placeholder(node, NodeTypes.MODE, reads, depth),
                            placeholder(node, NodeTypes.THRESHOLD, reads, depth));
                case "sensor_crosses/value" ->
                    lang.format(VALUE_SENSOR, placeholder(node, NodeTypes.VALUE, reads, depth),
                            placeholder(node, NodeTypes.LABEL_NAME, reads, depth));
                case "link_overloaded/over" ->
                    lang.format(CHECK_OVERLOADED, placeholder(node, NodeTypes.LABEL_NAME, reads, depth),
                            placeholder(node, NodeTypes.THRESHOLD, reads, depth));
                case "storage_full/any_full" ->
                    lang.format(CHECK_FULL, placeholder(node, NodeTypes.LABEL_NAME, reads, depth));
                case "craft_failed/missing_item" -> lang.text(MISSING_ITEM);
                case "power_changes/powered" -> lang.text(CHECK_POWERED);
                case "daytime_changes/day" -> lang.text(CHECK_DAY);
                case "player_nearby/near" ->
                    lang.format(CHECK_PLAYER_NEAR, placeholder(node, NodeTypes.LABEL_NAME, reads, depth));
                default -> {
                    Port port = output(node, output);
                    yield port == null ? output : portName(port);
                }
            };
        }

        /** What an event of an event node reads as inside another line ("the redstone changes"). */
        private String triggerEvent(LogicNode<K> node, String output, Set<Integer> reads, int depth) {
            return switch (node.type().id()) {
                case "redstone_changes" -> NodeTypes.channel(node.type(), node.settings()) >= 0
                        ? lang.format(EVENT_CHANNEL, placeholder(node, NodeTypes.LABEL_NAME, reads, depth))
                        : lang.text(EVENT_REDSTONE);
                case "count_crosses" -> lang.format(EVENT_COUNT, placeholder(node, "item", reads, depth),
                        placeholder(node, NodeTypes.THRESHOLD, reads, depth));
                case "sensor_crosses" ->
                    lang.format(EVENT_SENSOR, placeholder(node, NodeTypes.THRESHOLD, reads, depth));
                case "timer" -> lang.text(EVENT_TIMER);
                case "graph_start" -> lang.text(EVENT_START);
                default -> {
                    int events = 0;
                    for (Port port : node.type().outputs(node.settings())) {
                        if (port.type() == PortType.EVENT) {
                            events++;
                        }
                    }
                    Port port = output(node, output);
                    yield events > 1 && port != null
                            ? portName(port)
                            : lang.format(EVENT_OTHER, lower(lang.text(node.type().nameKey())));
                }
            };
        }

        /** The names of the outputs other nodes read, for an event node that only feeds values. */
        private String outputsRead(LogicNode<K> node) {
            Set<String> read = new LinkedHashSet<>();
            for (Wire wire : out.getOrDefault(node.id(), List.of())) {
                Port port = output(node, wire.output());
                if (port != null && port.type() != PortType.EVENT) {
                    read.add(portName(port));
                }
            }
            return String.join(", ", read);
        }

        /** The first output that is not an event; the first output when all are events. */
        private static String mainOutput(LogicNode<?> node) {
            List<Port> outputs = node.type().outputs(node.settings());
            for (Port port : outputs) {
                if (port.type() != PortType.EVENT) {
                    return port.name();
                }
            }
            return outputs.isEmpty() ? "" : outputs.getFirst().name();
        }

        private long number(LogicNode<K> node, String name) {
            SettingSpec spec = node.type().setting(name).orElse(null);
            if (spec == null) {
                return 0;
            }
            return spec.clamp(node.settings().number(name).orElse(spec.defaultValue()));
        }

        private static String choice(LogicNode<?> node, SettingSpec spec) {
            int index = spec.choiceIndex(node.settings().text(spec.name()).orElse(""));
            return spec.choices().get(Math.clamp(index, 0, spec.choices().size() - 1));
        }

        private @Nullable Wire wire(int node, String input) {
            Map<String, Wire> wires = into.get(node);
            return wires == null ? null : wires.get(input);
        }

        private static @Nullable Port input(NodeType type, String name) {
            for (Port port : type.inputs()) {
                if (port.name().equals(name)) {
                    return port;
                }
            }
            return null;
        }

        private static @Nullable Port output(LogicNode<?> node, String name) {
            for (Port port : node.type().outputs(node.settings())) {
                if (port.name().equals(name)) {
                    return port;
                }
            }
            return null;
        }

        /** {@code text} lower-cased at the start when the language asks for it ({@link #LOWER_WORDS}). */
        private String lower(String text) {
            return "yes".equals(lang.text(LOWER_WORDS)) ? lowerFirst(text) : text;
        }

        private String portName(Port port) {
            return lower(lang.text(port.nameKey()));
        }
    }

    /** {@code text} without the private-use characters the card's marks use, so typed text cannot fake one. */
    static String clean(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= OPEN && c <= MARK_LAST) {
                StringBuilder out = new StringBuilder(text.length());
                for (int k = 0; k < text.length(); k++) {
                    char d = text.charAt(k);
                    if (d < OPEN || d > MARK_LAST) {
                        out.append(d);
                    }
                }
                return out.toString();
            }
        }
        return text;
    }

    /** {@code text} with its first letter lower-cased, unless the word looks like an abbreviation ("FE"). */
    static String lowerFirst(String text) {
        if (text.length() > 1 && Character.isUpperCase(text.charAt(0)) && !Character.isUpperCase(text.charAt(1))) {
            return Character.toLowerCase(text.charAt(0)) + text.substring(1);
        }
        return text.length() == 1 ? text.toLowerCase(Locale.ROOT) : text;
    }
}
