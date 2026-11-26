package dev.silencebound.maxtech.storage.dev;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.hub.HubContent;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.ioport.IoPortContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortPart;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The small setups behind the guide's 3D scenes (B09 item 15): each {@link Scene} builds its blocks in a level with the
 * real game, and {@link #capture} turns the settled result into the vanilla structure file a guide page imports with
 * {@code <ImportStructure>}. Every scene has a controller at its local (0, 0, {@code controllerZ}); a creative energy
 * source goes right under it, outside the captured box, so the network is online without the source showing. Block
 * entities are saved as their client update tag, the data a client draws from (cable arms, drive lights), so no owner,
 * cell id or stock reaches the file. Dev tooling only: the {@code guide_scene_*} GameTests build and check the files.
 * Server thread only.
 */
public final class GuideScenes {
    private GuideScenes() {
    }

    /** One guide scene: its file name (without {@code .nbt}), its box and the controller's z. */
    public enum Scene {
        FIRST_NETWORK("first_network", 4, 2, 3, 1), // getting_started.md
        CABLES("cables", 7, 1, 2, 1), // cables.md
        DRIVES("drives", 3, 2, 1, 0), // cells.md
        TERMINALS("terminals", 4, 1, 2, 1), // terminals.md
        PORTS("ports", 4, 1, 3, 2), // ports.md
        PROVIDERS("providers", 4, 1, 3, 2), // pattern_providers.md
        CPU("crafting_cpu", 4, 2, 2, 1), // crafting_cpu.md
        IO_PORT("io_port", 3, 1, 2, 1), // io_port.md
        INTERFACE("interface", 2, 2, 2, 1), // interface.md
        LOGIC("logic", 4, 2, 3, 2), // logic_controller.md
        ENERGY("energy", 5, 1, 2, 1); // energy.md

        private final String id;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int controllerZ;

        Scene(String id, int sizeX, int sizeY, int sizeZ, int controllerZ) {
            this.id = id;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.controllerZ = controllerZ;
        }

        /** The structure file's name beside the guide pages, such as {@code first_network.nbt}. */
        public String file() {
            return id + ".nbt";
        }

        /** The captured box, from the scene's origin. */
        public Vec3i size() {
            return new Vec3i(sizeX, sizeY, sizeZ);
        }
    }

    /**
     * Builds {@code scene} with its north-west bottom corner at {@code origin}, plus the energy source one block under
     * the controller, and returns what did not set up (empty when all did). The box and the block under it must be air.
     */
    public static List<String> build(ServerLevel level, Scene scene, BlockPos origin) {
        List<String> warnings = new ArrayList<>();
        Showcase.Pad pad = new Showcase.Pad(level, origin, OwnerRecord.UNOWNED, warnings);
        pad.place(0, -1, scene.controllerZ, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get(), null);
        pad.place(0, 0, scene.controllerZ, ControllerContent.CONTROLLER.get(), null);
        switch (scene) {
            case FIRST_NETWORK -> firstNetwork(pad);
            case CABLES -> cables(pad);
            case DRIVES -> drives(pad);
            case TERMINALS -> terminals(pad);
            case PORTS -> ports(pad);
            case PROVIDERS -> providers(pad);
            case CPU -> cpu(pad);
            case IO_PORT -> ioPort(pad);
            case INTERFACE -> iface(pad);
            case LOGIC -> logic(pad);
            case ENERGY -> energy(pad);
        }
        return warnings;
    }

    /**
     * The scene's box at {@code origin} as a vanilla structure tag (no air, no entities), with each block entity's data
     * replaced by its client update tag. Same blocks and update tags give the same tag.
     */
    public static CompoundTag capture(ServerLevel level, Scene scene, BlockPos origin) {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, origin, scene.size(), false, List.of(Blocks.AIR, Blocks.STRUCTURE_VOID));
        CompoundTag root = template.save(new CompoundTag());
        HolderLookup.Provider registries = level.registryAccess();
        ListTag blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            if (block.getCompound("nbt").isEmpty()) {
                continue;
            }
            ListTag pos = block.getListOrEmpty("pos");
            BlockEntity entity = level
                    .getBlockEntity(origin.offset(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)));
            CompoundTag look = entity == null ? new CompoundTag() : entity.getUpdateTag(registries);
            if (look.isEmpty()) {
                block.remove("nbt");
            } else {
                block.put("nbt", look);
            }
        }
        return root;
    }

    private static void cable(Showcase.Pad pad, int x, int y, int z, int tier) {
        pad.cable(x, y, z, CableContent.cable(CableLook.PLAIN, tier).type().get(), null);
    }

    private static ItemStack itemCell(CellCasing casing) {
        return Showcase.cell(casing, CellContent.ITEM_TYPE.id());
    }

    /** Controller, two cables, two drives stacked, a chest beside the cable. */
    private static void firstNetwork(Showcase.Pad pad) {
        cable(pad, 1, 0, 1, 1);
        cable(pad, 2, 0, 1, 1);
        pad.drive(3, 0, 1, Direction.NORTH, itemCell(CellCasing.SMALL), itemCell(CellCasing.SMALL),
                itemCell(CellCasing.MEDIUM));
        pad.drive(3, 1, 1, Direction.NORTH, itemCell(CellCasing.SMALL));
        pad.set(2, 0, 2, Blocks.CHEST.defaultBlockState());
    }

    /** Tiers 4 down to 2 from the controller, a hub, then a tier-1 branch with a terminal and a drive. */
    private static void cables(Showcase.Pad pad) {
        cable(pad, 1, 0, 1, 4);
        cable(pad, 2, 0, 1, 3);
        cable(pad, 3, 0, 1, 2);
        pad.place(4, 0, 1, HubContent.TIER_1.get(), null);
        cable(pad, 5, 0, 1, 1);
        pad.place(5, 0, 0, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.drive(6, 0, 1, Direction.NORTH, itemCell(CellCasing.SMALL));
    }

    /** Four drives beside the controller with cells of each casing, and a terminal on the controller. */
    private static void drives(Showcase.Pad pad) {
        pad.drive(1, 0, 0, Direction.NORTH, itemCell(CellCasing.SMALL), itemCell(CellCasing.SMALL),
                itemCell(CellCasing.MEDIUM), itemCell(CellCasing.MEDIUM), itemCell(CellCasing.LARGE));
        pad.drive(2, 0, 0, Direction.NORTH, itemCell(CellCasing.LARGE), itemCell(CellCasing.BULK),
                Showcase.cell(CellCasing.MEDIUM, CellContent.FLUID_TYPE.id()));
        pad.drive(1, 1, 0, Direction.NORTH, itemCell(CellCasing.SMALL));
        pad.drive(2, 1, 0, Direction.NORTH);
        pad.place(0, 1, 0, TerminalContent.TERMINAL.get(), Direction.NORTH);
    }

    /** A cable along the controller with the three terminals on its north side. */
    private static void terminals(Showcase.Pad pad) {
        for (int x = 1; x <= 3; x++) {
            cable(pad, x, 0, 1, 1);
        }
        pad.place(1, 0, 0, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.place(2, 0, 0, CraftingTerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.place(3, 0, 0, PatternTerminalContent.TERMINAL.get(), Direction.NORTH);
    }

    /** An import port on a chest, an export port on a furnace and a storage bus (port part) on a barrel. */
    private static void ports(Showcase.Pad pad) {
        for (int x = 1; x <= 3; x++) {
            cable(pad, x, 0, 2, 1);
        }
        port(pad, 1, PortMode.IMPORT);
        pad.set(1, 0, 0, Blocks.CHEST.defaultBlockState());
        port(pad, 2, PortMode.EXPORT);
        pad.set(2, 0, 0, Blocks.FURNACE.defaultBlockState());
        pad.part(3, 0, 2, Direction.NORTH, PortContent.PORT_PART, new ItemStack(PortContent.PORT_PART_ITEM.get()));
        CableParts parts = StorageCableBlock.parts(pad.level, pad.pos(3, 0, 2));
        if (parts != null && parts.part(Direction.NORTH) instanceof PortPart part) {
            part.setMode(PortMode.STORAGE);
        } else {
            pad.warnings.add("no port part at " + pad.pos(3, 0, 2).toShortString());
        }
        pad.set(3, 0, 1, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
    }

    private static void port(Showcase.Pad pad, int x, PortMode mode) {
        PortBlockEntity port = pad.entity(pad.place(x, 0, 1, PortContent.PORT.get(), Direction.NORTH),
                PortBlockEntity.class);
        if (port != null) {
            port.setMode(mode);
        }
    }

    /** A pattern provider on a crafting assembler, a provider part on a furnace, and a crafter. */
    private static void providers(Showcase.Pad pad) {
        for (int x = 1; x <= 3; x++) {
            cable(pad, x, 0, 2, 1);
        }
        pad.place(1, 0, 1, ProviderContent.PROVIDER.get(), Direction.NORTH);
        pad.place(1, 0, 0, AssemblerContent.ASSEMBLER.get(), null);
        pad.part(2, 0, 2, Direction.NORTH, ProviderContent.PROVIDER_PART,
                new ItemStack(ProviderContent.PROVIDER_PART_ITEM.get()));
        pad.set(2, 0, 1, Blocks.FURNACE.defaultBlockState());
        pad.place(3, 0, 1, CrafterContent.CRAFTER.get(), Direction.NORTH);
    }

    /**
     * A CPU cluster: a core on the cable with its top in view, storage north of it, a cache east of it, a lane in the
     * corner and a second lane on top of that one.
     */
    private static void cpu(Showcase.Pad pad) {
        cable(pad, 1, 0, 1, 1);
        pad.place(2, 0, 1, CpuContent.CORE_1.get(), null);
        pad.place(2, 0, 0, CpuContent.STORAGE.get(), null);
        pad.place(3, 0, 0, CpuContent.LANE.get(), null);
        pad.place(3, 0, 1, CpuContent.CACHE.get(), null);
        pad.place(3, 1, 0, CpuContent.LANE.get(), null);
    }

    /** An IO port and a drive on a cable. */
    private static void ioPort(Showcase.Pad pad) {
        cable(pad, 1, 0, 1, 1);
        cable(pad, 2, 0, 1, 1);
        pad.place(1, 0, 0, IoPortContent.IO_PORT.get(), Direction.NORTH);
        pad.drive(2, 0, 0, Direction.NORTH, itemCell(CellCasing.MEDIUM), itemCell(CellCasing.SMALL));
    }

    /** An interface fed by a hopper under a chest. */
    private static void iface(Showcase.Pad pad) {
        cable(pad, 1, 0, 1, 1);
        pad.place(1, 0, 0, InterfaceContent.INTERFACE.get(), Direction.NORTH);
        pad.set(0, 0, 0,
                Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.EAST));
        pad.set(0, 1, 0, Blocks.CHEST.defaultBlockState());
    }

    /** A logic controller, a sensor on a chest and a condition switch with a lamp on top. */
    private static void logic(Showcase.Pad pad) {
        for (int x = 1; x <= 3; x++) {
            cable(pad, x, 0, 2, 1);
        }
        pad.place(1, 0, 1, LogicContent.CONTROLLER.get(), Direction.NORTH);
        pad.place(2, 0, 1, SensorContent.SENSOR.get(), Direction.NORTH);
        pad.set(2, 0, 0, Blocks.CHEST.defaultBlockState());
        pad.place(3, 0, 1, ConditionContent.SWITCH.get(), null);
        pad.set(3, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());
    }

    /** A fuel generator and the three energy cell tiers on a cable. */
    private static void energy(Showcase.Pad pad) {
        for (int x = 1; x <= 4; x++) {
            cable(pad, x, 0, 1, 1);
        }
        pad.place(1, 0, 0, MachineContent.FUEL_GENERATOR.get(), Direction.NORTH);
        pad.place(2, 0, 0, EnergyContent.TIER_1.get(), null);
        pad.place(3, 0, 0, EnergyContent.TIER_2.get(), null);
        pad.place(4, 0, 0, EnergyContent.TIER_3.get(), null);
    }
}
