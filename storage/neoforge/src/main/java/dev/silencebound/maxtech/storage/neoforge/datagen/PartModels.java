package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusContent;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortShapes;
import dev.silencebound.maxtech.storage.port.PortTier;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * Item models of the cable parts: the part's own boxes (as {@link PartType#boxes()} gives them for the north face), the
 * face lying on the block face showing the front, everything else the side, as the cable model draws the part on a
 * cable ({@code PartBaker.panel}). On a cable the part is drawn by the cable model, not by these.
 */
final class PartModels {
    private static final TextureSlot FRONT = TextureSlot.FRONT;
    private static final TextureSlot SIDE = TextureSlot.SIDE;
    private static final int FULL_BRIGHT = 15;

    private PartModels() {
    }

    static void generate(ItemModelGenerators itemModels) {
        item(itemModels, TerminalContent.TERMINAL_PART_ITEM.get(), TerminalContent.TERMINAL_PART,
                MaxtechStorage.id("block/terminal/screen"), MaxtechStorage.id("block/terminal/bezel"), true);
        for (PortTier tier : PortTier.values()) {
            portItem(itemModels, tier);
        }
        item(itemModels, ResearchContent.TERMINAL_PART_ITEM.get(), ResearchContent.TERMINAL_PART,
                MaxtechStorage.id("block/research_terminal_front"), MaxtechStorage.id("block/terminal/bezel"), false);
        item(itemModels, ConditionContent.SWITCH_PART_ITEM.get(), ConditionContent.SWITCH_PART,
                MaxtechStorage.id("block/condition_switch/front_off"), MaxtechStorage.id("block/condition_switch/side"),
                false);
        item(itemModels, SensorContent.SENSOR_PART_ITEM.get(), SensorContent.SENSOR_PART,
                MaxtechStorage.id("block/sensor/front"), MaxtechStorage.id("block/sensor/side"), false);
        item(itemModels, DisplayContent.PANEL_PART_ITEM.get(), DisplayContent.PANEL_PART,
                MaxtechStorage.id("block/display/part_screen"), MaxtechStorage.id("block/display/edge"), false);
        item(itemModels, InterfaceContent.INTERFACE_PART_ITEM.get(), InterfaceContent.INTERFACE_PART,
                MaxtechStorage.id("block/interface/face"), MaxtechStorage.id("block/port/side"), false);
        for (P2PKind kind : P2PKind.values()) {
            item(itemModels, P2PContent.partItem(kind).get(), P2PContent.part(kind),
                    MaxtechStorage.id("block/p2p/" + kind.getSerializedName()), MaxtechStorage.id("block/p2p/side"),
                    false);
        }
        // The collar in its linked look, the band lit, on every face.
        item(itemModels, ToggleBusContent.TOGGLE_BUS_ITEM.get(), ToggleBusContent.TOGGLE_BUS,
                MaxtechStorage.id("block/toggle_bus/linked"), MaxtechStorage.id("block/toggle_bus/linked"), false);
        item(itemModels, ToggleBusContent.INVERTED_ITEM.get(), ToggleBusContent.INVERTED,
                MaxtechStorage.id("block/toggle_bus/inverted_linked"),
                MaxtechStorage.id("block/toggle_bus/inverted_linked"), false);
        item(itemModels, WorldContent.NETWORK_CHEST_PART_ITEM.get(), WorldContent.NETWORK_CHEST_PART,
                MaxtechStorage.id("block/network_chest_front"), MaxtechStorage.id("block/port/side"), false);
        item(itemModels, WorldContent.COLLECTOR_PART_ITEM.get(), WorldContent.COLLECTOR_PART,
                MaxtechStorage.id("block/world/collector"), MaxtechStorage.id("block/port/side"), false);
        item(itemModels, WorldContent.PLACER_PART_ITEM.get(), WorldContent.PLACER_PART,
                MaxtechStorage.id("block/world/placer_front"), MaxtechStorage.id("block/port/side"), false);
        item(itemModels, WorldContent.BREAKER_PART_ITEM.get(), WorldContent.BREAKER_PART,
                MaxtechStorage.id("block/world/breaker_front"), MaxtechStorage.id("block/port/side"), false);
        item(itemModels, ProviderContent.PROVIDER_PART_ITEM.get(), ProviderContent.PROVIDER_PART,
                MaxtechStorage.id("block/pattern_provider_face"), MaxtechStorage.id("block/pattern_provider_side"),
                false);
        item(itemModels, PatternTerminalContent.TERMINAL_PART_ITEM.get(), PatternTerminalContent.TERMINAL_PART,
                MaxtechStorage.id("block/pattern_terminal_front"), MaxtechStorage.id("block/terminal/bezel"), false);
        item(itemModels, CraftingTerminalContent.TERMINAL_PART_ITEM.get(), CraftingTerminalContent.TERMINAL_PART,
                MaxtechStorage.id("block/crafting_terminal_front"), MaxtechStorage.id("block/terminal/bezel"), false);
    }

    /** A port part's item: the import mode's steps as the cable model draws them, without the cable's arm. */
    private static void portItem(ItemModelGenerators itemModels, PortTier tier) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(PortModels.STEP)
                .requiredTextureSlot(PortModels.TIP).requiredTextureSlot(TextureSlot.PARTICLE);
        PortModels.steps(builder, PortShapes.partSteps(PortMode.IMPORT));
        TextureMapping textures = new TextureMapping().put(PortModels.STEP, new Material(PortModels.partStep(tier)))
                .put(PortModels.TIP, new Material(PortModels.STEP_TIP))
                .put(TextureSlot.PARTICLE, new Material(PortModels.partStep(tier)));
        Item item = PortContent.partItem(tier).get();
        Identifier model = builder.build().create(BuiltInRegistries.ITEM.getKey(item).withPrefix("item/"), textures,
                itemModels.modelOutput);
        itemModels.itemModelOutput.accept(item, ItemModelUtils.plainModel(model));
    }

    private static void item(ItemModelGenerators itemModels, Item item, PartType type, Identifier front,
            Identifier side, boolean glowing) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(FRONT)
                .requiredTextureSlot(SIDE).requiredTextureSlot(TextureSlot.PARTICLE);
        for (AABB box : type.boxes()) {
            float[] from = {(float) box.minX, (float) box.minY, (float) box.minZ};
            float[] to = {(float) box.maxX, (float) box.maxY, (float) box.maxZ};
            builder.element(
                    element -> element.from(from[0], from[1], from[2]).to(to[0], to[1], to[2]).allFaces((face, f) -> {
                        if (face == Direction.NORTH && from[2] <= 0) {
                            f.texture(FRONT).uvs(0, 0, 16, 16);
                            if (glowing) {
                                f.lightEmission(FULL_BRIGHT);
                            }
                        } else {
                            float[] uv = ModelBoxes.faceUv(face, from, to);
                            f.texture(SIDE).uvs(uv[0], uv[1], uv[2], uv[3]);
                        }
                    }));
        }
        TextureMapping textures = new TextureMapping().put(FRONT, new Material(front)).put(SIDE, new Material(side))
                .put(TextureSlot.PARTICLE, new Material(side));
        Identifier id = BuiltInRegistries.ITEM.getKey(item).withPrefix("item/");
        Identifier model = builder.build().create(id, textures, itemModels.modelOutput);
        itemModels.itemModelOutput.accept(item, ItemModelUtils.plainModel(model));
    }
}
