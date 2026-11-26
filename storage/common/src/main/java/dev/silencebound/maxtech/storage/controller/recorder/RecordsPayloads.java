package dev.silencebound.maxtech.storage.controller.recorder;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

/**
 * The payloads of the controller's recorder views (M7R item 3), shared by the controller's History tab and the
 * terminal's history tab: the client asks for a page every second while it shows, the server answers with a
 * {@link RecordsView}, and an edit changes an alarm rule and answers with the page again. Every payload names the
 * container id of the sender's open menu: a controller menu (its primary's recorders) or a terminal (its network's
 * controller in charge). The server answers only for that open, valid menu, for players with view access to the
 * controller; edits need configure access, the alarm research for the owner's team and the alarms switched on. One
 * request per player per tick.
 */
public final class RecordsPayloads {
    /** The last view the client got; client side only, null before the first. */
    private static volatile @Nullable View received;
    /** Game tick of each player's last request; only players who used a view, cleared when the server stops. */
    private static final Object2LongOpenHashMap<UUID> LAST_REQUEST = new Object2LongOpenHashMap<>();

    static {
        LAST_REQUEST.defaultReturnValue(Long.MIN_VALUE);
    }

    private RecordsPayloads() {
    }

    /** Client to server: send page {@code page} of menu {@code containerId}'s controller. */
    public record Ask(int containerId, int page) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(MaxtechStorage.id("records_ask"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ask> CODEC = StreamCodec
                .composite(ByteBufCodecs.VAR_INT, Ask::containerId, ByteBufCodecs.VAR_INT, Ask::page, Ask::new);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    /** Client to server: apply alarm rule edit {@code op} to rule {@code rule}, then send the alarm page. */
    public record Edit(int containerId, int rule, int op) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(MaxtechStorage.id("records_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Edit::containerId, ByteBufCodecs.VAR_INT, Edit::rule, ByteBufCodecs.VAR_INT,
                Edit::op, Edit::new);

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }

    /** Server to client: one page of the views. */
    public record View(int containerId, RecordsView view) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(MaxtechStorage.id("records_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of((buffer, payload) -> {
            buffer.writeVarInt(payload.containerId());
            payload.view().write(buffer);
        }, buffer -> new View(buffer.readVarInt(), RecordsView.read(buffer)));

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; called once during mod construction. */
    public static void init() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Ask.TYPE, Ask.CODEC, (payload, player) -> {
            if (player instanceof ServerPlayer server && allowed(server)) {
                answer(server, payload.containerId(), payload.page());
            }
        });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Edit.TYPE, Edit.CODEC, (payload, player) -> {
            if (player instanceof ServerPlayer server && allowed(server)) {
                edit(server, payload.containerId(), payload.rule(), payload.op());
                answer(server, payload.containerId(), RecordsView.ALARMS);
            }
        });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, View.TYPE, View.CODEC, (payload, player) -> {
            if (player.containerMenu.containerId == payload.containerId()) {
                received = payload;
            }
        });
        Platform.get().lifecycle().onServerStopped(server -> LAST_REQUEST.clear());
    }

    /** The last view the client received for menu {@code containerId}; null when none came for it. Client side. */
    public static @Nullable RecordsView received(int containerId) {
        View last = received;
        return last != null && last.containerId() == containerId ? last.view() : null;
    }

    private static boolean allowed(ServerPlayer player) {
        long tick = player.level().getGameTime();
        long last = LAST_REQUEST.put(player.getUUID(), tick);
        return last != tick;
    }

    /**
     * The controller whose views {@code player}'s open menu {@code containerId} shows, or null: a controller menu's
     * primary, or a terminal's network's controller in charge; only for an open, valid menu and view access.
     */
    public static @Nullable ControllerBlockEntity controller(ServerPlayer player, int containerId) {
        AbstractContainerMenu menu = player.containerMenu;
        ControllerBlockEntity found = null;
        if (menu instanceof ControllerMenu controllerMenu && menu.containerId == containerId
                && menu.stillValid(player)) {
            ControllerBlockEntity core = controllerMenu.core();
            found = core == null ? null : core.primary();
        } else {
            CraftingSession session = RequestContent.session(player, containerId);
            NetworkState network = session == null ? null : session.network();
            if (network != null && network.controllerDevice() instanceof ControllerBlockEntity core) {
                found = core.primary();
            }
        }
        return found != null && !found.isRemoved() && RecordsView.mayView(player, found) ? found : null;
    }

    /** Sends page {@code page} of the views of menu {@code containerId} to {@code player}. Server thread. */
    public static void answer(ServerPlayer player, int containerId, int page) {
        ControllerBlockEntity controller = controller(player, containerId);
        RecordsView view = controller == null ? RecordsView.EMPTY : RecordsView.build(controller, player, page);
        Platform.get().network().sendToPlayer(player, new View(containerId, view));
    }

    /**
     * Applies an alarm rule edit for {@code player} through menu {@code containerId}: needs configure access, the alarm
     * research for the controller's owner's team and the alarms switched on. Returns whether a rule changed.
     */
    public static boolean edit(ServerPlayer player, int containerId, int rule, int op) {
        ControllerBlockEntity controller = controller(player, containerId);
        if (controller == null || !NetworkAccess.canConfigure(player, controller)
                || !RecordsView.unlocked(controller, RecordsView.ALARMS) || !RecordsView.enabled(RecordsView.ALARMS)) {
            return false;
        }
        return controller.recorders().edit(rule, op);
    }
}
