package dev.silencebound.maxtech.lib.neoforge.api.client.conduit;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.Objects;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;
import org.jetbrains.annotations.ApiStatus;

/**
 * Item models for an addon's conduits, written by the addon's own datagen: Lib serves each addon conduit's item model
 * definition, which names {@code <modid>:item/<name>}, and the addon ships that model
 * ({@code docs/api/conduit-addon.md}).
 * <p>
 * {@link #shell} writes a {@link ConduitLook.Shell} conduit's item as a block-style model of a straight east-west piece
 * ({@link ConduitParts#itemPiece}): the glass, frame bars and tier stripes the block model draws, with the same
 * sprites, the border and stripe colours baked into the faces, both ends closed as an open end is. A static element
 * model, so it costs what any block item costs. Datagen only (client data run).
 * <p>
 * Experimental (Lib 0.15.0, for Farms).
 */
@ApiStatus.Experimental
public final class ConduitItemModels {
    private static final int WHITE = 0xFFFFFFFF;
    private static final TextureSlot GLASS = TextureSlot.create("glass");
    private static final TextureSlot FLAT = TextureSlot.create("flat");
    private static final TextureSlot BAR = TextureSlot.create("bar");

    private ConduitItemModels() {
    }

    /**
     * Writes {@code conduit}'s item model, {@code <modid>:item/<name>}, for {@code look} to {@code itemModels}'s model
     * output and returns its id; the caller names it in the item model definition it hands the model provider (e.g.
     * {@code ItemModelUtils.plainModel(id)}). {@code look} should be the look the conduit was built with.
     *
     * @throws IllegalStateException
     *             when Lib has not set up its conduit parts (outside a game run)
     */
    public static Identifier shell(ItemModelGenerators itemModels, ConduitEntry<?> conduit, ConduitLook.Shell look) {
        Identifier glass = look.shell();
        Identifier bar = look.barTexture().orElse(null);
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(TextureSlot.PARTICLE)
                .requiredTextureSlot(GLASS).requiredTextureSlot(FLAT)
                // The normal block angle, larger than a block so the thin piece fills the slot, as Storage's cables.
                .transform(ItemDisplayContext.GUI, t -> t.rotation(30, 225, 0).scale(0.9F))
                .transform(ItemDisplayContext.GROUND, t -> t.translation(0, 3, 0).scale(0.4F))
                .transform(ItemDisplayContext.FIXED, t -> t.scale(0.75F));
        if (bar != null) {
            builder.requiredTextureSlot(BAR);
        }
        for (ConduitQuad quad : ConduitParts.itemPiece(look, conduit.type().get())) {
            TextureSlot slot = Objects.equals(quad.sprite(), glass)
                    ? GLASS
                    : bar != null && Objects.equals(quad.sprite(), bar) ? BAR : FLAT;
            AABB box = quad.box();
            ConduitUv uv = quad.uv();
            builder.element(element -> element.from((float) box.minX, (float) box.minY, (float) box.minZ)
                    .to((float) box.maxX, (float) box.maxY, (float) box.maxZ).face(quad.facing(), f -> {
                        f.texture(slot);
                        if (uv != null) {
                            f.uvs(uv.u0(), uv.v0(), uv.u1(), uv.v1());
                            if (uv.quarterTurns() != 0) {
                                f.rotation(turn(uv.quarterTurns()));
                            }
                        }
                        if (quad.argb() != WHITE) {
                            f.color(quad.argb());
                        }
                    }));
        }
        TextureMapping textures = new TextureMapping().put(GLASS, new Material(glass))
                .put(FLAT, new Material(ConduitLook.FLAT)).put(TextureSlot.PARTICLE, new Material(glass));
        if (bar != null) {
            textures.put(BAR, new Material(bar));
        }
        Identifier id = conduit.item().id().withPrefix("item/");
        builder.build().create(id, textures, itemModels.modelOutput);
        return id;
    }

    private static Quadrant turn(int quarterTurns) {
        return switch (quarterTurns) {
            case 1 -> Quadrant.R90;
            case 2 -> Quadrant.R180;
            case 3 -> Quadrant.R270;
            default -> Quadrant.R0;
        };
    }
}
