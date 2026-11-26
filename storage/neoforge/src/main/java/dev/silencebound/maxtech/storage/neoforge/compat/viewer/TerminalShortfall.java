package dev.silencebound.maxtech.storage.neoforge.compat.viewer;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingPanel;
import dev.silencebound.maxtech.storage.terminal.crafting.TerminalGrid;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What a recipe lacks, judged against what the storage terminal shows and the player carries. Shared by the recipe
 * viewer compat layers: each turns the viewer's input slots into {@link Input}s and maps the missing indices back to
 * its own slots. Keeps a per-open-list cache of the terminal's counts, so one instance belongs to one handler. Client
 * thread.
 */
public final class TerminalShortfall {
    /** One recipe input: the stacks that satisfy it (any one of them) and how many of one are needed. */
    public record Input(List<StackKey> candidates, long need) {
    }

    /**
     * The missing inputs by their index in the list given to {@link #compute}, the first missing input the network can
     * craft with its amount, and every missing craftable input as one order list.
     */
    public record Result(List<Integer> missing, @Nullable StackKey craftable, long amount,
            List<CraftingPlanner.Root> craftables) {
    }

    private @Nullable ClientSyncedList<StackKey> cachedList;
    private int cachedVersion = -1;
    private final Object2LongOpenHashMap<StackKey> stored = new Object2LongOpenHashMap<>();

    /** Inputs with no candidates count as satisfied. */
    @SuppressWarnings("ReferenceEquality") // one client list object per open menu
    public Result compute(TerminalMenu menu, Player player, List<Input> inputs) {
        ClientSyncedList<StackKey> list = menu.clientList();
        if (list != cachedList || list.version() != cachedVersion) {
            cachedList = list;
            cachedVersion = list.version();
            stored.clear();
            for (IntIterator it = list.ids().iterator(); it.hasNext();) {
                int id = it.nextInt();
                StackKey key = list.entry(id);
                if (key != null) {
                    stored.addTo(key, list.count(id));
                }
            }
        }
        Object2LongOpenHashMap<StackKey> available = new Object2LongOpenHashMap<>(stored);
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                available.addTo(ItemKey.of(stack), stack.getCount());
            }
        }
        CraftingPanel panel = menu.craftingPanel();
        if (panel != null) {
            // A crafting terminal's transfer empties its grid first, so what is in it counts as available.
            for (int cell = 0; cell < TerminalGrid.SIZE; cell++) {
                ItemStack stack = panel.grid().getItem(cell);
                if (!stack.isEmpty()) {
                    available.addTo(ItemKey.of(stack), stack.getCount());
                }
            }
        }
        List<Integer> missing = new ArrayList<>();
        StackKey craftable = null;
        Object2LongLinkedOpenHashMap<StackKey> craftables = new Object2LongLinkedOpenHashMap<>();
        for (int index = 0; index < inputs.size(); index++) {
            Input input = inputs.get(index);
            if (input.candidates().isEmpty()) {
                continue;
            }
            long need = input.need();
            boolean found = false;
            for (StackKey candidate : input.candidates()) {
                if (available.getLong(candidate) >= need) {
                    available.addTo(candidate, -need);
                    found = true;
                    break;
                }
            }
            if (found) {
                continue;
            }
            missing.add(index);
            for (StackKey candidate : input.candidates()) {
                if (menu.craftingData().isCraftable(candidate)) {
                    if (craftable == null) {
                        craftable = candidate;
                    }
                    craftables.addTo(candidate, need);
                    break;
                }
            }
        }
        List<CraftingPlanner.Root> roots = new ArrayList<>(craftables.size());
        for (Object2LongMap.Entry<StackKey> entry : craftables.object2LongEntrySet()) {
            roots.add(new CraftingPlanner.Root(entry.getKey(), entry.getLongValue()));
        }
        return new Result(missing, craftable, craftable == null ? 0 : craftables.getLong(craftable), roots);
    }
}
