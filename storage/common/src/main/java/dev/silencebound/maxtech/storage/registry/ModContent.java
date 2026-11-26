package dev.silencebound.maxtech.storage.registry;

import dev.silencebound.maxtech.storage.logic.LogicTexts;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterContent;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;

import dev.silencebound.maxtech.storage.board.BoardContent;
import dev.silencebound.maxtech.storage.bridge.BridgeContent;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockContent;
import dev.silencebound.maxtech.storage.ioport.IoPortContent;
import dev.silencebound.maxtech.storage.redstone.RedstoneTexts;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import dev.silencebound.maxtech.storage.mail.MailContent;
import dev.silencebound.maxtech.lib.content.Content;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusContent;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelContent;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.guide.GuideContent;
import dev.silencebound.maxtech.storage.hub.HubContent;
import dev.silencebound.maxtech.storage.network.NetworkContent;
import dev.silencebound.maxtech.storage.part.PartContent;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import dev.silencebound.maxtech.storage.material.EmberMaterialContent;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.machine.ModuleContent;
import dev.silencebound.maxtech.storage.storagechest.StorageChestContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterContent;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.priority.PriorityContent;
import dev.silencebound.maxtech.storage.label.LabelContent;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleContent;
import dev.silencebound.maxtech.storage.research.source.SourceContent;
import dev.silencebound.maxtech.storage.workbench.WorkbenchContent;
import dev.silencebound.maxtech.storage.worldgen.WorldgenContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.subnet.SubnetContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.carried.CarriedContent;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import dev.silencebound.maxtech.storage.tool.cartridge.CartridgeContent;
import dev.silencebound.maxtech.storage.tool.probe.ProbeContent;
import dev.silencebound.maxtech.storage.tool.usage.UsageContent;
import dev.silencebound.maxtech.storage.tool.blueprint.BlueprintContent;
import dev.silencebound.maxtech.storage.tool.setup.SetupContent;
import dev.silencebound.maxtech.storage.tool.wrench.WrenchContent;
import dev.silencebound.maxtech.storage.tool.quarantine.QuarantineContent;
import dev.silencebound.maxtech.storage.tool.goggles.GogglesContent;
import dev.silencebound.maxtech.storage.tool.layer.LayerContent;
import dev.silencebound.maxtech.storage.tool.tracepen.TracePenContent;
import dev.silencebound.maxtech.storage.tool.zone.ZonePainterContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.admin.LogicAdmin;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;

/**
 * Storage's content builder, which every area's {@code <Area>Content} class registers through; datagen and the creative
 * tab read it.
 */
public final class ModContent {
    public static final Content CONTENT = Content.create(MaxtechStorage.MOD_ID);

    private ModContent() {
    }

    /**
     * Loads every area's content class during mod construction. A class queues its entries when it loads, so this order
     * is the registration order and the creative tab's order. A new area adds its class here.
     */
    public static void init() {
        MaterialContent.init();
        WorldgenContent.init();
        CableContent.init();
        HubContent.init();
        ControllerContent.init();
        NetworkContent.init();
        MachineContent.init();
        ModDataComponents.init();
        CellContent.init();
        DriveContent.init();
        PriorityContent.init();
        LabelContent.init();
        TerminalContent.init();
        CraftingTerminalContent.init();
        ViewFilterContent.init();
        StorageChestContent.init();
        PortContent.init();
        PuzzleContent.init();
        TierTwoMaterialContent.init();
        EmberMaterialContent.init();
        DataCrystalContent.init();
        ModuleContent.init();
        ResearchContent.init();
        WorkbenchContent.init();
        PartContent.init();
        SourceContent.init();
        CreativeEnergyContent.init();
        ConditionContent.init();
        InterfaceContent.init();
        EnergyContent.init();
        DisplayContent.init();
        P2PContent.init();
        ToggleBusContent.init();
        SignalContent.init();
        WorldContent.init();
        SubnetContent.init();
        UplinkContent.init();
        CarriedContent.init();
        BeltContent.init();
        ProbeContent.init();
        CartridgeContent.init();
        UsageContent.init();
        WrenchContent.init();
        BlueprintContent.init();
        SetupContent.init();
        QuarantineContent.init();
        GogglesContent.init();
        TracePenContent.init();
        ZonePainterContent.init();
        MailContent.init();
        BoardContent.init();
        LayerContent.init();
        BulkContent.init();
        StorageBlockContent.init();
        IoPortContent.init();
        RedstoneTexts.init();
        DeviceTabTexts.init();
        VoidCrystalContent.init();
        PatternContent.init();
        PatternTerminalContent.init();
        StockKeeperContent.init();
        ProviderContent.init();
        GeneratorContent.init();
        AssemblerContent.init();
        CrafterContent.init();
        CraftingTabletContent.init();
        CpuContent.init();
        LogicTexts.init();
        LogicContent.init();
        RemoteContent.init();
        LogicAdmin.init();
        SensorContent.init();
        SmartLabelContent.init();
        BridgeContent.init();
        GuideContent.init();
        ModMenus.init();
        ModCreativeTabs.init();
    }
}
