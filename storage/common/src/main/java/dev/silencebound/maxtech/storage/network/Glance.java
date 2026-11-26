package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink.Tone;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A storage block's status at a glance, the first entry of its overlay info (owner feedback 2026-10-17: compact and
 * scannable). The colours follow the front line ({@link LinkLook}): online cyan, no channel red, offline grey, plus
 * amber for states that wait (paused, standby). The overlay draws the tone's sprite ({@code info/status_<tone>}, drawn
 * by {@code mtx_info.py}) and shows the word only with the details. Worked out from cached network state, O(1).
 */
public enum Glance {
    ONLINE(Tone.GOOD, "Online"), NO_CHANNEL(Tone.BAD, "No channel"), OFFLINE(Tone.DIM, "Offline"), NO_CONTROLLER(
            Tone.DIM, "No controller"), NO_POWER(Tone.BAD, "No power"), PAUSED(Tone.WARN,
                    "Paused"), QUARANTINED(Tone.BAD, "Quarantined"), LOGIC_OFF(Tone.DIM,
                            "Turned off by logic"), POWER_PAUSED(Tone.WARN, "Power paused"), CLAIM_BLOCKED(Tone.BAD,
                                    "Blocked by a land claim"), STANDBY(Tone.WARN, "Standby"),
    /** A port, IO port or interface its redstone mode keeps from working (B09 item 9). */
    REDSTONE_OFF(Tone.DIM, "Inactive by redstone");

    /** The channel lanes drawn before a channel bar, 8 x 8. */
    public static final Identifier CHANNELS = MaxtechStorage.id("info/channels");
    /** The port's import arrow (into the network), 8 x 8. */
    public static final Identifier PORT_IMPORT = MaxtechStorage.id("info/port_import");
    /** The port's export arrow (out of the network), 8 x 8. */
    public static final Identifier PORT_EXPORT = MaxtechStorage.id("info/port_export");
    /** The port in storage mode (a storage bus): a framed box with a cyan core, 8 x 8. */
    public static final Identifier PORT_STORAGE = MaxtechStorage.id("info/port_storage");
    /** A condition switch whose output is on, 8 x 8. */
    public static final Identifier SWITCH_ON = MaxtechStorage.id("info/switch_on");
    /** A condition switch whose output is off, 8 x 8. */
    public static final Identifier SWITCH_OFF = MaxtechStorage.id("info/switch_off");

    private final Tone tone;
    private final String english;

    Glance(Tone tone, String english) {
        this.tone = tone;
        this.english = english;
    }

    static {
        for (Glance glance : values()) {
            ModContent.CONTENT.translation(glance.key(), glance.english);
        }
    }

    /** The status's lang key, e.g. {@code info.maxtech_storage.glance.no_channel}. */
    public String key() {
        return "info." + MaxtechStorage.MOD_ID + ".glance." + name().toLowerCase(Locale.ROOT);
    }

    /** The overlay's tone. */
    public Tone tone() {
        return tone;
    }

    /** The sprite of {@code tone}: {@code maxtech_storage:info/status_<tone>}. */
    public static Identifier sprite(Tone tone) {
        return MaxtechStorage.id("info/status_" + tone.name().toLowerCase(Locale.ROOT));
    }

    /** Adds {@code glance} to {@code sink} as a status with its sprite. */
    public static void status(Glance glance, BlockInfoSink sink) {
        sink.status(glance.tone, Component.translatable(glance.key()), sprite(glance.tone));
    }

    /** A network's own status: quarantined, paused, without a controller, without power, or online. */
    public static Glance of(NetworkState network) {
        if (network.quarantined()) {
            return QUARANTINED;
        }
        if (network.status() != StorageNetwork.Status.ONLINE) {
            return PAUSED;
        }
        if (network.isAdHoc()) {
            return NO_CONTROLLER;
        }
        return network.isPowered() ? ONLINE : NO_POWER;
    }

    /**
     * A device's status: no channel while a network with channel limits turned it away
     * ({@link DeviceLink#noChannel()}), offline in no network, else its network's status, narrowed to the device's own
     * reason (logic, power priority, land claim) while the network is online.
     */
    public static Glance of(NetworkDevice device) {
        DeviceLink link = device.deviceLink();
        NetworkState network = link.network();
        if (network == null) {
            return link.noChannel() ? NO_CHANNEL : OFFLINE;
        }
        Glance glance = of(network);
        if (glance != ONLINE && glance != NO_CONTROLLER) {
            return glance;
        }
        if (device.switchedOff()) {
            return LOGIC_OFF;
        }
        if (link.redstoneOff()) {
            return REDSTONE_OFF;
        }
        if (device.claimBlocked()) {
            return CLAIM_BLOCKED;
        }
        if (device.powerPaused()) {
            return POWER_PAUSED;
        }
        return glance;
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    public static void init() {
    }
}
