package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.order.ListCode;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * Client to server: the list a player typed or pasted for a build material list screen ({@link ListCode}: a
 * {@code mtlist1:} code or a typed list), for the screen behind the open display menu {@code containerId}. Checked like
 * {@link DisplayTextSave}: the player's open, valid display menu, the menu's access check, one save per player per
 * tick. The server stores the list in its canonical code form (every id once, broken parts dropped); a non-blank text
 * with no readable entry is refused, a blank one clears the list.
 */
public record DisplayListSave(int containerId, String list) implements CustomPacketPayload {
    public static final Type<DisplayListSave> TYPE = new Type<>(MaxtechStorage.id("display_list_save"));
    public static final StreamCodec<ByteBuf, DisplayListSave> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DisplayListSave::containerId, ByteBufCodecs.stringUtf8(ListCode.MAX_LENGTH),
            DisplayListSave::list, DisplayListSave::new);

    /** The game tick of each player's last save. */
    private static final Object2LongOpenHashMap<UUID> LAST_SAVE = new Object2LongOpenHashMap<>();

    static {
        LAST_SAVE.defaultReturnValue(Long.MIN_VALUE);
    }

    @Override
    public Type<DisplayListSave> type() {
        return TYPE;
    }

    /** Forgets the save ticks; when the server stops. */
    static void clear() {
        LAST_SAVE.clear();
    }

    static void handle(DisplayListSave save, Player player) {
        apply(player, save.containerId, save.list);
    }

    /** The canonical code of {@code text}, cut to what a screen keeps; empty when nothing in it is readable. */
    public static String canonical(String text) {
        List<ListCode.Entry> entries = ListCode.decode(text).entries();
        while (!entries.isEmpty()) {
            String code = ListCode.encode(entries);
            if (code.length() <= DisplaySettings.LIST_MAX) {
                return code;
            }
            entries = entries.subList(0, entries.size() - 1);
        }
        return "";
    }

    /** Applies a save from {@code player}, as the payload handler does; server thread. */
    public static DisplayTextSave.Result apply(Player player, int containerId, String text) {
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
        String code = text.isBlank() ? "" : canonical(text);
        if (code.isEmpty() && !text.isBlank()) {
            return DisplayTextSave.Result.INVALID;
        }
        device.applySettings(device.settings().withList(code));
        return DisplayTextSave.Result.SAVED;
    }
}
