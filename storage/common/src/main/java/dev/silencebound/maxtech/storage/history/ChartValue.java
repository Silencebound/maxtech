package dev.silencebound.maxtech.storage.history;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.display.DisplayText;
import dev.silencebound.maxtech.storage.logic.LogicCompiler;
import dev.silencebound.maxtech.storage.logic.RuleCalls;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.sensor.SensorDevice;
import dev.silencebound.maxtech.storage.sensor.SensorRef;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * Which network value a history chart records, as the player types it in the display's settings (M7L2 history charts).
 * The forms, case-insensitive before the first colon:
 * <ul>
 * <li>{@code energy}: FE stored; {@code bytes}: bytes the cells use; {@code items}: items stored; {@code fluid}: mB of
 * fluid stored;</li>
 * <li>{@code count:minecraft:iron_ingot}: one item's stored amount; {@code fluid:minecraft:water}: one fluid's, in
 * mB;</li>
 * <li>{@code sensor:furnace:energy.stored}: a value of the first sensor labelled {@code furnace}, as the display text's
 * {@code sensor} placeholder ({@link DisplayText});</li>
 * <li>{@code variable:main:batch}: shared variable {@code batch} of the first Logic Controller labelled
 * {@code main};</li>
 * <li>{@code pin:main:Smelting:1}: the first pinned value of rule {@code Smelting} (a name or a row number) on that
 * controller.</li>
 * </ul>
 * Reading costs a label lookup at most and allocates nothing; a pin's rule is looked up by name only when the network
 * or the controller's rules changed. It happens once per sample. Server thread. A value that cannot be read (no such
 * device, a sensor that has not read yet) reads as {@link ValueRing#NO_VALUE}.
 */
public final class ChartValue {
    /** Longest reference a player may type. */
    public static final int MAX_LENGTH = 128;

    private enum Kind {
        ENERGY, BYTES, ITEMS, FLUIDS, KEY, SENSOR, VARIABLE, PIN
    }

    private final Kind kind;
    private final @Nullable StackKey key;
    private final String label;
    private final String name;
    private final @Nullable SensorRef ref;
    private final int pin;
    // The resolved rule of a pin reference (server thread; each recorder parses its own reference).
    private @Nullable NetworkState cachedNetwork;
    private @Nullable LogicControllerBlockEntity cachedController;
    private long cachedRevision = Long.MIN_VALUE;
    private int cachedGraph = -1;

    private ChartValue(Kind kind, @Nullable StackKey key, String label, String name, @Nullable SensorRef ref, int pin) {
        this.kind = kind;
        this.key = key;
        this.label = label;
        this.name = name;
        this.ref = ref;
        this.pin = pin;
    }

    private static ChartValue of(Kind kind) {
        return new ChartValue(kind, null, "", "", null, 0);
    }

    /** {@code text} read as a reference; empty when it is none of the forms, or names an unknown item or value. */
    public static Optional<ChartValue> parse(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        int colon = trimmed.indexOf(':');
        String head = (colon < 0 ? trimmed : trimmed.substring(0, colon)).toLowerCase(Locale.ROOT);
        String rest = colon < 0 ? "" : trimmed.substring(colon + 1).strip();
        if (colon < 0) {
            return switch (head) {
                case "energy" -> Optional.of(of(Kind.ENERGY));
                case "bytes" -> Optional.of(of(Kind.BYTES));
                case "items" -> Optional.of(of(Kind.ITEMS));
                case "fluid" -> Optional.of(of(Kind.FLUIDS));
                default -> Optional.empty();
            };
        }
        return switch (head) {
            case "count" -> {
                Item item = DisplayText.item(rest);
                yield item == null
                        ? Optional.empty()
                        : Optional.of(new ChartValue(Kind.KEY, ItemKey.of(item), "", "", null, 0));
            }
            case "fluid" -> {
                Fluid fluid = fluid(rest);
                yield fluid == null
                        ? Optional.empty()
                        : Optional.of(new ChartValue(Kind.KEY, FluidKey.of(fluid), "", "", null, 0));
            }
            case "sensor" -> {
                Optional<DisplayText.SensorTarget> target = DisplayText.sensor(rest);
                Optional<SensorRef> ref = target.isPresent()
                        ? SensorRef.parse(target.get().valueId())
                        : Optional.empty();
                yield ref.isPresent()
                        ? Optional.of(new ChartValue(Kind.SENSOR, null, target.get().labelKey(), "", ref.get(), 0))
                        : Optional.empty();
            }
            case "variable" -> {
                int split = rest.indexOf(':');
                String label = split < 0 ? "" : DeviceLabel.key(rest.substring(0, split));
                String variable = split < 0 ? "" : LogicCompiler.variableName(rest.substring(split + 1));
                yield label.isEmpty() || variable.isEmpty()
                        ? Optional.empty()
                        : Optional.of(new ChartValue(Kind.VARIABLE, null, label, variable, null, 0));
            }
            case "pin" -> pin(rest);
            default -> Optional.empty();
        };
    }

    /** {@code label:rule:n}, the rule's name may hold colons. */
    private static Optional<ChartValue> pin(String rest) {
        int first = rest.indexOf(':');
        int last = rest.lastIndexOf(':');
        if (first < 0 || last <= first) {
            return Optional.empty();
        }
        String label = DeviceLabel.key(rest.substring(0, first));
        String rule = rest.substring(first + 1, last).strip();
        String number = rest.substring(last + 1).strip();
        if (label.isEmpty() || rule.isEmpty() || number.isEmpty() || number.length() > 2
                || !number.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        int n = Integer.parseInt(number);
        return n < 1 ? Optional.empty() : Optional.of(new ChartValue(Kind.PIN, null, label, rule, null, n - 1));
    }

    private static @Nullable Fluid fluid(String id) {
        Identifier parsed = Identifier.tryParse(id.strip());
        if (parsed == null || !BuiltInRegistries.FLUID.containsKey(parsed)) {
            return null;
        }
        Fluid fluid = BuiltInRegistries.FLUID.getValue(parsed);
        return fluid.isSame(Fluids.EMPTY) ? null : fluid;
    }

    /** What the reference was read as, for tests: the kind, label, name and pin index. */
    String describe() {
        return kind.name().toLowerCase(Locale.ROOT) + "|" + label + "|" + name + "|" + pin;
    }

    /** The value on {@code network} now, or {@link ValueRing#NO_VALUE} when it cannot be read. */
    public long read(NetworkState network) {
        return switch (kind) {
            case ENERGY -> network.storedEnergy();
            case BYTES -> network.usedBytes();
            case ITEMS -> network.storedItems();
            case FLUIDS -> network.storedFluidMb();
            case KEY -> key == null ? ValueRing.NO_VALUE : network.contents().amount(key);
            case SENSOR -> sensor(network);
            case VARIABLE, PIN -> logic(network);
        };
    }

    private long sensor(NetworkState network) {
        SensorRef current = ref;
        List<NetworkDevice> devices = network.labels().devicesByKey(label);
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i) instanceof SensorDevice sensor && current != null) {
                return sensor.sensor().hasRead() ? sensor.sensor().value(current) : ValueRing.NO_VALUE;
            }
        }
        return ValueRing.NO_VALUE;
    }

    private long logic(NetworkState network) {
        List<NetworkDevice> devices = network.labels().devicesByKey(label);
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i) instanceof LogicControllerBlockEntity controller) {
                LogicWorker worker = controller.worker();
                if (kind == Kind.VARIABLE) {
                    return worker.variable(name);
                }
                int graph = graph(network, controller, worker);
                return graph < 0 || graph >= worker.size() || pin >= worker.pinCount(graph)
                        ? ValueRing.NO_VALUE
                        : worker.pinValue(graph, pin);
            }
        }
        return ValueRing.NO_VALUE;
    }

    /**
     * The row of the pinned rule on {@code controller}: looked up by name once, and again only when the network, the
     * controller or its rules (a save, rename, removal or new pins: {@link LogicWorker#revision()}) changed, so a
     * sample costs no lookup and no allocation.
     */
    // Network and controller are compared by identity: a rebuilt network or a replaced block entity is a new object.
    @SuppressWarnings("ReferenceEquality")
    private int graph(NetworkState network, LogicControllerBlockEntity controller, LogicWorker worker) {
        if (network != cachedNetwork || controller != cachedController || worker.revision() != cachedRevision) {
            cachedNetwork = network;
            cachedController = controller;
            cachedRevision = worker.revision();
            cachedGraph = RuleCalls.target(name, worker.size(), worker::name);
        }
        return cachedGraph;
    }
}
