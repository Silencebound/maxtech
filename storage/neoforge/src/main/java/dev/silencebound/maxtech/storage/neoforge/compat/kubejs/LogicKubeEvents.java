package dev.silencebound.maxtech.storage.neoforge.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.event.KubeStartupEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.silencebound.maxtech.storage.logic.LogicContext;
import dev.silencebound.maxtech.storage.logic.ScriptNodeSpec;
import dev.silencebound.maxtech.storage.logic.ScriptNodes;
import dev.silencebound.maxtech.storage.logic.controller.LogicEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The {@code MaxtechLogicEvents} KubeJS event group (guide page {@code logic_scripts.md}):
 * <ul>
 * <li>{@code registerNodeTypes} (startup): {@code event.create(id)} describes a script node type
 * ({@link ScriptNodeSpec}); each is registered with {@link ScriptNodes} after the startup scripts ran.</li>
 * <li>{@code notice} (server): a notify node posted a notice ({@link NoticeEvent}).</li>
 * <li>{@code ruleRan} (server): a rule ran ({@link RuleRanEvent}).</li>
 * </ul>
 * The server events are read-only and come after the fact; they carry plain values (the dimension's id, an immutable
 * position, names and numbers), never the live level, so a listener can't change the world through them. A script error
 * in a listener is logged by KubeJS (or here) and never stops the controller.
 */
public final class LogicKubeEvents {
    static final EventGroup GROUP = EventGroup.of("MaxtechLogicEvents");
    static final EventHandler REGISTER_NODE_TYPES = GROUP.startup("registerNodeTypes",
            () -> RegisterNodeTypesEvent.class);
    static final EventHandler NOTICE = GROUP.server("notice", () -> NoticeEvent.class);
    static final EventHandler RULE_RAN = GROUP.server("ruleRan", () -> RuleRanEvent.class);
    private static boolean registered;

    private LogicKubeEvents() {
    }

    /**
     * Posts {@code registerNodeTypes} once, registers what the scripts described and closes the registry; a type the
     * registry refuses is reported in the startup log and left out. Later reloads of the startup scripts change nothing
     * (saved graphs name these types).
     */
    static void registerNodeTypes() {
        if (registered) {
            ScriptType.STARTUP.console
                    .warn("Logic script node types are registered once; restart the game to change them");
            return;
        }
        registered = true;
        RegisterNodeTypesEvent event = new RegisterNodeTypesEvent();
        if (REGISTER_NODE_TYPES.hasListeners()) {
            REGISTER_NODE_TYPES.post(ScriptType.STARTUP, event);
        }
        for (String problem : event.registerAll()) {
            ScriptType.STARTUP.console.error(problem);
        }
        ScriptNodes.close();
    }

    /** The listener {@link LogicEvents} calls; posts only when a script listens. */
    static final class Listener implements LogicEvents.Listener {
        @Override
        public boolean wantsRuns() {
            return RULE_RAN.hasListeners();
        }

        @Override
        public void ruleRan(ServerLevel level, BlockPos pos, String rule, int index, int cost, int actions,
                boolean simulated, long gameTime) {
            post(RULE_RAN, new RuleRanEvent(level, pos, rule, index, cost, actions, simulated, gameTime));
        }

        @Override
        public void notice(ServerLevel level, BlockPos pos, String rule, String text, int channels, UUID team) {
            if (NOTICE.hasListeners()) {
                post(NOTICE, new NoticeEvent(level, pos, rule, text, channels, team));
            }
        }

        private static void post(EventHandler handler, KubeEvent event) {
            try {
                handler.post(ScriptType.SERVER, event);
            } catch (RuntimeException e) {
                // A broken listener script must never stop a controller.
                ScriptType.SERVER.console.error("A MaxtechLogicEvents listener failed", e);
            }
        }
    }

    /** {@code MaxtechLogicEvents.registerNodeTypes}: {@code event.create('mypack:my_node')} returns a spec to fill. */
    public static final class RegisterNodeTypesEvent implements KubeStartupEvent {
        private final List<ScriptNodeSpec> specs = new ArrayList<>();

        /** A new script node type {@code id} ({@code namespace:path}); chain its methods to describe it. */
        public ScriptNodeSpec create(String id) {
            ScriptNodeSpec spec = ScriptNodeSpec.of(id);
            specs.add(spec);
            return spec;
        }

        /** Registers every spec; returns a message per spec the registry refused. */
        List<String> registerAll() {
            List<String> problems = new ArrayList<>();
            for (ScriptNodeSpec spec : specs) {
                try {
                    ScriptNodes.register(spec);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    problems.add("Logic script node type not registered: " + e.getMessage());
                }
            }
            return problems;
        }
    }

    /** {@code MaxtechLogicEvents.notice}: a notify node posted a notice. Read-only. */
    public static final class NoticeEvent implements KubeEvent {
        private final String dimension;
        private final BlockPos pos;
        private final String rule;
        private final String text;
        private final int channels;
        private final UUID team;

        NoticeEvent(ServerLevel level, BlockPos pos, String rule, String text, int channels, UUID team) {
            this.dimension = level.dimension().identifier().toString();
            this.pos = pos;
            this.rule = rule;
            this.text = text;
            this.channels = channels;
            this.team = team;
        }

        /** The id of the logic controller's dimension, such as {@code minecraft:overworld}. */
        public String getDimension() {
            return dimension;
        }

        /** The logic controller's position. */
        public BlockPos getPos() {
            return pos;
        }

        /** The name of the rule (graph) whose notify node posted it. */
        public String getRule() {
            return rule;
        }

        /** The notice's text as the player wrote it (display markup included). */
        public String getText() {
            return text;
        }

        public boolean isLog() {
            return (channels & LogicContext.CHANNEL_LOG) != 0;
        }

        public boolean isToast() {
            return (channels & LogicContext.CHANNEL_TOAST) != 0;
        }

        public boolean isChat() {
            return (channels & LogicContext.CHANNEL_CHAT) != 0;
        }

        public boolean isDisplays() {
            return (channels & LogicContext.CHANNEL_DISPLAYS) != 0;
        }

        /** The team the notice went to: an FTB party, a research team, or the owner's own UUID. */
        public UUID getTeam() {
            return team;
        }
    }

    /** {@code MaxtechLogicEvents.ruleRan}: a rule ran. Read-only. */
    public static final class RuleRanEvent implements KubeEvent {
        private final String dimension;
        private final BlockPos pos;
        private final String rule;
        private final int index;
        private final int cost;
        private final int actions;
        private final boolean simulated;
        private final long gameTime;

        RuleRanEvent(ServerLevel level, BlockPos pos, String rule, int index, int cost, int actions, boolean simulated,
                long gameTime) {
            this.dimension = level.dimension().identifier().toString();
            this.pos = pos;
            this.rule = rule;
            this.index = index;
            this.cost = cost;
            this.actions = actions;
            this.simulated = simulated;
            this.gameTime = gameTime;
        }

        /** The id of the logic controller's dimension. */
        public String getDimension() {
            return dimension;
        }

        public BlockPos getPos() {
            return pos;
        }

        /** The rule's name. */
        public String getRule() {
            return rule;
        }

        /** The rule's row in the controller, from 0. */
        public int getIndex() {
            return index;
        }

        /** Checks of the team budget the run cost. */
        public int getCost() {
            return cost;
        }

        /** Actions the run asked for. */
        public int getActions() {
            return actions;
        }

        /** Whether the rule simulates, so its actions were only recorded. */
        public boolean isSimulated() {
            return simulated;
        }

        public long getGameTime() {
            return gameTime;
        }
    }
}
