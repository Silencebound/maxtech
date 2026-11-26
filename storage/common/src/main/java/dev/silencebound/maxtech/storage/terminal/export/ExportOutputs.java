package dev.silencebound.maxtech.storage.terminal.export;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.display.DisplayDevice;
import dev.silencebound.maxtech.storage.display.DisplayText;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkLabels;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.jspecify.annotations.Nullable;

/**
 * The export outputs that go through the server (M7C item 10): a written book made from a book and quill the player
 * carries, and a display's rich text. The client names the open terminal menu and, for "only what is shown", the keys
 * its view lists; counts and access come from the server's own view of the network. Each payload is checked as hostile:
 * the sender's open, valid terminal menu with view access, at most one output per {@link #COOLDOWN_TICKS}, lists
 * bounded by the codecs, a book at most {@link #MAX_PAGES} pages and a display text inside {@link RichText}'s caps.
 * Server thread; the client side keeps the last answer for the export screen.
 */
public final class ExportOutputs {
    /** Entries on one book page; a page shows 14 lines and a long name takes two. */
    public static final int PER_PAGE = 7;
    /** Pages a book holds at most. */
    public static final int MAX_PAGES = 50;
    /** Entries a book holds at most; the rest is counted on the last page. */
    public static final int MAX_BOOK_ENTRIES = PER_PAGE * MAX_PAGES;
    /** Items a display shows at most: one live count each. */
    public static final int MAX_DISPLAY_ENTRIES = RichText.MAX_VALUES;
    /** Display labels listed at most. */
    public static final int MAX_LABELS = 32;
    /** Ticks between two outputs of one player. */
    public static final int COOLDOWN_TICKS = 20;

    private static final String GUI = "gui." + MaxtechStorage.MOD_ID + ".export.";
    private static final String BOOK = "book." + MaxtechStorage.MOD_ID + ".export.";
    public static final String TITLE_KEY = GUI + "title";
    public static final String SHOWN_KEY = GUI + "shown";
    public static final String ALL_KEY = GUI + "all";
    public static final String COPY_CSV_KEY = GUI + "copy_csv";
    public static final String COPY_TEXT_KEY = GUI + "copy_text";
    public static final String SAVE_CSV_KEY = GUI + "save_csv";
    public static final String COPY_CODE_KEY = GUI + "copy_code";
    public static final String BOOK_KEY = GUI + "book";
    public static final String BOOK_HINT_KEY = GUI + "book_hint";
    public static final String DISPLAY_KEY = GUI + "display";
    public static final String NO_DISPLAYS_KEY = GUI + "no_displays";
    public static final String SEND_KEY = GUI + "send";
    public static final String DISPLAY_HINT_KEY = GUI + "display_hint";
    public static final String COPIED_KEY = GUI + "copied";
    public static final String CRAFTABLE_MARK_KEY = GUI + "craftable_mark";
    public static final String SAVED_KEY = GUI + "saved";
    public static final String SAVE_FAILED_KEY = GUI + "save_failed";
    public static final String BOOK_NAME_KEY = BOOK + "name";
    public static final String BOOK_ITEM_KEY = BOOK + "item";
    public static final String BOOK_FLUID_KEY = BOOK + "fluid";
    public static final String BOOK_MORE_KEY = BOOK + "more";

    /**
     * What became of an output; the client shows {@link #key()} with the outcome's count, written and refused numbers.
     * Tests read it.
     */
    public enum Result {
        /** A book was written; the count is its entries. */
        BOOK_WRITTEN(0, "book_written", "Wrote %s entries into a book"),
        /** Displays show the list; the count is its items, written the displays. */
        DISPLAY_WRITTEN(1, "display_written", "Sent %s items to %s displays"),
        /** The payload named no open terminal menu of the player. */
        NO_MENU(2, "no_menu", "The terminal is closed"),
        /** The player may not see the network, or is out of reach. */
        NO_ACCESS(3, "no_access", "No access to this network"),
        /** No book and quill in the inventory. */
        NO_BOOK(4, "no_book", "You need a book and quill in your inventory"),
        /** The terminal is not on a network. */
        NO_NETWORK(5, "no_network", "The terminal has no network"),
        /** No display carries the label. */
        NO_DISPLAY(6, "no_display", "No display with that label on this network"),
        /** The player may change none of the displays with the label; refused is how many. */
        DENIED(7, "denied", "You may not change those %3$s displays"),
        /** The list holds nothing the output can show. */
        EMPTY(8, "empty", "Nothing to export"),
        /** The player's last output was less than {@link #COOLDOWN_TICKS} ago. */
        TOO_SOON(9, "too_soon", "Wait a moment before exporting again"),
        /** Some displays with the label were written, others refused (no full access). */
        DISPLAY_PARTIAL(10, "display_partial", "Sent %s items to %s displays; %s refused (no access)");

        private static final Result[] VALUES = values();
        static final StreamCodec<ByteBuf, Result> STREAM_CODEC = ByteBufCodecs.idMapper(Result::byId, Result::id);
        private final int id;
        private final String key;
        private final String english;

        Result(int id, String name, String english) {
            this.id = id;
            this.key = GUI + "result." + name;
            this.english = english;
        }

        public String key() {
            return key;
        }

        /** Whether the output was made. */
        public boolean done() {
            return this == BOOK_WRITTEN || this == DISPLAY_WRITTEN || this == DISPLAY_PARTIAL;
        }

        int id() {
            return id;
        }

        static Result byId(int id) {
            for (Result result : VALUES) {
                if (result.id == id) {
                    return result;
                }
            }
            return NO_MENU;
        }
    }

    static {
        for (Result result : Result.VALUES) {
            ModContent.CONTENT.translation(result.key, result.english);
        }
        ModContent.CONTENT.translation(TITLE_KEY, "Export");
        ModContent.CONTENT.translation(SHOWN_KEY, "Shown: %s entries");
        ModContent.CONTENT.translation(ALL_KEY, "Everything: %s entries");
        ModContent.CONTENT.translation(COPY_CSV_KEY, "Copy CSV");
        ModContent.CONTENT.translation(COPY_TEXT_KEY, "Copy text");
        ModContent.CONTENT.translation(SAVE_CSV_KEY, "Save CSV");
        ModContent.CONTENT.translation(COPY_CODE_KEY, "Copy list code");
        ModContent.CONTENT.translation(BOOK_KEY, "Write book");
        ModContent.CONTENT.translation(BOOK_HINT_KEY,
                "Uses a book and quill from your inventory; at most %s entries, counted by the network");
        ModContent.CONTENT.translation(DISPLAY_KEY, "Display: %s");
        ModContent.CONTENT.translation(NO_DISPLAYS_KEY, "No labelled display");
        ModContent.CONTENT.translation(SEND_KEY, "Send");
        ModContent.CONTENT.translation(DISPLAY_HINT_KEY,
                "Shows the first %s items with live counts on every display with this label");
        ModContent.CONTENT.translation(COPIED_KEY, "Copied to the clipboard");
        ModContent.CONTENT.translation(CRAFTABLE_MARK_KEY, "(craftable)");
        ModContent.CONTENT.translation(SAVED_KEY, "Inventory list saved");
        ModContent.CONTENT.translation(SAVE_FAILED_KEY, "Could not save the inventory list");
        ModContent.CONTENT.translation(BOOK_NAME_KEY, "Inventory List");
        ModContent.CONTENT.translation(BOOK_ITEM_KEY, "%s × %s");
        ModContent.CONTENT.translation(BOOK_FLUID_KEY, "%s mB %s");
        ModContent.CONTENT.translation(BOOK_MORE_KEY, "… and %s more");
    }

    /** The game tick of each player's last output. */
    private static final Object2LongOpenHashMap<UUID> LAST_OUTPUT = new Object2LongOpenHashMap<>();
    private static final Object2LongOpenHashMap<UUID> LAST_LIST = new Object2LongOpenHashMap<>();
    private static int shownContainer = -1;
    private static List<DeviceLabel> shownLabels = List.of();
    private static @Nullable Done lastDone;

    private ExportOutputs() {
    }

    /**
     * Client to server: write the list into a book. {@code all} lists everything the network holds; otherwise
     * {@code keys} in the client's order, as many as the network holds.
     */
    public record Book(int containerId, boolean all, List<StackKey> keys) implements CustomPacketPayload {
        public static final Type<Book> TYPE = new Type<>(MaxtechStorage.id("export_book"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Book> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Book::containerId, ByteBufCodecs.BOOL, Book::all,
                StackKey.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_BOOK_ENTRIES)), Book::keys, Book::new);

        public Book {
            keys = List.copyOf(keys);
        }

        @Override
        public Type<Book> type() {
            return TYPE;
        }
    }

    /** Client to server: show the list on the network's displays labelled {@code label}. */
    public record Display(int containerId, String label, boolean all,
            List<StackKey> keys) implements CustomPacketPayload {
        public static final Type<Display> TYPE = new Type<>(MaxtechStorage.id("export_display"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Display> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Display::containerId, ByteBufCodecs.stringUtf8(DeviceLabel.MAX_LENGTH * 4),
                Display::label, ByteBufCodecs.BOOL, Display::all,
                StackKey.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_DISPLAY_ENTRIES)), Display::keys, Display::new);

        public Display {
            keys = List.copyOf(keys);
        }

        @Override
        public Type<Display> type() {
            return TYPE;
        }
    }

    /** Client to server: list the labels of the network's displays the player may change. */
    public record ListDisplays(int containerId) implements CustomPacketPayload {
        public static final Type<ListDisplays> TYPE = new Type<>(MaxtechStorage.id("export_list_displays"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ListDisplays> CODEC = StreamCodec
                .composite(ByteBufCodecs.VAR_INT, ListDisplays::containerId, ListDisplays::new);

        @Override
        public Type<ListDisplays> type() {
            return TYPE;
        }
    }

    /** Server to client: the display labels for menu {@code containerId}. */
    public record Displays(int containerId, List<DeviceLabel> labels) implements CustomPacketPayload {
        public static final Type<Displays> TYPE = new Type<>(MaxtechStorage.id("export_displays"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Displays> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Displays::containerId,
                DeviceLabel.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LABELS)), Displays::labels, Displays::new);

        public Displays {
            labels = List.copyOf(labels);
        }

        @Override
        public Type<Displays> type() {
            return TYPE;
        }
    }

    /** Server to client: what became of an output, with the outcome's numbers. */
    public record Done(int containerId, Result result, int count, int written,
            int refused) implements CustomPacketPayload {
        public static final Type<Done> TYPE = new Type<>(MaxtechStorage.id("export_done"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Done> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Done::containerId, Result.STREAM_CODEC, Done::result, ByteBufCodecs.VAR_INT,
                Done::count, ByteBufCodecs.VAR_INT, Done::written, ByteBufCodecs.VAR_INT, Done::refused, Done::new);

        /** The answer as the export screen shows it. */
        public Component message() {
            return Component.translatable(result.key(), count, written, refused);
        }

        @Override
        public Type<Done> type() {
            return TYPE;
        }
    }

    /**
     * An output's result and numbers.
     *
     * @param count
     *            the entries written (book) or items shown (display)
     * @param written
     *            the displays written
     * @param refused
     *            the displays with the label the player may not change
     */
    public record Outcome(Result result, int count, int written, int refused) {
        Outcome(Result result, int count) {
            this(result, count, 0, 0);
        }
    }

    /** Registers the payloads; called once during mod construction. */
    public static void init() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Book.TYPE, Book.CODEC,
                (payload, player) -> answer(player, payload.containerId(),
                        writeBook(player, payload.containerId(), payload.all(), payload.keys())));
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Display.TYPE, Display.CODEC,
                (payload, player) -> answer(player, payload.containerId(),
                        toDisplay(player, payload.containerId(), payload.label(), payload.all(), payload.keys())));
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, ListDisplays.TYPE, ListDisplays.CODEC,
                (payload, player) -> {
                    NetworkState network = network(player, payload.containerId());
                    long tick = player.level().getGameTime();
                    if (network != null && player instanceof ServerPlayer server
                            && LAST_LIST.put(player.getUUID(), tick) != tick) {
                        Platform.get().network().sendToPlayer(server,
                                new Displays(payload.containerId(), displayLabels(player, network)));
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Displays.TYPE, Displays.CODEC,
                (payload, player) -> {
                    if (player.containerMenu.containerId == payload.containerId()) {
                        shownContainer = payload.containerId();
                        shownLabels = payload.labels();
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Done.TYPE, Done.CODEC, (payload, player) -> {
            if (player.containerMenu.containerId == payload.containerId()) {
                lastDone = payload;
            }
        });
    }

    private static void answer(Player player, int containerId, Outcome outcome) {
        if (player instanceof ServerPlayer server) {
            Platform.get().network().sendToPlayer(server,
                    new Done(containerId, outcome.result(), outcome.count(), outcome.written(), outcome.refused()));
        }
    }

    /** Client side: the display labels the server last listed for menu {@code containerId}. */
    public static List<DeviceLabel> shownLabels(int containerId) {
        return shownContainer == containerId ? shownLabels : List.of();
    }

    /** Client side: the last answer for menu {@code containerId}, taken (null when there is none). */
    public static @Nullable Done takeDone(int containerId) {
        Done done = lastDone;
        if (done == null || done.containerId() != containerId) {
            return null;
        }
        lastDone = null;
        return done;
    }

    /** The sender's open terminal menu {@code containerId} when it is valid and the player may see its contents. */
    private static @Nullable TerminalMenu menu(Player player, int containerId) {
        return player.containerMenu instanceof TerminalMenu menu && menu.containerId == containerId
                && !player.isSpectator() && menu.stillValid(player) ? menu : null;
    }

    private static @Nullable NetworkState network(Player player, int containerId) {
        TerminalMenu menu = menu(player, containerId);
        TerminalSource source = menu == null ? null : menu.source();
        return source == null ? null : source.network();
    }

    /** Whether the player may make an output now; records the tick when so. */
    private static boolean cooledDown(Player player) {
        long tick = player.level().getGameTime();
        long last = LAST_OUTPUT.containsKey(player.getUUID()) ? LAST_OUTPUT.getLong(player.getUUID()) : Long.MIN_VALUE;
        if (last != Long.MIN_VALUE && last <= tick && tick - last < COOLDOWN_TICKS) {
            return false;
        }
        LAST_OUTPUT.put(player.getUUID(), tick);
        return true;
    }

    /** Forgets {@code player}'s cooldowns; when they log out, so the maps hold only players on the server. */
    public static void forget(UUID player) {
        LAST_OUTPUT.removeLong(player);
        LAST_LIST.removeLong(player);
    }

    /** Whether a cooldown of {@code player} is remembered; for tests. */
    public static boolean remembers(UUID player) {
        return LAST_OUTPUT.containsKey(player) || LAST_LIST.containsKey(player);
    }

    /** Forgets the cooldowns; for tests that export twice from one player. */
    public static void resetCooldowns() {
        LAST_OUTPUT.clear();
        LAST_LIST.clear();
    }

    /**
     * Writes the list into a written book that replaces one book and quill from {@code player}'s inventory, as the
     * payload handler does. Server thread.
     */
    public static Outcome writeBook(Player player, int containerId, boolean all, List<StackKey> keys) {
        TerminalMenu menu = player.containerMenu instanceof TerminalMenu open && open.containerId == containerId
                ? open
                : null;
        TerminalSource source = menu == null ? null : menu.source();
        if (menu == null || source == null) {
            return new Outcome(Result.NO_MENU, 0);
        }
        if (menu(player, containerId) == null) {
            return new Outcome(Result.NO_ACCESS, 0);
        }
        int slot = bookSlot(player.getInventory());
        if (slot < 0) {
            return new Outcome(Result.NO_BOOK, 0);
        }
        if (!cooledDown(player)) {
            return new Outcome(Result.TOO_SOON, 0);
        }
        List<Map.Entry<StackKey, Long>> entries = counted(source, all, keys);
        if (entries.isEmpty()) {
            return new Outcome(Result.EMPTY, 0);
        }
        ItemStack book = book(entries, player.getGameProfile().name());
        Inventory inventory = player.getInventory();
        ItemStack quill = inventory.getItem(slot);
        if (quill.getCount() == 1) {
            inventory.setItem(slot, book);
        } else {
            quill.shrink(1);
            if (!inventory.add(book)) {
                player.drop(book, false);
            }
        }
        menu.broadcastChanges();
        return new Outcome(Result.BOOK_WRITTEN, Math.min(entries.size(), MAX_BOOK_ENTRIES));
    }

    /** The first inventory slot holding a book and quill, or -1. */
    private static int bookSlot(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.WRITABLE_BOOK)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * The network's counts: everything it holds, most first, or {@code keys} in their order with what the network holds
     * of each (keys it holds none of, or named twice, left out).
     */
    private static List<Map.Entry<StackKey, Long>> counted(TerminalSource source, boolean all, List<StackKey> keys) {
        Int2LongMap contents = source.contents();
        Object2LongOpenHashMap<StackKey> amounts = new Object2LongOpenHashMap<>(contents.size());
        for (Int2LongMap.Entry entry : contents.int2LongEntrySet()) {
            StackKey key = source.key(entry.getIntKey());
            if (key != null && entry.getLongValue() > 0) {
                amounts.addTo(key, entry.getLongValue());
            }
        }
        List<Map.Entry<StackKey, Long>> out = new ArrayList<>();
        if (all) {
            for (Object2LongMap.Entry<StackKey> entry : amounts.object2LongEntrySet()) {
                out.add(Map.entry(entry.getKey(), entry.getLongValue()));
            }
            out.sort(Comparator.comparingLong((Map.Entry<StackKey, Long> entry) -> entry.getValue()).reversed());
            return out;
        }
        for (StackKey key : keys) {
            long amount = amounts.removeLong(key);
            if (amount > 0) {
                out.add(Map.entry(key, amount));
            }
        }
        return out;
    }

    /** The written book for {@code entries}: {@link #PER_PAGE} a page, at most {@link #MAX_PAGES} pages. */
    public static ItemStack book(List<Map.Entry<StackKey, Long>> entries, String author) {
        List<Filterable<Component>> pages = new ArrayList<>();
        int shown = Math.min(entries.size(), MAX_BOOK_ENTRIES);
        for (int start = 0; start < shown; start += PER_PAGE) {
            MutableComponent page = Component.empty();
            for (int i = start; i < Math.min(start + PER_PAGE, shown); i++) {
                if (i > start) {
                    page.append("\n");
                }
                StackKey key = entries.get(i).getKey();
                page.append(Component.translatable(key instanceof FluidKey ? BOOK_FLUID_KEY : BOOK_ITEM_KEY,
                        InfoFormat.number(entries.get(i).getValue()), JobToastPayload.name(key)));
            }
            if (start + PER_PAGE >= shown && entries.size() > shown) {
                page.append("\n").append(Component.translatable(BOOK_MORE_KEY, entries.size() - shown));
            }
            pages.add(Filterable.passThrough(page));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        // A blank title makes the stack fall back to its item name, which can be translated.
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(""), author, 0, pages, true));
        book.set(DataComponents.ITEM_NAME, Component.translatable(BOOK_NAME_KEY));
        return book;
    }

    /**
     * Shows the list's items with live counts on the network's displays labelled {@code label} that {@code player} may
     * change, as the payload handler does. Server thread.
     */
    public static Outcome toDisplay(Player player, int containerId, String label, boolean all, List<StackKey> keys) {
        TerminalMenu menu = player.containerMenu instanceof TerminalMenu open && open.containerId == containerId
                ? open
                : null;
        TerminalSource source = menu == null ? null : menu.source();
        if (menu == null || source == null) {
            return new Outcome(Result.NO_MENU, 0);
        }
        if (menu(player, containerId) == null) {
            return new Outcome(Result.NO_ACCESS, 0);
        }
        NetworkState network = source.network();
        if (network == null) {
            return new Outcome(Result.NO_NETWORK, 0);
        }
        List<DisplayDevice> displays = new ArrayList<>();
        int denied = 0;
        for (NetworkDevice device : network.labels().devices(label)) {
            if (device instanceof DisplayDevice display) {
                if (NetworkAccess.canConfigure(player, device)) {
                    displays.add(display);
                } else {
                    denied++;
                }
            }
        }
        if (displays.isEmpty()) {
            return denied > 0 ? new Outcome(Result.DENIED, 0, 0, denied) : new Outcome(Result.NO_DISPLAY, 0);
        }
        if (!cooledDown(player)) {
            return new Outcome(Result.TOO_SOON, 0);
        }
        List<Map.Entry<StackKey, Long>> entries = counted(source, all, keys);
        StringBuilder markup = new StringBuilder();
        int written = 0;
        for (Map.Entry<StackKey, Long> entry : entries) {
            if (written >= MAX_DISPLAY_ENTRIES) {
                break;
            }
            if (!(entry.getKey() instanceof ItemKey item)) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(item.item()).toString();
            String line = (written > 0 ? "\n" : "") + "[item:" + id + "] {" + DisplayText.COUNT + ":" + id + "}";
            if (markup.length() + line.length() > RichText.MAX_LENGTH) {
                break;
            }
            markup.append(line);
            written++;
        }
        if (written == 0 || DisplayText.problem(markup.toString()).isPresent()) {
            return new Outcome(Result.EMPTY, 0);
        }
        for (DisplayDevice display : displays) {
            display.showText(markup.toString());
        }
        return new Outcome(denied > 0 ? Result.DISPLAY_PARTIAL : Result.DISPLAY_WRITTEN, written, displays.size(),
                denied);
    }

    /** The labels of {@code network}'s displays that {@code player} may change, at most {@link #MAX_LABELS}. */
    public static List<DeviceLabel> displayLabels(Player player, NetworkState network) {
        List<DeviceLabel> out = new ArrayList<>();
        for (NetworkLabels.Entry entry : network.labels().entries(Integer.MAX_VALUE)) {
            if (out.size() >= MAX_LABELS) {
                break;
            }
            for (NetworkDevice device : network.labels().devicesByKey(entry.label().key())) {
                if (device instanceof DisplayDevice && NetworkAccess.canConfigure(player, device)) {
                    out.add(entry.label());
                    break;
                }
            }
        }
        return out;
    }
}
