package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.history.ChartValue;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * Client to server: the value a player typed for a history chart ({@link ChartValue}), for the screen behind the open
 * display menu {@code containerId}. Checked like {@link DisplayTextSave}: the player's open, valid display menu, the
 * menu's access check, one save per player per tick, and a value that parses (blank clears it). A refused save changes
 * nothing.
 */
public record DisplayChartSave(int containerId, String value) implements CustomPacketPayload {
    public static final Type<DisplayChartSave> TYPE = new Type<>(MaxtechStorage.id("display_chart_save"));
    public static final StreamCodec<ByteBuf, DisplayChartSave> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DisplayChartSave::containerId, ByteBufCodecs.stringUtf8(ChartValue.MAX_LENGTH),
            DisplayChartSave::value, DisplayChartSave::new);

    /** The game tick of each player's last save. */
    private static final Object2LongOpenHashMap<UUID> LAST_SAVE = new Object2LongOpenHashMap<>();

    static {
        LAST_SAVE.defaultReturnValue(Long.MIN_VALUE);
    }

    @Override
    public Type<DisplayChartSave> type() {
        return TYPE;
    }

    /** Forgets the save ticks; when the server stops. */
    static void clear() {
        LAST_SAVE.clear();
    }

    static void handle(DisplayChartSave save, Player player) {
        apply(player, save.containerId, save.value);
    }

    /** Applies a save from {@code player}, as the payload handler does; server thread. */
    public static DisplayTextSave.Result apply(Player player, int containerId, String value) {
        if (!(player.containerMenu instanceof DisplayMenu menu) || menu.containerId != containerId) {
            return DisplayTextSave.Result.NO_MENU;
        }
        DisplayDevice device = menu.device();
        if (device == null) {
            return DisplayTextSave.Result.NO_MENU;
        }
        if (player.isSpectator() || !menu.stillValid(player)) {
            return DisplayTextSave.Result.NO_ACCESS;
        }
        long tick = player.level().getGameTime();
        if (LAST_SAVE.put(player.getUUID(), tick) == tick) {
            return DisplayTextSave.Result.TOO_SOON;
        }
        if (!value.isBlank() && ChartValue.parse(value).isEmpty()) {
            return DisplayTextSave.Result.INVALID;
        }
        DisplaySettings settings = device.settings();
        device.applySettings(settings.withChart(settings.chart().withValue(value)));
        return DisplayTextSave.Result.SAVED;
    }
}
