package dev.silencebound.maxtech.storage.crafting.stock;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's stock tab (M7 C9): the client asks for the stock keepers of the terminal's network and edits their
 * rules; the server answers with every keeper's rules after each request. Every payload names the terminal menu's
 * container id, and the server acts only for the sender's open, valid terminal menu with that id. Listing needs view
 * access to the network (an open terminal has it), editing needs full access. One request per player per tick.
 */
public final class StockPayloads {
    /** Keepers sent at most; more on one network are left out. */
    public static final int MAX_KEEPERS = 32;

    /** The last answer the client got, for the stock tab's screen; client side only, null before the first. */
    private static volatile @Nullable Keepers received;
    /** Game tick of each player's last request; only players who used the tab, cleared when the server stops. */
    private static final Object2LongOpenHashMap<UUID> LAST_REQUEST = new Object2LongOpenHashMap<>();

    static {
        LAST_REQUEST.defaultReturnValue(Long.MIN_VALUE);
    }

    private StockPayloads() {
    }

    static final StreamCodec<RegistryFriendlyByteBuf, StockRule> RULE_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(StackKey.STREAM_CODEC), StockRule::key, ByteBufCodecs.VAR_LONG, StockRule::keep,
            ByteBufCodecs.VAR_LONG, StockRule::restart, ByteBufCodecs.VAR_LONG, StockRule::batch, ByteBufCodecs.VAR_INT,
            StockRule::priority, ByteBufCodecs.VAR_INT, StockRule::cpuTier,
            ByteBufCodecs.VAR_INT.map(StockRule.Redstone::byId, StockRule.Redstone::id), StockRule::redstone,
            ByteBufCodecs.BOOL, StockRule::switchedOff, StockRule::new);

    /** Client to server: send the keepers of terminal menu {@code containerId}'s network. */
    public record Watch(int containerId) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = new Type<>(MaxtechStorage.id("stock_watch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Watch> CODEC = StreamCodec
                .composite(ByteBufCodecs.VAR_INT, Watch::containerId, Watch::new);

        @Override
        public Type<Watch> type() {
            return TYPE;
        }
    }

    /** Client to server: replace rule {@code index} of the keeper at node {@code keeper} with {@code rule}. */
    public record Edit(int containerId, long keeper, int index, StockRule rule) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(MaxtechStorage.id("stock_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Edit::containerId, ByteBufCodecs.VAR_LONG, Edit::keeper, ByteBufCodecs.VAR_INT,
                Edit::index, RULE_CODEC, Edit::rule, Edit::new);

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }

    /**
     * One keeper as the tab shows it.
     *
     * @param node
     *            the keeper's node position ({@code BlockPos.asLong}), which names it in an {@link Edit}
     * @param rules
     *            its first {@code crafting.stockKeeperRules} rules
     * @param filling
     *            bit {@code i} set while rule {@code i} is crafting
     */
    public record KeeperView(long node, List<StockRule> rules, int filling) {
        static final StreamCodec<RegistryFriendlyByteBuf, KeeperView> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, KeeperView::node,
                RULE_CODEC.apply(ByteBufCodecs.list(StockKeeperWorker.MAX_RULES)), KeeperView::rules,
                ByteBufCodecs.VAR_INT, KeeperView::filling, KeeperView::new);
    }

    /** Server to client: the network's keepers and whether the player may edit them. */
    public record Keepers(int containerId, boolean canEdit, List<KeeperView> keepers) implements CustomPacketPayload {
        public static final Type<Keepers> TYPE = new Type<>(MaxtechStorage.id("stock_keepers"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Keepers> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Keepers::containerId, ByteBufCodecs.BOOL, Keepers::canEdit,
                KeeperView.CODEC.apply(ByteBufCodecs.list(MAX_KEEPERS)), Keepers::keepers, Keepers::new);

        @Override
        public Type<Keepers> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; called once during mod construction. */
    static void init() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Watch.TYPE, Watch.CODEC, (payload, player) -> {
            CraftingSession session = RequestContent.session(player, payload.containerId());
            if (session != null && allowed(player)) {
                watch(session);
            }
        });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Edit.TYPE, Edit.CODEC, (payload, player) -> {
            CraftingSession session = RequestContent.session(player, payload.containerId());
            if (session != null && allowed(player)) {
                edit(session, payload);
            }
        });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Keepers.TYPE, Keepers.CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof TerminalMenu menu
                            && menu.containerId == payload.containerId()) {
                        received = payload;
                    }
                });
    }

    /** Forgets the request ticks when the server stops. */
    static void clear() {
        LAST_REQUEST.clear();
    }

    /** The last keeper list the client received for menu {@code containerId}; null when none came for it. */
    public static @Nullable Keepers received(int containerId) {
        Keepers last = received;
        return last != null && last.containerId() == containerId ? last : null;
    }

    /** One request per player per game tick; the tab sends one per click. */
    private static boolean allowed(Player player) {
        long tick = player.level().getGameTime();
        long last = LAST_REQUEST.put(player.getUUID(), tick);
        return last != tick;
    }

    /** Answers with the keepers of {@code session}'s network; false (nothing sent) without a network or access. */
    public static boolean watch(CraftingSession session) {
        NetworkState network = session.network();
        if (network == null) {
            return false;
        }
        AccessLevel access = network.accessLevel(session.player());
        if (!access.atLeast(AccessLevel.VIEW)) {
            return false;
        }
        session.send(keepers(session.containerId(), network, access == AccessLevel.FULL));
        return true;
    }

    /**
     * Replaces the rule {@code edit} names, then answers with the keepers as {@link #watch} does. False without a
     * network or full access, for a keeper not on the network or a rule past {@code crafting.stockKeeperRules}.
     */
    public static boolean edit(CraftingSession session, Edit edit) {
        NetworkState network = session.network();
        if (network == null || network.accessLevel(session.player()) != AccessLevel.FULL || edit.index() < 0
                || edit.index() >= StockKeeperWorker.ruleSlots()) {
            return false;
        }
        List<StockKeeperWorker> keepers = StockKeeperWorker.keepersOn(network);
        for (int i = 0; i < keepers.size(); i++) {
            StockKeeperWorker keeper = keepers.get(i);
            if (keeper.device().nodePos() == edit.keeper()) {
                keeper.setRule(edit.index(), edit.rule());
                session.send(keepers(session.containerId(), network, true));
                return true;
            }
        }
        return false;
    }

    private static Keepers keepers(int containerId, NetworkState network, boolean canEdit) {
        List<StockKeeperWorker> keepers = StockKeeperWorker.keepersOn(network);
        keepers.sort((a, b) -> Long.compare(a.device().nodePos(), b.device().nodePos()));
        int slots = StockKeeperWorker.ruleSlots();
        List<KeeperView> views = new ArrayList<>(Math.min(keepers.size(), MAX_KEEPERS));
        for (int i = 0; i < keepers.size() && i < MAX_KEEPERS; i++) {
            StockKeeperWorker keeper = keepers.get(i);
            int filling = 0;
            for (int r = 0; r < slots; r++) {
                if (keeper.filling(r)) {
                    filling |= 1 << r;
                }
            }
            views.add(
                    new KeeperView(keeper.device().nodePos(), List.copyOf(keeper.rules().subList(0, slots)), filling));
        }
        return new Keepers(containerId, canEdit, views);
    }

    /** A rule for the tab's step and cycle buttons, the same edits as the keeper's own screen. */
    public static StockRule stepped(StockRule rule, StockKeeperMenu.Field field, int step) {
        return switch (field) {
            case KEEP -> rule.withKeep(StockKeeperMenu.step(rule.keep(), step));
            case RESTART -> rule.withRestart(StockKeeperMenu.step(rule.restart(), step));
            case BATCH -> rule.withBatch(StockKeeperMenu.step(rule.batch(), step));
        };
    }

    /** The rule with {@code key}; a new key on a rule that keeps nothing starts at {@code defaultKeep}. */
    public static StockRule withKey(StockRule rule, Optional<StackKey> key, long defaultKeep) {
        StockRule changed = rule.withKey(key);
        return key.isPresent() && changed.keep() == 0 ? changed.withKeep(defaultKeep) : changed;
    }
}
