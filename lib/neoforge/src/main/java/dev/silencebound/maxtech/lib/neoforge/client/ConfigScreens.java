package dev.silencebound.maxtech.lib.neoforge.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's config screen for a MaxTech mod (Mods list, "Config"): client, common and server files, the server file
 * only in singleplayer while the world is not open to LAN, as NeoForge allows. On top of NeoForge's screen, the top
 * sections of each file come in a given order (what players change most first), and every value's tooltip also shows
 * its default and, if it has one, the restart a change needs.
 */
public final class ConfigScreens {
    /** Tooltip line: the value's default. */
    public static final String DEFAULT_KEY = "maxtech_lib.config_screen.default";
    /** Tooltip line of a value marked {@code worldRestart}. */
    public static final String WORLD_RESTART_KEY = "maxtech_lib.config_screen.restart.world";
    /** Tooltip line of a value marked {@code gameRestart}. */
    public static final String GAME_RESTART_KEY = "maxtech_lib.config_screen.restart.game";
    // NeoForge's own keys for a section button (ConfigurationScreen keeps them private).
    private static final String SECTION_KEY = "neoforge.configuration.uitext.section";
    private static final String SECTION_TEXT_KEY = "neoforge.configuration.uitext.sectiontext";
    private static final Component NEW_LINE = Component.literal("\n");

    private ConfigScreens() {
    }

    /**
     * Registers the config screen of {@code container}'s mod. Call from the mod's client entrypoint during mod
     * construction.
     *
     * @param sectionOrder
     *            top-level section names in the order they should appear; values at the top come first, unlisted
     *            sections follow in declaration order
     */
    public static void register(ModContainer container, List<String> sectionOrder) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, parent) -> new ConfigurationScreen(mod, parent,
                        (screen, type, config, title) -> new SectionScreen(
                                ordered(ConfigurationScreen.ConfigurationSectionScreen.Context.top(config.getModId(),
                                        screen, config, (context, key, element) -> element), sectionOrder),
                                title)));
    }

    /** {@code top} with its entries sorted: values first, then {@code order}, then the rest as declared. */
    private static ConfigurationScreen.ConfigurationSectionScreen.Context ordered(
            ConfigurationScreen.ConfigurationSectionScreen.Context top, List<String> order) {
        List<UnmodifiableConfig.Entry> values = new ArrayList<>();
        List<UnmodifiableConfig.Entry> sections = new ArrayList<>();
        for (UnmodifiableConfig.Entry entry : top.entries()) {
            (entry.getRawValue() instanceof UnmodifiableConfig ? sections : values).add(entry);
        }
        sections.sort((a, b) -> Integer.compare(rank(order, a.getKey()), rank(order, b.getKey())));
        Set<UnmodifiableConfig.Entry> entries = new LinkedHashSet<>(values);
        entries.addAll(sections);
        return new ConfigurationScreen.ConfigurationSectionScreen.Context(top.modId(), top.parent(), top.modConfig(),
                top.modSpec(), entries, top.valueSpecs(), top.keylist(), top.filter());
    }

    private static int rank(List<String> order, String key) {
        int index = order.indexOf(key);
        return index < 0 ? order.size() : index;
    }

    /** NeoForge's section screen with the default and restart lines; its sub-sections use it too. */
    private static final class SectionScreen extends ConfigurationScreen.ConfigurationSectionScreen {
        SectionScreen(Context context, Component title) {
            super(context, title);
        }

        SectionScreen(Context parentContext, Screen parent, Map<String, Object> valueSpecs, String key,
                Set<? extends UnmodifiableConfig.Entry> entrySet, Component title) {
            super(parentContext, parent, valueSpecs, key, entrySet, title);
        }

        @Override
        protected Component getTooltipComponent(String key, ModConfigSpec.@Nullable Range<?> range) {
            Component base = super.getTooltipComponent(key, range);
            ModConfigSpec.ValueSpec spec = getValueSpec(key);
            if (spec == null) {
                return base;
            }
            MutableComponent tooltip = Component.empty().append(base);
            Object fallback = spec.getDefault();
            Component shown = fallback instanceof Boolean flag
                    ? CommonComponents.optionStatus(flag)
                    : Component.literal(String.valueOf(fallback));
            tooltip.append(NEW_LINE).append(Component.translatable(DEFAULT_KEY, shown).withStyle(ChatFormatting.GRAY));
            switch (spec.restartType()) {
                case WORLD -> tooltip.append(NEW_LINE)
                        .append(Component.translatable(WORLD_RESTART_KEY).withStyle(ChatFormatting.GOLD));
                case GAME -> tooltip.append(NEW_LINE)
                        .append(Component.translatable(GAME_RESTART_KEY).withStyle(ChatFormatting.GOLD));
                case NONE -> {
                }
            }
            return tooltip;
        }

        @Override
        protected @Nullable Element createSection(String key, UnmodifiableConfig subconfig,
                UnmodifiableConfig subsection) {
            if (subconfig.isEmpty()) {
                return null;
            }
            String buttonKey = getTranslationKey(key) + ".button";
            Component tooltip = getTooltipComponent(key, null);
            return new Element(
                    Component.translatable(SECTION_KEY, getTranslationComponent(key)), tooltip, Button
                            .builder(
                                    Component.translatable(
                                            SECTION_KEY,
                                            Component.translatable(
                                                    I18n.exists(buttonKey) ? buttonKey : SECTION_TEXT_KEY)),
                                    button -> Minecraft.getInstance()
                                            .setScreen(sectionCache.computeIfAbsent(key,
                                                    k -> new SectionScreen(context, this, subconfig.valueMap(), key,
                                                            subsection.entrySet(),
                                                            Component.translatable(getTranslationKey(key))).rebuild())))
                            .tooltip(Tooltip.create(tooltip)).width(Button.DEFAULT_WIDTH).build(),
                    false);
        }
    }
}
