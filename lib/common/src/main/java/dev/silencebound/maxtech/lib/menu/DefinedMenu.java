package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.LayoutPlan;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuItems;
import dev.silencebound.maxtech.lib.api.menu.MenuSlots;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The menu of a {@link MenuDefinition}, on both sides: the definition's slot groups at the places its layout gives them
 * (in declaration order, then the player's inventory), its shift-click order and its tanks' bucket clicks. Slot
 * positions come from {@link MenuDefinition#plan()}, pure arithmetic, so they are the same on the server and the
 * client. A group the layout lacks (a problem in dev runs) sits far off the panel.
 */
public final class DefinedMenu<H> extends ToolkitMenu<H> {
    /** Where slots of a group the layout lacks go: off any screen. */
    private static final int NOWHERE = -10_000;

    private final MenuDefinition<H> definition;
    private final @Nullable H host;
    private final int[] tankButtons;

    /** The server menu over {@code host}, or with a null host the client menu over empty copies of the slots. */
    public DefinedMenu(MenuDefinition<H> definition, int containerId, Inventory inventory, @Nullable H host) {
        super(definition.type().get(), containerId, inventory.player, definition.values(), host);
        this.definition = definition;
        this.host = host;
        List<MenuItems<H>> items = definition.items();
        ItemSlots[] inventories = new ItemSlots[items.size()];
        for (int i = 0; i < inventories.length; i++) {
            MenuItems<H> handle = items.get(i);
            ItemSlots slots = host != null ? handle.get(host) : handle.shape().build(slot -> {
            });
            if (slots.size() != handle.size()) {
                throw new IllegalArgumentException("Inventory " + i + " of menu " + definition.name() + " has "
                        + slots.size() + " slots, its shape " + handle.size());
            }
            inventories[i] = slots;
        }
        IdentityHashMap<MenuSlots, LayoutPlan.Box> placed = new IdentityHashMap<>();
        LayoutPlan.@Nullable Box playerBox = null;
        for (LayoutPlan.Box box : definition.plan().boxes()) {
            if (box.node() instanceof Layout.Slots slots) {
                placed.putIfAbsent(slots.group(), box);
            } else if (box.node() instanceof Layout.PlayerInventory && playerBox == null) {
                playerBox = box;
            }
        }
        List<MenuSlots> groups = definition.slots();
        SlotGroup[] added = new SlotGroup[groups.size()];
        for (MenuSlots group : groups) {
            LayoutPlan.Box box = placed.get(group);
            int x = box != null ? box.x() + 1 : NOWHERE;
            int y = box != null ? box.y() + 1 : NOWHERE;
            int columns = box != null ? ((Layout.Slots) box.node()).columns() : group.size();
            added[group.index()] = addSlots(inventories[group.items().index()], group.from(), group.to(), x, y,
                    columns);
        }
        if (playerBox != null) {
            addPlayerInventory(inventory, playerBox.x() + 1, playerBox.y() + 1);
        }
        List<MenuSlots> order = definition.shiftOrder();
        SlotGroup[] shift = new SlotGroup[order.size()];
        for (int i = 0; i < shift.length; i++) {
            shift[i] = added[order.get(i).index()];
        }
        shiftClick(shift);
        List<MenuDefinition.Tank<H>> tanks = definition.tanks();
        tankButtons = new int[tanks.size()];
        for (int i = 0; i < tankButtons.length; i++) {
            tankButtons[i] = addTank(host != null ? tanks.get(i).tank().apply(host) : null);
        }
    }

    /** The definition this menu was built from. */
    public MenuDefinition<H> definition() {
        return definition;
    }

    /** The {@code clickMenuButton} id of {@code tank}'s bucket clicks, or -1 for a tank of another definition. */
    public int tankButton(TankValue tank) {
        List<MenuDefinition.Tank<H>> tanks = definition.tanks();
        for (int i = 0; i < tanks.size(); i++) {
            if (tanks.get(i).value() == tank) {
                return tankButtons[i];
            }
        }
        return -1;
    }

    @Override
    public boolean stillValid(Player player) {
        H source = host;
        if (source == null) {
            return true;
        }
        BiPredicate<? super H, Player> test = definition.validWhile().orElse(null);
        if (test != null) {
            return test.test(source, player);
        }
        return !(source instanceof BlockEntity blockEntity) || Container.stillValidBlockEntity(blockEntity, player);
    }
}
