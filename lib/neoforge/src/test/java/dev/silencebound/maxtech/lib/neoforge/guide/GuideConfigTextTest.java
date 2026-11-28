package dev.silencebound.maxtech.lib.neoforge.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@code <Config>} tag's key parsing, lookup and formatting, without a game. {@code GuideConfigSourceTest} covers
 * how a preset config's source reads its values.
 */
class GuideConfigTextTest {
    @Test
    void numbersAreGroupedAndFollowedByTheUnit() {
        assertEquals("140,000 MU", GuideConfigText.text(140_000L, "MU").getString());
        assertEquals("64 channels", GuideConfigText.text(64, "channels").getString());
        assertEquals("1,024", GuideConfigText.text(1024, "").getString());
        assertEquals("-2,500", GuideConfigText.text(-2500, "").getString());
        assertEquals("0.25 %", GuideConfigText.text(0.25, "%").getString());
        assertEquals("2", GuideConfigText.text(2.0, "").getString());
        assertEquals("12,000", GuideConfigText.text(12_000.0, "").getString());
        assertEquals("ALWAYS", GuideConfigText.text("ALWAYS", "").getString());
    }

    @Test
    void booleansUseLangKeys() {
        assertEquals(GuideConfigText.ON_KEY, GuideConfigText.text(true, "").getString());
        assertEquals(GuideConfigText.OFF_KEY, GuideConfigText.text(false, "").getString());
    }

    @Test
    void aKeyWithoutNamespaceNamesThePagesMod() {
        assertEquals(Optional.of(new GuideConfigText.Ref("maxtech_storage", "network.channels.adHoc")),
                GuideConfigText.ref("network.channels.adHoc", "maxtech_storage"));
        assertEquals(Optional.of(new GuideConfigText.Ref("maxtech_storage", "network.channels.adHoc")),
                GuideConfigText.ref("maxtech_storage:network.channels.adHoc", "maxtech_farms"));
        assertEquals(Optional.of(new GuideConfigText.Ref("maxtech_lib", "energy.feRate")),
                GuideConfigText.ref("maxtech_lib:energy.feRate", "maxtech_storage"));
        assertEquals(Optional.empty(), GuideConfigText.ref("", "maxtech_storage"));
        assertEquals(Optional.empty(), GuideConfigText.ref("maxtech_storage:", "maxtech_storage"));
        assertEquals(Optional.empty(), GuideConfigText.ref(":a.b", "maxtech_storage"));
        assertEquals(Optional.empty(), GuideConfigText.ref("a:b:c", "maxtech_storage"));
    }

    @Test
    void valuesComeFromTheModsSource() {
        Map<String, GuideConfigValues.Value> values = Map.of("crops.growth", new GuideConfigValues.Value(3, 2));
        GuideConfigValues.register("guide_config_text_test", new GuideConfigValues.Source() {
            @Override
            public Set<String> keys() {
                return values.keySet();
            }

            @Override
            public Optional<GuideConfigValues.Value> value(String key) {
                return Optional.ofNullable(values.get(key));
            }
        });
        assertEquals(Optional.of(new GuideConfigValues.Value(3, 2)),
                GuideConfigText.value("crops.growth", "guide_config_text_test"));
        assertEquals(Optional.of(new GuideConfigValues.Value(3, 2)),
                GuideConfigText.value("guide_config_text_test:crops.growth", "maxtech_storage"));
        assertEquals(Optional.empty(), GuideConfigText.value("crops.nope", "guide_config_text_test"));
        assertFalse(GuideConfigText.value("crops.growth", "guide_config_text_unregistered").isPresent());
    }
}
