package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuItems;
import dev.silencebound.maxtech.lib.api.menu.MenuSlots;
import dev.silencebound.maxtech.lib.api.menu.MenuStatus;
import dev.silencebound.maxtech.lib.api.menu.Menus;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import dev.silencebound.maxtech.lib.api.schedule.TaskSchedulers;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.neoforge.api.inventory.Handlers;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jspecify.annotations.Nullable;

/**
 * The toolkit demo machine (GameTest source set, so dev runs only, never the jar), built on Lib's public api alone: a
 * block with a filtered input, a fuel slot and a take-only 3x3 output over {@link ItemSlots}, a fluid tank with bucket
 * clicks, an MU buffer, an arrow and a flame, an enum status line, a live number and a mint {@link AccentTheme}. Right
 * click opens it; {@code /maxtech_lib_demo toolkit} places one ({@link ToolkitDemoCommand}). One task in a group of the
 * level moves the values every few ticks so the bars visibly run; nothing ticks.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ToolkitDemo {
    static final int ARROW_TICKS = 100;
    static final int BURN_TICKS = 200;
    static final int STEP = 5;
    static final long TANK_CAPACITY = 4000;
    static final long ENERGY_CAPACITY = 10_000;
    static final int INPUT_SLOT = 0;
    static final int FUEL_SLOT = 1;
    static final int FIRST_OUTPUT = 2;
    static final int SIZE = 11;

    /** The mint look of a mod with its own accent, as Farms will have. */
    static final AccentTheme MINT = new AccentTheme(0xFF2FA37B, 0xFF1D6B52, 0xFF6FE0B5, 0xFF2A7D8C, 0xFFB5F0DA,
            0xFF6FE0B5);

    /** 0: raw ore input. 1: fuel. 2 to 10: output, taken from only. */
    static final ItemSlots.Builder SLOTS = ItemSlots.builder(SIZE)
            .input(INPUT_SLOT, 1,
                    stack -> stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD))
            .input(FUEL_SLOT, 2, stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL)).output(FIRST_OUTPUT, SIZE);

    private static final Registrar<Block> BLOCKS = Registrars.create(MaxtechLib.MOD_ID, Registries.BLOCK);
    static final RegistryEntry<Block, MachineBlock> BLOCK = BLOCKS.register("test_toolkit_machine",
            id -> new MachineBlock(
                    BlockBehaviour.Properties.of().strength(1.5F).setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final Registrar<BlockEntityType<?>> ENTITIES = Registrars.create(MaxtechLib.MOD_ID,
            Registries.BLOCK_ENTITY_TYPE);
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<MachineEntity>> ENTITY = ENTITIES
            .register("test_toolkit_machine", () -> new BlockEntityType<>(MachineEntity::new, Set.of(BLOCK.get())));

    /** What the status line says. */
    enum Status implements MenuStatus {
        IDLE("maxtech_lib_demo.status.idle", Tone.OFF), WORKING("maxtech_lib_demo.status.working", Tone.OK), NO_FUEL(
                "maxtech_lib_demo.status.no_fuel", Tone.WARN), FULL("maxtech_lib_demo.status.full", Tone.ERROR);

        private final String key;
        private final Tone tone;

        Status(String key, Tone tone) {
            this.key = key;
            this.tone = tone;
        }

        @Override
        public String translationKey() {
            return key;
        }

        @Override
        public Tone tone() {
            return tone;
        }
    }

    /** Handles the layout names, kept for tests. */
    static final class Handles {
        final MenuSlots input;
        final MenuSlots fuel;
        final MenuSlots output;
        final TankValue tank;
        final EnergyValue energy;
        final ProgressValue arrow;
        final ProgressValue flame;
        final EnumValue<Status> status;
        final IntValue cycles;

        Handles(MenuSlots input, MenuSlots fuel, MenuSlots output, TankValue tank, EnergyValue energy,
                ProgressValue arrow, ProgressValue flame, EnumValue<Status> status, IntValue cycles) {
            this.input = input;
            this.fuel = fuel;
            this.output = output;
            this.tank = tank;
            this.energy = energy;
            this.arrow = arrow;
            this.flame = flame;
            this.status = status;
            this.cycles = cycles;
        }
    }

    private static @Nullable Handles handles;
    static final MenuDefinition<MachineEntity> DEFINITION = Menus.define(MaxtechLib.MOD_ID, "test_toolkit_machine",
            MachineEntity.class, m -> {
                MenuItems<MachineEntity> items = m.items(host -> host.slots, SLOTS);
                MenuSlots input = m.slots(items, INPUT_SLOT, FUEL_SLOT);
                MenuSlots fuel = m.slots(items, FUEL_SLOT, FIRST_OUTPUT);
                MenuSlots output = m.slots(items, FIRST_OUTPUT, SIZE);
                TankValue tank = m.tank(host -> host.tank);
                EnergyValue energy = m.energy(host -> host.energy);
                ProgressValue arrow = m.progress(host -> host.progress, host -> ARROW_TICKS);
                ProgressValue flame = m.progress(host -> host.burn, host -> BURN_TICKS);
                EnumValue<Status> status = m.enumValue(Status.class, MachineEntity::status);
                IntValue cycles = m.intValue(host -> host.cycles);
                handles = new Handles(input, fuel, output, tank, energy, arrow, flame, status, cycles);
                m.title("maxtech_lib_demo.toolkit.title");
                m.theme(MINT);
                m.shiftClick(input, fuel);
                m.layout(Layout.column(
                        Layout.row(Layout.tank(tank, 18, 54),
                                Layout.column(Layout.SMALL_GAP, Layout.slots(input, 1, 1), Layout.slots(fuel, 1, 1),
                                        Layout.progress(flame, Layout.ProgressShape.FLAME)),
                                Layout.progress(arrow, Layout.ProgressShape.ARROW), Layout.slots(output, 3, 3),
                                Layout.energy(energy, 54)),
                        Layout.row(Layout.status(status, 90), Layout.value(cycles, "maxtech_lib_demo.cycles", 90)),
                        Layout.playerInventory()));
            });

    static {
        DEFINITION.register(Registrars.create(MaxtechLib.MOD_ID, Registries.MENU));
    }

    private static final Map<ServerLevel, TaskGroup> GROUPS = new WeakHashMap<>();

    private ToolkitDemo() {
    }

    static Handles handles() {
        Handles found = handles;
        if (found == null) {
            throw new IllegalStateException("Definition not built");
        }
        return found;
    }

    private static TaskGroup group(ServerLevel level) {
        TaskGroup group = GROUPS.get(level);
        if (group == null || group.isClosed()) {
            group = TaskSchedulers.newGroup(level);
            GROUPS.put(level, group);
        }
        return group;
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, ENTITY.get(),
                (entity, side) -> Handlers.items(entity.slots));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, ENTITY.get(),
                (entity, side) -> Handlers.fluids(entity.tank));
    }

    /** Opens {@link #DEFINITION} on right click. */
    static final class MachineBlock extends Block implements EntityBlock {
        static final MapCodec<MachineBlock> CODEC = simpleCodec(MachineBlock::new);

        MachineBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new MachineEntity(pos, state);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                BlockHitResult hit) {
            if (level.getBlockEntity(pos) instanceof MachineEntity machine && player instanceof ServerPlayer server) {
                DEFINITION.open(server, machine);
            }
            return InteractionResult.SUCCESS;
        }
    }

    /**
     * The machine: its values run on a loop for the demo. The arrow runs always; a finished arrow turns one input into
     * ingots in the output while the flame burns; the flame burns one fuel item for {@link #BURN_TICKS}; the tank and
     * the MU buffer sweep up and down. Saved with the block.
     */
    static final class MachineEntity extends BlockEntity {
        final ItemSlots slots = SLOTS.build(slot -> setChanged());
        final FluidTank tank = FluidTank.create(TANK_CAPACITY, this::setChanged);
        final EnergyEndpoint energy = EnergyEndpoint.buffer(ENERGY_CAPACITY, 0, 0, EnergyPriority.LEFTOVERS);
        int progress;
        int burn;
        int cycles;
        private boolean tankFilling = true;
        private boolean energyCharging = true;
        private final Task task = new Task();

        MachineEntity(BlockPos pos, BlockState state) {
            super(ENTITY.get(), pos, state);
        }

        Status status() {
            if (slots.stack(INPUT_SLOT).isEmpty()) {
                return Status.IDLE;
            }
            if (!outputHasRoom()) {
                return Status.FULL;
            }
            return burn > 0 ? Status.WORKING : Status.NO_FUEL;
        }

        private boolean outputHasRoom() {
            ItemStack ingot = new ItemStack(ingotFor(slots.stack(INPUT_SLOT)));
            for (int slot = FIRST_OUTPUT; slot < SIZE; slot++) {
                if (slots.insert(slot, ingot, true) > 0) {
                    return true;
                }
            }
            return false;
        }

        private static Item ingotFor(ItemStack raw) {
            if (raw.is(Items.RAW_COPPER)) {
                return Items.COPPER_INGOT;
            }
            return raw.is(Items.RAW_GOLD) ? Items.GOLD_INGOT : Items.IRON_INGOT;
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level instanceof ServerLevel server) {
                group(server).add(task);
                task.wake();
            }
        }

        @Override
        public void setRemoved() {
            TaskGroup group = task.group();
            if (group != null) {
                group.remove(task);
            }
            super.setRemoved();
        }

        @Override
        public void preRemoveSideEffects(BlockPos pos, BlockState state) {
            if (level != null) {
                slots.dropContents(level, pos);
            }
        }

        @Override
        protected void saveAdditional(ValueOutput out) {
            super.saveAdditional(out);
            slots.save(out.child("slots"));
            tank.save(out.child("tank"));
            out.putLong("energy", energy.stored());
            out.putInt("progress", progress);
            out.putInt("burn", burn);
            out.putInt("cycles", cycles);
        }

        @Override
        protected void loadAdditional(ValueInput in) {
            super.loadAdditional(in);
            in.child("slots").ifPresent(slots::load);
            in.child("tank").ifPresent(tank::load);
            energy.setStored(in.getLongOr("energy", 0));
            progress = in.getIntOr("progress", 0);
            burn = in.getIntOr("burn", 0);
            cycles = in.getIntOr("cycles", 0);
        }

        /** One step of the demo loop; every {@link #STEP} ticks. */
        void step() {
            burn = Math.max(0, burn - STEP);
            if (burn == 0 && !slots.stack(INPUT_SLOT).isEmpty() && slots.extract(FUEL_SLOT, 1, false) > 0) {
                burn = BURN_TICKS;
            }
            progress += STEP;
            if (progress >= ARROW_TICKS) {
                progress = 0;
                cycles++;
                finishCycle();
            }
            sweepTank();
            sweepEnergy();
            setChanged();
        }

        private void finishCycle() {
            ItemStack raw = slots.stack(INPUT_SLOT);
            if (raw.isEmpty() || burn == 0) {
                return;
            }
            ItemStack ingot = new ItemStack(ingotFor(raw));
            for (int slot = FIRST_OUTPUT; slot < SIZE; slot++) {
                if (slots.insert(slot, ingot, false) > 0) {
                    slots.extract(INPUT_SLOT, 1, false);
                    return;
                }
            }
        }

        private void sweepTank() {
            long amount = tank.amount();
            amount = Math.clamp(amount + (tankFilling ? 100 : -100), 0, TANK_CAPACITY);
            tankFilling = amount == 0 || (tankFilling && amount < TANK_CAPACITY);
            if (amount == 0) {
                tank.clear();
            } else {
                tank.set(FluidKey.of(Fluids.WATER), amount);
            }
        }

        private void sweepEnergy() {
            long stored = Math.clamp(energy.stored() + (energyCharging ? 250 : -250), 0, ENERGY_CAPACITY);
            energyCharging = stored == 0 || (energyCharging && stored < ENERGY_CAPACITY);
            energy.setStored(stored);
        }

        private final class Task extends ScheduledTask {
            Task() {
                super(TaskPriority.BACKGROUND);
            }

            @Override
            protected int run(TaskGroup group, long gameTime) {
                if (level == null || isRemoved()) {
                    return DONE;
                }
                step();
                return STEP;
            }
        }
    }
}
