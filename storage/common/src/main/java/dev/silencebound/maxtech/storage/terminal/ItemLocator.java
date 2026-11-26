package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The terminal's locate action (M7C item 4): lists which blocks of the network hold a key, with the cell slot for a
 * drive and the amount each holds, largest first, as chat lines, and outlines those blocks through walls with the same
 * highlight as the controller's Locate button. Storage seen through a subnet controller is found in the subnet's own
 * drives. Amounts are what the network's index was told, i.e. committed amounts. A player's click, never per tick;
 * server thread. Access checks are the caller's.
 */
public final class ItemLocator {
    /** Chat lines listed at most; the rest are counted in one line. A bound on the message, not a balance value. */
    static final int MAX_LINES = 8;
    /** Blocks outlined at most. A bound on the highlight packet, not a balance value. */
    static final int MAX_OUTLINES = 64;
    /** Ticks the outlines stay, as long as the controller's Locate. */
    public static final int LOCATE_TICKS = 200;

    public static final String FOUND_KEY = key("found");
    public static final String NONE_KEY = key("none");
    public static final String LINE_KEY = key("line");
    public static final String CELL_LINE_KEY = key("cell_line");
    public static final String LABELLED_KEY = key("labelled");
    public static final String MORE_KEY = key("more");
    public static final String STORAGE_KEY = key("storage");

    static {
        ModContent.CONTENT.translation(FOUND_KEY, "%s: %s stored in %s places, outlined for 10 seconds");
        ModContent.CONTENT.translation(NONE_KEY, "%s: not found in the network's storage");
        ModContent.CONTENT.translation(LINE_KEY, "  %s at %s: %s");
        ModContent.CONTENT.translation(CELL_LINE_KEY, "  %s at %s, cell %s: %s");
        ModContent.CONTENT.translation(LABELLED_KEY, "%s \"%s\"");
        ModContent.CONTENT.translation(MORE_KEY, "  and %s more places");
        ModContent.CONTENT.translation(STORAGE_KEY, "Storage");
    }

    /** One block holding the key: its position, the drive slot (-1 for none) and the amount. */
    public record Place(BlockPos pos, int slot, long amount, Component name) {
    }

    private ItemLocator() {
    }

    /** Loads the class so its lang keys are queued; during mod construction. */
    static void init() {
    }

    /** Every place of {@code network} that holds {@code key}, largest amount first; empty when none does. */
    public static List<Place> places(NetworkState network, StackKey key) {
        List<Place> places = new ArrayList<>();
        network.forEachHolder(key, (owner, device, provider, amount) -> places
                .add(new Place(BlockPos.of(device.blockPos()), slot(device, provider), amount, name(device))));
        places.sort(Comparator.comparingLong(Place::amount).reversed());
        return places;
    }

    /**
     * Tells {@code player} where {@code key} is stored in {@code network} (chat lines, {@link #lines}) and outlines
     * those blocks; returns how many places hold it.
     */
    public static int locate(ServerPlayer player, NetworkState network, StackKey key, Component keyName) {
        List<Place> places = places(network, key);
        List<Component> lines = lines(places, keyName);
        // A player with no connection (a GameTest's) cannot receive chat.
        if (player.connection != null) {
            for (Component line : lines) {
                player.sendSystemMessage(line);
            }
        }
        if (!places.isEmpty()) {
            int outlined = Math.min(places.size(), MAX_OUTLINES);
            BlockPos[] positions = new BlockPos[outlined];
            for (int i = 0; i < outlined; i++) {
                positions[i] = places.get(i).pos();
            }
            Highlights.locate(player, LOCATE_TICKS, positions);
        }
        return places.size();
    }

    /**
     * The chat lines for {@code places} of the key named {@code keyName}: a total, then one line per place (at most
     * {@link #MAX_LINES}, the rest counted), or one line saying it is nowhere.
     */
    public static List<Component> lines(List<Place> places, Component keyName) {
        if (places.isEmpty()) {
            return List.of(Component.translatable(NONE_KEY, keyName));
        }
        long total = 0;
        for (Place place : places) {
            total += place.amount();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(FOUND_KEY, keyName, AmountFormat.full(total), places.size()));
        int shown = Math.min(places.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            Place place = places.get(i);
            String at = place.pos().getX() + ", " + place.pos().getY() + ", " + place.pos().getZ();
            lines.add(place.slot() >= 0
                    ? Component.translatable(CELL_LINE_KEY, place.name(), at, place.slot() + 1,
                            AmountFormat.full(place.amount()))
                    : Component.translatable(LINE_KEY, place.name(), at, AmountFormat.full(place.amount())));
        }
        if (places.size() > shown) {
            lines.add(Component.translatable(MORE_KEY, places.size() - shown));
        }
        return lines;
    }

    /** The drive slot {@code provider} is mounted in; -1 for a provider that is no drive's cell. */
    @SuppressWarnings("ReferenceEquality") // a mounted cell is one object
    private static int slot(NetworkDevice device, StorageProvider provider) {
        if (device instanceof DriveBlockEntity drive) {
            for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
                if (drive.cell(slot) == provider) {
                    return slot;
                }
            }
        }
        return -1;
    }

    /** The device's block name, read from its block entity (never from the level, which could load a chunk). */
    private static Component name(NetworkDevice device) {
        MutableComponent name = device instanceof BlockEntity entity
                ? entity.getBlockState().getBlock().getName()
                : Component.translatable(STORAGE_KEY);
        return device.label().isEmpty() ? name : Component.translatable(LABELLED_KEY, name, device.label().text());
    }

    private static String key(String name) {
        return "message." + MaxtechStorage.MOD_ID + ".locate." + name;
    }
}
