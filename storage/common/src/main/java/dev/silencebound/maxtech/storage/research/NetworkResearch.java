package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageDevice;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.NetworkObserver;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Research's view of one network (PRD 7.2: research progress is tracked incrementally from the change stream, never by
 * recounting). It keeps running sums of the items stored (in all, and per filtered store task) from the committed
 * deltas, reads the kind count and device counts the network keeps anyway, and reports a level to the owner's team only
 * when it rises above what this network already reported, so a busy network costs a few additions and comparisons per
 * change. Discovery is checked only when a key enters the network. Server thread.
 */
final class NetworkResearch implements NetworkObserver {
    private final NetworkState network;
    private final MinecraftServer server;
    private final ResearchTree tree;
    private final int storeItems;
    private final int storeKinds;
    private final int cellFill;
    private final int drivesRunning;
    private final int portsThroughput;
    /** Filtered store tasks: their counters, items or tags, and this network's sums. */
    private final int[] filterCounters;
    private final @Nullable Item[] filterItems;
    private final @Nullable TagKey<Item>[] filterTags;
    private final long[] filtered;
    private final long[] reportedFiltered;
    private long items;
    private long reportedItems;
    private long reportedKinds;
    private long reportedDrives;
    private long reportedFill;
    private @Nullable ResearchData data;
    private @Nullable ResearchTeam team;
    private @Nullable UUID teamOwner;
    private int teamVersion = -1;

    // Arrays of a generic type are created raw; they hold only TagKey<Item>s.
    @SuppressWarnings("unchecked")
    NetworkResearch(NetworkState network) {
        this.network = network;
        this.server = network.level().getServer();
        this.tree = ResearchTree.of(server.registryAccess());
        this.storeItems = tree.counterIndex(TaskType.STORE_ITEMS.getSerializedName());
        this.storeKinds = tree.counterIndex(TaskType.STORE_KINDS.getSerializedName());
        this.cellFill = tree.counterIndex(TaskType.CELL_FILL.getSerializedName());
        this.drivesRunning = tree.counterIndex(TaskType.DRIVES_RUNNING.getSerializedName());
        this.portsThroughput = tree.counterIndex(TaskType.PORTS_THROUGHPUT.getSerializedName());
        List<ResearchTree.Counter> counters = tree.counters();
        int count = 0;
        for (ResearchTree.Counter counter : counters) {
            if (filteredStore(counter)) {
                count++;
            }
        }
        filterCounters = new int[count];
        filterItems = new Item[count];
        filterTags = new TagKey[count];
        filtered = new long[count];
        reportedFiltered = new long[count];
        int at = 0;
        for (int i = 0; i < counters.size(); i++) {
            ResearchTree.Counter counter = counters.get(i);
            if (filteredStore(counter)) {
                filterCounters[at] = i;
                ResearchOption.Task task = counter.task();
                Identifier item = task.item().orElse(null);
                filterItems[at] = item == null ? null : BuiltInRegistries.ITEM.getValue(item);
                filterTags[at] = task.tag().orElse(null);
                at++;
            }
        }
    }

    private static boolean filteredStore(ResearchTree.Counter counter) {
        ResearchOption.Task task = counter.task();
        return task.task() == TaskType.STORE_ITEMS && (task.item().isPresent() || task.tag().isPresent());
    }

    @Override
    public void keyChanged(StackKey key, long total, long delta) {
        if (key instanceof ItemKey item) {
            items += delta;
            for (int i = 0; i < filterCounters.length; i++) {
                if (matches(i, item.item())) {
                    filtered[i] += delta;
                }
            }
        }
        if (delta <= 0 || !Research.active()) {
            return;
        }
        ResearchTeam current = team();
        if (current == null) {
            return;
        }
        if (total == delta) {
            Research.entered(server, current, key);
        }
        if (storeItems >= 0 && items > reportedItems) {
            reportedItems = items;
            Research.count(server, current, storeItems, items);
        }
        long kinds = network.contents().size();
        if (storeKinds >= 0 && kinds > reportedKinds) {
            reportedKinds = kinds;
            Research.count(server, current, storeKinds, kinds);
        }
        for (int i = 0; i < filterCounters.length; i++) {
            if (filtered[i] > reportedFiltered[i]) {
                reportedFiltered[i] = filtered[i];
                Research.count(server, current, filterCounters[i], filtered[i]);
            }
        }
    }

    private boolean matches(int filter, Item item) {
        Item wanted = filterItems[filter];
        if (wanted != null) {
            return wanted.equals(item);
        }
        TagKey<Item> tag = filterTags[filter];
        return tag != null && item.builtInRegistryHolder().is(tag);
    }

    @Override
    public void devicesChanged() {
        if (drivesRunning < 0 || !Research.active()) {
            return;
        }
        long drives = network.status() == StorageNetwork.Status.ONLINE && network.isPowered()
                ? network.deviceCount(StorageDevice.Role.STORAGE)
                : 0;
        ResearchTeam current = team();
        if (current != null && drives > reportedDrives) {
            reportedDrives = drives;
            Research.count(server, current, drivesRunning, drives);
        }
    }

    @Override
    public void transferred(StackKey key, long amount) {
        if (portsThroughput < 0 || key.kind() != StackKey.Kind.ITEM || !Research.active()) {
            return;
        }
        ResearchTeam current = team();
        if (current != null) {
            Research.count(server, current, portsThroughput, amount);
        }
    }

    @Override
    public void cellFilled(int percent) {
        if (cellFill < 0 || percent <= reportedFill || !Research.active()) {
            return;
        }
        ResearchTeam current = team();
        if (current != null) {
            reportedFill = percent;
            Research.count(server, current, cellFill, percent);
        }
    }

    /**
     * The owner's team, looked up again when the owner or any team membership changed; the levels this network reported
     * start over then, since they were reported to another team.
     */
    private @Nullable ResearchTeam team() {
        OwnerRecord owner = network.owner();
        if (!owner.isOwned()) {
            return null;
        }
        ResearchData current = data;
        if (current == null) {
            current = Research.data(server);
            data = current;
        }
        if (team == null || teamVersion != current.version() || !owner.owner().equals(teamOwner)) {
            ResearchTeam found = current.team(server, owner);
            if (found != team) {
                reportedItems = 0;
                reportedKinds = 0;
                reportedDrives = 0;
                reportedFill = 0;
                java.util.Arrays.fill(reportedFiltered, 0);
            }
            team = found;
            teamOwner = owner.owner();
            teamVersion = current.version();
        }
        return team;
    }
}
