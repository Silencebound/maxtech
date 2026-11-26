package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import net.minecraft.world.level.Level;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The security module's screen (PRD 7.2, Controller): one row for everyone else, one for the owner's team, one per
 * player (those with a rule, those the owner trusts and those online) and one per scoreboard team (those with a rule
 * and those that exist). The rows are fixed when the screen opens and travel with its open data; each row's rule and
 * trusted mark travel back as a data slot ({@link SecurityRowValue}). Clicking a row is a vanilla menu button click
 * with the row's index, which cycles its level; {@link #TRUST_BUTTON} plus a player row's index trusts or distrusts
 * that player (the owner and game masters only, since trusted players may change the rules). A typed name arrives as
 * {@link SecurityPayloads.AddPlayer} and adds a rule for a player who is not online, after which the screen reopens
 * with the new row. Only the network's owner, players the owner trusts and game masters can open it.
 */
public final class SecurityMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 166;
    /** Button ids from here on trust or distrust the player of row {@code id - TRUST_BUTTON}. */
    public static final int TRUST_BUTTON = 1000;
    private static final int MAX_NAME = SecuritySettings.MAX_TEAM_NAME;
    private static final double REACH = 8.0;
    /** Players with a name lookup in flight; at most one each. */
    private static final Set<UUID> LOOKUPS = ConcurrentHashMap.newKeySet();

    /** What a row sets. */
    public enum Kind {
        EVERYONE, OWNER_TEAM, PLAYER, TEAM;

        static Kind byId(int id) {
            return switch (id) {
                case 0 -> EVERYONE;
                case 1 -> OWNER_TEAM;
                case 2 -> PLAYER;
                default -> TEAM;
            };
        }

        int id() {
            return switch (this) {
                case EVERYONE -> 0;
                case OWNER_TEAM -> 1;
                case PLAYER -> 2;
                case TEAM -> 3;
            };
        }
    }

    /** One row: its kind, the player's id (players only) and the name shown. */
    public record Row(Kind kind, @Nullable UUID player, String name) {
    }

    private final List<Row> rows;
    private final DataSlot[] levels;
    private final @Nullable ControllerBlockEntity core;
    private final BlockPos module;
    private final Component title;

    private SecurityMenu(int containerId, List<Row> rows, @Nullable ControllerBlockEntity core, BlockPos module,
            Component title) {
        super(ModMenus.SECURITY.get(), containerId);
        this.rows = rows;
        this.core = core;
        this.module = module;
        this.title = title;
        levels = new DataSlot[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            levels[i] = core == null ? DataSlot.standalone() : new Live(core, rows.get(i));
            addDataSlot(levels[i]);
        }
    }

    /** The client's menu: the rows from the open data, levels from the data slots. */
    public static SecurityMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        BlockPos module = buffer.readBlockPos();
        int count = Math.min(buffer.readVarInt(), maxRows());
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Kind kind = Kind.byId(buffer.readByte());
            UUID player = kind == Kind.PLAYER ? buffer.readUUID() : null;
            rows.add(new Row(kind, player, buffer.readUtf(MAX_NAME)));
        }
        return new SecurityMenu(containerId, rows, null, module, Component.empty());
    }

    /**
     * The server's menu over {@code core}'s rules with {@code rows} (see {@link #rows}); {@code title} is the screen's
     * title when it reopens after a rule is added by name. {@link #open} uses it.
     */
    public static SecurityMenu forCore(int containerId, ControllerBlockEntity core, List<Row> rows, BlockPos module,
            Component title) {
        return new SecurityMenu(containerId, rows, core, module, title);
    }

    /** Most rows a screen has: the two fixed ones, every player rule and trusted player, and every team rule. */
    public static int maxRows() {
        return 2 + SecuritySettings.MAX_PLAYERS + OwnerRecord.MAX_TRUSTED + SecuritySettings.MAX_TEAMS;
    }

    /**
     * The rows for {@code core}'s rules: everyone, the owner's team, players with a rule, trusted or online (not the
     * owner), then scoreboard teams with a rule or existing, each list by name and cut at its rule cap. A trusted
     * player without a rule who is offline is named from the server's name cache, or by the start of their id.
     */
    public static List<Row> rows(ControllerBlockEntity core, ServerPlayer viewer) {
        SecuritySettings rules = core.security();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Kind.EVERYONE, null, ""));
        rows.add(new Row(Kind.OWNER_TEAM, null, ""));
        Map<UUID, String> players = new LinkedHashMap<>();
        for (SecuritySettings.PlayerRule rule : rules.players()) {
            players.put(rule.id(), rule.name());
        }
        OwnerRecord owner = core.ownerRecord();
        MinecraftServer server = viewer.level().getServer();
        for (UUID trusted : owner.trusted()) {
            if (!players.containsKey(trusted)) {
                players.put(trusted, nameOf(server, trusted));
            }
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (!owner.isOwner(online.getUUID())) {
                players.putIfAbsent(online.getUUID(), online.getGameProfile().name());
            }
        }
        List<Map.Entry<UUID, String>> sortedPlayers = new ArrayList<>(players.entrySet());
        sortedPlayers.sort(Map.Entry.comparingByValue());
        for (int i = 0; i < sortedPlayers.size() && i < SecuritySettings.MAX_PLAYERS + OwnerRecord.MAX_TRUSTED; i++) {
            rows.add(new Row(Kind.PLAYER, sortedPlayers.get(i).getKey(), sortedPlayers.get(i).getValue()));
        }
        List<String> teams = new ArrayList<>();
        for (SecuritySettings.TeamRule rule : rules.teams()) {
            teams.add(rule.team());
        }
        for (PlayerTeam team : viewer.level().getScoreboard().getPlayerTeams()) {
            if (!teams.contains(team.getName()) && team.getName().length() <= MAX_NAME) {
                teams.add(team.getName());
            }
        }
        teams.sort(Comparator.naturalOrder());
        for (int i = 0; i < teams.size() && i < SecuritySettings.MAX_TEAMS; i++) {
            rows.add(new Row(Kind.TEAM, null, teams.get(i)));
        }
        return rows;
    }

    /** An offline player's name from the server's cache (never a remote lookup), else the start of their id. */
    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().name();
        }
        return server.services().nameToIdCache().get(id).map(NameAndId::name).orElse(id.toString().substring(0, 8));
    }

    /** Opens {@code core}'s security screen for {@code player}, who clicked the module at {@code module}. */
    public static void open(ServerPlayer player, ControllerBlockEntity core, BlockPos module, Component title) {
        List<Row> rows = rows(core, player);
        Platform.get().menus().open(player, new SimpleMenuProvider(
                (id, inventory, opener) -> new SecurityMenu(id, rows, core, module, title), title), buffer -> {
                    buffer.writeBlockPos(module);
                    buffer.writeVarInt(rows.size());
                    for (Row row : rows) {
                        buffer.writeByte(row.kind().id());
                        UUID id = row.player();
                        if (row.kind() == Kind.PLAYER && id != null) {
                            buffer.writeUUID(id);
                        }
                        buffer.writeUtf(row.name(), MAX_NAME);
                    }
                });
    }

    public List<Row> rows() {
        return rows;
    }

    /** Row {@code index}'s level, or null for a player or team without a rule; as synced on the client. */
    public @Nullable AccessLevel level(int index) {
        return SecurityRowValue.level(levels[index].get());
    }

    /** Whether the owner trusts row {@code index}'s player; as synced on the client. */
    public boolean trusted(int index) {
        return SecurityRowValue.trusted(levels[index].get());
    }

    /**
     * Clicking row {@code id} cycles its level; {@link #TRUST_BUTTON} plus a player row's index toggles that player's
     * trust. Refused for anyone who may not edit the rules, and trust for anyone but the owner and game masters.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        ControllerBlockEntity current = core;
        if (current == null || !SecuritySettings.canEdit(player, current.ownerRecord())) {
            return false;
        }
        if (id >= TRUST_BUTTON) {
            return toggleTrust(player, current, id - TRUST_BUTTON);
        }
        if (id < 0 || id >= rows.size()) {
            return false;
        }
        Row row = rows.get(id);
        SecuritySettings rules = current.security();
        SecuritySettings next = switch (row.kind()) {
            case EVERYONE -> rules.withEveryone(rules.everyone().next());
            case OWNER_TEAM -> rules.withOwnerTeam(rules.ownerTeam().next());
            case PLAYER -> {
                UUID target = row.player();
                yield target == null ? rules : rules.withPlayer(target, row.name(), cycle(rules.playerLevel(target)));
            }
            case TEAM -> rules.withTeam(row.name(), cycle(rules.teamLevel(row.name())));
        };
        current.setSecurity(next);
        return true;
    }

    private boolean toggleTrust(Player player, ControllerBlockEntity current, int index) {
        OwnerRecord owner = current.ownerRecord();
        if (index >= rows.size() || rows.get(index).kind() != Kind.PLAYER || !canTrust(player, owner)) {
            return false;
        }
        UUID target = rows.get(index).player();
        if (target == null) {
            return false;
        }
        current.setOwner(owner.isTrusted(target) ? owner.withoutTrusted(target) : owner.withTrusted(target));
        return true;
    }

    /**
     * Whether {@code player} may change who the owner of an owned network trusts: the owner and game masters, not
     * trusted players, who could otherwise trust anyone.
     */
    public static boolean canTrust(Player player, OwnerRecord owner) {
        return owner.isOwned() && (owner.isOwner(player.getUUID())
                || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    /**
     * Adds a view rule for the player called {@code name}, typed into the screen: an online player at once, anyone else
     * after a lookup in the server's name cache off the server thread (one lookup per player at a time). Nothing
     * happens for an invalid name, a player who may not edit the rules or a screen that closed meanwhile. Server
     * thread.
     */
    public void addPlayer(ServerPlayer player, String name) {
        ControllerBlockEntity current = core;
        if (current == null || !StringUtil.isValidPlayerName(name) || !stillValid(player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            addRule(player, online.getUUID(), online.getGameProfile().name());
            return;
        }
        UUID requester = player.getUUID();
        if (!LOOKUPS.add(requester)) {
            return;
        }
        UserNameToIdResolver names = server.services().nameToIdCache();
        // The cache asks Mojang's profile service for a name it does not know, which blocks: never on the server
        // thread. The answer is applied on the server thread, and only while this screen is still open.
        // The handler takes the lookup's failure as "no such player"; nothing else is left to report.
        CompletableFuture<?> _ = CompletableFuture.supplyAsync(() -> names.get(name), Util.nonCriticalIoPool())
                .whenCompleteAsync((found, error) -> {
                    LOOKUPS.remove(requester);
                    if (player.containerMenu != this) {
                        return;
                    }
                    if (error == null && found != null && found.isPresent()) {
                        addRule(player, found.get().id(), found.get().name());
                    } else if (player.connection != null) {
                        player.sendOverlayMessage(Component.translatable(SecurityTexts.UNKNOWN_PLAYER, name));
                    }
                }, server);
    }

    /**
     * Adds a view rule for player {@code id} unless they already have one or own the network, then reopens the screen
     * so the row shows. Returns whether {@code player} could edit the rules. Server thread.
     */
    public boolean addRule(ServerPlayer player, UUID id, String name) {
        ControllerBlockEntity current = core;
        if (current == null || !stillValid(player)) {
            return false;
        }
        SecuritySettings rules = current.security();
        if (!current.ownerRecord().isOwner(id) && rules.playerLevel(id) == null) {
            current.setSecurity(rules.withPlayer(id, name, AccessLevel.VIEW));
        }
        // A GameTest player has no connection to open a screen on.
        if (player.connection != null && player.containerMenu == this) {
            open(player, current, module, title);
        }
        return true;
    }

    /** No rule, none, view, deposit, craft, logic, full, then no rule again. */
    private static @Nullable AccessLevel cycle(@Nullable AccessLevel level) {
        if (level == null) {
            return AccessLevel.NONE;
        }
        return level == AccessLevel.FULL ? null : level.next();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        ControllerBlockEntity current = core;
        return current == null || (!current.isRemoved() && player.position().closerThan(module.getCenter(), REACH)
                && SecuritySettings.canEdit(player, current.ownerRecord()));
    }

    /** A row's rule and trusted mark read live from the core; server side. */
    private static final class Live extends DataSlot {
        private final ControllerBlockEntity core;
        private final Row row;

        Live(ControllerBlockEntity core, Row row) {
            this.core = core;
            this.row = row;
        }

        @Override
        public int get() {
            UUID id = row.player();
            boolean trusted = id != null && core.ownerRecord().isTrusted(id);
            SecuritySettings rules = core.security();
            AccessLevel level = switch (row.kind()) {
                case EVERYONE -> rules.everyone();
                case OWNER_TEAM -> rules.ownerTeam();
                case PLAYER -> id == null ? null : rules.playerLevel(id);
                case TEAM -> rules.teamLevel(row.name());
            };
            return SecurityRowValue.encode(level, trusted);
        }

        @Override
        public void set(int value) {
            // Server side the rules are the truth; clicks change them.
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        // The screen belongs to the security module beside the core, so the module is what gets labelled.
        ControllerBlockEntity current = core;
        Level level = current == null ? null : current.getLevel();
        return level == null ? null : LabelMenu.device(level.getBlockEntity(module));
    }
}
