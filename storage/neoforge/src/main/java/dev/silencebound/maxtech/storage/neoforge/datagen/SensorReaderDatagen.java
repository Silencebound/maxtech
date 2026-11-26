package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.sensor.reader.BlockMatch;
import dev.silencebound.maxtech.storage.sensor.reader.PackReader;
import dev.silencebound.maxtech.storage.sensor.reader.PackReaderListener;
import dev.silencebound.maxtech.storage.sensor.reader.ReaderPath;
import dev.silencebound.maxtech.storage.sensor.reader.ReaderType;
import dev.silencebound.maxtech.storage.sensor.reader.ReaderValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.JsonCodecProvider;

/**
 * The pack readers in Storage's jar ({@code data/maxtech_storage/maxtech_storage/readers/<modid>/}). Only vanilla
 * blocks so far: no mod on the list has a 26.1 build to check its saved data against yet. A reader for another mod goes
 * under its mod id with a {@code neoforge:mod_loaded} condition ({@code conditionally}). Field names verified in the
 * 26.1.2 sources ({@code AbstractFurnaceBlockEntity}, {@code BrewingStandBlockEntity}).
 */
final class SensorReaderDatagen extends JsonCodecProvider<PackReader> {
    private static final String TICKS = "ticks";

    SensorReaderDatagen(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, PackOutput.Target.DATA_PACK, PackReaderListener.DIRECTORY, PackReader.CODEC, registries,
                MaxtechStorage.MOD_ID);
    }

    @Override
    protected void gather() {
        Map<String, ReaderValue> furnace = new LinkedHashMap<>();
        furnace.put("cook_time", number("cooking_time_spent", TICKS));
        furnace.put("cook_total", number("cooking_total_time", TICKS));
        furnace.put("burn_left", number("lit_time_remaining", TICKS));
        furnace.put("burn_total", number("lit_total_time", TICKS));
        unconditional(MaxtechStorage.id("minecraft/furnace"),
                new PackReader(blocks("minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace"), furnace));

        Map<String, ReaderValue> brewing = new LinkedHashMap<>();
        brewing.put("brew_left", number("BrewTime", TICKS));
        brewing.put("fuel", number("Fuel", ""));
        unconditional(MaxtechStorage.id("minecraft/brewing_stand"),
                new PackReader(blocks("minecraft:brewing_stand"), brewing));
    }

    private static ReaderValue number(String path, String unit) {
        return new ReaderValue(ReaderPath.parse(path).getOrThrow(), unit, ReaderType.NUMBER, 1.0);
    }

    private static BlockMatch blocks(String... ids) {
        return BlockMatch.parse(List.of(ids)).getOrThrow();
    }

    @Override
    public String getName() {
        return "Sensor pack readers";
    }
}
