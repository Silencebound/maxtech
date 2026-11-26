package dev.silencebound.maxtech.storage.label;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.PowerPriority;
import dev.silencebound.maxtech.storage.network.SwitchSource;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

/**
 * The label tab's packets. The client {@link Ask}s when a device screen opens and the server answers with {@link Show}
 * only when the open menu has a device; {@link Change} changes the label and {@link SetPowerPriority} the power
 * priority (M7L2 load shedding), each answered with {@link Show} too. The server acts only for the sender's open
 * {@link LabelMenu} with the payload's container id, while the menu is still valid, and changes the device only for a
 * player who may configure it. The label codec cleans the text and refuses more than {@link DeviceLabel#MAX_LENGTH}
 * characters; the priority is clamped to 1 to 5.
 */
public final class LabelPayloads {
    /** The container the last {@link Show} was for; -1 for none. Client thread only. */
    private static int shownContainer = -1;
    private static DeviceLabel shown = DeviceLabel.NONE;
    private static boolean shownSwitchable;
    private static boolean shownOff;
    private static @Nullable SwitchSource shownOffBy;
    private static int shownPowerPriority;
    private static boolean shownPowerPaused;
    private static @Nullable ChannelShortage shownOffline;
    private static boolean shownUnpowered;
    /** Changes whenever the shown status does, so screens rebuild what they draw from it only then. */
    private static int version;

    private LabelPayloads() {
    }

    /** Client to server: what is the open device's label? */
    public record Ask(int containerId) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(MaxtechStorage.id("label_ask"));
        public static final StreamCodec<ByteBuf, Ask> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Ask::containerId, Ask::new);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    /** Client to server: label the open device {@code label} ({@link DeviceLabel#NONE} clears it). */
    public record Change(int containerId, DeviceLabel label) implements CustomPacketPayload {
        public static final Type<Change> TYPE = new Type<>(MaxtechStorage.id("label_change"));
        public static final StreamCodec<ByteBuf, Change> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Change::containerId, DeviceLabel.STREAM_CODEC, Change::label, Change::new);

        @Override
        public Type<Change> type() {
            return TYPE;
        }
    }

    /** Client to server: give the open device power priority {@code priority} (1 to 5, M7L2 load shedding). */
    public record SetPowerPriority(int containerId, int priority) implements CustomPacketPayload {
        public static final Type<SetPowerPriority> TYPE = new Type<>(MaxtechStorage.id("power_priority_set"));
        public static final StreamCodec<ByteBuf, SetPowerPriority> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetPowerPriority::containerId, ByteBufCodecs.VAR_INT, SetPowerPriority::priority,
                SetPowerPriority::new);

        @Override
        public Type<SetPowerPriority> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the open device's label, whether logic can turn it off, whether it did and which graph did
     * (M7L, shown in the screen's status tab), and its power priority (0 for a device load shedding never pauses) and
     * whether load shedding paused it (M7L2), why it is offline for channels (M7N) and whether its network has no power
     * (B09 item 21d); never sent for a menu without a device.
     */
    public record Show(int containerId, DeviceLabel label, boolean switchable, boolean switchedOff,
            Optional<SwitchSource> switchedOffBy, int powerPriority, boolean powerPaused,
            Optional<ChannelShortage> offline, boolean unpowered) implements CustomPacketPayload {
        public static final Type<Show> TYPE = new Type<>(MaxtechStorage.id("label_show"));
        public static final StreamCodec<ByteBuf, Show> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Show::containerId, DeviceLabel.STREAM_CODEC, Show::label, ByteBufCodecs.BOOL, Show::switchable,
                ByteBufCodecs.BOOL, Show::switchedOff, ByteBufCodecs.optional(SwitchSource.STREAM_CODEC),
                Show::switchedOffBy, ByteBufCodecs.VAR_INT, Show::powerPriority, ByteBufCodecs.BOOL, Show::powerPaused,
                ByteBufCodecs.optional(ChannelShortage.STREAM_CODEC), Show::offline, ByteBufCodecs.BOOL,
                Show::unpowered, Show::new);

        @Override
        public Type<Show> type() {
            return TYPE;
        }
    }

    /** Registers the four payloads; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Ask.TYPE, Ask.STREAM_CODEC,
                LabelPayloads::ask);
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Change.TYPE, Change.STREAM_CODEC,
                LabelPayloads::set);
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, SetPowerPriority.TYPE,
                SetPowerPriority.STREAM_CODEC, LabelPayloads::setPower);
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Show.TYPE, Show.STREAM_CODEC,
                LabelPayloads::show);
        LabelCopy.register();
    }

    /** The device of {@code player}'s open label menu with {@code containerId}, if it is still valid; server side. */
    public static @Nullable NetworkDevice target(Player player, int containerId) {
        AbstractContainerMenu open = player.containerMenu;
        if (open instanceof LabelMenu menu && open.containerId == containerId && open.stillValid(player)) {
            return menu.labelDevice();
        }
        return null;
    }

    /**
     * Labels {@code player}'s open device {@code label}, as a {@link Change} does; false, changing nothing, when there
     * is no such device or the player may not configure it. Server side.
     */
    public static boolean apply(Player player, int containerId, DeviceLabel label) {
        NetworkDevice device = target(player, containerId);
        if (device == null || !NetworkAccess.canConfigure(player, device)) {
            return false;
        }
        device.setLabel(label);
        return true;
    }

    /**
     * Gives {@code player}'s open device power priority {@code priority} (clamped to 1 to 5), as a
     * {@link SetPowerPriority} does; false, changing nothing, when there is no such device, load shedding never pauses
     * it, or the player may not configure it. Server side.
     */
    public static boolean applyPowerPriority(Player player, int containerId, int priority) {
        NetworkDevice device = target(player, containerId);
        if (device == null || !device.sheddable() || !NetworkAccess.canConfigure(player, device)) {
            return false;
        }
        device.setPowerPriority(PowerPriority.clamp(priority));
        return true;
    }

    private static void ask(Ask payload, Player player) {
        reply(player, payload.containerId());
    }

    private static void set(Change payload, Player player) {
        apply(player, payload.containerId(), payload.label());
        reply(player, payload.containerId());
    }

    private static void setPower(SetPowerPriority payload, Player player) {
        applyPowerPriority(player, payload.containerId(), payload.priority());
        reply(player, payload.containerId());
    }

    private static void reply(Player player, int containerId) {
        NetworkDevice device = target(player, containerId);
        if (device != null && player instanceof ServerPlayer serverPlayer) {
            Platform.get().network().sendToPlayer(serverPlayer,
                    new Show(containerId, device.label(), device.switchable(), device.switchedOff(),
                            device.switchedOffBy(), device.sheddable() ? device.powerPriority() : 0,
                            device.powerPaused(), device.deviceLink().channelShortage(), unpowered(device)));
        }
    }

    private static boolean unpowered(NetworkDevice device) {
        NetworkState network = device.deviceLink().network();
        return network != null && !network.isPowered();
    }

    private static void show(Show payload, Player player) {
        if (player.containerMenu.containerId == payload.containerId()) {
            shownContainer = payload.containerId();
            shown = payload.label();
            shownSwitchable = payload.switchable();
            shownOff = payload.switchedOff();
            shownOffBy = payload.switchedOffBy().orElse(null);
            shownPowerPriority = payload.powerPriority();
            shownPowerPaused = payload.powerPaused();
            shownOffline = payload.offline().orElse(null);
            shownUnpowered = payload.unpowered();
            version++;
        }
    }

    /** Client side: forgets the last answer, before a screen asks again. */
    public static void forget() {
        shownContainer = -1;
        shown = DeviceLabel.NONE;
        shownSwitchable = false;
        shownOff = false;
        shownOffBy = null;
        shownPowerPriority = 0;
        shownPowerPaused = false;
        shownOffline = null;
        shownUnpowered = false;
        version++;
    }

    /** Client side: changes whenever the shown status or label changes (a new answer, or {@link #forget}). */
    public static int version() {
        return version;
    }

    /** Client side: whether the server said the menu {@code containerId} has a device, so the tab shows. */
    public static boolean shownFor(int containerId) {
        return shownContainer == containerId;
    }

    /** Client side: the label the server last showed. */
    public static DeviceLabel shown() {
        return shown;
    }

    /** Client side: whether the server said logic can turn the open device off, so the status tab shows. */
    public static boolean shownSwitchable() {
        return shownSwitchable;
    }

    /** Client side: whether the server said the open device is turned off by logic. */
    public static boolean shownOff() {
        return shownOff;
    }

    /** Client side: the graph the server said turned the open device off; empty when on or not known. */
    public static Optional<SwitchSource> shownOffBy() {
        return Optional.ofNullable(shownOffBy);
    }

    /** Client side: the open device's power priority (1 to 5), 0 when load shedding never pauses it. */
    public static int shownPowerPriority() {
        return shownPowerPriority;
    }

    /** Client side: whether the server said load shedding paused the open device. */
    public static boolean shownPowerPaused() {
        return shownPowerPaused;
    }

    /** Client side: whether the server said the open device's network has no power (B09 item 21d). */
    public static boolean shownUnpowered() {
        return shownUnpowered;
    }

    /** Client side: why the server said the open device is offline for channels (M7N); empty while it is not. */
    public static Optional<ChannelShortage> shownOffline() {
        return Optional.ofNullable(shownOffline);
    }

    /**
     * Client side: whether the status tab shows: logic can turn the device off, load shedding can pause it, or it is
     * offline for channels.
     */
    public static boolean shownStatus() {
        return shownSwitchable || shownPowerPriority > 0 || shownOffline != null;
    }
}
