package dev.silencebound.maxtech.storage.crafting.stock;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The stock keeper's lines (GUI, tooltip, info) and its rules in an item ({@code maxtech_storage:stock_rules}, only
 * when one names a key, so new keepers stack).
 */
public final class StockTexts {
    public static final String RULE = key("rule");
    public static final String NOTHING = key("nothing");
    public static final String KEEP = key("keep");
    public static final String RESTART = key("restart");
    public static final String BATCH = key("batch");
    public static final String NO_LIMIT = key("no_limit");
    public static final String PRIORITY = key("priority");
    public static final String CPU_ANY = key("cpu.any");
    public static final String CPU_TIER = key("cpu.tier");
    public static final String FILLING = key("filling");
    public static final String WAITING = key("waiting");
    public static final String SWITCHED_OFF = key("switched_off");
    public static final String ON = key("on");
    public static final String OFF = key("off");
    public static final String TOGGLE_TIP = key("toggle_tip");
    public static final String SUMMARY = key("summary");
    public static final String RULES = key("rules");
    public static final String TAB_TITLE = key("tab");
    public static final String KEEPER = key("keeper");
    public static final String NO_KEEPERS = key("no_keepers");
    public static final String SET_HELD = key("set_held");
    public static final String CLEAR = key("clear");
    public static final String READ_ONLY = key("read_only");
    /** The caption above the CPU tier in the mode tab. */
    public static final String CPU_CAPTION = key("cpu.caption");
    /** The rule priority button's tooltip. */
    public static final String PRIORITY_HINT = key("priority_hint");

    static {
        ModContent.CONTENT.translation(RULE, "Rule %s of %s");
        ModContent.CONTENT.translation(NOTHING, "nothing set");
        ModContent.CONTENT.translation(KEEP, "Keep");
        ModContent.CONTENT.translation(RESTART, "Restart below");
        ModContent.CONTENT.translation(BATCH, "Batch");
        ModContent.CONTENT.translation(NO_LIMIT, "no limit");
        ModContent.CONTENT.translation(PRIORITY, "Priority %s");
        ModContent.CONTENT.translation(CPU_ANY, "Any CPU");
        ModContent.CONTENT.translation(CPU_TIER, "CPU tier %s");
        ModContent.CONTENT.translation(FILLING, "Crafting");
        ModContent.CONTENT.translation(WAITING, "Stocked");
        ModContent.CONTENT.translation(SWITCHED_OFF, "Off");
        ModContent.CONTENT.translation(ON, "On");
        ModContent.CONTENT.translation(OFF, "Off");
        ModContent.CONTENT.translation(TOGGLE_TIP,
                "Switch this rule on or off; a switched-off rule requests nothing. Logic rules can switch it too.");
        ModContent.CONTENT.translation(SUMMARY, "Keeps %s of %s");
        ModContent.CONTENT.translation(RULES, "%s stock rules");
        ModContent.CONTENT.translation(TAB_TITLE, "Stock keepers");
        ModContent.CONTENT.translation(KEEPER, "Keeper %s of %s");
        ModContent.CONTENT.translation(NO_KEEPERS, "No stock keepers on this network");
        ModContent.CONTENT.translation(SET_HELD, "Held item");
        ModContent.CONTENT.translation(CLEAR, "Clear");
        ModContent.CONTENT.translation(READ_ONLY, "Full access needed to edit");
        ModContent.CONTENT.translation(CPU_CAPTION, "Crafting CPU");
        ModContent.CONTENT.translation(PRIORITY_HINT, "Left click: +1, right click: -1");
        for (StockRule.Redstone mode : StockRule.Redstone.values()) {
            ModContent.CONTENT.translation(mode.translationKey(), switch (mode) {
                case ALWAYS -> "Ignores redstone";
                case HIGH -> "With signal";
                case LOW -> "Without signal";
            });
        }
    }

    private StockTexts() {
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }

    static String key(String path) {
        return "gui." + MaxtechStorage.MOD_ID + ".stock_keeper." + path;
    }

    /** What a rule keeps: the item's or fluid's name, or "nothing set". */
    public static Component subject(StockRule rule) {
        return rule.key().map(StockTexts::name).orElse(Component.translatable(NOTHING));
    }

    private static Component name(StackKey key) {
        return PatternItem.name(key);
    }

    /** "Keeps 64 of Iron Ingot". */
    public static Component summary(StockRule rule) {
        return Component.translatable(SUMMARY, InfoFormat.number(rule.keep()), subject(rule));
    }

    /** One line per active rule, with whether it is crafting now. */
    static void info(StockKeeperWorker worker, BlockInfoSink sink) {
        for (int i = 0; i < StockKeeperWorker.MAX_RULES; i++) {
            StockRule rule = worker.rule(i);
            if (rule.active()) {
                sink.line(Component.empty().append(summary(rule)).append(": ")
                        .append(Component.translatable(state(rule, worker.filling(i)))));
            }
        }
    }

    /** The state word of {@code rule}: switched off, crafting or stocked. */
    public static String state(StockRule rule, boolean filling) {
        return rule.switchedOff() ? SWITCHED_OFF : filling ? FILLING : WAITING;
    }

    /** The item's tooltip: one line per active rule. */
    public static void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        for (StockRule rule : read(stack)) {
            if (rule.active()) {
                lines.accept(summary(rule));
            }
        }
    }

    static void write(DataComponentMap.Builder components, List<StockRule> rules) {
        if (!rules.isEmpty()) {
            components.set(ModDataComponents.STOCK_RULES.get(), rules);
        }
    }

    static List<StockRule> read(DataComponentGetter components) {
        return components.getOrDefault(ModDataComponents.STOCK_RULES.get(), List.of());
    }
}
