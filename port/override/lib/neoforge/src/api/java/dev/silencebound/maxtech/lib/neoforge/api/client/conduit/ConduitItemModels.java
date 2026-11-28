// port-override: base=1e2d93f78255b7b5
package dev.silencebound.maxtech.lib.neoforge.api.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.client.model.generators.ModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import org.jetbrains.annotations.ApiStatus;

/**
 * Item models for an addon's conduits, written by the addon's own datagen: the addon ships each conduit's item model
 * {@code <modid>:item/<name>} ({@code docs/api/conduit-addon.md}).
 * <p>
 * {@link #shell} writes a {@link ConduitLook.Shell} conduit's item as a block-style model of a straight east-west piece
 * ({@link ConduitParts#itemPiece}): the glass, frame bars and tier stripes the block model draws, with the same
 * sprites, the border and stripe colours baked into the faces, both ends closed as an open end is. A static element
 * model, so it costs what any block item costs. Datagen only (client data run).
 * <p>
 * Experimental (Lib 0.15.0, for Farms). 1.21.1 port: takes the addon's {@link ItemModelProvider}; 26.1 takes its
 * {@code ItemModelGenerators}.
 */
@ApiStatus.Experimental
public final class ConduitItemModels {
    private static final int WHITE = 0xFFFFFFFF;
    private static final ModelBuilder.FaceRotation[] TURNS = {ModelBuilder.FaceRotation.ZERO,
            ModelBuilder.FaceRotation.CLOCKWISE_90, ModelBuilder.FaceRotation.UPSIDE_DOWN,
            ModelBuilder.FaceRotation.COUNTERCLOCKWISE_90};

    private ConduitItemModels() {
    }

    /**
     * Writes {@code conduit}'s item model, {@code <modid>:item/<name>}, for {@code look} to {@code itemModels} and
     * returns its id. {@code look} should be the look the conduit was built with.
     *
     * @throws IllegalStateException
     *             when Lib has not set up its conduit parts (outside a game run)
     */
    public static ResourceLocation shell(ItemModelProvider itemModels, ConduitEntry<?> conduit,
            ConduitLook.Shell look) {
        ResourceLocation glass = look.shell();
        ResourceLocation bar = look.barTexture().orElse(null);
        ResourceLocation id = conduit.item().id().withPrefix("item/");
        ItemModelBuilder builder = itemModels.getBuilder(id.toString())
                .parent(new ModelFile.UncheckedModelFile(ResourceLocation.withDefaultNamespace("block/block")))
                .texture("particle", glass).texture("glass", glass).texture("flat", ConduitLook.FLAT);
        // The normal block angle, larger than a block so the thin piece fills the slot, as Storage's cables.
        builder.transforms().transform(ItemDisplayContext.GUI).rotation(30, 225, 0).scale(0.9F).end()
                .transform(ItemDisplayContext.GROUND).translation(0, 3, 0).scale(0.4F).end()
                .transform(ItemDisplayContext.FIXED).scale(0.75F).end();
        if (bar != null) {
            builder.texture("bar", bar);
        }
        for (ConduitQuad quad : ConduitParts.itemPiece(look, conduit.type().get())) {
            String slot = Objects.equals(quad.sprite(), glass)
                    ? "#glass"
                    : bar != null && Objects.equals(quad.sprite(), bar) ? "#bar" : "#flat";
            AABB box = quad.box();
            ConduitUv uv = quad.uv();
            ItemModelBuilder.ElementBuilder.FaceBuilder face = builder.element()
                    .from((float) box.minX, (float) box.minY, (float) box.minZ)
                    .to((float) box.maxX, (float) box.maxY, (float) box.maxZ).face(quad.facing()).texture(slot);
            if (uv != null) {
                face.uvs(uv.u0(), uv.v0(), uv.u1(), uv.v1());
                if (uv.quarterTurns() != 0) {
                    face.rotation(TURNS[uv.quarterTurns()]);
                }
            }
            if (quad.argb() != WHITE) {
                face.color(quad.argb());
            }
        }
        return id;
    }
}
