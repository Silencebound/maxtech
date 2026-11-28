package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.api.client.gui.AccentEnergyBar;
import dev.silencebound.maxtech.lib.api.client.gui.AccentProgress;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTank;
import dev.silencebound.maxtech.lib.api.machine.MachineStatus;
import dev.silencebound.maxtech.lib.api.machine.RedstoneMode;
import dev.silencebound.maxtech.lib.facade.FacadeContent;
import dev.silencebound.maxtech.lib.facade.FacadeItem;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.client.conduit.ConduitLabels;
import dev.silencebound.maxtech.lib.config.LibConfigNames;
import dev.silencebound.maxtech.lib.info.ConduitInfo;
import dev.silencebound.maxtech.lib.info.EnergyInfo;
import dev.silencebound.maxtech.lib.info.ScannerItem;
import dev.silencebound.maxtech.lib.neoforge.client.ConfigScreens;
import dev.silencebound.maxtech.lib.neoforge.guide.GuideConfigText;
import dev.silencebound.maxtech.lib.neoforge.compat.jade.BlockInfoLines;
import dev.silencebound.maxtech.lib.neoforge.platform.ConfigTranslations;
import dev.silencebound.maxtech.lib.viewer.RecipeViewText;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import dev.silencebound.maxtech.lib.MaxtechLib;
import net.neoforged.neoforge.common.data.LanguageProvider;

/** Lib's {@code en_us}: the frameworks' GUI, tooltip, info and recipe viewer lines. */
final class LibLanguageProvider extends LanguageProvider {
    LibLanguageProvider(PackOutput output) {
        super(output, MaxtechLib.MOD_ID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add(FacadeContent.FACADE.get(), "Facade");
        add(FacadeItem.LOOKS_LIKE_KEY, "Looks like %s");
        add(ScannerItem.DESCRIPTION_KEY, "Use on a block to print its stats and network to chat.");
        add(ScannerItem.HEADER_KEY, "Scan: %s");
        add(ScannerItem.NOTHING_KEY, "Nothing to report.");
        add(BlockInfoLines.CONFIG_KEY, "Block Info");
        add(BlockInfoLines.MORE_KEY, "...and %s more; use a scanner for the rest");
        add(EnergyInfo.PRODUCING_KEY, "Producing: %s / %s MU/t");
        add(EnergyInfo.USING_KEY, "Using: %s / %s MU/t (%s%%)");
        add(EnergyInfo.STORED_KEY, "Stored: %s / %s MU");
        add(EnergyInfo.BAR_KEY, "%s%%");
        add(EnergyInfo.CHARGING_KEY, "Charging: %s MU/t");
        add(EnergyInfo.DISCHARGING_KEY, "Discharging: %s MU/t");
        add(EnergyInfo.NETWORK_KEY, "Energy network: %s MU/t supply, %s MU/t demand");
        add(EnergyInfo.BROWNOUT_KEY, "Brownout: machines get %s%% of their demand");
        add(EnergyInfo.SUPPLIED_KEY, "Supplied: %s%%");
        add(EnergyInfo.FULL_IN_KEY, "Full in %s");
        add(EnergyInfo.EMPTY_IN_KEY, "Empty in %s");
        add(EnergyInfo.NETWORK_STORED_KEY, "Network stored: %s / %s MU");
        add(EnergyInfo.BUFFERS_KEY, "Batteries: %s MU/t in, %s MU/t out");
        add(AmountUnit.ITEMS.langKey(), "%s");
        add(AmountUnit.MILLIBUCKETS.langKey(), "%s mB");
        add(AmountUnit.MU.langKey(), "%s MU");
        add(EnergyInfo.COST_KEY, "Pass cost: %s µs, %s passes, %s");
        add(EnergyInfo.ASLEEP_KEY, "asleep");
        add(EnergyInfo.ACTIVE_KEY, "active");
        add(EnergyInfo.PAUSED_KEY, "Paused: part of the network is in unloaded chunks");
        add(EnergyInfo.NO_NETWORK_KEY, "Not in an energy network");
        add(EnergyInfo.SECONDS_KEY, "%ss");
        add(EnergyInfo.MINUTES_KEY, "%sm %ss");
        add(EnergyInfo.HOURS_KEY, "%sh %sm");
        add(EnergyInfo.DAYS_KEY, "%sd %sh");
        add(ConduitBlock.FACE_FIXED_KEY, "%s face: no other mode");
        add(ConduitInfo.CONDUIT_KEY, "%s (%s, %s)");
        add(ConduitInfo.UNCOLOURED_KEY, "uncoloured");
        add(ConduitInfo.FACE_KEY, "%s face: %s");
        add(ConduitInfo.faceModeKey(ConduitFaceMode.BOTH), "insert and extract");
        add(ConduitInfo.faceModeKey(ConduitFaceMode.INSERT), "insert");
        add(ConduitInfo.faceModeKey(ConduitFaceMode.EXTRACT), "extract");
        add(ConduitInfo.faceModeKey(ConduitFaceMode.DISABLED), "disabled");
        add(ConduitInfo.sideKey(Direction.DOWN), "Bottom");
        add(ConduitInfo.sideKey(Direction.UP), "Top");
        add(ConduitInfo.sideKey(Direction.NORTH), "North");
        add(ConduitInfo.sideKey(Direction.SOUTH), "South");
        add(ConduitInfo.sideKey(Direction.WEST), "West");
        add(ConduitInfo.sideKey(Direction.EAST), "East");
        add(ConduitLabels.COLOUR_MARK_KEY, "Colour mark %s: %s");
        add(ConduitLabels.MODE_MARK_KEY, "%s face: %s, marked %s");
        add(ConduitLabels.modeGlyphKey(ConduitFaceMode.INSERT), "with an arrow into the block");
        add(ConduitLabels.modeGlyphKey(ConduitFaceMode.EXTRACT), "with an arrow out of the block");
        add(ConduitLabels.modeGlyphKey(ConduitFaceMode.BOTH), "with a diamond");
        addGuiTranslations();
        addConfigTranslations();
        addRecipeViewerTranslations();
    }

    private void addGuiTranslations() {
        add(ScreenComponents.ON_KEY, "on");
        add(ScreenComponents.OFF_KEY, "off");
        add(AccentTank.EMPTY_KEY, "Empty");
        add(AccentTank.AMOUNT_KEY, "%s / %s mB");
        add(AccentTank.UNIT_AMOUNT_KEY, "%s / %s");
        add(AccentTank.UNKNOWN_KEY, "Unknown");
        add(AccentEnergyBar.MU_KEY, "%s / %s MU");
        add(AccentEnergyBar.FE_KEY, "%s / %s FE");
        add(AccentProgress.PERCENT_KEY, "%s%%");
        add(AccentProgress.LEFT_KEY, "%s s left");
        add(RedstoneMode.BUTTON_KEY, "Redstone: %s");
        addRedstoneMode(RedstoneMode.ALWAYS, "Always", "Runs whatever the redstone signal");
        addRedstoneMode(RedstoneMode.HIGH, "High", "Runs only with a redstone signal");
        addRedstoneMode(RedstoneMode.LOW, "Low", "Runs only without a redstone signal");
        addRedstoneMode(RedstoneMode.PULSE, "Pulse", "Runs once for each redstone pulse");
        addRedstoneMode(RedstoneMode.NEVER, "Never", "Never runs, whatever the redstone signal");
        add(MachineStatus.IDLE.translationKey(), "Idle");
        add(MachineStatus.RUNNING.translationKey(), "Running");
        add(MachineStatus.NO_RECIPE.translationKey(), "No matching recipe");
        add(MachineStatus.NO_ENERGY.translationKey(), "No energy");
        add(MachineStatus.OUTPUT_FULL.translationKey(), "Output full");
        add(MachineStatus.REDSTONE_OFF.translationKey(), "Off by redstone");
    }

    private void addRedstoneMode(RedstoneMode mode, String name, String hint) {
        add(mode.translationKey(), name);
        add(mode.hintKey(), hint);
    }

    /** Names and tooltips of every config value and section, and the config screen's own lines. */
    private void addConfigTranslations() {
        ConfigTranslations.english(MaxtechLib.MOD_ID, LibConfigNames.NAMES).forEach(this::add);
        add(ConfigScreens.DEFAULT_KEY, "Default: %s");
        add(ConfigScreens.WORLD_RESTART_KEY, "Applies after the world is reloaded");
        add(ConfigScreens.GAME_RESTART_KEY, "Applies after the game is restarted");
        // A guide page's <Config> tag (any mod's guide).
        add(GuideConfigText.DEFAULT_KEY, "Default: %s");
        add(GuideConfigText.CHANGED_KEY, "Changed by this server");
        add(GuideConfigText.ON_KEY, "on");
        add(GuideConfigText.OFF_KEY, "off");
    }

    private void addRecipeViewerTranslations() {
        add(RecipeViewText.DURATION_KEY, "%s s (%s ticks)");
        add(RecipeViewText.MODE_KEY, "Mode: %s");
        add(RecipeViewText.CHANCE_KEY, "Chance: %s%%");
        add(RecipeViewText.NOT_CONSUMED_KEY, "Not consumed");
    }
}
