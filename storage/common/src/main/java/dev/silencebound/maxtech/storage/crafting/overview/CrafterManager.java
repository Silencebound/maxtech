package dev.silencebound.maxtech.storage.crafting.overview;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderPart;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.terminal.RemoteCost;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The crafter manager (B09 item 23, like Refined Storage's): opened from the pattern overview, it lists every pattern
 * provider of the terminal's network and shows the pattern slots of one picked provider as real menu slots
 * ({@link CrafterManagerMenu}), so patterns go in and out, are reordered and carried to another provider on the cursor
 * the vanilla way. Opening needs view access to the network; picking a provider needs full access to it, as opening its
 * own screen does. Only providers on the network are listed: a provider in an unloaded chunk leaves its network, so it
 * is neither listed nor editable until its chunk loads again. Payloads name the open menu's container id and are acted
 * on only for the sender's open, valid menu with that id; one request per player per tick.
 */
public final class CrafterManager {
    /** Providers sent at most; more are counted in {@link Providers#hidden()}. */
    public static final int MAX_PROVIDERS = 512;
    /** Pattern outputs sent per provider at most, for the list's icon and the search. */
    public static final int MAX_OUTPUTS = 36;
    /** Encoded bytes of the list sent at most, far below the payload limit; providers past it are counted as hidden. */
    public static final int MAX_BYTES = 256 * 1024;

    public static final String TITLE_KEY = gui("title");
    public static final String OPEN_KEY = gui("open");
    public static final String OPEN_HINT_KEY = gui("open_hint");
    public static final String SEARCH_KEY = gui("search");
    public static final String EMPTY_KEY = gui("empty");
    public static final String PICK_KEY = gui("pick");
    public static final String LOCKED_KEY = gui("locked");
    public static final String COUNT_KEY = gui("count");
    public static final String HIDDEN_KEY = gui("hidden");
    public static final String PATTERNS_KEY = gui("patterns");

    private static final Object2LongOpenHashMap<UUID> LAST_REQUEST = new Object2LongOpenHashMap<>();
    /** The last list the client got; client side only, null before the first. */
    private static volatile @Nullable Providers received;

    static {
        LAST_REQUEST.defaultReturnValue(Long.MIN_VALUE);
        ModContent.CONTENT.translation(TITLE_KEY, "Crafter Manager");
        ModContent.CONTENT.translation(OPEN_KEY, "Manage providers");
        ModContent.CONTENT.translation(OPEN_HINT_KEY,
                "Lists every pattern provider on this network with its pattern slots");
        ModContent.CONTENT.translation(SEARCH_KEY, "Search providers");
        ModContent.CONTENT.translation(EMPTY_KEY, "No pattern providers on this network");
        ModContent.CONTENT.translation(PICK_KEY, "Pick a provider to edit its patterns");
        ModContent.CONTENT.translation(LOCKED_KEY, "Changing this provider needs full access");
        ModContent.CONTENT.translation(COUNT_KEY, "%s providers");
        ModContent.CONTENT.translation(HIDDEN_KEY, "%s more providers not shown");
        ModContent.CONTENT.translation(PATTERNS_KEY, "%s / %s patterns");
    }

    private CrafterManager() {
    }

    /** Client to server: open the manager over terminal menu {@code containerId}'s network. */
    public record Open(int containerId) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(MaxtechStorage.id("crafter_manager_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec
                .composite(ByteBufCodecs.VAR_INT, Open::containerId, Open::new);

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    /** Client to server: show the slots of the provider at {@code pos} facing {@code facing} in manager menu. */
    public record Select(int containerId, BlockPos pos, Direction facing) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(MaxtechStorage.id("crafter_manager_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Select::containerId, BlockPos.STREAM_CODEC, Select::pos, Direction.STREAM_CODEC,
                Select::facing, Select::new);

        @Override
        public Type<Select> type() {
            return TYPE;
        }
    }

    /**
     * One provider as the manager lists it.
     *
     * @param pos
     *            the provider's block (a part's cable)
     * @param facing
     *            the side its machine is on; with {@code pos} it names the provider
     * @param part
     *            whether the provider is a cable part
     * @param usable
     *            its working pattern slots
     * @param editable
     *            whether the player may change it (full access)
     * @param outputs
     *            the main output of each of its patterns, in slot order, at most {@link #MAX_OUTPUTS}
     */
    public record Provider(BlockPos pos, Direction facing, boolean part, DeviceLabel label, int usable,
            boolean editable, List<StackKey> outputs) {
        static final StreamCodec<RegistryFriendlyByteBuf, Provider> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
                Provider::pos, Direction.STREAM_CODEC, Provider::facing, ByteBufCodecs.BOOL, Provider::part,
                DeviceLabel.STREAM_CODEC, Provider::label, ByteBufCodecs.VAR_INT, Provider::usable, ByteBufCodecs.BOOL,
                Provider::editable, StackKey.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_OUTPUTS)), Provider::outputs,
                Provider::new);

        /** Whether this row names the provider at {@code pos} facing {@code facing}. */
        public boolean is(BlockPos at, Direction side) {
            return pos.equals(at) && facing == side;
        }
    }

    /**
     * Server to client: the network's providers, the index of the one whose slots the menu shows (-1 for none) and how
     * many were left out.
     */
    public record Providers(int containerId, List<Provider> providers, int selected,
            int hidden) implements CustomPacketPayload {
        public static final Type<Providers> TYPE = new Type<>(MaxtechStorage.id("crafter_manager_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Providers> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Providers::containerId, Provider.CODEC.apply(ByteBufCodecs.list(MAX_PROVIDERS)),
                Providers::providers, ByteBufCodecs.VAR_INT, Providers::selected, ByteBufCodecs.VAR_INT,
                Providers::hidden, Providers::new);

        @Override
        public Type<Providers> type() {
            return TYPE;
        }

        /** The selected provider's row, or null. */
        public @Nullable Provider selectedRow() {
            return selected >= 0 && selected < providers.size() ? providers.get(selected) : null;
        }
    }

    /** Registers the payloads; called once during mod construction. */
    public static void init() {
        Platform.get().lifecycle().onServerStopped(server -> LAST_REQUEST.clear());
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Open.TYPE, Open.CODEC, (payload, player) -> {
            CraftingSession session = RequestContent.session(player, payload.containerId());
            if (session != null && player instanceof ServerPlayer server && allowed(player)) {
                open(server, session);
            }
        });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Select.TYPE, Select.CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof CrafterManagerMenu menu
                            && menu.containerId == payload.containerId() && menu.stillValid(player)
                            && allowed(player)) {
                        menu.select(player, payload.pos(), payload.facing());
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Providers.TYPE, Providers.CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof CrafterManagerMenu menu
                            && menu.containerId == payload.containerId()) {
                        received = payload;
                    }
                });
    }

    /** The last list the client received for menu {@code containerId}; null when none came for it. */
    public static @Nullable Providers received(int containerId) {
        Providers last = received;
        return last != null && last.containerId() == containerId ? last : null;
    }

    /** One request per player per game tick. */
    private static boolean allowed(Player player) {
        long tick = player.level().getGameTime();
        return LAST_REQUEST.put(player.getUUID(), tick) != tick;
    }

    /**
     * Whether {@code player} may open the manager over {@code network}: view access, as for the pattern overview's
     * list.
     */
    public static boolean mayOpen(Player player, NetworkState network) {
        return !network.isClosed() && network.accessLevel(player).atLeast(AccessLevel.VIEW);
    }

    /**
     * Opens the manager over the network of {@code session}'s terminal for {@code player}; the manager stays open only
     * while that terminal stays valid for the player. Pays the terminal's action cost ({@link RemoteCost}). False
     * (nothing opens) without a network, the terminal menu, view access or the cost.
     */
    public static boolean open(ServerPlayer player, CraftingSession session) {
        NetworkState network = session.network();
        TerminalSource source = PatternOverview.source(session);
        if (network == null || source == null || !mayOpen(player, network) || !RemoteCost.pay(source)) {
            return false;
        }
        Platform.get().menus().open(player,
                new SimpleMenuProvider(
                        (id, inventory, opener) -> CrafterManagerMenu.server(id, inventory, network, source,
                                payload -> Platform.get().network().sendToPlayer(player, payload)),
                        Component.translatable(TITLE_KEY)),
                buffer -> {
                });
        return true;
    }

    /** The provider at {@code pos} facing {@code facing} among {@code network}'s own providers, or null. */
    public static @Nullable ProviderLogic find(NetworkState network, BlockPos pos, Direction facing) {
        List<PatternProvider> providers = network.patterns().providers();
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i) instanceof ProviderLogic logic && logic.pos().equals(pos) && logic.facing() == facing
                    && !logic.device().isRemoved() && logic.device().deviceLink().network() == network) {
                return logic;
            }
        }
        return null;
    }

    /**
     * The list for {@code viewer}: {@code network}'s own pattern providers (not generators or linked networks') by
     * position, then facing, with {@code selected}'s index; bounded by {@link #MAX_PROVIDERS} and {@code maxBytes}.
     */
    public static Providers providers(int containerId, NetworkState network, Player viewer,
            @Nullable ProviderLogic selected, int maxBytes) {
        List<PatternProvider> registered = network.patterns().providers();
        List<ProviderLogic> logics = new ArrayList<>(registered.size());
        for (int i = 0; i < registered.size(); i++) {
            if (registered.get(i) instanceof ProviderLogic logic && !logic.device().isRemoved()) {
                logics.add(logic);
            }
        }
        logics.sort((a, b) -> {
            int byPos = a.pos().compareTo(b.pos());
            return byPos != 0 ? byPos : Integer.compare(a.facing().get3DDataValue(), b.facing().get3DDataValue());
        });
        List<Provider> rows = new ArrayList<>(Math.min(logics.size(), MAX_PROVIDERS));
        int selectedIndex = -1;
        // Room for the picked provider, which is always sent (last, when the bound cut it off).
        boolean pickedListed = selected != null && logics.contains(selected);
        int limit = pickedListed ? MAX_PROVIDERS - 1 : MAX_PROVIDERS;
        RegistryFriendlyByteBuf scratch = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                network.level().registryAccess());
        try {
            for (int i = 0; i < logics.size() && rows.size() < limit; i++) {
                ProviderLogic logic = logics.get(i);
                Provider row = row(logic, viewer);
                Provider.CODEC.encode(scratch, row);
                if (scratch.writerIndex() > maxBytes) {
                    break;
                }
                if (logic == selected) {
                    selectedIndex = rows.size();
                }
                rows.add(row);
            }
        } finally {
            scratch.release();
        }
        if (selected != null && pickedListed && selectedIndex < 0) {
            selectedIndex = rows.size();
            rows.add(row(selected, viewer));
        }
        return new Providers(containerId, rows, selectedIndex, logics.size() - rows.size());
    }

    private static Provider row(ProviderLogic logic, Player viewer) {
        List<CraftingPattern> patterns = logic.patterns();
        List<StackKey> outputs = new ArrayList<>(Math.min(patterns.size(), MAX_OUTPUTS));
        for (int p = 0; p < patterns.size() && p < MAX_OUTPUTS; p++) {
            outputs.add(patterns.get(p).mainOutput().key());
        }
        return new Provider(logic.pos(), logic.facing(), logic.device() instanceof PatternProviderPart,
                logic.device().label(), logic.usableSlots(), NetworkAccess.canConfigure(viewer, logic.device()),
                outputs);
    }

    private static String gui(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".crafter_manager." + name;
    }
}
