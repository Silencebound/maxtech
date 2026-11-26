package dev.silencebound.maxtech.storage.crafting.overview;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.crafting.pattern.NetworkPatterns;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderPart;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderMenu;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.terminal.RemoteCost;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The pattern overview (M7C items 1 and 2): a terminal tab listing every pattern in the pattern providers of the
 * terminal's network, with the provider holding it, its priority and a way to open that provider's screen from the
 * terminal. Listing needs view access to the network; changing a priority and opening a provider need full access to
 * the provider, as its own screen does, and pay the terminal's action cost ({@link RemoteCost}). A provider screen
 * opened from here stays open only while the terminal is valid for the player. Every payload names the terminal menu's
 * container id, and the server acts only for the sender's open, valid terminal menu with that id; one request per
 * player per tick. Linked subnets' patterns are not listed: their own terminals show them.
 */
public final class PatternOverview {
    /** Rows sent at most; more are counted in {@link Patterns#hidden()}. */
    public static final int MAX_ROWS = 1024;
    /**
     * Encoded bytes of rows sent at most, far below the 1 MiB a server payload may hold; rows past it are counted in
     * {@link Patterns#hidden()}.
     */
    public static final int MAX_BYTES = 256 * 1024;

    public static final String TITLE_KEY = gui("title");
    public static final String SEARCH_KEY = gui("search");
    public static final String EMPTY_KEY = gui("empty");
    public static final String PRIORITY_KEY = gui("priority");
    public static final String PRIORITY_HINT_KEY = gui("priority_hint");
    public static final String CONFLICT_KEY = gui("conflict");
    public static final String CONFLICT_HINT_KEY = gui("conflict_hint");
    public static final String PROVIDER_KEY = gui("provider");
    public static final String PROVIDER_LABEL_KEY = gui("provider_label");
    public static final String SLOT_KEY = gui("slot");
    public static final String OPEN_KEY = gui("open");
    public static final String HIDDEN_KEY = gui("hidden");
    public static final String READ_ONLY_KEY = gui("read_only");
    public static final String COUNT_KEY = gui("count");

    /** Game tick of each player's last request; only players who used the tab, cleared when the server stops. */
    private static final Object2LongOpenHashMap<UUID> LAST_REQUEST = new Object2LongOpenHashMap<>();
    /** The last list the client got, for the screen; client side only, null before the first. */
    private static volatile @Nullable Patterns received;

    static {
        LAST_REQUEST.defaultReturnValue(Long.MIN_VALUE);
        ModContent.CONTENT.translation(TITLE_KEY, "Patterns");
        ModContent.CONTENT.translation(SEARCH_KEY, "Search patterns");
        ModContent.CONTENT.translation(EMPTY_KEY, "No patterns on this network");
        ModContent.CONTENT.translation(PRIORITY_KEY, "Priority: %s");
        ModContent.CONTENT.translation(PRIORITY_HINT_KEY,
                "Among patterns making the same item, the highest priority with all its materials is used");
        ModContent.CONTENT.translation(CONFLICT_KEY, "%s patterns make this item");
        ModContent.CONTENT.translation(CONFLICT_HINT_KEY,
                "The planner tries the highest priority first and falls back to the next when materials are missing");
        ModContent.CONTENT.translation(PROVIDER_KEY, "In %s at %s %s %s");
        ModContent.CONTENT.translation(PROVIDER_LABEL_KEY, "In %s (%s) at %s %s %s");
        ModContent.CONTENT.translation(SLOT_KEY, "Slot %s");
        ModContent.CONTENT.translation(OPEN_KEY, "Open provider");
        ModContent.CONTENT.translation(HIDDEN_KEY, "%s more patterns not shown");
        ModContent.CONTENT.translation(READ_ONLY_KEY, "View only: changing needs full access");
        ModContent.CONTENT.translation(COUNT_KEY, "%s patterns");
    }

    private PatternOverview() {
    }

    /** Client to server: send the patterns of terminal menu {@code containerId}'s network. */
    public record Watch(int containerId) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = new Type<>(MaxtechStorage.id("pattern_overview_watch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Watch> CODEC = StreamCodec
                .composite(ByteBufCodecs.VAR_INT, Watch::containerId, Watch::new);

        @Override
        public Type<Watch> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: set the priority of the pattern in {@code slot} of the provider at {@code pos} facing
     * {@code facing}.
     */
    public record SetPriority(int containerId, BlockPos pos, Direction facing, int slot,
            int priority) implements CustomPacketPayload {
        public static final Type<SetPriority> TYPE = new Type<>(MaxtechStorage.id("pattern_overview_priority"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetPriority> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetPriority::containerId, BlockPos.STREAM_CODEC, SetPriority::pos,
                Direction.STREAM_CODEC, SetPriority::facing, ByteBufCodecs.VAR_INT, SetPriority::slot,
                ByteBufCodecs.VAR_INT, SetPriority::priority, SetPriority::new);

        @Override
        public Type<SetPriority> type() {
            return TYPE;
        }
    }

    /** Client to server: open the screen of the provider at {@code pos} facing {@code facing}. */
    public record Open(int containerId, BlockPos pos, Direction facing) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(MaxtechStorage.id("pattern_overview_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Open::containerId, BlockPos.STREAM_CODEC, Open::pos, Direction.STREAM_CODEC,
                Open::facing, Open::new);

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    /**
     * One pattern as the overview shows it.
     *
     * @param pos
     *            the provider's block (a part's cable)
     * @param facing
     *            the side its machine is on; with {@code pos} it names the provider
     * @param part
     *            whether the provider is a cable part
     * @param label
     *            the provider's label, {@link DeviceLabel#NONE} without one
     * @param slot
     *            the provider slot holding the pattern
     */
    public record Row(BlockPos pos, Direction facing, boolean part, DeviceLabel label, int slot,
            CraftingPattern pattern, int priority) {
        static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
                Row::pos, Direction.STREAM_CODEC, Row::facing, ByteBufCodecs.BOOL, Row::part, DeviceLabel.STREAM_CODEC,
                Row::label, ByteBufCodecs.VAR_INT, Row::slot, PatternCodecs.STREAM_CODEC, Row::pattern,
                ByteBufCodecs.VAR_INT, Row::priority, Row::new);
    }

    /** Server to client: the network's patterns, whether the player may change them, and how many were left out. */
    public record Patterns(int containerId, boolean canEdit, List<Row> rows,
            int hidden) implements CustomPacketPayload {
        public static final Type<Patterns> TYPE = new Type<>(MaxtechStorage.id("pattern_overview_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Patterns> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Patterns::containerId, ByteBufCodecs.BOOL, Patterns::canEdit,
                Row.CODEC.apply(ByteBufCodecs.list(MAX_ROWS)), Patterns::rows, ByteBufCodecs.VAR_INT, Patterns::hidden,
                Patterns::new);

        @Override
        public Type<Patterns> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; called once during mod construction. */
    public static void init() {
        Platform.get().lifecycle().onServerStopped(server -> clear());
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Watch.TYPE, Watch.CODEC, (payload, player) -> {
            CraftingSession session = RequestContent.session(player, payload.containerId());
            if (session != null && allowed(player)) {
                watch(session);
            }
        });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, SetPriority.TYPE, SetPriority.CODEC,
                (payload, player) -> {
                    CraftingSession session = RequestContent.session(player, payload.containerId());
                    if (session != null && allowed(player)) {
                        setPriority(session, payload);
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Open.TYPE, Open.CODEC, (payload, player) -> {
            CraftingSession session = RequestContent.session(player, payload.containerId());
            if (session != null && allowed(player)) {
                open(session, payload);
            }
        });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Patterns.TYPE, Patterns.CODEC,
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

    /** The last list the client received for menu {@code containerId}; null when none came for it. */
    public static @Nullable Patterns received(int containerId) {
        Patterns last = received;
        return last != null && last.containerId() == containerId ? last : null;
    }

    /** One request per player per game tick; the tab sends one per click. */
    private static boolean allowed(Player player) {
        long tick = player.level().getGameTime();
        return LAST_REQUEST.put(player.getUUID(), tick) != tick;
    }

    /** Answers with the patterns of {@code session}'s network; false (nothing sent) without a network or access. */
    public static boolean watch(CraftingSession session) {
        NetworkState network = session.network();
        if (network == null) {
            return false;
        }
        AccessLevel access = network.accessLevel(session.player());
        if (!access.atLeast(AccessLevel.VIEW)) {
            return false;
        }
        session.send(patterns(session.containerId(), network, access == AccessLevel.FULL));
        return true;
    }

    /**
     * Sets the priority the payload names, then answers with the list as {@link #watch} does. False without a network,
     * for a provider not on it or without full access to it, and for an empty slot.
     */
    public static boolean setPriority(CraftingSession session, SetPriority edit) {
        NetworkState network = session.network();
        ProviderLogic provider = network == null ? null : find(network, edit.pos(), edit.facing());
        if (network == null || provider == null || !NetworkAccess.canConfigure(session.player(), provider.device())) {
            return false;
        }
        if (!RemoteCost.run(source(session), () -> provider.setPriority(edit.slot(), edit.priority()))) {
            return false;
        }
        watch(session);
        return true;
    }

    /**
     * Opens the screen of the provider the payload names for the session's player. The provider's menu then stays open
     * while the provider is on the same network and the player keeps full access to it, wherever the player is. False
     * without a network, for a provider not on it or without full access to it.
     */
    public static boolean open(CraftingSession session, Open request) {
        NetworkState network = session.network();
        ProviderLogic provider = network == null ? null : find(network, request.pos(), request.facing());
        TerminalSource source = source(session);
        if (network == null || provider == null || source == null || !(session.player() instanceof ServerPlayer player)
                || !NetworkAccess.canConfigure(player, provider.device()) || !RemoteCost.pay(source)) {
            return false;
        }
        ProviderMenu.openRemote(player, provider.device(), provider.pos(), ProviderContent.PROVIDER.get().getName(),
                remoteValid(provider, network, source));
        return true;
    }

    /**
     * How long a provider screen opened from the overview stays open: while the terminal that opened it
     * ({@code source}) stays valid for the viewer (in reach, in wireless range, still held), the provider is still on
     * {@code network} and the viewer keeps full access to it.
     */
    public static Predicate<Player> remoteValid(ProviderLogic provider, NetworkState network, TerminalSource source) {
        return viewer -> source.stillValid(viewer) && source.canAccess(viewer) && !provider.device().isRemoved()
                && provider.device().deviceLink().network() == network
                && NetworkAccess.canConfigure(viewer, provider.device());
    }

    /** The source of the terminal menu {@code session} belongs to, while it is the player's open menu; else null. */
    @SuppressWarnings("ReferenceEquality") // a session is one object per menu
    public static @Nullable TerminalSource source(CraftingSession session) {
        return session.player().containerMenu instanceof TerminalMenu menu && menu.crafting() == session
                ? menu.source()
                : null;
    }

    /** The provider at {@code pos} facing {@code facing} on {@code network}, or null. */
    public static @Nullable ProviderLogic find(NetworkState network, BlockPos pos, Direction facing) {
        List<NetworkPatterns.Entry> entries = network.patterns().entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).provider() instanceof ProviderLogic logic && logic.pos().equals(pos)
                    && logic.facing() == facing && !logic.device().isRemoved()) {
                return logic;
            }
        }
        return null;
    }

    /** The overview of {@code network}: its providers' patterns by provider position, then slot. */
    public static Patterns patterns(int containerId, NetworkState network, boolean canEdit) {
        return patterns(containerId, network, canEdit, MAX_BYTES);
    }

    /** {@link #patterns(int, NetworkState, boolean)} with rows bounded at {@code maxBytes} encoded; for tests. */
    public static Patterns patterns(int containerId, NetworkState network, boolean canEdit, int maxBytes) {
        List<Row> rows = new ArrayList<>();
        List<NetworkPatterns.Entry> entries = network.patterns().entries();
        int hidden = 0;
        ReferenceOpenHashSet<ProviderLogic> seen = new ReferenceOpenHashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i).provider() instanceof ProviderLogic logic) || !seen.add(logic)) {
                continue;
            }
            for (int p = 0; p < logic.patterns().size(); p++) {
                rows.add(new Row(logic.pos(), logic.facing(), logic.device() instanceof PatternProviderPart,
                        logic.device().label(), logic.slotOf(p), logic.patterns().get(p), logic.priority(p)));
            }
        }
        rows.sort((a, b) -> {
            int byPos = a.pos().compareTo(b.pos());
            if (byPos != 0) {
                return byPos;
            }
            int byFacing = Integer.compare(a.facing().get3DDataValue(), b.facing().get3DDataValue());
            return byFacing != 0 ? byFacing : Integer.compare(a.slot(), b.slot());
        });
        // Bounded by count and by encoded size, well below the payload limit; the rest is counted as hidden.
        RegistryFriendlyByteBuf scratch = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                network.level().registryAccess());
        int kept = 0;
        try {
            while (kept < rows.size() && kept < MAX_ROWS) {
                Row.CODEC.encode(scratch, rows.get(kept));
                if (scratch.writerIndex() > maxBytes) {
                    break;
                }
                kept++;
            }
        } finally {
            scratch.release();
        }
        if (kept < rows.size()) {
            hidden = rows.size() - kept;
            rows = new ArrayList<>(rows.subList(0, kept));
        }
        return new Patterns(containerId, canEdit, rows, hidden);
    }

    /** Clamps a priority to the pattern item's range. */
    public static int clampPriority(int priority) {
        return Math.clamp(priority, PatternItem.MIN_PRIORITY, PatternItem.MAX_PRIORITY);
    }

    private static String gui(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".pattern_overview." + name;
    }
}
