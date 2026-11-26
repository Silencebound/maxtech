package dev.silencebound.maxtech.storage.neoforge.datagen;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.research.source.SourceContent;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * Models of the devices that act on the block in front of them (owner decision 2026-10-07): a full cube drawn facing
 * north with the device's feature on the front, the cable connector on the back, where it links to the network, and the
 * shared arrow side on the other four faces, each turned so its arrow points at the front. The block state turns the
 * whole model to the block's {@code FACING} like a dispenser, so the arrows keep pointing at the front from every
 * angle. The ports use the same connector on the back but plain sides ({@link PortModels}).
 */
final class FacingModels {
    /** The connector on every directional device's back. */
    static final Identifier BACK = MaxtechStorage.id("block/port/stub_end");

    private static final TextureSlot FRONT = TextureSlot.FRONT;
    private static final TextureSlot SIDE = TextureSlot.SIDE;
    private static final TextureSlot BACK_SLOT = TextureSlot.BACK;

    private FacingModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        block(blockModels, SensorContent.SENSOR.get(), id("sensor/front"), id("sensor/side"));
        block(blockModels, WorldContent.PLACER.get(), id("world/placer_front"), id("world/side"));
        block(blockModels, WorldContent.BREAKER.get(), id("world/breaker_front"), id("world/side"));
        block(blockModels, ProviderContent.PROVIDER.get(), id("pattern_provider_face"), id("pattern_provider_side"));
        block(blockModels, GeneratorContent.GENERATOR.get(), id("generator_provider_face"),
                id("pattern_provider_side"));
        block(blockModels, SourceContent.MACHINE_OBSERVER.get(), id("machine_observer_front"),
                id("machine_observer_side"));
        for (P2PKind kind : P2PKind.values()) {
            block(blockModels, P2PContent.block(kind).get(), id("p2p/" + kind.getSerializedName()), id("p2p/side"));
        }
    }

    private static void block(BlockModelGenerators blockModels, Block block, Identifier front, Identifier side) {
        Identifier model = model(blockModels, ModelLocationUtils.getModelLocation(block), front, side);
        blockModels.blockStateOutput.accept(MultiVariantGenerator
                .dispatch(block, BlockModelGenerators.plainVariant(model)).with(BlockModelGenerators.ROTATION_FACING));
    }

    /** The cube model {@code id}: {@code front} on north, the connector on south, {@code side} turned on the rest. */
    static Identifier model(BlockModelGenerators blockModels, Identifier id, Identifier front, Identifier side) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(FRONT)
                .requiredTextureSlot(SIDE).requiredTextureSlot(BACK_SLOT).requiredTextureSlot(TextureSlot.PARTICLE);
        builder.element(element -> element.from(0, 0, 0).to(16, 16, 16).allFaces((face, f) -> {
            TextureSlot slot = switch (face) {
                case NORTH -> FRONT;
                case SOUTH -> BACK_SLOT;
                default -> SIDE;
            };
            f.texture(slot).uvs(0, 0, 16, 16).rotation(arrowTurn(face)).cullface(face);
        }));
        TextureMapping textures = new TextureMapping().put(FRONT, new Material(front)).put(SIDE, new Material(side))
                .put(BACK_SLOT, new Material(BACK)).put(TextureSlot.PARTICLE, new Material(side));
        return builder.build().create(id, textures, blockModels.modelOutput);
    }

    /**
     * The turn (clockwise) of a side face's texture that makes the arrow, drawn pointing at the texture's top, point
     * north on a model facing north. Vanilla's default UVs put north at the top of the up face, the bottom of the down
     * face, the right of the east face and the left of the west face.
     */
    static Quadrant arrowTurn(Direction face) {
        return switch (face) {
            case DOWN -> Quadrant.R180;
            case EAST -> Quadrant.R90;
            case WEST -> Quadrant.R270;
            default -> Quadrant.R0;
        };
    }

    private static Identifier id(String name) {
        return MaxtechStorage.id("block/" + name);
    }
}
