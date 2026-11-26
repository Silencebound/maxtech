package dev.silencebound.maxtech.storage.network.snapshot;

import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorSettings;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.SharedVariable;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.p2p.P2PDevice;
import dev.silencebound.maxtech.storage.p2p.P2PSettings;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorDevice;
import dev.silencebound.maxtech.storage.sensor.SensorSettings;
import dev.silencebound.maxtech.storage.smartlabel.LabelSettings;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelBlockEntity;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelContent;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes the settings a {@link NetworkSnapshot} keeps (M7C item 27). What a device gives:
 * <ul>
 * <li>a device with blueprint card settings ({@link SettingsCopyable}: ports, interfaces, condition switches, displays,
 * world devices, bulk drives; blocks and parts): those settings, written and applied the card's way;</li>
 * <li>every device: its label, its storage priority ({@link Prioritized}) and, when the network may pause it, its power
 * priority;</li>
 * <li>the controller: its access list (security rules), craft limits and the network name; the logic controller: its
 * rules and shared variables; a smart label, a sensor (block and part), a generator provider and an alarm module: their
 * settings; a stock keeper: its rules; a P2P end (block and part): its link (role and frequency).</li>
 * </ul>
 * Contents never: no items, cells, energy or patterns. Whether a switchable device is switched off is kept as a stored
 * setting (not load shedding's pause or redstone power) and restored through {@link NetworkDevice#setSwitchedOff}; the
 * controller cannot be switched, so it has none. Snapshots taken before the sensors to the network name were kept leave
 * those settings alone on restore ({@link NetworkSnapshot#format}). The network name is a server-wide unique claim: a
 * restore takes it back only when no other network holds it now, and reports it otherwise. Server thread.
 */
public final class SnapshotDevices {
    private SnapshotDevices() {
    }

    /** Why a device's settings were not restored. */
    public enum Skip {
        /** No device of any kind there now. */
        GONE,
        /** A device of another kind there now. */
        CHANGED,
        /** The chunk is not loaded; nothing was loaded for the restore. */
        UNLOADED
    }

    /** A device a restore skipped. */
    public record Skipped(BlockPos pos, Optional<Direction> face, Skip why) {
    }

    /**
     * What a restore did: devices whose settings were applied, those skipped, and the saved network name when another
     * network holds it now, so the controller kept its current name (empty when the name came back or did not change).
     */
    public record Result(int restored, List<Skipped> skipped, Optional<String> nameTaken) {
    }

    /**
     * What {@code device} is, as a restore matches it: the blueprint card's kind for devices with card settings (block
     * and part forms share it), else {@code part/<part type>} or {@code block/<block entity type>}; empty for devices
     * that are neither a block entity nor a cable part.
     */
    public static Optional<Identifier> kind(NetworkDevice device) {
        if (device instanceof SettingsCopyable copyable) {
            return Optional.of(copyable.copyableSettings().kind());
        }
        if (device instanceof CablePart part) {
            return Optional.of(part.type().id().withPrefix("part/"));
        }
        if (device instanceof BlockEntity entity) {
            Identifier type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
            return type == null ? Optional.empty() : Optional.of(type.withPrefix("block/"));
        }
        return Optional.empty();
    }

    /** The settings of every device in {@code network} that has a place and a kind. Allocates; for the command. */
    public static List<NetworkSnapshot.Device> capture(NetworkState network) {
        List<NetworkSnapshot.Device> out = new ArrayList<>();
        for (NetworkDevice device : network.devices()) {
            if (device.isRemoved()) {
                continue;
            }
            NetworkSnapshot.Device entry = capture(device);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /** {@code device}'s settings; null when it has no place or kind. */
    public static NetworkSnapshot.@Nullable Device capture(NetworkDevice device) {
        Optional<Identifier> kind = kind(device);
        BlockPos pos;
        Optional<Direction> face;
        if (device instanceof CablePart part) {
            pos = part.pos();
            face = Optional.of(part.face());
        } else if (device instanceof BlockEntity entity) {
            pos = entity.getBlockPos();
            face = Optional.empty();
        } else {
            return null;
        }
        if (kind.isEmpty()) {
            return null;
        }
        DataComponentPatch.Builder settings = DataComponentPatch.builder();
        if (device instanceof SettingsCopyable copyable) {
            settings.set(copyable.copyableSettings().copy().get());
        }
        DeviceLabel label = device.label();
        if (!label.isEmpty()) {
            settings.set(ModDataComponents.DEVICE_LABEL.get(), label);
        }
        if (device instanceof ControllerBlockEntity core && core.isPrimary()) {
            settings.set(ModDataComponents.NETWORK_SECURITY.get(), core.security());
            settings.set(ModDataComponents.CRAFT_LIMITS.get(), core.craftLimits());
        } else if (device instanceof LogicControllerBlockEntity logic) {
            settings.set(ModDataComponents.LOGIC_GRAPHS.get(), logic.worker().slots());
            settings.set(ModDataComponents.LOGIC_VARIABLES.get(), logic.worker().variables());
        } else if (device instanceof SmartLabelBlockEntity smart) {
            settings.set(SmartLabelContent.SETTINGS.get(), smart.settings());
        }
        captureMore(device, settings);
        Optional<Integer> priority = device instanceof Prioritized prioritized
                ? Optional.of(prioritized.priority())
                : Optional.empty();
        Optional<Integer> power = device.sheddable() ? Optional.of(device.powerPriority()) : Optional.empty();
        Optional<Boolean> off = device.switchable() ? Optional.of(device.switchedOff()) : Optional.empty();
        return new NetworkSnapshot.Device(pos.immutable(), face, kind.get(), settings.build(), priority, power, off);
    }

    /**
     * The settings {@link NetworkSnapshot#FORMAT_KINDS} added, each only when it is not its default, as the devices
     * drop with them.
     */
    private static void captureMore(NetworkDevice device, DataComponentPatch.Builder settings) {
        if (device instanceof ControllerBlockEntity core && core.isPrimary()) {
            if (!core.networkName().isEmpty()) {
                settings.set(ModDataComponents.NETWORK_NAME.get(), core.networkName());
            }
            List<AlarmSettings> rules = core.recorders().ruleSettings();
            if (!rules.isEmpty()) {
                settings.set(ModDataComponents.ALARM_RULES.get(), rules);
            }
        } else if (device instanceof SensorDevice sensor) {
            SensorSettings value = sensor.sensor().settings();
            if (!value.equals(SensorSettings.DEFAULT)) {
                settings.set(SensorContent.SETTINGS.get(), value);
            }
        } else if (device instanceof StockKeeperBlockEntity keeper) {
            List<StockRule> rules = keeper.worker().usedRules();
            if (!rules.isEmpty()) {
                settings.set(ModDataComponents.STOCK_RULES.get(), rules);
            }
        } else if (device instanceof P2PDevice end) {
            P2PSettings link = end.endpoint().settings();
            if (link.isLinked()) {
                settings.set(ModDataComponents.P2P_LINK.get(), link);
            }
        } else if (device instanceof GeneratorProviderBlockEntity generator) {
            GeneratorSettings value = generator.logic().settings();
            if (!value.equals(GeneratorSettings.DEFAULT)) {
                settings.set(ModDataComponents.GENERATOR_SETTINGS.get(), value);
            }
        }
    }

    /**
     * Applies {@code snapshot}'s settings in {@code level} to the devices that are still there and of the same kind,
     * whatever network they are in now; devices added since keep theirs. Chunks that are not loaded are skipped, never
     * loaded.
     */
    public static Result restore(ServerLevel level, NetworkSnapshot snapshot) {
        int restored = 0;
        List<Skipped> skipped = new ArrayList<>();
        Optional<String> nameTaken = Optional.empty();
        for (NetworkSnapshot.Device entry : snapshot.devices()) {
            BlockPos pos = entry.pos();
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                skipped.add(new Skipped(pos, entry.face(), Skip.UNLOADED));
                continue;
            }
            NetworkDevice device = deviceAt(level, pos, entry.face());
            if (device == null || device.isRemoved()) {
                skipped.add(new Skipped(pos, entry.face(), Skip.GONE));
                continue;
            }
            if (!kind(device).map(entry.kind()::equals).orElse(false)) {
                skipped.add(new Skipped(pos, entry.face(), Skip.CHANGED));
                continue;
            }
            apply(device, entry);
            if (snapshot.keepsAllKinds()) {
                DataComponentMap components = entry.settings().split().added();
                if (!applyMore(device, components, snapshot.keepsAlarmRules())) {
                    nameTaken = Optional.ofNullable(components.get(ModDataComponents.NETWORK_NAME.get()));
                }
            }
            if (entry.switchedOff().isPresent() && device.switchable()
                    && device.switchedOff() != entry.switchedOff().get()) {
                // The call logic uses; it saves the block and wakes or stops the tasks.
                device.setSwitchedOff(entry.switchedOff().get());
            }
            restored++;
        }
        return new Result(restored, skipped, nameTaken);
    }

    private static @Nullable NetworkDevice deviceAt(ServerLevel level, BlockPos pos, Optional<Direction> face) {
        if (face.isPresent()) {
            CableParts parts = StorageCableBlock.parts(level, pos);
            CablePart part = parts == null ? null : parts.part(face.get());
            return part;
        }
        return level.getBlockEntity(pos) instanceof NetworkDevice device ? device : null;
    }

    /** Writes {@code entry}'s settings onto {@code device}, which is of the entry's kind. */
    static void apply(NetworkDevice device, NetworkSnapshot.Device entry) {
        DataComponentMap components = entry.settings().split().added();
        if (device instanceof SettingsCopyable copyable) {
            DeviceSettings settings = copyable.copyableSettings();
            settings.paste().accept(components);
        }
        device.setLabel(components.getOrDefault(ModDataComponents.DEVICE_LABEL.get(), DeviceLabel.NONE));
        if (device instanceof ControllerBlockEntity core && core.isPrimary()) {
            core.setSecurity(
                    components.getOrDefault(ModDataComponents.NETWORK_SECURITY.get(), SecuritySettings.DEFAULT));
            core.setCraftLimits(components.getOrDefault(ModDataComponents.CRAFT_LIMITS.get(), CraftLimits.DEFAULT));
        } else if (device instanceof LogicControllerBlockEntity logic) {
            restoreLogic(logic, components.getOrDefault(ModDataComponents.LOGIC_GRAPHS.get(), List.of()),
                    components.getOrDefault(ModDataComponents.LOGIC_VARIABLES.get(), List.of()));
        } else if (device instanceof SmartLabelBlockEntity smart) {
            smart.setSettings(components.getOrDefault(SmartLabelContent.SETTINGS.get(), LabelSettings.DEFAULT));
        }
        if (device instanceof Prioritized prioritized && entry.priority().isPresent()) {
            prioritized.setPriority(entry.priority().get());
        }
        if (device.sheddable() && entry.powerPriority().isPresent()) {
            device.setPowerPriority(entry.powerPriority().get());
        }
    }

    /**
     * Writes the settings {@link NetworkSnapshot#FORMAT_KINDS} added onto {@code device}; a missing component means its
     * default, apart from the network name, which stays when none was saved. Each is set only when it differs, so an
     * untouched device keeps running as it was. Returns false when another network holds the saved network name now and
     * the controller kept its own.
     */
    static boolean applyMore(NetworkDevice device, DataComponentMap components, boolean alarmRules) {
        if (device instanceof ControllerBlockEntity core && core.isPrimary()) {
            if (alarmRules) {
                core.recorders().setRules(components.getOrDefault(ModDataComponents.ALARM_RULES.get(), List.of()));
            }
            String name = components.get(ModDataComponents.NETWORK_NAME.get());
            return name == null || core.rename(name) != NetworkNames.Renamed.TAKEN;
        }
        if (device instanceof SensorDevice sensor) {
            sensor.sensor().setSettings(components.getOrDefault(SensorContent.SETTINGS.get(), SensorSettings.DEFAULT));
        } else if (device instanceof StockKeeperBlockEntity keeper) {
            keeper.worker().setRules(components.getOrDefault(ModDataComponents.STOCK_RULES.get(), List.of()));
        } else if (device instanceof P2PDevice end) {
            P2PSettings link = components.getOrDefault(ModDataComponents.P2P_LINK.get(), P2PSettings.NONE);
            if (!link.equals(end.endpoint().settings())) {
                end.endpoint().link(link);
            }
        } else if (device instanceof GeneratorProviderBlockEntity generator) {
            generator.logic().setSettings(
                    components.getOrDefault(ModDataComponents.GENERATOR_SETTINGS.get(), GeneratorSettings.DEFAULT));
        }
        return true;
    }

    /**
     * Puts the saved rules back unless they are the rules there now (apart from their memory), so an unchanged
     * controller keeps running as it was; loading them recompiles every rule and takes the saved memory. Shared
     * variables are loaded only when their names or kinds differ, so the values rules set since stay when the set is
     * the same.
     */
    private static void restoreLogic(LogicControllerBlockEntity logic, List<GraphSlot> graphs,
            List<SharedVariable> variables) {
        LogicWorker worker = logic.worker();
        boolean changed = false;
        if (!sameRules(worker.slots(), graphs)) {
            worker.load(graphs);
            changed = true;
        }
        if (!sameVariables(worker.variables(), variables)) {
            worker.loadVariables(variables);
            changed = true;
        }
        if (changed) {
            logic.setChanged();
        }
    }

    static boolean sameRules(List<GraphSlot> now, List<GraphSlot> saved) {
        if (now.size() != saved.size()) {
            return false;
        }
        for (int i = 0; i < now.size(); i++) {
            GraphSlot a = now.get(i);
            GraphSlot b = saved.get(i);
            if (!(a.name().equals(b.name()) && a.enabled() == b.enabled() && a.graph().equals(b.graph())
                    && a.simulate() == b.simulate() && a.previous().equals(b.previous()) && a.pins().equals(b.pins())
                    && a.history().equals(b.history()) && a.waiting() == b.waiting()
                    && a.instance().equals(b.instance()) && a.id() == b.id())) {
                return false;
            }
        }
        return true;
    }

    static boolean sameVariables(List<SharedVariable> now, List<SharedVariable> saved) {
        if (now.size() != saved.size()) {
            return false;
        }
        for (SharedVariable variable : saved) {
            boolean found = false;
            for (SharedVariable current : now) {
                if (current.name().equals(variable.name()) && current.yesNo() == variable.yesNo()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
