package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A sensor's lines (screen, tooltip, info) and its settings in an item ({@code maxtech_storage:sensor}, only when they
 * differ from a new sensor's, so new sensors stack).
 */
public final class SensorTexts {
    public static final String POLL = key("poll");
    public static final String NOT_LOADED = key("not_loaded");
    public static final String NOTHING = key("nothing");
    public static final String READS = key("reads");
    public static final String VALUES = key("values");
    public static final String STATE = key("value.state");
    public static final String TANK_AMOUNT = key("value.tank_amount");
    public static final String TANK_CAPACITY = key("value.tank_capacity");
    /** A pack reader value whose path was not in the target's saved data. */
    public static final String NOT_FOUND = key("not_found");
    /** The info line naming the pack reader values that were not found. */
    public static final String MISSING = key("missing");
    /** The header of a pack reader's values on the screen. */
    public static final String READER = key("reader");
    /** The tooltip line with a value's id, as a logic node names it. */
    public static final String VALUE_ID = key("value_id");
    public static final String YES = key("yes");
    public static final String NO = key("no");
    /** A time value of -1: the energy network's batteries do not fill or empty. */
    public static final String NEVER = key("never");
    /** The why line of a logic node that reads a sensor value the sensor did not find. */
    public static final String WHY_NOT_FOUND = key("why_not_found");

    static {
        for (SensorPoll poll : SensorPoll.values()) {
            ModContent.CONTENT.translation(pollKey(poll), switch (poll) {
                case OFF -> "off";
                case ONE_SECOND -> "every second";
                case FIVE_SECONDS -> "every 5 seconds";
                case THIRTY_SECONDS -> "every 30 seconds";
            });
        }
        for (SensorRef.Kind kind : SensorRef.Kind.values()) {
            if (!kind.named()) {
                ModContent.CONTENT.translation(valueKey(kind), switch (kind) {
                    case REDSTONE -> "Redstone signal";
                    case COMPARATOR -> "Comparator output";
                    case ENERGY_STORED -> "Energy stored";
                    case ENERGY_CAPACITY -> "Energy capacity";
                    case MU_SUPPLY -> "Energy network supply";
                    case MU_DEMAND -> "Energy network demand";
                    case MU_RECEIVED -> "Energy network delivered";
                    case MU_SUPPLIED -> "Energy network supplied";
                    case MU_STORED -> "Energy network stored";
                    case MU_CAPACITY -> "Energy network capacity";
                    case MU_TICKS_TO_FULL -> "Energy network full in";
                    case MU_TICKS_TO_EMPTY -> "Energy network empty in";
                    case MU_PASS_NANOS -> "Energy network pass cost";
                    case ITEMS_TOTAL -> "Items stored";
                    case ITEMS_CAPACITY -> "Item capacity";
                    case ITEMS_SLOTS_USED -> "Slots used";
                    case ITEMS_SLOTS_FREE -> "Slots free";
                    case FLUIDS_TOTAL -> "Fluid stored";
                    case FLUIDS_CAPACITY -> "Fluid capacity";
                    case STATE, ITEM, FLUID, TANK, DATA -> "";
                });
            }
        }
        for (SensorUnit unit : SensorUnit.values()) {
            ModContent.CONTENT.translation(unitKey(unit), switch (unit) {
                case SIGNAL, STATE, CUSTOM, YES_NO, TEXT -> "%s";
                case ITEMS -> "%s items";
                case MILLIBUCKETS -> "%s mB";
                case ENERGY -> "%s MU";
                case SLOTS -> "%s slots";
                case MU -> "%s MU";
                case MU_PER_TICK -> "%s MU/t";
                case PERCENT -> "%s%%";
                case TICKS -> "%s ticks";
                case NANOSECONDS -> "%s ns";
            });
        }
        ModContent.CONTENT.translation(STATE, "State: %s");
        ModContent.CONTENT.translation(TANK_AMOUNT, "Tank %s fluid");
        ModContent.CONTENT.translation(TANK_CAPACITY, "Tank %s capacity");
        ModContent.CONTENT.translation(NOT_FOUND, "value not found");
        ModContent.CONTENT.translation(MISSING, "Value not found: %s");
        ModContent.CONTENT.translation(READER, "Reader %s");
        ModContent.CONTENT.translation(VALUE_ID, "Logic name: %s");
        ModContent.CONTENT.translation(YES, "yes");
        ModContent.CONTENT.translation(NO, "no");
        ModContent.CONTENT.translation(NEVER, "never");
        ModContent.CONTENT.translation(WHY_NOT_FOUND,
                "The sensor has no value by this name, or its reader did not find it in the block's data");
        ModContent.CONTENT.translation(POLL, "Polling: %s");
        ModContent.CONTENT.translation(NOT_LOADED, "Target not loaded");
        ModContent.CONTENT.translation(NOTHING, "Nothing to read");
        ModContent.CONTENT.translation(READS, "Reading: %s");
        ModContent.CONTENT.translation(VALUES, "Readable values: %s");
    }

    private SensorTexts() {
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }

    private static String key(String path) {
        return "gui." + MaxtechStorage.MOD_ID + ".sensor." + path;
    }

    public static String pollKey(SensorPoll poll) {
        return key("poll." + poll.getSerializedName());
    }

    /** The name of a fixed value kind; state, item and fluid values are named after what they read. */
    public static String valueKey(SensorRef.Kind kind) {
        return key("value." + kind.id());
    }

    /** "%s items", "%s mB" and so on. */
    public static String unitKey(SensorUnit unit) {
        return key("unit." + unit.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** {@code value} as the screen shows it: the state's word, or the number with its unit. */
    public static Component formatValue(SensorValue value) {
        if (!value.found()) {
            return Component.translatable(NOT_FOUND);
        }
        return switch (value.unit()) {
            case STATE -> value.text().isEmpty()
                    ? Component.literal(InfoFormat.number(value.value()))
                    : Component.literal(value.text());
            case TEXT -> Component.literal(value.text());
            case YES_NO -> Component.translatable(value.value() != 0 ? YES : NO);
            case TICKS -> value.value() < 0
                    ? Component.translatable(NEVER)
                    : Component.translatable(unitKey(value.unit()), InfoFormat.number(value.value()));
            case CUSTOM -> value.text().isEmpty()
                    ? Component.literal(InfoFormat.number(value.value()))
                    : Component.literal(InfoFormat.number(value.value()) + " " + value.text());
            default -> Component.translatable(unitKey(value.unit()), InfoFormat.number(value.value()));
        };
    }

    /** "Polling: every 5 seconds". */
    public static Component poll(SensorPoll poll) {
        return Component.translatable(POLL, Component.translatable(pollKey(poll)));
    }

    /** At a glance, for the scanner, the analyzer and Jade: what the sensor reads, or that its target is not loaded. */
    static void glance(SensorWorker worker, BlockInfoSink sink) {
        if (!worker.targetLoaded()) {
            sink.line(Component.translatable(NOT_LOADED));
        } else if (worker.reads() > 0 && !worker.claimBlocked()) {
            sink.line(Component.translatable(READS, worker.targetState().getBlock().getName()));
        }
    }

    /** The details after the glance: how many values, the missing ones, the poll interval. */
    static void info(SensorWorker worker, BlockInfoSink sink) {
        if (worker.targetLoaded() && worker.reads() > 0 && !worker.claimBlocked()) {
            sink.line(Component.translatable(VALUES, Integer.toString(worker.values().size())));
            List<String> missing = worker.missingValues();
            if (!missing.isEmpty()) {
                sink.line(Component.translatable(MISSING, String.join(", ", missing)));
            }
        }
        sink.line(poll(worker.settings().poll()));
    }

    /** The item's tooltip: the polling, when it is on. */
    public static void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        SensorSettings settings = read(stack);
        if (settings.poll() != SensorPoll.OFF) {
            lines.accept(poll(settings.poll()));
        }
    }

    static void write(DataComponentMap.Builder components, SensorSettings settings) {
        if (!settings.equals(SensorSettings.DEFAULT)) {
            components.set(SensorContent.SETTINGS.get(), settings);
        }
    }

    static SensorSettings read(DataComponentGetter components) {
        return components.getOrDefault(SensorContent.SETTINGS.get(), SensorSettings.DEFAULT);
    }
}
