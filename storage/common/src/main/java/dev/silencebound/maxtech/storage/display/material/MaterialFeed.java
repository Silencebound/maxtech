package dev.silencebound.maxtech.storage.display.material;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.QueueBackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.display.DisplayTerminal;
import dev.silencebound.maxtech.storage.display.RowFeed;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * A build material list screen's feed (M7C item 17): one row per entry of the list code in the screen's settings with
 * what the network has and needs of it, then one row per {@link Action} (craft the missing amounts, take the listed
 * amounts, copy the code). It listens to the counts of the listed keys only (the per-key listener, so a network moving
 * other items costs the screen nothing) and reads the network's cached counts when someone sees the screen. The list is
 * read when the feed is bound; a changed list makes a new feed. Rows keep the list's order. Server thread.
 */
public final class MaterialFeed implements RowFeed, KeyListener {
    /** Most entries a screen counts; the action rows come after them. */
    public static final int MAX_ENTRIES = ScreenRows.MAX_ROWS - Action.values().length;

    /** Ticks after a "take all" on this screen in which the next one is ignored, so a double-click takes once. */
    public static final int TAKE_COOLDOWN_TICKS = 10;

    /** What a click on a row after the entries does, in the order of the rows. */
    public enum Action {
        CRAFT_MISSING(MaterialTexts.CRAFT_MISSING), TAKE_ALL(MaterialTexts.TAKE_ALL), COPY_CODE(
                MaterialTexts.COPY_CODE);

        private final String labelKey;

        Action(String labelKey) {
            this.labelKey = labelKey;
        }

        /** The lang key of the row's label. */
        public String labelKey() {
            return labelKey;
        }
    }

    /** One listed key and the amount the list needs of it. */
    public record Entry(StackKey key, long need) {
    }

    private final RowFeed.Screen screen;
    private final List<Entry> entries = new ArrayList<>();
    private boolean cut;
    private @Nullable NetworkState bound;
    private boolean planning;
    /** A craft-missing job of this display is queued or running; cleared when it ends (done, partial or cancelled). */
    private boolean crafting;
    /** Game time of each player's last "take all" on this screen; entries past the cooldown are pruned on each take. */
    private final Map<UUID, Long> lastTake = new HashMap<>();

    public MaterialFeed(RowFeed.Screen screen) {
        this.screen = screen;
    }

    /** The entries counted, in the list's order; for tests. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    @Override
    public void bind(@Nullable NetworkState network) {
        unbind();
        bound = network;
        List<CraftingPlanner.Root> roots = OrderList.resolve(screen.host().settings().list()).roots();
        cut = roots.size() > MAX_ENTRIES;
        for (int i = 0; i < roots.size() && i < MAX_ENTRIES; i++) {
            entries.add(new Entry(roots.get(i).key(), roots.get(i).amount()));
        }
        if (network != null) {
            for (int i = 0; i < entries.size(); i++) {
                network.listenKey(entries.get(i).key(), this);
            }
            screen.rowsChanged();
        }
    }

    @Override
    public void unbind() {
        NetworkState network = bound;
        if (network != null) {
            for (int i = 0; i < entries.size(); i++) {
                network.unlistenKey(entries.get(i).key(), this);
            }
        }
        bound = null;
        entries.clear();
    }

    @Override
    public void keyChanged(StackKey key, long total) {
        screen.rowsChanged();
    }

    @Override
    public ScreenRows rows(NetworkState network, long gameTime) {
        Component title = Component.translatable(MaterialTexts.TITLE);
        if (entries.isEmpty()) {
            return new ScreenRows(title, Component.empty(), ScreenRows.NO_FILL, List.of(
                    ScreenRows.Row.of(ScreenRows.DIM, Component.translatable(MaterialTexts.EMPTY), Component.empty())));
        }
        List<ScreenRows.Row> rows = new ArrayList<>(entries.size() + Action.values().length);
        int complete = 0;
        long haveSum = 0;
        long needSum = 0;
        boolean anyMissing = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            long have = network.contents().amount(entry.key());
            if (have >= entry.need()) {
                complete++;
            } else {
                anyMissing = true;
            }
            haveSum += Math.min(have, entry.need());
            needSum += entry.need();
            rows.add(row(entry, have));
        }
        for (Action action : Action.values()) {
            int tone = action == Action.CRAFT_MISSING && !anyMissing ? ScreenRows.DIM : ScreenRows.BAND;
            String label = action == Action.CRAFT_MISSING && crafting ? MaterialTexts.CRAFTING : action.labelKey();
            rows.add(ScreenRows.Row.of(tone, Component.translatable(label), Component.empty()));
        }
        Component status = cut
                ? Component.translatable(MaterialTexts.STATUS_CUT, complete, entries.size(), MAX_ENTRIES)
                : Component.translatable(MaterialTexts.STATUS, complete, entries.size());
        return new ScreenRows(title, status, ScreenRows.perMille(haveSum, needSum), rows);
    }

    private static ScreenRows.Row row(Entry entry, long have) {
        boolean done = have >= entry.need();
        ScreenRows.Row row = ScreenRows.Row
                .of(done ? ScreenRows.GOOD : ScreenRows.WARN, PatternItem.name(entry.key()), Component.translatable(
                        MaterialTexts.HAVE_NEED, AmountFormat.compact(have), AmountFormat.compact(entry.need())))
                .withFill(ScreenRows.perMille(have, entry.need()));
        return entry.key() instanceof ItemKey item ? row.withIcon(item.item()) : row;
    }

    /** The entries' missing amounts that have a pattern on {@code network}, in order. */
    public static List<CraftingPlanner.Root> craftable(NetworkState network, List<Entry> entries) {
        List<CraftingPlanner.Root> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            long missing = entry.need() - network.contents().amount(entry.key());
            if (missing > 0 && network.patterns().isCraftable(entry.key())) {
                out.add(new CraftingPlanner.Root(entry.key(), missing));
            }
        }
        return out;
    }

    private static boolean anyMissing(NetworkState network, List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (network.contents().amount(entries.get(i).key()) < entries.get(i).need()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shown row {@code row} is clicked: an action row runs its action; a click on an entry's row is taken and does
     * nothing, so a missed aim does not open the settings. An empty list opens them.
     */
    @Override
    public boolean click(ServerPlayer player, NetworkState network, int row) {
        if (entries.isEmpty()) {
            return false;
        }
        if (row >= entries.size() && row < entries.size() + Action.values().length) {
            switch (Action.values()[row - entries.size()]) {
                case CRAFT_MISSING -> craftMissing(player, network);
                case TAKE_ALL -> takeAll(player, network);
                case COPY_CODE -> copyCode(player, network);
            }
        }
        return true;
    }

    /**
     * Moves each listed item entry from the network into the player's inventory, up to the amount the list needs and
     * only what fits, through the display terminal's take ({@link DisplayTerminal#takeInto}: one transaction per entry,
     * counting only what moved). Fluid entries are skipped. Needs {@link AccessLevel#FULL}, as taking from a display
     * does; a click within {@link #TAKE_COOLDOWN_TICKS} of that player's last one on this screen is ignored. Tells the
     * player how much moved and how much of the list is still short. Returns how many items moved.
     */
    long takeAll(ServerPlayer player, NetworkState network) {
        if (!network.accessLevel(player).atLeast(AccessLevel.FULL)) {
            tell(player, Component.translatable(MaterialTexts.TAKE_NO_ACCESS), true);
            return 0;
        }
        long now = network.level().getGameTime();
        lastTake.values().removeIf(last -> now - last >= TAKE_COOLDOWN_TICKS);
        if (lastTake.putIfAbsent(player.getUUID(), now) != null) {
            return 0;
        }
        long taken = 0;
        long shortBy = 0;
        int shortEntries = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!(entry.key() instanceof ItemKey item)) {
                continue;
            }
            long moved = DisplayTerminal.takeInto(player, network, item, entry.need());
            taken += moved;
            if (moved < entry.need()) {
                shortBy += entry.need() - moved;
                shortEntries++;
            }
        }
        tell(player,
                shortEntries == 0
                        ? Component.translatable(MaterialTexts.TAKEN, AmountFormat.compact(taken))
                        : Component.translatable(MaterialTexts.TAKEN_SHORT, AmountFormat.compact(taken),
                                AmountFormat.compact(shortBy), shortEntries),
                true);
        return taken;
    }

    /** Sends the player the list code as a chat line whose click copies it to their clipboard. */
    private void copyCode(ServerPlayer player, NetworkState network) {
        if (!network.accessLevel(player).atLeast(AccessLevel.VIEW)) {
            return;
        }
        List<CraftingPlanner.Root> roots = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            roots.add(new CraftingPlanner.Root(entries.get(i).key(), entries.get(i).need()));
        }
        Component line = Component.translatable(MaterialTexts.COPY_LINE)
                .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.CopyToClipboard(OrderList.encode(roots)))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable(MaterialTexts.COPY_TIP)))
                        .withUnderlined(true));
        tell(player, line, false);
    }

    /**
     * Plans the missing amounts of the craftable entries as one order (the order list's planner: every request crafted
     * in full against one stock) and queues the plan as the player's job. Needs {@link AccessLevel#CRAFT}, as the order
     * list does; a click while the last plan is still being made, or while the job it queued is unfinished, is refused.
     * Returns whether a plan was started.
     */
    boolean craftMissing(ServerPlayer player, NetworkState network) {
        if (!network.accessLevel(player).atLeast(AccessLevel.CRAFT)) {
            tell(player, Component.translatable(MaterialTexts.NO_ACCESS), true);
            return false;
        }
        if (planning || crafting) {
            tell(player, Component.translatable(MaterialTexts.BUSY), true);
            return false;
        }
        List<CraftingPlanner.Root> roots = craftable(network, entries);
        if (roots.isEmpty()) {
            tell(player, Component.translatable(
                    anyMissing(network, entries) ? MaterialTexts.NOTHING_CRAFTABLE : MaterialTexts.NOTHING_MISSING),
                    true);
            return false;
        }
        planning = true;
        UUID requester = player.getUUID();
        int count = roots.size();
        boolean started = CraftingPlanner.submitOrder(network, roots, plan -> {
            planning = false;
            long origin = QueueBackgroundCrafting.origin(network);
            // Set before submit: onEnd may fire inside it and must win.
            crafting = true;
            JobQueue.Request request = new JobQueue.Request(Optional.of(requester), 0, 0, state -> crafting = false);
            boolean queued = origin != Long.MIN_VALUE
                    && JobQueue.submit(network, origin, plan, request).job().isPresent();
            if (!queued) {
                crafting = false;
            }
            ServerPlayer who = network.level().getServer().getPlayerList().getPlayer(requester);
            if (who != null) {
                tell(who, Component.translatable(queued ? MaterialTexts.QUEUED : MaterialTexts.REFUSED, count), true);
            }
        });
        if (!started) {
            planning = false;
            tell(player, Component.translatable(MaterialTexts.POOL_FULL), true);
        }
        return started;
    }

    private static void tell(ServerPlayer player, Component message, boolean overlay) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            if (overlay) {
                player.sendOverlayMessage(message);
            } else {
                player.sendSystemMessage(message);
            }
        }
    }
}
