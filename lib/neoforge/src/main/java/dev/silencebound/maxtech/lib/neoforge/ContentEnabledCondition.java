package dev.silencebound.maxtech.lib.neoforge;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.content.ContentToggle;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The recipe condition {@code maxtech_lib:content_enabled} ({@code {"type": "maxtech_lib:content_enabled", "toggle":
 * "<toggle id>"}}): true while the {@link ContentToggle} is on or unknown. Datagen attaches it with
 * {@code output.withConditions(new ContentEnabledCondition(id))}. Also removes the items of disabled toggles from every
 * creative tab as it builds.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public record ContentEnabledCondition(Identifier toggle) implements ICondition {
    public static final MapCodec<ContentEnabledCondition> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(Identifier.CODEC.fieldOf("toggle").forGetter(ContentEnabledCondition::toggle))
                    .apply(instance, ContentEnabledCondition::new));
    public static final RegistryEntry<MapCodec<? extends ICondition>, MapCodec<ContentEnabledCondition>> TYPE = Platform
            .get().registry()
            .<MapCodec<? extends ICondition>>registrar(MaxtechLib.MOD_ID, NeoForgeRegistries.Keys.CONDITION_CODECS)
            .register("content_enabled", () -> CODEC);

    /** Loads the class, which registers the condition type; call during mod construction. */
    static void init() {
    }

    @Override
    public boolean test(IContext context) {
        return ContentToggle.isEnabled(toggle);
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }

    /**
     * Fires for every tab of every mod on each mod's bus (NeoForge posts it through {@code ModLoader.postEvent}), after
     * the tab's own generator ran; hiding here covers tabs that list the items directly.
     */
    @SubscribeEvent
    static void hideDisabled(BuildCreativeModeTabContentsEvent event) {
        Set<Item> hidden = ContentToggle.hiddenItems();
        if (!hidden.isEmpty()) {
            event.removeIf(stack -> hidden.contains(stack.getItem()),
                    CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
