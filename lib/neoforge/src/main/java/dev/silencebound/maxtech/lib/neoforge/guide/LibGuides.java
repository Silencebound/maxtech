package dev.silencebound.maxtech.lib.neoforge.guide;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.guide.GuideBook;
import dev.silencebound.maxtech.lib.neoforge.api.guide.Guides;
import guideme.Guide;
import guideme.GuideBuilder;
import guideme.GuidesCommon;
import guideme.compiler.TagCompiler;
import guideme.siteexport.ExportPostProcessor;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The GuideME side of {@link Guides}: declares each registered guide with MaxTech's tags and the website export's
 * rewrite of them ({@link GuideWebExport}), and opens a {@link GuideBook}'s guide on the using client; the server only
 * sees the use succeed. Client only.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
public final class LibGuides {
    private LibGuides() {
    }

    /** Makes {@link Guides#register} declare guides; from Lib's client constructor, before any addon's. */
    public static void install() {
        Guides.declareWith(LibGuides::declare);
    }

    /**
     * Declares the guide {@code id}, during mod construction, so GuideME loads its pages on the first resource reload.
     * GuideME reads the hot reload property {@code guideme.<ns>.<path>.sources} here.
     */
    static Guide declare(Identifier id) {
        GuideBuilder builder = Guide.builder(id);
        for (TagCompiler tag : tagCompilers()) {
            builder.extension(TagCompiler.EXTENSION_POINT, tag);
        }
        builder.extension(ExportPostProcessor.EXTENSION_POINT,
                new GuideWebExport(id.getNamespace(), LibGuides::defaultKeyName));
        return builder.build();
    }

    /** The name of a key mapping's default key, for the website (which knows no player's bindings). */
    private static Optional<String> defaultKeyName(String id) {
        KeyMapping mapping = KeyMapping.get(id);
        return mapping == null ? Optional.empty() : Optional.of(mapping.getDefaultKey().getDisplayName().getString());
    }

    /**
     * MaxTech's own tags on top of GuideME's: {@code <Config>}. A new list on each call, since GuideME refuses one
     * instance twice in a guide; a mod's page check builds its own.
     */
    public static List<TagCompiler> tagCompilers() {
        return List.of(new ConfigTagCompiler());
    }

    @SubscribeEvent
    static void openBook(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide() && event.getItemStack().getItem() instanceof GuideBook book) {
            GuidesCommon.openGuide(event.getEntity(), book.guide());
        }
    }
}
