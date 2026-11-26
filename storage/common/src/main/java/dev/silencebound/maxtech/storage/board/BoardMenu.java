package dev.silencebound.maxtech.storage.board;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.lib.sync.ServerSyncedList;
import dev.silencebound.maxtech.lib.sync.SyncedListHolder;
import dev.silencebound.maxtech.lib.sync.SyncedListType;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.mail.ServiceBlockEntity;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A request board's screen: the team's open requests with Fulfil and Take down per row, Post buttons that ask for the
 * held item or the one picked on the grid in a few fixed amounts, and a read-only grid of what the board's network
 * holds (B09 item 30). Every action but posting a picked item is a menu button (the request id travels in the button
 * id); a picked item travels in a {@link Post}, which names a kind of item and takes nothing from the player. The list
 * comes in {@link BoardView} payloads, sent when the screen opens and after any change of the boards. The grid is the
 * terminal's synced list, only for a player with view access to the board, watching the board's network while the menu
 * is open; it never takes or stores anything.
 */
public final class BoardMenu extends AbstractContainerMenu implements LabelMenu, SyncedListHolder, ContentsWatcher {
    public static final int WIDTH = 280;
    public static final int HEIGHT = 196;
    /** Amounts the Post buttons ask for; button {@code POST + i} posts {@code AMOUNTS[i]}. */
    private static final int[] AMOUNTS = {1, 16, 64, 256};
    public static final int POST = 0;
    public static final int POST_COUNT = AMOUNTS.length;
    /** Button {@code FULFIL + id} fulfils request {@code id}. */
    public static final int FULFIL = BoardData.MAX_ID;
    /** Button {@code CANCEL + id} takes request {@code id} down. */
    public static final int CANCEL = 2 * BoardData.MAX_ID;

    private final @Nullable ServiceBlockEntity board;
    private final Player viewer;
    private long sentVersion = -1;
    private long lastAction = Long.MIN_VALUE;
    private List<BoardView.Entry> entries = List.of();
    private final @Nullable ServerSyncedList<StackKey> list;
    private final ClientSyncedList<StackKey> clientList = new ClientSyncedList<>();
    /** The network whose contents the grid shows now; null for none (no network, or no view access). */
    private @Nullable NetworkState watched;
    /** The board's network when the grid was last dumped, whether or not the viewer may see it. */
    private @Nullable NetworkState seen;
    /** Whether the grid's first dump went out, so changes may follow. */
    private boolean listed;

    private BoardMenu(int containerId, Inventory inventory, @Nullable ServiceBlockEntity board,
            ServerSyncedList.@Nullable Sink<StackKey> sink) {
        super(ModMenus.REQUEST_BOARD.get(), containerId);
        this.board = board;
        this.viewer = inventory.player;
        this.list = board == null || sink == null
                ? null
                : new ServerSyncedList<>(TerminalContent.LIST, containerId, this::key, sink,
                        TerminalContent.LIST.sizer(inventory.player.registryAccess()));
    }

    /** The client's menu; the list arrives in a {@link BoardView}, the grid in the terminal's synced list. */
    public static BoardMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        return new BoardMenu(containerId, inventory, null, null);
    }

    /** A server menu over {@code board} for {@code player} whose grid goes to {@code sink}; for tests. */
    public static BoardMenu server(int containerId, ServerPlayer player, ServiceBlockEntity board,
            ServerSyncedList.Sink<StackKey> sink) {
        return new BoardMenu(containerId, player.getInventory(), board, sink);
    }

    /** A server menu over {@code board} for {@code player}, its grid sent nowhere; for tests. */
    public static BoardMenu server(int containerId, ServerPlayer player, ServiceBlockEntity board) {
        return server(containerId, player, board, update -> {
        });
    }

    /** Opens the board's screen for {@code player}. */
    public static void open(ServerPlayer player, ServiceBlockEntity board, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> new BoardMenu(id, inventory, board,
                        update -> Platform.get().network().sendToPlayer(player, update)), title),
                buffer -> buffer.writeBlockPos(board.getBlockPos()));
    }

    /** The amount Post button {@code i} asks for. */
    public static int amount(int i) {
        return AMOUNTS[i];
    }

    /** The requests the screen shows. */
    public List<BoardView.Entry> entries() {
        return entries;
    }

    void setEntries(List<BoardView.Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** The client's copy of the board network's contents; empty without a network or view access. */
    public ClientSyncedList<StackKey> clientList() {
        return clientList;
    }

    /** The network the grid shows; null on the client, without a network or without view access. */
    public @Nullable NetworkState watched() {
        return watched;
    }

    private @Nullable StackKey key(int id) {
        NetworkState network = watched;
        return network == null ? null : network.contents().key(id);
    }

    @Override
    @SuppressWarnings("unchecked") // the menu holds exactly one list, of this type
    public <E> @Nullable ClientSyncedList<E> syncedList(SyncedListType<E> type) {
        return type.payloadType().equals(TerminalContent.LIST.payloadType()) ? (ClientSyncedList<E>) clientList : null;
    }

    @Override
    public void changed(int id, long total) {
        ServerSyncedList<StackKey> current = list;
        if (listed && current != null) {
            current.changed(id, total);
        }
    }

    @Override
    public void released(int id) {
        ServerSyncedList<StackKey> current = list;
        if (listed && current != null) {
            // The id may name another key soon; whatever the client still has to learn about it goes now.
            current.tick(viewer.level().getGameTime(), 0);
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        ServiceBlockEntity current = board;
        if (current == null || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (!nextAction(serverPlayer)) {
            return false;
        }
        @Nullable
        String message;
        if (id >= POST && id < POST + POST_COUNT) {
            message = BoardService.post(serverPlayer, current, AMOUNTS[id - POST]);
        } else if (id >= FULFIL && id < FULFIL + BoardData.MAX_ID) {
            int moved = BoardService.fulfil(serverPlayer, current, id - FULFIL);
            message = moved > 0
                    ? BoardService.FULFILLED_KEY
                    : NetworkAccess.canConfigure(serverPlayer, current)
                            ? BoardService.nothingMoved(serverPlayer.level().getServer(), current, id - FULFIL)
                            : BoardService.DENIED_KEY;
        } else if (id >= CANCEL && id < CANCEL + BoardData.MAX_ID) {
            if (!BoardService.cancel(serverPlayer, current, id - CANCEL)) {
                return false;
            }
            message = null;
        } else {
            return false;
        }
        tell(serverPlayer, message);
        return true;
    }

    /** One action per tick; a client spamming buttons gets nothing more. */
    private boolean nextAction(ServerPlayer player) {
        long now = player.level().getGameTime();
        if (now == lastAction) {
            return false;
        }
        lastAction = now;
        return true;
    }

    private static void tell(ServerPlayer player, @Nullable String message) {
        if (message != null && player.connection != null) {
            player.sendOverlayMessage(Component.translatable(message));
        }
    }

    /**
     * Posts a request for {@code AMOUNTS[amount]} of {@code item}, picked on the grid or from a recipe viewer; nothing
     * is taken from the player. False for an amount out of range or a second action in the same tick.
     */
    public boolean post(ServerPlayer player, ItemKey item, int amount) {
        ServiceBlockEntity current = board;
        if (current == null || amount < 0 || amount >= POST_COUNT || !nextAction(player)) {
            return false;
        }
        tell(player, BoardService.post(player, current, item.toStack(1), AMOUNTS[amount]));
        return true;
    }

    /**
     * Sends the list when the boards changed since the last one sent (and once when the screen opens), and the grid: a
     * full dump when the board's network changed, then its changes.
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        ServiceBlockEntity current = board;
        if (current == null || !(viewer instanceof ServerPlayer player)) {
            return;
        }
        syncGrid(current, player);
        MinecraftServer server = player.level().getServer();
        BoardData data = BoardData.get(server);
        if (data.version() == sentVersion) {
            return;
        }
        sentVersion = data.version();
        boolean full = NetworkAccess.canConfigure(player, current);
        UUID team = BoardService.team(server, current);
        List<BoardData.Request> requests = data.requests(team);
        List<BoardView.Entry> view = new ArrayList<>(Math.min(requests.size(), BoardView.MAX_ENTRIES));
        for (int i = 0; i < requests.size() && i < BoardView.MAX_ENTRIES; i++) {
            BoardData.Request request = requests.get(i);
            view.add(new BoardView.Entry(request.id(), request.posterName(), request.item(), request.wanted(),
                    request.delivered(), full || request.poster().equals(player.getUUID())));
        }
        entries = view;
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, new BoardView(containerId, view));
        }
    }

    /**
     * Watches the board's network, moving to another when the board joined one, and sends its contents; nothing without
     * view access. A reference check per tick while the menu is open, no lookup.
     */
    private void syncGrid(ServiceBlockEntity current, ServerPlayer player) {
        ServerSyncedList<StackKey> synced = list;
        if (synced == null) {
            return;
        }
        NetworkState network = current.network();
        long now = player.level().getGameTime();
        if (!listed || network != seen) {
            seen = network;
            listed = true;
            NetworkState previous = watched;
            if (previous != null) {
                previous.unwatch(this);
            }
            watched = null;
            if (network != null && NetworkAccess.level(player, current).atLeast(AccessLevel.VIEW)) {
                watched = network;
                network.watch(this);
                synced.open(network.contents().snapshot().totals(), now);
            } else {
                synced.open(Int2LongMaps.EMPTY_MAP, now);
            }
        } else if (watched != null) {
            synced.tick(now);
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        NetworkState network = watched;
        if (network != null) {
            network.unwatch(this);
            watched = null;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        ServiceBlockEntity current = board;
        return current == null || current.stillValid(player);
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return board;
    }

    /**
     * Client to server: post a request on open board {@code containerId} for Post button {@code amount}'s amount of
     * {@code item}, picked on the grid or dragged from a recipe viewer. Only the kind of item travels.
     */
    public record Post(int containerId, StackKey item, int amount) implements CustomPacketPayload {
        public static final Type<Post> TYPE = new Type<>(MaxtechStorage.id("board_post"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Post> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Post::containerId, StackKey.STREAM_CODEC, Post::item, ByteBufCodecs.VAR_INT,
                Post::amount, Post::new);

        @Override
        public Type<Post> type() {
            return TYPE;
        }

        /** Server side: posts for the player's open board with that container id, if it is still in reach. */
        static void handle(Post post, Player player) {
            if (player instanceof ServerPlayer serverPlayer && player.containerMenu instanceof BoardMenu menu
                    && menu.containerId == post.containerId() && menu.stillValid(player)
                    && post.item() instanceof ItemKey item) {
                menu.post(serverPlayer, item, post.amount());
            }
        }
    }
}
