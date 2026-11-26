package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.resource.MachineItemStorage;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: machine slots are NeoForge item handlers; NeoForge's handler-backed slot writes through to them.
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import org.jspecify.annotations.Nullable;

/**
 * The menu of every Storage machine: its input, catalyst, output and module slots, laid out by {@link MachineLayout},
 * and the player's inventory. Progress, energy, mode and state travel as data slots, sent only when they change and
 * only to the player who has the menu open. On the client the menu is built from the machine's kind with empty stand-in
 * storages that vanilla's slot sync fills. One button cycles the mode, another exports the machine's recipe as a
 * pattern ({@link MachineExport}).
 */
public final class StorageMachineMenu extends AbstractContainerMenu {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    private static final double RANGE_BUFFER = 4.0;
    static final int PROGRESS = 0;
    static final int DURATION = 1;
    static final int ENERGY = 2;
    static final int CAPACITY = 3;
    static final int MODE = 4;
    static final int STATE = 5;
    static final int EXPORTABLE = 6;
    private static final int DATA = 7;
    /** The button id {@link #clickMenuButton} takes for the mode. */
    public static final int CYCLE_MODE = 0;
    /** The button id that exports the machine's recipe as a pattern ({@link MachineExport}). */
    public static final int EXPORT_PATTERN = 1;

    private final @Nullable StorageMachineBlockEntity machine;
    private final MachineKind kind;
    private final ContainerData data;
    private final int machineSlots;

    private StorageMachineMenu(int containerId, Inventory inventory, MachineKind kind,
            @Nullable StorageMachineBlockEntity machine, MachineItemStorage inputs, MachineItemStorage catalysts,
            MachineItemStorage outputs, MachineItemStorage modules, ContainerData data) {
        super(ModMenus.MACHINE.get(), containerId);
        this.machine = machine;
        this.kind = kind;
        this.data = data;
        MachineLayout layout = MachineLayout.of(kind);
        for (int i = 0; i < inputs.size(); i++) {
            addSlot(new ResourceHandlerSlot(inputs, inputs::set, i, layout.inputX(i), layout.inputY(i)));
        }
        for (int i = 0; i < catalysts.size(); i++) {
            addSlot(new ResourceHandlerSlot(catalysts, catalysts::set, i, layout.catalystX(), layout.catalystY()));
        }
        for (int i = 0; i < outputs.size(); i++) {
            addSlot(new OutputSlot(outputs, i, layout.outputX(i), layout.outputY()));
        }
        for (int i = 0; i < modules.size(); i++) {
            addSlot(new ResourceHandlerSlot(modules, modules::set, i, MachineLayout.MODULE_X,
                    MachineLayout.moduleY(i)));
        }
        this.machineSlots = inputs.size() + catalysts.size() + outputs.size() + modules.size();
        addStandardInventorySlots(inventory, MachineLayout.INVENTORY_X, MachineLayout.INVENTORY_Y);
        addDataSlots(data);
    }

    /** The server's menu, reading the machine directly. */
    public static StorageMachineMenu forMachine(int containerId, Inventory inventory,
            StorageMachineBlockEntity machine) {
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case PROGRESS -> machine.progress();
                    case DURATION -> machine.duration();
                    case ENERGY -> (int) Math.min(Integer.MAX_VALUE, machine.energy().stored());
                    case CAPACITY -> (int) Math.min(Integer.MAX_VALUE, machine.energy().capacity());
                    case MODE -> machine.modeIndex();
                    case STATE -> machine.state().index();
                    case EXPORTABLE ->
                        machine instanceof ProcessingMachineBlockEntity processing && processing.hasExportRecipe()
                                ? 1
                                : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA;
            }
        };
        return new StorageMachineMenu(containerId, inventory, machine.kind(), machine, machine.inputs(),
                machine.catalysts(), machine.outputs(), machine.modules(), data);
    }

    /** The client's menu, from the kind the server sent; its storages are filled by slot sync. */
    public static StorageMachineMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        MachineKind kind = MachineKind.byId(buffer.readUtf());
        return new StorageMachineMenu(containerId, inventory, kind, null,
                new MachineItemStorage(kind.inputs(), StorageMachineMenu::ignoreChange),
                new MachineItemStorage(kind.catalysts(), StorageMachineMenu::ignoreChange),
                new MachineItemStorage(kind.outputs(), StorageMachineMenu::ignoreChange),
                new MachineItemStorage(kind.modules(), StorageMachineMenu::ignoreChange),
                new SimpleContainerData(DATA));
    }

    /** Opens {@code machine}'s GUI for {@code player}. */
    public static void open(ServerPlayer player, StorageMachineBlockEntity machine) {
        BlockPos pos = machine.getBlockPos();
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forMachine(id, inventory, machine),
                        machine.getBlockState().getBlock().getName()),
                buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeUtf(machine.kind().id());
                });
    }

    private static void ignoreChange() {
    }

    public MachineKind kind() {
        return kind;
    }

    public int progress() {
        return data.get(PROGRESS);
    }

    public int duration() {
        return data.get(DURATION);
    }

    public int energy() {
        return data.get(ENERGY);
    }

    public int capacity() {
        return data.get(CAPACITY);
    }

    public int modeIndex() {
        return data.get(MODE);
    }

    public MachineState state() {
        return MachineState.byIndex(data.get(STATE));
    }

    /** Whether the machine has a recipe the export button can write; the screen greys the button otherwise. */
    public boolean exportable() {
        return data.get(EXPORTABLE) != 0;
    }

    /** Whether {@code slot} is one of the machine's upgrade module slots. */
    public boolean isModuleSlot(Slot slot) {
        return slot.index >= kind.inputs() + kind.catalysts() + kind.outputs() && slot.index < machineSlots;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        StorageMachineBlockEntity current = machine;
        if (id == CYCLE_MODE && current != null && !kind.modes().isEmpty()) {
            current.cycleMode();
            return true;
        }
        if (id == EXPORT_PATTERN && current instanceof ProcessingMachineBlockEntity processing
                && player instanceof ServerPlayer serverPlayer && stillValid(player)) {
            MachineExport.Result result = MachineExport.export(serverPlayer, processing);
            // A GameTest player has no connection to send the message to.
            if (serverPlayer.connection != null) {
                serverPlayer.sendOverlayMessage(Component.translatable(MachineTexts.exportKey(result)));
            }
            return result == MachineExport.Result.EXPORTED;
        }
        return false;
    }

    /**
     * Machine slots go to the inventory; inventory stacks go into the module slot, the catalyst slot or the inputs,
     * whichever takes them.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int inventoryEnd = slots.size();
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, inventoryEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            int moduleStart = kind.inputs() + kind.catalysts() + kind.outputs();
            boolean moved = MachineModuleItem.of(stack) != null
                    ? moveItemStackTo(stack, moduleStart, machineSlots, false)
                    : moveItemStackTo(stack, 0, kind.inputs() + kind.catalysts(), false);
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        StorageMachineBlockEntity current = machine;
        return current == null
                || (!current.isRemoved() && player.isWithinBlockInteractionRange(current.getBlockPos(), RANGE_BUFFER));
    }

    /** A slot the player can only take from. */
    private static final class OutputSlot extends ResourceHandlerSlot {
        OutputSlot(MachineItemStorage outputs, int index, int x, int y) {
            super(outputs, outputs::set, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
