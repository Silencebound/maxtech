package dev.silencebound.maxtech.storage.controller.recorder;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.module.LedgerBook;
import dev.silencebound.maxtech.storage.controller.module.TelemetryRecorder;
import dev.silencebound.maxtech.storage.mail.MailData;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkHistory;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

/**
 * One page of a controller's recorder views (M7R item 3) as the server sends it to a player: the flags that say which
 * views the owner's team has researched and which the config turns off, and the shown page's content: the telemetry
 * graphs (operations and FE in), the ledger (everyone, then each player busiest first) or the redstone alarm rules. A
 * locked or turned-off page carries no content. Immutable; built on the server thread, read on the client.
 *
 * @param flags
 *            {@link #NO_CONTROLLER} and the other flag bits
 * @param page
 *            {@link #TELEMETRY}, {@link #LEDGER} or {@link #ALARMS}
 */
public record RecordsView(int flags, int page, Telemetry telemetry, Ledger ledger, Alarms alarms) {
    public static final int TELEMETRY = 0;
    public static final int LEDGER = 1;
    public static final int ALARMS = 2;
    public static final int PAGES = 3;

    /** The network has no controller, or the player may not see it. */
    public static final int NO_CONTROLLER = 1;
    /** The owner's team lacks the page's research: bit {@code LOCKED << page}. */
    public static final int LOCKED = 1 << 1;
    /** The config turns the page's recorder off: bit {@code OFF << page}. */
    public static final int OFF = 1 << 4;
    /** The player may change the alarm rules. */
    public static final int MAY_CONFIGURE = 1 << 7;

    /** Graph samples sent at most. */
    public static final int MAX_SAMPLES = NetworkOverview.MAX_SAMPLES;
    /** Rows of the everyone view sent at most; the busiest keys. */
    public static final int MAX_ROWS = 128;
    /** Players sent at most, busiest first, and rows per player. */
    public static final int MAX_PLAYERS = 16;
    public static final int MAX_PLAYER_ROWS = 64;
    private static final int NAME_LENGTH = 64;

    public static final RecordsView EMPTY = new RecordsView(NO_CONTROLLER, TELEMETRY, Telemetry.NONE, Ledger.NONE,
            Alarms.NONE);

    /** The telemetry graphs: operations and FE in per sample, oldest first. */
    public record Telemetry(int sampleTicks, LongList operations, LongList energyIn) {
        public static final Telemetry NONE = new Telemetry(0, LongList.of(), LongList.of());
    }

    /**
     * The ledger: the days kept, the changes that found no row and the views, everyone first (never empty once sent).
     */
    public record Ledger(int days, long unrecorded, List<LedgerView> views) {
        public static final Ledger NONE = new Ledger(0, 0, List.of());
    }

    /**
     * One filter of the ledger: everyone summed ({@code player} null), automation ({@link LedgerBook#AUTOMATION}, empty
     * name) or one player, with its rows busiest first.
     */
    public record LedgerView(@Nullable UUID player, String name, List<LedgerBook.Row<StackKey>> rows) {
        /** The automation view. */
        public boolean automation() {
            return LedgerBook.AUTOMATION.equals(player);
        }
    }

    /** The redstone alarm rules and how many the controller holds at most. */
    public record Alarms(int max, List<Rule> rules) {
        public static final Alarms NONE = new Alarms(0, List.of());
    }

    /** One rule: its settings, whether it holds now and the value it last read. */
    public record Rule(AlarmSettings settings, boolean output, int value) {
    }

    /** Whether flag {@code bit} is set. */
    public boolean has(int bit) {
        return (flags & bit) != 0;
    }

    /** Whether the owner's team lacks the research of {@code shown}. */
    public boolean locked(int shown) {
        return has(LOCKED << shown);
    }

    /** Whether the config turns the recorder of {@code shown} off. */
    public boolean off(int shown) {
        return has(OFF << shown);
    }

    /** The research each page needs. */
    public static Identifier gate(int page) {
        return switch (page) {
            case LEDGER -> ResearchGates.LEDGER;
            case ALARMS -> ResearchGates.ALARM_MODULE;
            default -> ResearchGates.TELEMETRY_RECORDER;
        };
    }

    /** Whether the owner's team of {@code controller} has the research of {@code page} (unowned: yes). */
    public static boolean unlocked(ControllerBlockEntity controller, int page) {
        return controller.getLevel() instanceof ServerLevel server
                && ResearchUnlocks.isUnlocked(server, controller.ownerRecord(), gate(page));
    }

    /** Whether the config runs the recorder of {@code page}. */
    public static boolean enabled(int page) {
        return switch (page) {
            case LEDGER -> StorageServerConfig.RECORD_LEDGER.getAsBoolean();
            case ALARMS -> StorageServerConfig.RECORD_ALARMS.getAsBoolean();
            default -> StorageServerConfig.RECORD_TELEMETRY.getAsBoolean();
        };
    }

    /**
     * The view of {@code page} of the primary {@code controller} for {@code player}, who has view access to it (checked
     * by the caller): every page's flags, and the page's content when it is unlocked and on. Server thread.
     */
    public static RecordsView build(ControllerBlockEntity controller, ServerPlayer player, int page) {
        int shown = Math.clamp(page, TELEMETRY, ALARMS);
        int flags = 0;
        for (int i = 0; i < PAGES; i++) {
            if (!unlocked(controller, i)) {
                flags |= LOCKED << i;
            }
            if (!enabled(i)) {
                flags |= OFF << i;
            }
        }
        if (NetworkAccess.canConfigure(player, controller)) {
            flags |= MAY_CONFIGURE;
        }
        boolean open = (flags & ((LOCKED | OFF) << shown)) == 0;
        ControllerRecorders recorders = controller.recorders();
        Telemetry telemetry = open && shown == TELEMETRY ? telemetry(recorders.telemetry()) : Telemetry.NONE;
        Ledger ledger = open && shown == LEDGER ? ledger(player.level().getServer(), recorders.ledger()) : Ledger.NONE;
        Alarms alarms = open && shown == ALARMS ? alarms(recorders) : Alarms.NONE;
        return new RecordsView(flags, shown, telemetry, ledger, alarms);
    }

    /** The player may see the views of {@code controller}: view access to it. */
    public static boolean mayView(ServerPlayer player, ControllerBlockEntity controller) {
        return NetworkAccess.level(player, controller).atLeast(AccessLevel.VIEW);
    }

    private static Telemetry telemetry(@Nullable TelemetryRecorder history) {
        if (history == null) {
            return Telemetry.NONE;
        }
        // Ten minutes of samples, as the load graph always showed.
        int wanted = Math.min(MAX_SAMPLES, Math.max(1, NetworkOverview.HISTORY_TICKS / history.sampleTicks()));
        int count = Math.min(wanted, history.size());
        long[] buffer = new long[count];
        int read = history.read(NetworkHistory.Series.OPERATIONS, buffer);
        LongArrayList operations = new LongArrayList(buffer, 0, read);
        buffer = new long[count];
        read = history.read(NetworkHistory.Series.ENERGY_IN, buffer);
        LongArrayList energyIn = new LongArrayList(buffer, 0, read);
        return new Telemetry(history.sampleTicks(), operations, energyIn);
    }

    private static Ledger ledger(@Nullable MinecraftServer server, LedgerRecorder ledger) {
        List<LedgerView> views = new ArrayList<>();
        views.add(new LedgerView(null, "", ledger.totals(MAX_ROWS, null)));
        List<UUID> players = ledger.book().players();
        for (int i = 0; i < players.size() && i < MAX_PLAYERS; i++) {
            UUID player = players.get(i);
            views.add(new LedgerView(player, name(server, player), ledger.totals(MAX_PLAYER_ROWS, player)));
        }
        return new Ledger(StorageServerConfig.LEDGER_DAYS.getAsInt(), ledger.book().unrecorded(), views);
    }

    private static Alarms alarms(ControllerRecorders recorders) {
        List<AlarmWorker> workers = recorders.rules();
        List<Rule> rules = new ArrayList<>(workers.size());
        for (AlarmWorker worker : workers) {
            rules.add(new Rule(worker.settings(), worker.output(), worker.value()));
        }
        return new Alarms(StorageServerConfig.ALARM_RULES.getAsInt(), rules);
    }

    /** A player's name: online, then the server's name cache, then the mail's name table, else the UUID's start. */
    static String name(@Nullable MinecraftServer server, UUID player) {
        if (LedgerBook.AUTOMATION.equals(player)) {
            return "";
        }
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online != null) {
                return online.getGameProfile().name();
            }
            Optional<NameAndId> cached = server.services().nameToIdCache().get(player);
            if (cached.isPresent()) {
                return cached.get().name();
            }
            String mailed = MailData.get(server).name(player);
            if (mailed != null) {
                return mailed;
            }
        }
        return player.toString().substring(0, 8);
    }

    /** Writes the view. */
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(flags);
        buffer.writeVarInt(page);
        buffer.writeVarInt(telemetry.sampleTicks());
        writeLongs(buffer, telemetry.operations());
        writeLongs(buffer, telemetry.energyIn());
        buffer.writeVarInt(ledger.days());
        buffer.writeVarLong(ledger.unrecorded());
        buffer.writeVarInt(ledger.views().size());
        for (LedgerView view : ledger.views()) {
            buffer.writeBoolean(view.player() != null);
            if (view.player() != null) {
                buffer.writeUUID(view.player());
            }
            buffer.writeUtf(view.name(), NAME_LENGTH);
            buffer.writeVarInt(view.rows().size());
            for (LedgerBook.Row<StackKey> row : view.rows()) {
                StackKey.STREAM_CODEC.encode(buffer, row.key());
                buffer.writeVarLong(row.in());
                buffer.writeVarLong(row.out());
            }
        }
        buffer.writeVarInt(alarms.max());
        buffer.writeVarInt(alarms.rules().size());
        for (Rule rule : alarms.rules()) {
            AlarmSettings.STREAM_CODEC.encode(buffer, rule.settings());
            buffer.writeBoolean(rule.output());
            buffer.writeVarInt(rule.value());
        }
    }

    /** Reads a view; list sizes are capped, so a bad packet cannot ask for much memory. */
    public static RecordsView read(RegistryFriendlyByteBuf buffer) {
        int flags = buffer.readVarInt();
        int page = Math.clamp(buffer.readVarInt(), TELEMETRY, ALARMS);
        int sampleTicks = buffer.readVarInt();
        LongList operations = readLongs(buffer);
        LongList energyIn = readLongs(buffer);
        int days = buffer.readVarInt();
        long unrecorded = buffer.readVarLong();
        int viewCount = Math.min(buffer.readVarInt(), MAX_PLAYERS + 1);
        List<LedgerView> views = new ArrayList<>(viewCount);
        for (int v = 0; v < viewCount; v++) {
            UUID player = buffer.readBoolean() ? buffer.readUUID() : null;
            String name = buffer.readUtf(NAME_LENGTH);
            int count = Math.min(buffer.readVarInt(), MAX_ROWS);
            List<LedgerBook.Row<StackKey>> rows = new ArrayList<>(count);
            UUID label = player == null ? LedgerBook.AUTOMATION : player;
            for (int i = 0; i < count; i++) {
                rows.add(new LedgerBook.Row<>(label, StackKey.STREAM_CODEC.decode(buffer), buffer.readVarLong(),
                        buffer.readVarLong()));
            }
            views.add(new LedgerView(player, name, List.copyOf(rows)));
        }
        int max = buffer.readVarInt();
        int ruleCount = Math.min(buffer.readVarInt(), ControllerRecorders.MOST_RULES);
        List<Rule> rules = new ArrayList<>(ruleCount);
        for (int i = 0; i < ruleCount; i++) {
            rules.add(new Rule(AlarmSettings.STREAM_CODEC.decode(buffer), buffer.readBoolean(), buffer.readVarInt()));
        }
        return new RecordsView(flags, page, new Telemetry(sampleTicks, operations, energyIn),
                new Ledger(days, unrecorded, List.copyOf(views)), new Alarms(max, List.copyOf(rules)));
    }

    private static void writeLongs(RegistryFriendlyByteBuf buffer, LongList values) {
        buffer.writeVarInt(values.size());
        for (int i = 0; i < values.size(); i++) {
            buffer.writeVarLong(values.getLong(i));
        }
    }

    private static LongList readLongs(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_SAMPLES);
        LongArrayList values = new LongArrayList(count);
        for (int i = 0; i < count; i++) {
            values.add(buffer.readVarLong());
        }
        return values;
    }
}
