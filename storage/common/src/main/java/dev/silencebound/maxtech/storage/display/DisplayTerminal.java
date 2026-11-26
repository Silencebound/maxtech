package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: every move between the network and the player is a NeoForge transfer transaction (PRD 7.2).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.PlayerInventoryWrapper;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * A display that shows one item's count works as a small terminal (M7C item 13): right-click with that item stores the
 * held stack, a second right-click within {@link #DOUBLE_CLICK_TICKS} stores every matching stack from the inventory
 * (as AE2's terminals do), a left-click takes one and a sneaking left-click takes a stack. Only for a screen whose
 * source is {@link DisplaySourceKind#ITEM_COUNT} with an item (not a fluid); every other screen keeps its usual clicks.
 *
 * <p>
 * Server side: storing needs {@link AccessLevel#DEPOSIT} on the display's network and taking {@link AccessLevel#FULL},
 * as in a terminal, and the player must be within block interaction range. Each move is one root transaction: the
 * network side first, then the player's inventory side for exactly that amount, both rolled back unless they match, so
 * a full network or a full inventory moves nothing and loses nothing. A store or take within {@link #COOLDOWN_TICKS} of
 * the same player's last one is ignored, so one click never fires twice. A left-click in creative mode breaks the block
 * instead (vanilla does not call {@code attack} there), so taking is for survival and adventure players.
 */
public final class DisplayTerminal {
    /** Ticks after a store or take in which the same player's next one of that kind is ignored. */
    public static final int COOLDOWN_TICKS = 3;
    /** Ticks after a store in which an empty-handed right-click on the same display stores all matching items. */
    public static final int DOUBLE_CLICK_TICKS = 10;

    /** Each player's last store and take; server thread only, one small entry per player. */
    private static final Map<UUID, Recent> RECENT = new Object2ObjectOpenHashMap<>();

    private DisplayTerminal() {
    }

    /** The item a screen with {@code settings} shows on its own, or null when it shows anything else. */
    public static @Nullable ItemKey item(DisplaySettings settings) {
        return settings.source() == DisplaySourceKind.ITEM_COUNT && settings.item().isPresent()
                && settings.item().get() instanceof ItemKey key ? key : null;
    }

    /** Whether {@code stack} is the item a screen with {@code settings} shows; both sides. */
    public static boolean shows(DisplaySettings settings, ItemStack stack) {
        ItemKey key = item(settings);
        return key != null && !stack.isEmpty() && StackKeys.of(ItemResource.of(stack)).equals(key);
    }

    /** What {@link #store} returns for a click ignored by the cooldown: the click counts as handled. */
    public static final long IGNORED = -1;

    /**
     * Stores the stack in {@code hand} when the display shows that item. Returns how much moved; 0 when the display
     * shows another item or none, the player may not deposit or is out of reach or the network is full (the click then
     * falls through to the screen's settings); {@link #IGNORED} within the cooldown. Only a store that moved something
     * arms the double-click. Server side.
     */
    public static long store(Player player, InteractionHand hand, NetworkDevice device, DisplaySettings settings,
            BlockPos pos) {
        ItemStack held = player.getItemInHand(hand);
        NetworkState network = usable(player, device, settings, pos, AccessLevel.DEPOSIT);
        if (network == null || !shows(settings, held)) {
            return 0;
        }
        Recent recent = recent(player);
        long now = player.level().getGameTime();
        if (now - recent.store < COOLDOWN_TICKS) {
            return IGNORED;
        }
        ItemKey key = StackKeys.of(ItemResource.of(held));
        long moved = storeFrom(PlayerInventoryWrapper.of(player).getHandSlot(hand), 0, key, network, player);
        recent.store = now;
        recent.storePos = moved > 0 ? pos.asLong() : Long.MIN_VALUE;
        return moved;
    }

    /**
     * An empty-handed right-click: right after a store on this display (within {@link #DOUBLE_CLICK_TICKS}), stores
     * every stack of the shown item from the player's main inventory and returns true; otherwise does nothing and
     * returns false, so the click opens the screen's settings as before. Server side.
     */
    public static boolean storeAll(Player player, NetworkDevice device, DisplaySettings settings, BlockPos pos) {
        Recent recent = RECENT.get(player.getUUID());
        long now = player.level().getGameTime();
        if (recent == null || recent.storePos != pos.asLong() || now - recent.store > DOUBLE_CLICK_TICKS) {
            return false;
        }
        // One double-click per store.
        recent.storePos = Long.MIN_VALUE;
        NetworkState network = usable(player, device, settings, pos, AccessLevel.DEPOSIT);
        ItemKey key = item(settings);
        if (network == null || key == null) {
            return true;
        }
        ResourceHandler<ItemResource> slots = PlayerInventoryWrapper.of(player).getMainSlots();
        ItemResource resource = StackKeys.toResource(key);
        for (int i = 0; i < slots.size(); i++) {
            if (slots.getResource(i).equals(resource) && storeFrom(slots, i, key, network, player) <= 0) {
                // The network is full: the other stacks would not fit either.
                break;
            }
        }
        return true;
    }

    /**
     * Takes one of the shown item, or a stack of it when {@code stack}, into the player's inventory. Returns how much
     * moved; 0 when the display shows no item, the player lacks full access or is out of reach, the network holds none,
     * the inventory is full or within the cooldown. Server side.
     */
    public static long take(Player player, NetworkDevice device, DisplaySettings settings, BlockPos pos,
            boolean stack) {
        NetworkState network = usable(player, device, settings, pos, AccessLevel.FULL);
        ItemKey key = item(settings);
        if (network == null || key == null) {
            return 0;
        }
        Recent recent = recent(player);
        long now = player.level().getGameTime();
        if (now - recent.take < COOLDOWN_TICKS) {
            return 0;
        }
        recent.take = now;
        return takeInto(player, network, key, stack ? StackKeys.toResource(key).getMaxStackSize() : 1);
    }

    /**
     * Moves up to {@code want} of {@code key} from {@code network} into the player's inventory in one root transaction
     * and returns how much moved: the inventory's room is simulated first, the network gives at most that, and the
     * inventory must take exactly what the network gave or both sides roll back. Nothing is checked about access, reach
     * or cooldowns; the caller does that. Server side.
     */
    public static long takeInto(Player player, NetworkState network, ItemKey key, long want) {
        if (want <= 0) {
            return 0;
        }
        ItemResource resource = StackKeys.toResource(key);
        PlayerInventoryWrapper inventory = PlayerInventoryWrapper.of(player);
        try (Transaction root = Transaction.openRoot()) {
            int room;
            try (Transaction simulation = Transaction.open(root)) {
                room = inventory.insert(resource, (int) Math.min(want, Integer.MAX_VALUE), simulation);
            }
            if (room <= 0) {
                return 0;
            }
            long taken = network.extract(key, room, root, player);
            if (taken <= 0 || inventory.insert(resource, (int) taken, root) != taken) {
                return 0;
            }
            root.commit();
            return taken;
        }
    }

    /** Moves what the network takes of slot {@code index}'s stack into it; the slot then gives exactly that. */
    private static long storeFrom(ResourceHandler<ItemResource> slots, int index, ItemKey key, NetworkState network,
            Player player) {
        ItemResource resource = slots.getResource(index);
        int amount = slots.getAmountAsInt(index);
        if (resource.isEmpty() || amount <= 0) {
            return 0;
        }
        try (Transaction root = Transaction.openRoot()) {
            long stored = network.insert(key, amount, root, player);
            if (stored <= 0 || slots.extract(index, resource, (int) stored, root) != stored) {
                return 0;
            }
            root.commit();
            return stored;
        }
    }

    /**
     * The display's network when the screen shows an item, the player is within reach and has {@code level} there; null
     * otherwise.
     */
    private static @Nullable NetworkState usable(Player player, NetworkDevice device, DisplaySettings settings,
            BlockPos pos, AccessLevel level) {
        NetworkState network = device.deviceLink().network();
        if (network == null || item(settings) == null || !player.isWithinBlockInteractionRange(pos, 1.0)
                || !network.accessLevel(player).atLeast(level)) {
            return null;
        }
        return network;
    }

    /** Forgets {@code player}'s clicks; the loader calls it when the player logs out. Server thread. */
    public static void forget(UUID player) {
        RECENT.remove(player);
    }

    /** Whether {@code player}'s clicks are remembered; for tests. */
    public static boolean remembers(UUID player) {
        return RECENT.containsKey(player);
    }

    private static Recent recent(Player player) {
        Recent recent = RECENT.get(player.getUUID());
        if (recent == null) {
            recent = new Recent();
            RECENT.put(player.getUUID(), recent);
        }
        return recent;
    }

    /** A player's last store (time and display) and take; game time, {@code Long.MIN_VALUE / 2} for never. */
    private static final class Recent {
        long store = Long.MIN_VALUE / 2;
        long storePos = Long.MIN_VALUE;
        long take = Long.MIN_VALUE / 2;
    }
}
