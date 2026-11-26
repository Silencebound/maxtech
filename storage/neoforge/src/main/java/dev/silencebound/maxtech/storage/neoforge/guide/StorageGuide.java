package dev.silencebound.maxtech.storage.neoforge.guide;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.guide.GuideContent;
import dev.silencebound.maxtech.storage.guide.StorageManualItem;
import guideme.Guide;
import guideme.GuideBuilder;
import guideme.GuidesCommon;
import guideme.compiler.TagCompiler;
import guideme.siteexport.ExportPostProcessor;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Storage's guide on GuideME. The pages under {@code assets/maxtech_storage/guides/maxtech_storage/guide/} are
 * GuideME's default folder for {@link GuideContent#GUIDE}; GuideME's hold key opens a page from an item's tooltip
 * through the pages' {@code item_ids}. The manual opens the guide on the using client; the server only sees the use
 * succeed.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
public final class StorageGuide {
    private StorageGuide() {
    }

    /**
     * Declares the guide with Storage's tags and the website export's rewrite of them ({@link GuideWebExport}). Client
     * only, during mod construction, so GuideME loads the pages on the first resource reload. GuideME reads the hot
     * reload property {@code guideme.maxtech_storage.guide.sources} here.
     */
    public static Guide declare() {
        GuideBuilder builder = Guide.builder(GuideContent.GUIDE);
        for (TagCompiler tag : tagCompilers()) {
            builder.extension(TagCompiler.EXTENSION_POINT, tag);
        }
        builder.extension(ExportPostProcessor.EXTENSION_POINT, new GuideWebExport(StorageGuide::defaultKeyName));
        return builder.build();
    }

    /** The name of a key mapping's default key, for the website (which knows no player's bindings). */
    private static Optional<String> defaultKeyName(String id) {
        KeyMapping mapping = KeyMapping.get(id);
        return mapping == null ? Optional.empty() : Optional.of(mapping.getDefaultKey().getDisplayName().getString());
    }

    /**
     * Storage's own tags on top of GuideME's: {@code <Config>}. A new list on each call, since GuideME refuses one
     * instance twice in a guide; the page check builds its own.
     */
    public static List<TagCompiler> tagCompilers() {
        return List.of(new StorageConfigTagCompiler());
    }

    @SubscribeEvent
    static void openManual(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide() && event.getItemStack().getItem() instanceof StorageManualItem manual) {
            GuidesCommon.openGuide(event.getEntity(), manual.guide());
        }
    }
}
