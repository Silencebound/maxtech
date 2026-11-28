package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.menu.CycleAction;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.LayoutPlan;
import dev.silencebound.maxtech.lib.api.menu.MenuChoice;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuItems;
import dev.silencebound.maxtech.lib.api.menu.MenuSlots;
import dev.silencebound.maxtech.lib.api.menu.PortValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The menu of a {@link MenuDefinition}, on both sides: the definition's slot groups at the places its layout gives them
 * (in declaration order, then the player's inventory), its shift-click order and its tanks' bucket clicks. Slot
 * positions come from {@link MenuDefinition#plan()}, pure arithmetic, so they are the same on the server and the
 * client. A group the layout lacks (a problem in dev runs) sits far off the panel. Button ids: fluid tank {@code n} of
 * the definition (declaration order, counting only tanks that take bucket clicks) is {@link ToolkitMenu#TANK_BUTTONS}
 * plus n; choice {@code c} (the constant's ordinal) of cycle action {@code a} is {@link ToolkitMenu#ACTION_BUTTONS}
 * plus {@code a * }{@link CycleAction#MAX_CHOICES}{@code  + c}. Mode {@code m} ({@link PortFaceMode#id()}) of face
 * {@code f} ({@code get3DDataValue}) of kind {@code k} (its position in the value's kinds) of port value {@code p} is
 * {@link ToolkitMenu#PORT_BUTTONS} plus {@code ((p * }{@link PortValue#MAX_KINDS}{@code  + k) * 6 + f) * 3 + m}. Any
 * other id, an ordinal past the enum's constants, or a mode the port does not allow for the kind does nothing.
 */
public final class DefinedMenu<H> extends ToolkitMenu<H> {
    /** Where slots of a group the layout lacks go: off any screen. */
    private static final int NOWHERE = -10_000;

    private final MenuDefinition<H> definition;
    private final @Nullable H host;
    private static final int FACES = 6;
    private static final int MODES = 3;

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
        List<MenuDefinition.Tank<H, ?>> tanks = definition.tanks();
        tankButtons = new int[tanks.size()];
        for (int i = 0; i < tankButtons.length; i++) {
            MenuDefinition.Tank<H, ?> tank = tanks.get(i);
            if (!tank.buckets()) {
                tankButtons[i] = -1;
                continue;
            }
            // Only Builder.tank(Function) sets buckets, and its getter returns a FluidTank.
            ResourceTank<?> held = host != null ? tank.tank().apply(host) : null;
            tankButtons[i] = addTank((FluidTank) held);
        }
    }

    /** The definition this menu was built from. */
    public MenuDefinition<H> definition() {
        return definition;
    }

    /**
     * The {@code clickMenuButton} id of {@code tank}'s bucket clicks, or -1 for a tank that takes none (not a fluid
     * tank) or of another definition.
     */
    public int tankButton(TankValue tank) {
        List<MenuDefinition.Tank<H, ?>> tanks = definition.tanks();
        for (int i = 0; i < tanks.size(); i++) {
            if (tanks.get(i).value() == tank) {
                return tankButtons[i];
            }
        }
        return -1;
    }

    /**
     * The {@code clickMenuButton} id that asks for {@code choice} of {@code action}, or -1 for another definition's.
     */
    @SuppressWarnings("EnumOrdinal") // the ordinal only travels to a server of the same build
    public <E extends Enum<E> & MenuChoice> int cycleButton(CycleAction<E> action, E choice) {
        List<MenuDefinition.Cycle<H, ?>> cycles = definition.cycles();
        int index = action.index();
        if (index >= cycles.size() || cycles.get(index).action() != action) {
            return -1;
        }
        return ACTION_BUTTONS + index * CycleAction.MAX_CHOICES + choice.ordinal();
    }

    /**
     * The {@code clickMenuButton} id that asks for {@code mode} on {@code face} of {@code kind} of {@code port}, or -1
     * for another definition's port value or a kind it does not show.
     */
    public int faceButton(PortValue port, ResourceKind<?> kind, Direction face, PortFaceMode mode) {
        List<MenuDefinition.Port<H>> ports = definition.ports();
        int index = port.index();
        int kindIndex = port.kindIndex(kind);
        if (index >= ports.size() || ports.get(index).value() != port || kindIndex < 0) {
            return -1;
        }
        return PORT_BUTTONS + ((index * PortValue.MAX_KINDS + kindIndex) * FACES + face.get3DDataValue()) * MODES
                + mode.id();
    }

    /**
     * A cycle button (see the class comment) hands its choice to the definition's setter on the server; tank buttons go
     * to {@link ToolkitMenu}. The client menu has no host and ignores them.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id < ACTION_BUTTONS) {
            return super.clickMenuButton(player, id);
        }
        H source = host;
        if (id >= PORT_BUTTONS) {
            return source != null && clickFace(source, id - PORT_BUTTONS);
        }
        int offset = id - ACTION_BUTTONS;
        int index = offset / CycleAction.MAX_CHOICES;
        List<MenuDefinition.Cycle<H, ?>> cycles = definition.cycles();
        if (source == null || index >= cycles.size()) {
            return false;
        }
        return cycles.get(index).apply(source, offset % CycleAction.MAX_CHOICES);
    }

    private boolean clickFace(H source, int offset) {
        int mode = offset % MODES;
        int face = offset / MODES % FACES;
        int kind = offset / (MODES * FACES) % PortValue.MAX_KINDS;
        int index = offset / (MODES * FACES * PortValue.MAX_KINDS);
        List<MenuDefinition.Port<H>> ports = definition.ports();
        return index < ports.size() && ports.get(index).apply(source, kind, face, mode);
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
