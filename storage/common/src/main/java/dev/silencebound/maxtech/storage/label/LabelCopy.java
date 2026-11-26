package dev.silencebound.maxtech.storage.label;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprint;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Copy settings to many devices (M7C item 8): the label page's "copy to all" button applies the open device's settings
 * to every other device on its network with the same label. Settings are what the blueprint card copies (a port's
 * filter and mode, an interface's, a bulk drive's priority and so on: {@link SettingsCopyable}) and, for blocks with a
 * storage priority ({@link Prioritized}: drives, bulk drives, subnet controllers), the priority. A device takes them
 * only when it is the same kind (the card's kind, or the same block for a priority) and the player may configure it;
 * the player must be able to configure the open device too. Contents (cells, cards, stock) never move. The player gets
 * a summary of what changed, what was refused and what was another kind. A subnet's labels are its own, so devices in a
 * subnet are reached from a device in that subnet. Server thread.
 */
public final class LabelCopy {
    public static final String BUTTON_KEY = key("button");
    public static final String HINT_KEY = key("hint");
    public static final String DONE_KEY = key("done");
    public static final String DENIED_KEY = key("denied");
    public static final String OTHER_KIND_KEY = key("other_kind");
    public static final String NOTHING_KEY = key("nothing");
    public static final String NO_LABEL_KEY = key("no_label");

    static {
        ModContent.CONTENT.translation(BUTTON_KEY, "Copy to all");
        ModContent.CONTENT.translation(HINT_KEY,
                "Give every device on this network labelled the same this device's settings: filter, mode and"
                        + " priority. Devices of another kind and devices you may not configure are left alone.");
        ModContent.CONTENT.translation(DONE_KEY, "Copied the settings to %s devices labelled \"%s\"");
        ModContent.CONTENT.translation(DENIED_KEY, "%s devices skipped: you may not configure them");
        ModContent.CONTENT.translation(OTHER_KIND_KEY, "%s devices of another kind left alone");
        ModContent.CONTENT.translation(NOTHING_KEY, "This device has no settings to copy");
        ModContent.CONTENT.translation(NO_LABEL_KEY, "Label this device first; the settings go to its label");
    }

    /** What a copy did: devices changed, refused for access, and left alone for being another kind. */
    public record Result(int changed, int denied, int otherKind) {
        static final Result NONE = new Result(0, 0, 0);
    }

    /** Client to server: copy the open device's settings to every device with its label. */
    public record Copy(int containerId) implements CustomPacketPayload {
        public static final Type<Copy> TYPE = new Type<>(MaxtechStorage.id("label_copy"));
        public static final StreamCodec<ByteBuf, Copy> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Copy::containerId, Copy::new);

        @Override
        public Type<Copy> type() {
            return TYPE;
        }
    }

    private LabelCopy() {
    }

    /** Registers the payload; from {@link LabelPayloads#register}. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Copy.TYPE, Copy.STREAM_CODEC, LabelCopy::copy);
    }

    private static void copy(Copy payload, Player player) {
        NetworkDevice device = LabelPayloads.target(player, payload.containerId());
        if (device != null) {
            copy(player, device);
        }
    }

    /**
     * Copies {@code source}'s settings to the other devices of its network with its label, telling {@code player} what
     * changed (chat, when the player has a connection). Nothing changes when the player may not configure
     * {@code source}, it has no label or no network, or it has no settings to copy.
     */
    public static Result copy(Player player, NetworkDevice source) {
        DeviceLabel label = source.label();
        NetworkState network = source.deviceLink().network();
        if (!NetworkAccess.canConfigure(player, source)) {
            return Result.NONE;
        }
        if (label.isEmpty() || network == null) {
            tell(player, Component.translatable(NO_LABEL_KEY));
            return Result.NONE;
        }
        // The card's settings without the label: every target carries the label already, in its own colour.
        Blueprint blueprint = null;
        if (source instanceof SettingsCopyable copyable) {
            DeviceSettings settings = copyable.copyableSettings();
            blueprint = new Blueprint(settings.kind(), DataComponentPatch.builder().set(settings.copy().get()).build());
        }
        boolean hasPriority = source instanceof Prioritized;
        int priority = source instanceof Prioritized prioritized ? prioritized.priority() : 0;
        if (blueprint == null && !hasPriority) {
            tell(player, Component.translatable(NOTHING_KEY));
            return Result.NONE;
        }
        int changed = 0;
        int denied = 0;
        int otherKind = 0;
        List<NetworkDevice> devices = List.copyOf(network.labels().devicesByKey(label.key()));
        for (NetworkDevice target : devices) {
            if (target.equals(source)) {
                continue;
            }
            boolean settings = blueprint != null && target instanceof SettingsCopyable copyable
                    && Blueprints.fits(copyable, blueprint);
            boolean prioritySame = hasPriority && target instanceof Prioritized
                    && target.getClass().equals(source.getClass());
            if (!settings && !prioritySame) {
                otherKind++;
                continue;
            }
            if (!NetworkAccess.canConfigure(player, target)) {
                denied++;
                continue;
            }
            if (settings && blueprint != null && target instanceof SettingsCopyable copyable) {
                Blueprints.paste(copyable, blueprint);
            }
            if (prioritySame) {
                ((Prioritized) target).setPriority(priority);
            }
            changed++;
        }
        tell(player, Component.translatable(DONE_KEY, changed, label.text()));
        if (denied > 0) {
            tell(player, Component.translatable(DENIED_KEY, denied));
        }
        if (otherKind > 0) {
            tell(player, Component.translatable(OTHER_KIND_KEY, otherKind));
        }
        return new Result(changed, denied, otherKind);
    }

    private static void tell(Player player, Component line) {
        // A player with no connection (a GameTest's) cannot receive chat.
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
            serverPlayer.sendSystemMessage(line);
        }
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".label.copy." + name;
    }
}
