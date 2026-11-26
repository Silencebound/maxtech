package dev.silencebound.maxtech.storage.config;

import java.util.HashMap;
import java.util.Map;

/**
 * English names of Storage's config values and sections for NeoForge's config screen, by dotted path. Tooltips come
 * from the values' comments ({@code ConfigTranslations}). Datagen and {@code StorageConfigNamesTest} fail while a value
 * or section has no name here; the test also fails on a name without a value. Names stay short (the screen gives a
 * label about 150 pixels) and name the unit where it is not obvious.
 */
public final class StorageConfigNames {
    public static final Map<String, String> NAMES;

    static {
        Map<String, String> names = new HashMap<>();
        names.put("preset", "Preset");

        names.put("network", "Network");
        names.put("network.channels", "Channels");
        names.put("network.channels.mode", "Channel limits");
        names.put("network.channels.tier1", "Tier-1 cable");
        names.put("network.channels.tier2", "Tier-2 cable");
        names.put("network.channels.tier3", "Tier-3 cable");
        names.put("network.channels.tier4", "Tier-4 cable");
        names.put("network.channels.rootLink", "Controller root link");
        names.put("network.channels.adHoc", "Without a controller");
        names.put("network.channels.hubTier1", "Tier-1 hub");
        names.put("network.channels.hubTier2", "Tier-2 hub");
        names.put("network.channels.hubTier3", "Tier-3 hub");
        names.put("network.batch", "Batch size");
        names.put("network.batch.items", "Items per operation");
        names.put("network.batch.fluidMb", "Fluid per operation (mB)");
        names.put("network.energy", "Energy cost");
        names.put("network.energy.perOperation", "MU per operation");
        names.put("network.energy.perUnit", "MU per item moved");
        names.put("network.energy.fluidMbPerUnit", "mB counted as one item");
        names.put("network.zoneSize", "Zone size (nodes)");
        names.put("network.toggleBus", "Toggle bus");
        names.put("network.toggleBus.minIntervalTicks", "Fewest ticks between switches");
        names.put("network.adHoc", "Without a controller");
        names.put("network.adHoc.bufferMu", "Energy buffer (MU)");
        names.put("network.adHoc.bufferFe", "Old buffer key (deprecated)");
        names.put("network.subnet", "Subnets");
        names.put("network.subnet.bufferMu", "Energy buffer (MU)");
        names.put("network.subnet.bufferFe", "Old buffer key (deprecated)");
        names.put("network.subnet.inputPerTick", "Input per insert (MU)");
        names.put("network.subnet.maxDevices", "Devices per subnet");

        names.put("controller", "Controller");
        names.put("controller.bufferMu", "Buffer per block (MU)");
        names.put("controller.bufferFe", "Old buffer key (deprecated)");
        names.put("controller.inputPerTick", "Input per block (MU/t)");
        names.put("controller.size", "Multiblock size");
        names.put("controller.size.base", "Blocks without research");
        names.put("controller.size.step1", "Blocks after research 1");
        names.put("controller.size.step2", "Blocks after research 2");
        names.put("controller.size.step3", "Blocks after research 3");
        names.put("controller.size.max", "Most blocks");
        names.put("controller.cards", "Upgrade cards");
        names.put("controller.cards.base", "Card slots, one block");
        names.put("controller.cards.perBlock", "Slots per extra block");
        names.put("controller.cards.max", "Most card slots");
        names.put("controller.cards.batchAmplifiers", "Batch amplifiers");
        names.put("controller.cards.efficiencyCores", "Efficiency cores");
        names.put("controller.cards.surgeRegulators", "Surge regulators");
        names.put("controller.cards.batchAmplifier", "Batch amplifier");
        names.put("controller.cards.batchAmplifier.batchPercent", "Batch bonus per card (%)");
        names.put("controller.cards.batchAmplifier.maxBatchPercent", "Most batch bonus (%)");
        names.put("controller.cards.efficiencyCore", "Efficiency core");
        names.put("controller.cards.efficiencyCore.percent", "Saving per card (%)");
        names.put("controller.cards.efficiencyCore.maxPercent", "Most saving (%)");
        names.put("controller.cards.surgeRegulator", "Surge regulator");
        names.put("controller.cards.surgeRegulator.reservePercent", "Reserve (%)");
        names.put("controller.recorders", "Recorders");
        names.put("controller.recorders.telemetry", "Record history");
        names.put("controller.recorders.ledger", "Keep a ledger");
        names.put("controller.recorders.alarms", "Run alarm rules");
        names.put("controller.recorders.alarmRules", "Alarm rules");
        names.put("controller.recorders.telemetrySampleTicks", "History sample (ticks)");
        names.put("controller.recorders.telemetrySamples", "History samples kept");
        names.put("controller.recorders.telemetryMaxKeys", "Tracked items");
        names.put("controller.recorders.ledgerDays", "Ledger days");
        names.put("controller.recorders.ledgerMaxRows", "Ledger rows");
        names.put("controller.modules", "Old module keys (deprecated)");
        names.put("controller.modules.batchAmplifier", "Batch amplifier");
        names.put("controller.modules.batchAmplifier.batchPercent", "Batch bonus per card");
        names.put("controller.modules.batchAmplifier.maxBatchPercent", "Most batch bonus");
        names.put("controller.modules.efficiencyCore", "Efficiency core");
        names.put("controller.modules.efficiencyCore.percent", "Saving per card");
        names.put("controller.modules.efficiencyCore.maxPercent", "Most saving");
        names.put("controller.modules.surgeRegulator", "Surge regulator");
        names.put("controller.modules.surgeRegulator.reservePercent", "Reserve");
        names.put("controller.modules.networkAnchor", "Network anchor");
        names.put("controller.modules.networkAnchor.chunks", "Chunks");
        names.put("controller.modules.telemetryRecorder", "Telemetry recorder");
        names.put("controller.modules.telemetryRecorder.sampleTicks", "Sample ticks");
        names.put("controller.modules.telemetryRecorder.samples", "Samples");
        names.put("controller.modules.telemetryRecorder.maxKeys", "Tracked items");
        names.put("controller.modules.dimensionBridge", "Dimension bridge");
        names.put("controller.modules.dimensionBridge.drainPerTick", "Drain per tick");
        names.put("controller.modules.dimensionBridge.surchargePerOperation", "Surcharge per operation");

        names.put("worldgen", "World generation");
        ore(names, "worldgen.lumenQuartz", "Lumen quartz ore");
        ore(names, "worldgen.tantalite", "Tantalite ore");
        ore(names, "worldgen.emberQuartz", "Ember quartz ore");
        names.put("worldgen.deepDataGeode", "Deep data geodes");
        names.put("worldgen.deepDataGeode.rarity", "Rarity (chunks)");
        names.put("worldgen.deepDataGeode.minY", "Lowest y");
        names.put("worldgen.deepDataGeode.maxY", "Highest y");
        names.put("worldgen.voidGeode", "Void geodes");
        names.put("worldgen.voidGeode.rarity", "Rarity (chunks)");
        names.put("worldgen.voidGeode.minDepth", "Least depth (blocks)");
        names.put("worldgen.voidGeode.maxDepth", "Most depth (blocks)");

        names.put("machines", "Machines");
        names.put("machines.energyCapacity", "Buffer (MU)");
        names.put("machines.maxEnergyInput", "Input per transfer (MU)");
        names.put("machines.fuelGenerator", "Fuel generator");
        names.put("machines.fuelGenerator.energyPerTick", "Output (MU/t)");
        names.put("machines.fuelGenerator.maxEnergyOutput", "Push per side (MU/t)");
        names.put("machines.modules", "Machine modules");
        names.put("machines.modules.precisionPrintYield", "Precision print yield");
        names.put("machines.modules.speedBonusPercent", "Speed bonus (%)");
        names.put("machines.modules.speedEnergyPercent", "Speed energy cost (%)");
        names.put("machines.modules.efficiencySavingPercent", "Efficiency saving (%)");
        names.put("machines.modules.voidParallel", "Void parallel runs");
        names.put("machines.modules.voidEnergyPercent", "Void energy cost (%)");

        names.put("cells", "Storage cells");
        names.put("cells.itemsPerByte", "Items per byte");
        names.put("cells.fluidMbPerByte", "Fluid per byte (mB)");
        names.put("cells.kindBytes", "Bytes per kind");
        names.put("cells.componentKindBytes", "Bytes per kind with data");
        names.put("cells.memoryBytes", "Memory modules (bytes)");
        String[] numerals = {"I", "II", "III", "IV", "V", "VI"};
        for (int tier = 1; tier <= numerals.length; tier++) {
            names.put("cells.memoryBytes.tier" + tier, "Memory module " + numerals[tier - 1]);
        }
        names.put("cells.bulkMultiplier", "Bulk cell multiplier");
        names.put("cells.casings", "Casing slots");
        casing(names, "small", "Small casing");
        casing(names, "medium", "Medium casing");
        casing(names, "large", "Large casing");
        casing(names, "bulk", "Bulk casing");
        casing(names, "portable", "Portable casing");
        names.put("cells.portable", "Portable cell");
        names.put("cells.portable.energyCapacity", "Battery (MU)");
        names.put("cells.portable.maxInput", "Charge per transfer (MU)");
        names.put("cells.transferOperations", "Drive transfer (ops/t)");
        names.put("cells.storageBlockBytes", "Storage blocks (bytes)");
        names.put("cells.storageBlockBytes.tier1", "Storage block I");
        names.put("cells.storageBlockBytes.tier2", "Storage block II");
        names.put("cells.storageBlockBytes.tier3", "Storage block III");

        names.put("ioPort", "IO port");
        names.put("ioPort.operationsPerTick", "Operations per tick");
        names.put("ioPort.speedCardMultiplier", "Speed card multiplier");

        names.put("ports", "Ports");
        for (int tier = 1; tier <= 3; tier++) {
            names.put("ports.tier" + tier, "Tier-" + tier + " port");
            names.put("ports.tier" + tier + ".itemsPerSecond", "Items per second");
            names.put("ports.tier" + tier + ".fluidMbPerSecond", "Fluid per second (mB)");
        }
        names.put("ports.speedCards", "Speed cards");
        names.put("ports.speedCards.multiplier", "Rate multiplier");
        names.put("ports.speedCards.slots", "Card slots");
        names.put("ports.storageBus", "Storage bus");
        names.put("ports.storageBus.minScanTicks", "Fastest rescan (ticks)");
        names.put("ports.storageBus.maxScanTicks", "Slowest rescan (ticks)");
        names.put("ports.filter", "Filter");
        names.put("ports.filter.exportKeysPerOperation", "Kinds tried per export");

        names.put("research", "Research");
        names.put("research.preset", "Difficulty");
        names.put("research.relaxedScale", "Relaxed scale");
        names.put("research.expertScale", "Expert scale");
        names.put("research.points", "Points");
        names.put("research.points.discovery", "Per new item or fluid");
        names.put("research.points.advancement", "Per advancement");
        names.put("research.skipTokens", "Skip tokens");
        names.put("research.skipTokens.pointsPerToken", "Points per token");
        names.put("research.skipTokens.lootChance", "Chest chance (0-1)");
        names.put("research.sources", "Point sources");
        names.put("research.sources.relaxedPointScale", "Relaxed point scale");
        names.put("research.sources.expertPointScale", "Expert point scale");
        names.put("research.sources.observer", "Observer");
        names.put("research.sources.observer.runsPerPoint", "Runs per point");
        names.put("research.sources.observer.dailyCap", "Points per day");
        names.put("research.sources.scanner", "Scanner");
        names.put("research.sources.scanner.points", "Points per new block");
        names.put("research.sources.scanner.cooldownTicks", "Cooldown (ticks)");
        names.put("research.sources.notes", "Research notes");
        names.put("research.sources.notes.minPoints", "Fewest points");
        names.put("research.sources.notes.maxPoints", "Most points");
        names.put("research.sources.notes.lootChance", "Chest chance (0-1)");
        names.put("research.sources.analyzer", "Analyzer");
        names.put("research.sources.analyzer.points", "Points per new item");
        names.put("research.puzzles", "Puzzles");
        puzzle(names, "signalRouting", "Signal routing");
        puzzle(names, "maskAlignment", "Mask alignment");
        puzzle(names, "bytePacking", "Byte packing");
        puzzle(names, "waveformTuning", "Waveform tuning");

        names.put("condition", "Condition switch");
        names.put("condition.minIntervalTicks", "Shortest interval (ticks)");

        names.put("logic", "Logic");
        names.put("logic.maxNodes", "Nodes per graph");
        names.put("logic.maxGraphs", "Graphs per controller");
        names.put("logic.teamChecksPerSecond", "Team checks per second");
        names.put("logic.teamActionPointsPerSecond", "Team actions per second");
        names.put("logic.crossNetworkChecks", "Other-network check cost");
        names.put("logic.crossNetworkActionPoints", "Other-network action cost");
        names.put("logic.maxImportLength", "Import length (chars)");
        names.put("logic.displayWriteTicks", "Display write gap (ticks)");
        names.put("logic.maxVariables", "Shared variables");
        names.put("logic.maxRuleDepth", "Rule chain depth");
        names.put("logic.playerCheckTicks", "Player check (ticks)");
        names.put("logic.playerRadiusMax", "Player radius (blocks)");
        names.put("logic.maxPins", "Pins per rule");
        names.put("logic.historyEntries", "History per rule");
        names.put("logic.forEachMaxItems", "For each: most items");
        names.put("logic.remote", "Logic remote");
        names.put("logic.remote.energyCapacity", "Battery (MU)");
        names.put("logic.remote.maxInput", "Charge per transfer (MU)");
        names.put("logic.remote.energyPerOperation", "MU per action");
        names.put("logic.toasts", "Show logic toasts");
        names.put("logic.remotePings", "Remote notice toasts");
        names.put("crafting.toasts", "Crafting toasts");

        names.put("sensor", "Sensor");
        names.put("sensor.savedDataCost", "Saved data cost (checks)");
        names.put("sensor.savedDataMinIntervalTicks", "Saved data gap (ticks)");

        names.put("energy", "Energy storage");
        names.put("energy.cells", "Energy cells");
        for (int tier = 1; tier <= 3; tier++) {
            names.put("energy.cells.tier" + tier, "Tier-" + tier + " energy cell");
            names.put("energy.cells.tier" + tier + ".capacity", "Capacity (MU)");
            names.put("energy.cells.tier" + tier + ".maxTransfer", "Transfer (MU)");
        }

        names.put("world", "World devices");
        names.put("world.collector", "Collector");
        names.put("world.collector.radius", "Radius (blocks)");
        names.put("world.collector.intervalTicks", "Interval (ticks)");
        names.put("world.placer", "Placer");
        names.put("world.placer.intervalTicks", "Interval (ticks)");
        names.put("world.placer.dropEntityCap", "Drop entity cap");
        names.put("world.breaker", "Breaker");
        names.put("world.breaker.intervalTicks", "Interval (ticks)");
        names.put("world.breaker.maxHardness", "Hardest block");

        names.put("claims", "Land claims");
        names.put("claims.enabled", "Ask claim mods");
        names.put("claims.checkUnowned", "Check unowned devices");
        names.put("claims.cacheSeconds", "Answer cache (seconds)");

        names.put("history", "History charts");
        names.put("history.minIntervalTicks", "Shortest sample (ticks)");
        names.put("history.maxSeriesPerNetwork", "Series per network");
        names.put("history.pinSeriesPerController", "Pinned series");
        names.put("history.pinIntervalTicks", "Pinned sample (ticks)");

        names.put("loadShedding", "Load shedding");
        names.put("loadShedding.optionalPercent", "Optional devices (%)");
        names.put("loadShedding.lowPercent", "Low priority (%)");
        names.put("loadShedding.normalPercent", "Normal priority (%)");
        names.put("loadShedding.highPercent", "High priority (%)");
        names.put("loadShedding.resumeMargin", "Resume margin (%)");

        names.put("display", "Displays");
        names.put("display.updateTicks", "Update gap (ticks)");
        names.put("display.maxWallSize", "Wall size (panels)");

        names.put("alarms", "Alarm panel");
        names.put("alarms.lowPowerPercent", "Low power (%)");
        names.put("alarms.storageFullPercent", "Storage nearly full (%)");
        names.put("alarms.recheckTicks", "Recheck (ticks)");
        names.put("alarms.clearedKeepTicks", "Keep cleared (ticks)");
        names.put("alarms.clearMarginPercent", "Clear margin (%)");
        names.put("alarms.reannounceTicks", "Quiet repeat (ticks)");
        names.put("alarms.chat", "Send to chat");

        names.put("recovery", "Recovery bin");
        names.put("recovery.keepTicks", "Keep (ticks)");
        names.put("recovery.maxEntries", "Rows");

        names.put("setup", "Setup blueprints");
        names.put("setup.ghostKeepTicks", "Keep ghosts (ticks)");
        names.put("setup.maxGhostsTotal", "Ghosts on the server");
        names.put("setup.buildPerTick", "Auto-build per tick");

        names.put("snapshots", "Settings snapshots");
        names.put("snapshots.maxPerNetwork", "Per network");
        names.put("snapshots.maxTotal", "On the server");

        names.put("interface", "Interface");
        names.put("interface.fluidTankMb", "Fluid tank (mB)");

        names.put("p2p", "P2P links");
        names.put("p2p.energyLossPercent", "Energy loss (%)");

        names.put("materials", "Crystals");
        names.put("materials.dataCrystal", "Data crystal");
        names.put("materials.dataCrystal.growthChance", "Growth: 1 in");
        names.put("materials.voidCrystal", "Void crystal");
        names.put("materials.voidCrystal.growthChance", "Growth: 1 in");

        names.put("signal", "Antennas and wireless");
        names.put("signal.antenna", "Antennas");
        names.put("signal.antenna.rangeTier1", "Tier-1 range (blocks)");
        names.put("signal.antenna.rangeTier2", "Tier-2 range (blocks)");
        names.put("signal.antenna.channelsTier1", "Tier-1 channels");
        names.put("signal.antenna.channelsTier2", "Tier-2 channels");
        names.put("signal.antenna.channelsTier3", "Tier-3 channels");
        names.put("signal.wireless", "Wireless terminal");
        names.put("signal.wireless.baseRange", "Base range (blocks)");
        names.put("signal.wireless.rangePerMount", "Range per mount (blocks)");
        names.put("signal.wireless.maxRange", "Most range (blocks)");
        names.put("signal.wireless.energyCapacity", "Battery (MU)");
        names.put("signal.wireless.maxInput", "Charge per transfer (MU)");
        names.put("signal.wireless.energyPerOperation", "MU per operation");

        names.put("caps", "Team limits");
        names.put("caps.devicesPerTeam", "Devices per team");
        names.put("caps.zonesPerTeam", "Zones per team");
        names.put("caps.bytesPerTeam", "Cell bytes per team");

        names.put("tools", "Tools");
        names.put("tools.probe", "Signal probe");
        names.put("tools.probe.windowSeconds", "Graph window (seconds)");
        names.put("tools.probe.sampleTicks", "Sample (ticks)");
        names.put("tools.cartridge", "Puzzle cartridge");
        names.put("tools.cartridge.creditPercent", "Points (% of a token)");
        names.put("tools.cartridge.difficulty", "Difficulty (0-2)");
        names.put("tools.blueprint", "Blueprint card");
        names.put("tools.blueprint.rowLength", "Row paste length");
        names.put("tools.goggles", "Network goggles");
        names.put("tools.goggles.energyCapacity", "Battery (MU)");
        names.put("tools.goggles.maxInput", "Charge per transfer (MU)");
        names.put("tools.goggles.energyPerSecond", "MU per second");
        names.put("tools.uplink", "Pocket uplink");
        names.put("tools.uplink.filterSize", "Filter size");
        names.put("tools.belt", "Restock belt");
        names.put("tools.belt.intervalTicks", "Refill (ticks)");
        names.put("tools.belt.maxMarks", "Marked slots");
        names.put("tools.layer", "Cable layer");
        names.put("tools.layer.energyCapacity", "Battery (MU)");
        names.put("tools.layer.maxInput", "Charge per transfer (MU)");
        names.put("tools.layer.energyPerCable", "MU per cable");
        names.put("tools.layer.maxLength", "Run length (blocks)");

        names.put("services", "Services");
        names.put("services.mail", "Mail");
        names.put("services.mail.muPerStack", "MU per stack");
        names.put("services.mail.fePerStack", "Old cost key (deprecated)");
        names.put("services.mail.inboxStacks", "Inbox (stacks)");
        names.put("services.board", "Request board");
        names.put("services.board.maxRequests", "Open requests");
        names.put("services.ledger", "Old ledger keys (deprecated)");
        names.put("services.ledger.days", "Days");
        names.put("services.ledger.maxRows", "Rows");

        names.put("devices", "Controller devices");
        names.put("devices.networkAnchor", "Network anchor");
        names.put("devices.networkAnchor.chunks", "Chunks per team");
        names.put("devices.dimensionBridge", "Dimension bridge");
        names.put("devices.dimensionBridge.drainPerTick", "Drain (MU/t)");
        names.put("devices.dimensionBridge.surchargePerOperation", "Surcharge (MU per op)");

        names.put("bulkDrive", "Bulk drive");
        names.put("bulkDrive.maxCount", "Most items");

        names.put("crafting", "Autocrafting");
        names.put("crafting.autocraftingMode", "Mode");
        names.put("crafting.plannerNodeCap", "Planner node limit");
        names.put("crafting.plannerTimeLimitMs", "Planner time (ms)");
        names.put("crafting.chanceRepeatCap", "Chance repeat limit");
        names.put("crafting.deliveryRange", "Delivery range (blocks)");
        names.put("crafting.teamToasts", "Toasts for the team");
        names.put("crafting.stockKeeperRules", "Stock keeper rules");
        names.put("crafting.stockRetryTicks", "Stock retry (ticks)");
        names.put("crafting.cardIntervalTicks", "Crafting card gap (ticks)");
        names.put("crafting.cardMaxRequest", "Crafting card request");
        names.put("crafting.automationMaxPerJob", "Automation job size");
        names.put("crafting.automationCpusKeptFree", "CPUs kept for players");
        names.put("crafting.dispatchStrategy", "Dispatch strategy");
        names.put("crafting.providerExtraSlots", "Provider extra slots");
        names.put("crafting.providerPullTicks", "Provider check (ticks)");
        names.put("crafting.assemblerEnergyPerCraft", "Assembler MU per craft");
        names.put("crafting.crafterEnergyPerCraft", "Crafter MU per craft");
        names.put("crafting.generatorTimeoutTicks", "Generator timeout (ticks)");
        names.put("crafting.tablet", "Crafting tablet");
        names.put("crafting.tablet.energyCapacity", "Battery (MU)");
        names.put("crafting.tablet.maxInput", "Charge per transfer (MU)");
        names.put("crafting.tablet.energyPerOperation", "MU per operation");
        names.put("crafting.tablet.maxPlannedJobs", "Planned jobs");
        names.put("crafting.cpu", "Crafting CPUs");
        names.put("crafting.cpu.coreSteps", "Steps of a core");
        names.put("crafting.cpu.stepsPerStorage", "Steps per storage block");
        names.put("crafting.cpu.batchPerCache", "Batch per cache block");
        names.put("crafting.cpu.maxParts", "Most part blocks");
        for (int tier = 1; tier <= 3; tier++) {
            String prefix = "crafting.cpu.tier" + tier;
            names.put(prefix, "Tier-" + tier + " CPU");
            names.put(prefix + ".maxSteps", "Most steps");
            names.put(prefix + ".maxLanes", "Most lanes");
            names.put(prefix + ".maxBatch", "Most batch");
            names.put(prefix + ".muPerLane", "MU per lane per tick");
            names.put(prefix + ".fePerLane", "Old cost key (deprecated)");
        }

        names.put("bridges", "AE2 and RS bridges");
        names.put("bridges.refreshTicks", "Refresh gap (ticks)");
        names.put("bridges.pollIntervalTicks", "Poll (ticks)");

        names.put("modules", "Optional content");
        names.put("modules.placerBreaker", "Placer and breaker");
        names.put("modules.collector", "Collector");
        names.put("modules.p2pItem", "P2P item links");
        names.put("modules.p2pFluid", "P2P fluid links");
        names.put("modules.p2pEnergy", "P2P energy links");
        names.put("modules.p2pRedstone", "P2P redstone links");
        names.put("modules.networkAnchor", "Network anchor");
        names.put("modules.wireless", "Wireless terminal");
        names.put("modules.dataGeodes", "Deep data geodes");
        names.put("modules.voidGeodes", "Void geodes");

        names.put("visuals", "Visuals");
        names.put("visuals.effects", "Animated effects");
        names.put("visuals.cableTierGlow", "Lit cable lanes");
        names.put("visuals.palette", "Status colours");
        names.put("visuals.craftingDisplay", "Item being crafted");
        names.put("visuals.craftingDisplayDistance", "Crafted item distance");
        names.put("terminal", "Terminal");
        names.put("terminal.sort", "Sort by");
        names.put("terminal.direction", "Sort direction");
        names.put("terminal.jeiSearchSync", "Sync search with JEI");
        names.put("terminal.amountFormat", "Amount format");
        NAMES = Map.copyOf(names);
    }

    private StorageConfigNames() {
    }

    private static void ore(Map<String, String> names, String path, String name) {
        names.put(path, name);
        names.put(path + ".veinsPerChunk", "Veins per chunk");
        names.put(path + ".veinSize", "Vein size (blocks)");
        names.put(path + ".minY", "Lowest y");
        names.put(path + ".maxY", "Highest y");
    }

    private static void casing(Map<String, String> names, String casing, String name) {
        String path = "cells.casings." + casing;
        names.put(path, name);
        names.put(path + ".memorySlots", "Memory module slots");
        names.put(path + ".addonSlots", "Add-on slots");
    }

    private static void puzzle(Map<String, String> names, String puzzle, String name) {
        String path = "research.puzzles." + puzzle;
        names.put(path, name);
        names.put(path + ".enabled", "Offered");
        names.put(path + ".difficultyOffset", "Difficulty offset");
    }
}
