package dev.silencebound.maxtech.farms.neoforge.datagen;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

/** Farms' {@code en_us}: items, blocks, config screen, messages and tooltips. */
final class FarmsLanguageProvider extends LanguageProvider {
    FarmsLanguageProvider(PackOutput output) {
        super(output, MaxtechFarms.MOD_ID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add("block.maxtech_farms.fluid_port", "Lab Fluid Port");
        add("block.maxtech_farms.lab_hub", "Lab Hub");
        add("block.maxtech_farms.lab_tube", "Lab Tube");
        add("item.maxtech_farms.sampler", "Sampler");
        add("item.maxtech_farms.specimen", "Specimen");
        add("item.maxtech_farms.specimen.named", "%s Specimen");
        add("maxtech_farms.configuration.hub", "Lab hub");
        add("maxtech_farms.configuration.hub.energyCapacity", "Energy capacity (MU)");
        add("maxtech_farms.configuration.hub.energyCapacity.tooltip", "MU the lab hub stores.");
        add("maxtech_farms.configuration.hub.fuelMuPerTick", "Fuel energy (MU per burn tick)");
        add("maxtech_farms.configuration.hub.fuelMuPerTick.tooltip",
                "MU the lab hub's fuel slot makes per tick of furnace burn time.");
        add("maxtech_farms.configuration.hub.tankCapacity", "Tank capacity (mB)");
        add("maxtech_farms.configuration.hub.tankCapacity.tooltip", "Nutrient the lab hub's tank holds, in mB.");
        add("maxtech_farms.configuration.preset", "Preset");
        add("maxtech_farms.configuration.preset.tooltip",
                "Default bundle for the values below: standalone, pack, custom, or the name of a JSON file in config/maxtech_farms/presets/. Values you edit are kept; untouched values follow the preset, also across mod updates. custom never changes any value.");
        add("maxtech_farms.configuration.sampler", "Sampler");
        add("maxtech_farms.configuration.sampler.capacityMu", "Capacity (MU)");
        add("maxtech_farms.configuration.sampler.capacityMu.tooltip", "MU a fully charged sampler holds.");
        add("maxtech_farms.configuration.sampler.muPerSample", "Cost per sample (MU)");
        add("maxtech_farms.configuration.sampler.muPerSample.tooltip", "MU the sampler spends on one sample.");
        add("message.maxtech_farms.fluid_port.feeds", "Feeds the lab hub");
        add("message.maxtech_farms.fluid_port.no_hub", "No lab hub on these lab tubes");
        add("message.maxtech_farms.lab_hub.empty", "Tank empty");
        add("message.maxtech_farms.lab_hub.no_tubes", "no lab tubes");
        add("message.maxtech_farms.lab_hub.refused",
                "Inactive: another lab hub runs this lab network. This hub still burns fuel into its own MU store");
        add("message.maxtech_farms.lab_hub.status", "%s: %s / %s mB, energy %s / %s MU");
        add("message.maxtech_farms.lab_hub.status_line", "%s | %s");
        add("message.maxtech_farms.lab_hub.tubes", "%s lab tubes, %s fluid ports");
        add("message.maxtech_farms.sampler.boss", "Bosses can't be sampled");
        add("message.maxtech_farms.sampler.no_charge", "The sampler needs charge");
        add("message.maxtech_farms.sampler.player", "Players can't be sampled");
        add("specimen.maxtech_farms.kind.animal", "Animal");
        add("specimen.maxtech_farms.kind.crop", "Crop");
        add("specimen.maxtech_farms.kind.mineral", "Mineral");
        add("specimen.maxtech_farms.kind.mob", "Mob");
        add("specimen.maxtech_farms.kind.sapling", "Sapling");
        add("specimen.maxtech_farms.trait.frugal", "Frugal");
        add("specimen.maxtech_farms.trait.hardy", "Hardy");
        add("specimen.maxtech_farms.trait.lucky", "Lucky");
        add("tooltip.maxtech_farms.sampler.charge", "Charge: %s / %s MU");
        add("tooltip.maxtech_farms.sampler.cost", "%s MU per sample");
        add("tooltip.maxtech_farms.sampler.usage", "Hold use on a mob to take a specimen");
        add("tooltip.maxtech_farms.specimen.generation", "Generation %s");
        add("tooltip.maxtech_farms.specimen.kind", "%s: %s");
        add("tooltip.maxtech_farms.specimen.no_traits", "none");
        add("tooltip.maxtech_farms.specimen.traits", "Traits: %s");
        add("tooltip.maxtech_farms.specimen.values", "Growth %s, Gain %s, Strength %s");
    }
}
