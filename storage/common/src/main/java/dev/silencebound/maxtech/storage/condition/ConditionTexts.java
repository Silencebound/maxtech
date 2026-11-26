package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.Glance;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/**
 * A condition switch's lines (GUI, tooltip, info) and its settings in an item ({@code maxtech_storage:condition}, only
 * when they differ from a new switch's, so new switches stack).
 */
public final class ConditionTexts {
    public static final String ABOVE = key("above");
    public static final String BELOW = key("below");
    public static final String SUMMARY = key("summary");
    public static final String SUMMARY_PERCENT = key("summary.percent");
    public static final String SUMMARY_RESEARCH = key("summary.research");
    public static final String NOTHING = key("nothing");
    /** The subject in logic mode (M7L): who sets the output. */
    public static final String BY_LOGIC = key("by_logic");
    /** "Strength: %s", for logic mode. */
    public static final String STRENGTH = key("strength");
    public static final String INVERTED = key("inverted");
    public static final String NOT_INVERTED = key("not_inverted");
    public static final String OUTPUT_ON = key("output.on");
    public static final String OUTPUT_OFF = key("output.off");
    public static final String WATCHES = key("watches");
    public static final String THRESHOLD = key("threshold");
    /** "%s channel": a network redstone channel by its colour (M7L2). */
    public static final String CHANNEL = key("channel");
    /** "Red channel: 7", for Jade, the scanner and the analyzer. */
    public static final String CHANNEL_VALUE = key("channel.value");
    /** Network log: a channel switch left out the share of a writer whose owner may not configure it. */
    public static final String LOG_CHANNEL_DENIED = key("log.channel_denied");
    /** "Input: %s", the redstone into a switch writing a channel. */
    public static final String CHANNEL_INPUT = key("channel.input");
    /** "Requested: %s", what a switch in request mode still owes its jobs. */
    public static final String REQUESTED = key("requested");

    static {
        for (ConditionType type : ConditionType.values()) {
            ModContent.CONTENT.translation(type.translationKey(), switch (type) {
                case ITEM_COUNT -> "Item count";
                case TAG_COUNT -> "Tag count";
                case POWER -> "Power";
                case OVERLOAD -> "Link channels in use";
                case RESEARCH -> "Research done";
                case LOGIC -> "Driven by logic";
                case CHANNEL_OUT -> "Channel to redstone";
                case CHANNEL_IN -> "Redstone to channel";
                case WHILE_CRAFTING -> "While crafting";
                case CRAFT_REQUEST -> "Request on signal";
            });
            ModContent.CONTENT.translation(type.hintKey(), switch (type) {
                case ITEM_COUNT -> "On while the network holds more (or fewer) of the item than the threshold";
                case TAG_COUNT -> "On while the network holds more (or fewer) items of the tag than the threshold";
                case POWER -> "On while the network's stored energy is above (or below) the percentage";
                case OVERLOAD -> "On while the busiest link on the way to the controller is above (or below) the "
                        + "percentage of its channels";
                case RESEARCH -> "On once your team has done the research";
                case LOGIC -> "A logic graph sets the strength through the switch's label";
                case CHANNEL_OUT -> "Gives the value of one of the network's redstone channels";
                case CHANNEL_IN -> "Writes the redstone coming in to one of the network's redstone channels";
                case WHILE_CRAFTING -> "On while a crafting job of the network is making the item";
                case CRAFT_REQUEST -> "Shows the item as craftable. When a job needs it, the switch is on until the "
                        + "job's amount has arrived in the network, so a machine on redstone makes it";
            });
        }
        ModContent.CONTENT.translation(REQUESTED, "Requested: %s");
        ModContent.CONTENT.translation(CHANNEL, "%s channel");
        ModContent.CONTENT.translation(CHANNEL_VALUE, "%s: %s");
        ModContent.CONTENT.translation(CHANNEL_INPUT, "Input: %s");
        ModContent.CONTENT.translation(LOG_CHANNEL_DENIED,
                "A channel switch ignores the redstone channel value from %s: they may not change it");
        ModContent.CONTENT.translation(ABOVE, "above");
        ModContent.CONTENT.translation(BELOW, "below");
        ModContent.CONTENT.translation(SUMMARY, "%s of %s %s %s");
        ModContent.CONTENT.translation(SUMMARY_PERCENT, "%s %s %s%%");
        ModContent.CONTENT.translation(SUMMARY_RESEARCH, "%s: %s");
        ModContent.CONTENT.translation(NOTHING, "nothing set");
        ModContent.CONTENT.translation(BY_LOGIC, "Set by graphs through the label");
        ModContent.CONTENT.translation(STRENGTH, "Strength: %s");
        ModContent.CONTENT.translation(INVERTED, "Inverted");
        ModContent.CONTENT.translation(NOT_INVERTED, "Not inverted");
        ModContent.CONTENT.translation(OUTPUT_ON, "Output: on");
        ModContent.CONTENT.translation(OUTPUT_OFF, "Output: off");
        ModContent.CONTENT.translation(WATCHES, "Watches");
        ModContent.CONTENT.translation(THRESHOLD, "Threshold: %s");
    }

    private ConditionTexts() {
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }

    private static String key(String path) {
        return "gui." + MaxtechStorage.MOD_ID + ".condition." + path;
    }

    /** What is watched: the item's name, {@code #tag}, the node's name, or "nothing set". */
    public static Component subject(ConditionSettings settings) {
        return switch (settings.type()) {
            case ITEM_COUNT, WHILE_CRAFTING, CRAFT_REQUEST -> settings.item()
                    .<Component>map(key -> key.toStack(1).getHoverName()).orElse(Component.translatable(NOTHING));
            case TAG_COUNT -> settings.tag().<Component>map(tag -> Component.literal("#" + tag.location()))
                    .orElse(Component.translatable(NOTHING));
            case RESEARCH -> settings.node().<Component>map(node -> Component.translatable(ResearchNode.nameKey(node)))
                    .orElse(Component.translatable(NOTHING));
            case LOGIC -> Component.translatable(BY_LOGIC);
            case CHANNEL_OUT, CHANNEL_IN -> channel(settings.channel());
            case POWER, OVERLOAD -> Component.empty();
        };
    }

    /** "Red channel", in the dye's colour, for network redstone channel {@code channel} (a dye colour id). */
    public static Component channel(int channel) {
        DyeColor dye = DyeColor.byId(channel);
        return Component.translatable(CHANNEL, Component.translatable("color.minecraft." + dye.getName()))
                .withColor(dye.getTextColor());
    }

    /** One line: "Item count of Cobblestone above 64", "Power below 20%", "Research done: Memory II". */
    public static Component summary(ConditionSettings settings) {
        Component type = Component.translatable(settings.type().translationKey());
        Component side = Component.translatable(settings.above() ? ABOVE : BELOW);
        String threshold = InfoFormat.number(settings.threshold());
        return switch (settings.type()) {
            case ITEM_COUNT, TAG_COUNT -> Component.translatable(SUMMARY, type, subject(settings), side, threshold);
            case POWER, OVERLOAD -> Component.translatable(SUMMARY_PERCENT, type, side, threshold);
            case RESEARCH, LOGIC, CHANNEL_OUT, CHANNEL_IN, WHILE_CRAFTING, CRAFT_REQUEST ->
                Component.translatable(SUMMARY_RESEARCH, type, subject(settings));
        };
    }

    /** At a glance, for the scanner, the analyzer and Jade: the on or off icon and the condition in short. */
    static void glance(ConditionWorker worker, BlockInfoSink sink) {
        sink.icon(worker.output() ? Glance.SWITCH_ON : Glance.SWITCH_OFF, summary(worker.settings()));
    }

    /** The details after the glance: inversion, output, strength, channel values and what a request still owes. */
    static void info(ConditionWorker worker, BlockInfoSink sink) {
        if (worker.settings().inverted()) {
            sink.line(Component.translatable(INVERTED));
        }
        sink.line(Component.translatable(worker.output() ? OUTPUT_ON : OUTPUT_OFF));
        if (worker.settings().type() == ConditionType.LOGIC) {
            sink.line(Component.translatable(STRENGTH, Integer.toString(worker.strength())));
        }
        if (worker.settings().type().channel()) {
            sink.line(Component.translatable(CHANNEL_VALUE, channel(worker.settings().channel()),
                    Integer.toString(worker.channelValue())));
        }
        if (worker.settings().type() == ConditionType.CHANNEL_IN) {
            sink.line(Component.translatable(CHANNEL_INPUT, Integer.toString(worker.input())));
        } else if (worker.settings().type() == ConditionType.CHANNEL_OUT) {
            sink.line(Component.translatable(STRENGTH, Integer.toString(worker.strength())));
        } else if (worker.settings().type() == ConditionType.CRAFT_REQUEST && worker.requested() > 0) {
            sink.line(Component.translatable(REQUESTED, InfoFormat.number(worker.requested())));
        }
    }

    /** The item's tooltip: the summary, and "Inverted" when it is. */
    public static void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        ConditionSettings settings = read(stack);
        lines.accept(summary(settings));
        if (settings.inverted()) {
            lines.accept(Component.translatable(INVERTED));
        }
    }

    static void write(DataComponentMap.Builder components, ConditionSettings settings) {
        if (!settings.equals(ConditionSettings.DEFAULT)) {
            components.set(ModDataComponents.CONDITION.get(), settings);
        }
    }

    static ConditionSettings read(DataComponentGetter components) {
        return components.getOrDefault(ModDataComponents.CONDITION.get(), ConditionSettings.DEFAULT);
    }
}
