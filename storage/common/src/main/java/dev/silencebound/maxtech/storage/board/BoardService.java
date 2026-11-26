package dev.silencebound.maxtech.storage.board;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.mail.MailData;
import dev.silencebound.maxtech.storage.mail.MailService;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.UUID;
import net.minecraft.util.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * What a request board does (M6e area G). A board shows the requests of its owner's team (the owner's research team,
 * else the owner alone): players with view access post requests for the item in their hand or one picked on the board's
 * grid, and players with full access fulfil them from the board's network into the poster's mail inbox, as far as the
 * inbox has room. The poster and players with full access can take a request down. A delivery to a poster in another
 * dimension (as mail sees it, {@link MailService#crosses}) needs a dimension bridge on the board's network and pays its
 * surcharge. Server thread.
 */
public final class BoardService {
    public static final String POSTED_KEY = "message." + MaxtechStorage.MOD_ID + ".board.posted";
    public static final String NOTHING_HELD_KEY = "message." + MaxtechStorage.MOD_ID + ".board.nothing_held";
    public static final String FULL_KEY = "message." + MaxtechStorage.MOD_ID + ".board.full";
    public static final String FULFILLED_KEY = "message." + MaxtechStorage.MOD_ID + ".board.fulfilled";
    public static final String NONE_STORED_KEY = "message." + MaxtechStorage.MOD_ID + ".board.none_stored";
    public static final String INBOX_FULL_KEY = "message." + MaxtechStorage.MOD_ID + ".board.inbox_full";
    public static final String POST_DENIED_KEY = "message." + MaxtechStorage.MOD_ID + ".board.post_denied";
    public static final String DENIED_KEY = "message." + MaxtechStorage.MOD_ID + ".board.denied";
    public static final String NO_BRIDGE_KEY = "message." + MaxtechStorage.MOD_ID + ".board.no_bridge";

    private BoardService() {
    }

    /** The team whose requests {@code board} shows: its owner's research team, else the owner alone. */
    public static UUID team(MinecraftServer server, StorageDeviceBlockEntity board) {
        OwnerRecord owner = board.ownerRecord();
        if (!owner.isOwned()) {
            return Util.NIL_UUID;
        }
        ResearchTeam team = Research.data(server).team(server, owner);
        return team != null ? team.anchor() : owner.owner();
    }

    /** Posts a request for {@code wanted} of the item in {@code player}'s main hand; returns the message key. */
    public static String post(ServerPlayer player, StorageDeviceBlockEntity board, int wanted) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return NOTHING_HELD_KEY;
        }
        return post(player, board, held, wanted);
    }

    /**
     * Posts a request for {@code wanted} of {@code item} (picked on the board's grid or from a recipe viewer; only its
     * kind counts, nothing is taken from the player); returns the message key. View access is checked on every post, so
     * a player whose access was taken while the screen is open posts nothing.
     */
    public static String post(ServerPlayer player, StorageDeviceBlockEntity board, ItemStack item, int wanted) {
        if (!NetworkAccess.level(player, board).atLeast(AccessLevel.VIEW)) {
            return POST_DENIED_KEY;
        }
        MinecraftServer server = player.level().getServer();
        BoardData.Request request = BoardData.get(server).post(team(server, board), player.getUUID(),
                player.getGameProfile().name(), item, wanted, StorageServerConfig.BOARD_MAX_REQUESTS.getAsInt());
        return request == null ? FULL_KEY : POSTED_KEY;
    }

    /**
     * Fulfils request {@code id} from {@code board}'s network for {@code player} (full access needed): takes as many as
     * are still wanted, are stored and fit the poster's inbox, and mails them. Returns how many went; 0 with nothing to
     * do.
     */
    public static int fulfil(ServerPlayer player, StorageDeviceBlockEntity board, int id) {
        NetworkState network = board.network();
        if (network == null || network.status() != StorageNetwork.Status.ONLINE
                || network.accessLevel(player) != AccessLevel.FULL) {
            return 0;
        }
        MinecraftServer server = player.level().getServer();
        UUID team = team(server, board);
        BoardData data = BoardData.get(server);
        BoardData.Request request = data.request(team, id);
        if (request == null) {
            return 0;
        }
        MailData mail = MailData.get(server);
        // Gives a poster who never opened a mail port an inbox to deliver to.
        mail.remember(request.poster(), request.posterName());
        int capacity = StorageServerConfig.MAIL_INBOX_STACKS.getAsInt();
        int stackSize = Math.max(1, request.item().getMaxStackSize());
        long fits = (long) mail.room(request.poster(), capacity) * stackSize;
        long wanted = Math.min(request.remaining(), fits);
        boolean crossing = MailService.crosses(server, request.poster(), network.level().dimension());
        if (wanted <= 0 || (crossing && !DimensionBridge.bridged(network))) {
            return 0;
        }
        long taken;
        try (Transaction transaction = Transaction.openRoot()) {
            taken = network.extract(ItemKey.of(request.item()), wanted, transaction, player);
            if (taken > 0 && crossing && !DimensionBridge.pay(network, transaction)) {
                taken = 0;
            } else {
                transaction.commit();
            }
        }
        int left = (int) taken;
        while (left > 0) {
            int count = Math.min(left, stackSize);
            // Room was counted first, so every stack fits.
            mail.deliver(request.poster(), request.item().copyWithCount(count), capacity);
            left -= count;
        }
        data.delivered(team, id, (int) taken);
        return (int) taken;
    }

    /**
     * The message for a fulfil that moved nothing: the poster in another dimension without a bridge, the poster's inbox
     * full, or nothing stored (or the bridge's surcharge unpaid).
     */
    public static String nothingMoved(MinecraftServer server, StorageDeviceBlockEntity board, int id) {
        BoardData.Request request = BoardData.get(server).request(team(server, board), id);
        NetworkState network = board.network();
        if (request != null && network != null
                && MailService.crosses(server, request.poster(), network.level().dimension())
                && !DimensionBridge.bridged(network)) {
            return NO_BRIDGE_KEY;
        }
        if (request != null
                && MailData.get(server).room(request.poster(), StorageServerConfig.MAIL_INBOX_STACKS.getAsInt()) == 0) {
            return INBOX_FULL_KEY;
        }
        return NONE_STORED_KEY;
    }

    /** Takes request {@code id} down for its poster or a player with full access to the board; false otherwise. */
    public static boolean cancel(ServerPlayer player, StorageDeviceBlockEntity board, int id) {
        MinecraftServer server = player.level().getServer();
        UUID team = team(server, board);
        BoardData data = BoardData.get(server);
        BoardData.Request request = data.request(team, id);
        if (request == null) {
            return false;
        }
        if (!request.poster().equals(player.getUUID()) && !NetworkAccess.canConfigure(player, board)) {
            return false;
        }
        return data.cancel(team, id);
    }
}
