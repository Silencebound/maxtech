package dev.silencebound.maxtech.storage.logic;

import java.util.List;

/**
 * A graph read as a rule card (M7L2 chunk 11, read only): REMEMBER (memory nodes), LET (values several nodes read),
 * STEPS (step sequences with what each step does), WHEN (what wakes the rule, joined by OR), FOR EACH (the items a For
 * each goes through, M7L2 row 18), IF (checks, nested ALL OF / ANY OF) and THEN (actions in order), each line built
 * from its node's sentence. {@link RuleCards#build} makes it; the controller screen draws it. Immutable.
 *
 * @param custom
 *            whether the graph is not plainly WHEN / IF / THEN shaped: actions with different triggers, no action, a
 *            loop, or nodes the card cannot place (those show as "Custom step" lines in {@link Section#OTHER})
 * @param nodes
 *            how many nodes the graph has
 * @param lines
 *            the card's lines, section by section
 */
public record RuleCard(boolean custom, int nodes, List<Line> lines) {
    public RuleCard {
        lines = List.copyOf(lines);
    }

    /** The card's sections, in the order they show. */
    public enum Section {
        REMEMBER, LET, STEPS, WHEN, EACH, IF, THEN, OTHER
    }

    /** How a line's live value reads. */
    public enum Live {
        /** The line shows no value. */
        NONE,
        /** A number with thousands grouped. */
        NUMBER,
        /** Yes or no. */
        YES_NO,
        /** A crossing's watched value against its limit. */
        CROSSING,
        /** Seconds until a delay, timer or pulse is due. */
        DUE,
        /**
         * A step sequence's step ({@code LiveLine} entry "step" of the node): the current step's name on the entry
         * "step", or a mark on the line of step k while it is the current one, for the entry "step:k".
         */
        STEP
    }

    /**
     * One line of the card.
     *
     * @param section
     *            the section it belongs to
     * @param depth
     *            how deep it sits under ALL OF / ANY OF lines; 0 at the top
     * @param text
     *            what it says, already in the player's language
     * @param nodes
     *            the ids of the nodes it reads, the one it is mainly about first; never empty
     * @param liveNode
     *            the node whose value shows beside it, or -1 for none
     * @param liveEntry
     *            the live entry of that node: an output's name, {@code LiveLine.WATCHED} or {@code LiveLine.DUE}
     * @param live
     *            how the value reads
     * @param fields
     *            the parts of {@code text} that show a setting, in text order; the card view edits them in place
     */
    public record Line(Section section, int depth, String text, List<Integer> nodes, int liveNode, String liveEntry,
            Live live, List<Field> fields) {
        public Line {
            nodes = List.copyOf(nodes);
            fields = List.copyOf(fields);
        }

        /** The node the line is mainly about. */
        public int node() {
            return nodes.getFirst();
        }
    }

    /**
     * A part of a line's text that shows one setting of one node: a number, a choice, a yes/no, a text, a label or an
     * item. A choice may also show as the words that depend on it, such as "is above" for a Compare's mode.
     *
     * @param start
     *            the first character of the part in the line's text
     * @param end
     *            the character after the part
     * @param node
     *            the id of the node whose setting it shows
     * @param setting
     *            the setting's name
     */
    public record Field(int start, int end, int node, String setting) {
    }

    /**
     * The lang key of the word in front of line {@code index}: the section's name on its first top-level line, then OR
     * between triggers and AND between checks and actions; empty for nested lines and later lines of the other
     * sections.
     */
    public String keywordKey(int index) {
        Line line = lines.get(index);
        if (line.depth() > 0) {
            return "";
        }
        Section previous = null;
        for (int i = index - 1; i >= 0; i--) {
            if (lines.get(i).depth() == 0) {
                previous = lines.get(i).section();
                break;
            }
        }
        if (previous != line.section()) {
            return RuleCards.sectionKey(line.section());
        }
        return switch (line.section()) {
            case WHEN -> RuleCards.WORD_OR;
            case IF, THEN -> RuleCards.WORD_AND;
            case REMEMBER, LET, STEPS, EACH, OTHER -> "";
        };
    }
}
