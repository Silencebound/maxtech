package dev.silencebound.maxtech.storage.logic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.Test;

/** Item and fluid tags in saved graphs: each keeps its own field, so neither reads back as the other. */
class LogicKeyCodecTest {
    @Test
    void itemAndFluidTagsRoundTrip() {
        LogicKey items = LogicKey.tag(TagKey.create(Registries.ITEM, Identifier.parse("c:ingots/iron")));
        LogicKey fluids = LogicKey.fluidTag(TagKey.create(Registries.FLUID, Identifier.parse("c:water")));
        JsonElement itemJson = LogicKey.CODEC.encodeStart(JsonOps.INSTANCE, items).getOrThrow();
        JsonElement fluidJson = LogicKey.CODEC.encodeStart(JsonOps.INSTANCE, fluids).getOrThrow();
        assertEquals("{\"tag\":\"c:ingots/iron\"}", itemJson.toString());
        assertEquals("{\"fluid_tag\":\"c:water\"}", fluidJson.toString());
        assertEquals(items, LogicKey.CODEC.parse(JsonOps.INSTANCE, itemJson).getOrThrow());
        assertEquals(fluids, LogicKey.CODEC.parse(JsonOps.INSTANCE, fluidJson).getOrThrow());
    }
}
