package dev.silencebound.maxtech.storage.neoforge.datagen;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.content.ConduitEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.CableGeometry;
import dev.silencebound.maxtech.storage.cable.CableLane;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cable.CableTierMarks;
import dev.silencebound.maxtech.storage.cable.SmartLoad;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * The storage cables' item models (owner decision 2026-10-15): a straight east-west piece of the cable as the block
 * model draws it, built from the same {@link CableGeometry} faces with the same sprites, so the glass sleeve (closed at
 * its ends here), the lane, the tier pips on the lane's core and the covered sleeve look as they do in the world. The
 * lane's colour and light ({@link CableLane}, the glow on) and a covered sleeve's colour are baked into the faces, so
 * the item needs no tint source and costs what any element model costs. Each face is an element of its own, since a
 * face's box is the whole arm or core it belongs to. The block model is dynamic and draws from block entity data an
 * item does not have, which is why the items get these static models; {@code CableContent} names them with
 * {@code ItemModel.existing}.
 */
final class CableItemModels {
    private static final int STRAIGHT = ConduitConnections.bit(Direction.EAST) | ConduitConnections.bit(Direction.WEST);
    private static final TextureSlot SLEEVE = TextureSlot.create("sleeve");
    private static final TextureSlot CORE = TextureSlot.create("core");
    private static final TextureSlot END = TextureSlot.create("end");
    private static final TextureSlot LANE = TextureSlot.create("lane");
    private static final TextureSlot LANE_CORE = TextureSlot.create("lane_core");

    private CableItemModels() {
    }

    static void generate(ItemModelGenerators itemModels) {
        for (CableLook look : CableLook.values()) {
            for (int tier = 1; tier <= StorageCableConduitType.MAX_TIER; tier++) {
                item(itemModels, CableContent.cable(look, tier));
            }
        }
    }

    private static void item(ItemModelGenerators itemModels, ConduitEntry<StorageCableConduitType> entry) {
        StorageCableConduitType type = entry.type().get();
        boolean covered = type.look() == CableLook.COVERED;
        int lineArgb = CableLane.argb(type, null, SmartLoad.IDLE);
        int emission = CableTierMarks.lineEmission(CableLane.brightness(type, SmartLoad.IDLE), true);
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(TextureSlot.PARTICLE)
                .requiredTextureSlot(SLEEVE).requiredTextureSlot(CORE).requiredTextureSlot(END)
                // The normal block angle like other pipe mods, but larger than a block so the thin piece fills
                // the slot (owner decision 2026-10-18).
                .transform(ItemDisplayContext.GUI, t -> t.rotation(30, 225, 0).scale(0.9F))
                .transform(ItemDisplayContext.GROUND, t -> t.translation(0, 3, 0).scale(0.4F))
                .transform(ItemDisplayContext.FIXED, t -> t.scale(0.75F));
        if (!covered) {
            builder.requiredTextureSlot(LANE).requiredTextureSlot(LANE_CORE);
        }
        for (CableGeometry.Face face : CableGeometry.body(type.thickness(), STRAIGHT, 0, !covered)) {
            boolean lane = face.texture() == CableGeometry.Texture.LANE
                    || face.texture() == CableGeometry.Texture.LANE_CORE;
            AABB box = face.box();
            builder.element(element -> element.from((float) box.minX, (float) box.minY, (float) box.minZ)
                    .to((float) box.maxX, (float) box.maxY, (float) box.maxZ).face(face.facing(), f -> {
                        f.texture(slot(face.texture())).uvs(face.u0(), face.v0(), face.u1(), face.v1());
                        if (face.rotated()) {
                            f.rotation(Quadrant.R90);
                        }
                        if (lane || covered) {
                            f.color(lineArgb);
                        }
                        if (lane && emission > 0) {
                            f.lightEmission(emission);
                        }
                    }));
        }
        if (!covered) {
            // A glass sleeve is open on the block face so the next cable continues it; an item has no next cable,
            // so its ends get the opaque end cell a covered sleeve uses instead of showing a hollow tube.
            float lo = (float) (8 - ConduitShapes.width(type.thickness()) / 2);
            float hi = 16 - lo;
            builder.element(element -> element.from(0, lo, lo).to(lo, hi, hi).face(Direction.WEST,
                    f -> f.texture(END).uvs(0, 0, 4, 4)));
            builder.element(element -> element.from(hi, lo, lo).to(16, hi, hi).face(Direction.EAST,
                    f -> f.texture(END).uvs(0, 0, 4, 4)));
        }

        int level = type.level();
        TextureMapping textures = new TextureMapping();
        if (covered) {
            textures.put(SLEEVE, sprite("covered")).put(CORE, sprite("covered_core_tier_" + level))
                    .put(END, sprite("covered_core")).put(TextureSlot.PARTICLE, sprite("covered"));
        } else {
            textures.put(SLEEVE, sprite("sleeve")).put(CORE, sprite("sleeve_core")).put(END, sprite("sleeve_core"))
                    .put(LANE, sprite(type.look() == CableLook.FIBER ? "fiber_lane" : "lane"))
                    .put(LANE_CORE, sprite("lane_core_tier_" + level)).put(TextureSlot.PARTICLE, sprite("sleeve"));
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(entry.item().get()).withPrefix("item/");
        builder.build().create(id, textures, itemModels.modelOutput);
    }

    private static TextureSlot slot(CableGeometry.Texture texture) {
        return switch (texture) {
            case SLEEVE -> SLEEVE;
            case SLEEVE_CORE -> CORE;
            case SLEEVE_END -> END;
            case LANE -> LANE;
            case LANE_CORE -> LANE_CORE;
        };
    }

    private static Material sprite(String name) {
        return new Material(MaxtechStorage.id("block/cable/" + name));
    }
}
