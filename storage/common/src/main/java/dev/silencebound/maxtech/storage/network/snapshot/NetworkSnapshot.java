package dev.silencebound.maxtech.storage.network.snapshot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A saved copy of a storage network's device settings (M7C item 27), taken by an admin with
 * {@code /maxtech network snapshot save}. Settings only: never items, energy, block placement or the devices
 * themselves. Immutable.
 *
 * @param name
 *            the snapshot's name, unique on the server ({@link NetworkSnapshots#validName})
 * @param dimension
 *            the dimension the devices are in
 * @param anchor
 *            the network's controller when it had one, else the position the admin named; snapshots with the same
 *            dimension and anchor count as one network's for the cap
 * @param createdAt
 *            the overworld's game time when it was taken
 * @param createdBy
 *            the name of whoever took it (a player or the console)
 * @param devices
 *            one entry per device found in the network
 * @param format
 *            what the snapshot covers: {@link #FORMAT_FIRST} for snapshots taken before sensors, stock keepers, P2P
 *            links, generator providers, alarm modules and the network name were kept (saved without the field), whose
 *            restore leaves those settings as they are; {@link #FORMAT_CURRENT} for snapshots that keep them, where a
 *            missing component means its default like for every other setting
 */
public record NetworkSnapshot(String name, ResourceKey<Level> dimension, BlockPos anchor, long createdAt,
        String createdBy, List<Device> devices, int format) {
    /** Snapshots from M7C item 27's first round: device card settings, labels, priorities and controller settings. */
    public static final int FORMAT_FIRST = 1;
    /** Adds sensors, stock keepers, P2P links, generator providers, alarm modules and the network name. */
    public static final int FORMAT_KINDS = 2;
    /** Adds whether a switchable device is switched off. */
    public static final int FORMAT_SWITCHED = 3;
    /**
     * Adds the controller's redstone alarm rules (M7R item 3); a restore of an older snapshot leaves the rules alone.
     */
    public static final int FORMAT_ALARM_RULES = 4;
    /** The format of snapshots taken now. */
    public static final int FORMAT_CURRENT = FORMAT_ALARM_RULES;
    public static final Codec<NetworkSnapshot> CODEC = RecordCodecBuilder.create(i -> i
            .group(Codec.STRING.fieldOf("name").forGetter(NetworkSnapshot::name),
                    Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(NetworkSnapshot::dimension),
                    BlockPos.CODEC.fieldOf("anchor").forGetter(NetworkSnapshot::anchor),
                    Codec.LONG.fieldOf("created_at").forGetter(NetworkSnapshot::createdAt),
                    Codec.STRING.optionalFieldOf("created_by", "").forGetter(NetworkSnapshot::createdBy),
                    Device.CODEC.listOf().fieldOf("devices").forGetter(NetworkSnapshot::devices),
                    Codec.INT.optionalFieldOf("format", FORMAT_FIRST).forGetter(NetworkSnapshot::format))
            .apply(i, NetworkSnapshot::new));

    public NetworkSnapshot {
        devices = List.copyOf(devices);
    }

    /** A snapshot taken now, of the {@link #FORMAT_CURRENT current format}. */
    public NetworkSnapshot(String name, ResourceKey<Level> dimension, BlockPos anchor, long createdAt, String createdBy,
            List<Device> devices) {
        this(name, dimension, anchor, createdAt, createdBy, devices, FORMAT_CURRENT);
    }

    /** Whether the snapshot keeps the settings {@link #FORMAT_KINDS} added. */
    public boolean keepsAllKinds() {
        return format >= FORMAT_KINDS;
    }

    /** Whether the snapshot keeps the controller's alarm rules ({@link #FORMAT_ALARM_RULES}). */
    public boolean keepsAlarmRules() {
        return format >= FORMAT_ALARM_RULES;
    }

    /**
     * One device's settings.
     *
     * @param pos
     *            the device's block; for a cable part, the cable's
     * @param face
     *            the cable face of a part; empty for a block device
     * @param kind
     *            what the device is ({@link SnapshotDevices#kind}); a restore applies the settings only to a device of
     *            the same kind at the same place
     * @param settings
     *            the settings as item components: the blueprint card's for devices that have them, the device label,
     *            and the controller's (with the network name), logic controller's, smart label's, sensor's, stock
     *            keeper's, P2P link's, generator provider's or alarm module's own; a missing component means its
     *            default (see {@link NetworkSnapshot#format} for older snapshots)
     * @param priority
     *            the storage priority of a device that has one
     * @param powerPriority
     *            the power priority of a device the network may pause
     * @param switchedOff
     *            whether a switchable device was switched off (by logic or any other switch); empty for devices that
     *            cannot be switched and for snapshots older than {@link NetworkSnapshot#FORMAT_SWITCHED}, where a
     *            restore leaves the state as it is
     */
    public record Device(BlockPos pos, Optional<Direction> face, Identifier kind, DataComponentPatch settings,
            Optional<Integer> priority, Optional<Integer> powerPriority, Optional<Boolean> switchedOff) {
        /** A device entry without a switched-off state, as snapshots before {@link NetworkSnapshot#FORMAT_SWITCHED}. */
        public Device(BlockPos pos, Optional<Direction> face, Identifier kind, DataComponentPatch settings,
                Optional<Integer> priority, Optional<Integer> powerPriority) {
            this(pos, face, kind, settings, priority, powerPriority, Optional.empty());
        }

        public static final Codec<Device> CODEC = RecordCodecBuilder.create(i -> i
                .group(BlockPos.CODEC.fieldOf("pos").forGetter(Device::pos),
                        Direction.CODEC.optionalFieldOf("face").forGetter(Device::face),
                        Identifier.CODEC.fieldOf("kind").forGetter(Device::kind),
                        DataComponentPatch.CODEC.optionalFieldOf("settings", DataComponentPatch.EMPTY).forGetter(
                                Device::settings),
                        Codec.INT.optionalFieldOf("priority").forGetter(Device::priority),
                        Codec.INT.optionalFieldOf("power_priority").forGetter(Device::powerPriority),
                        Codec.BOOL.optionalFieldOf("switched_off").forGetter(Device::switchedOff))
                .apply(i, Device::new));
    }
}
