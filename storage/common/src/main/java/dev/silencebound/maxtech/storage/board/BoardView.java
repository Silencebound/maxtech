package dev.silencebound.maxtech.storage.board;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A request board's list (server to client), sent to a player whose board screen is open when it opens and whenever the
 * boards changed, at most once per tick.
 *
 * @param containerId
 *            the menu it is for; the client drops it for any other
 * @param entries
 *            the team's open requests, oldest first, at most {@link #MAX_ENTRIES}
 */
public record BoardView(int containerId, List<Entry> entries) implements CustomPacketPayload {
    /** Entries a view carries at most; the config's request cap is lower. */
    public static final int MAX_ENTRIES = 256;
    public static final Type<BoardView> TYPE = new Type<>(MaxtechStorage.id("board_view"));

    /**
     * One request as the screen shows it.
     *
     * @param mine
     *            whether the viewer may take it down (they posted it, or they have full access)
     */
    public record Entry(int id, String poster, ItemStack item, int wanted, int delivered, boolean mine) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Entry::id, ByteBufCodecs.stringUtf8(64), Entry::poster, ItemStack.STREAM_CODEC,
                Entry::item, ByteBufCodecs.VAR_INT, Entry::wanted, ByteBufCodecs.VAR_INT, Entry::delivered,
                ByteBufCodecs.BOOL, Entry::mine, Entry::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, BoardView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BoardView::containerId, Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)),
            BoardView::entries, BoardView::new);

    @Override
    public Type<BoardView> type() {
        return TYPE;
    }

    /** Client side: hands the list to the player's open board menu with that container id. */
    static void handle(BoardView view, Player player) {
        if (player.containerMenu instanceof BoardMenu menu && menu.containerId == view.containerId()) {
            menu.setEntries(view.entries());
        }
    }
}
