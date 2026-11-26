package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * Client to server: the rich text a player typed in a screen's text editor, for the screen behind the open display menu
 * {@code containerId}. The server treats it as hostile: it must name the player's open, valid display menu, the player
 * must be allowed to configure the screen (the menu's own check), one save per player per tick, and the text must pass
 * {@link DisplayText#problem} (length, lines, icons of existing items, readable placeholders). A refused save changes
 * nothing.
 */
public record DisplayTextSave(int containerId, String text) implements CustomPacketPayload {
    public static final Type<DisplayTextSave> TYPE = new Type<>(MaxtechStorage.id("display_text_save"));
    public static final StreamCodec<ByteBuf, DisplayTextSave> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DisplayTextSave::containerId, ByteBufCodecs.stringUtf8(RichText.MAX_LENGTH),
            DisplayTextSave::text, DisplayTextSave::new);

    /** What became of a save; tests read it. */
    public enum Result {
        SAVED, NO_MENU, NO_ACCESS, INVALID, TOO_SOON
    }

    /** The game tick of each player's last save. */
    private static final Object2LongOpenHashMap<UUID> LAST_SAVE = new Object2LongOpenHashMap<>();

    static {
        LAST_SAVE.defaultReturnValue(Long.MIN_VALUE);
    }

    @Override
    public Type<DisplayTextSave> type() {
        return TYPE;
    }

    /** Forgets the save ticks; when the server stops. */
    static void clear() {
        LAST_SAVE.clear();
    }

    static void handle(DisplayTextSave save, Player player) {
        apply(player, save.containerId, save.text);
    }

    /** Applies a save from {@code player}, as the payload handler does; server thread. */
    public static Result apply(Player player, int containerId, String text) {
        if (!(player.containerMenu instanceof DisplayMenu menu) || menu.containerId != containerId) {
            return Result.NO_MENU;
        }
        DisplayDevice device = menu.device();
        if (device == null) {
            return Result.NO_MENU;
        }
        if (player.isSpectator() || !menu.stillValid(player)) {
            return Result.NO_ACCESS;
        }
        long tick = player.level().getGameTime();
        if (LAST_SAVE.put(player.getUUID(), tick) == tick) {
            return Result.TOO_SOON;
        }
        if (DisplayText.problem(text).isPresent()) {
            return Result.INVALID;
        }
        device.applySettings(device.settings().withText(text));
        return Result.SAVED;
    }
}
