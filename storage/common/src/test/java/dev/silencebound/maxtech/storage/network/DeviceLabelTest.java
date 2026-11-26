package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

/** Label text cleaning and the case-insensitive lookup key ({@link DeviceLabel}). */
class DeviceLabelTest {
    @Test
    void trimsAndKeepsCaseForDisplay() {
        DeviceLabel label = DeviceLabel.of("  Iron Smelters ", DyeColor.ORANGE);
        assertEquals("Iron Smelters", label.text());
        assertEquals("iron smelters", label.key());
        assertEquals(DyeColor.ORANGE, label.colour());
    }

    @Test
    void blankTextIsNoLabelWhateverTheColour() {
        assertSame(DeviceLabel.NONE, DeviceLabel.of("   ", DyeColor.RED));
        assertSame(DeviceLabel.NONE, DeviceLabel.of("", null));
        assertNull(DeviceLabel.NONE.colour());
    }

    @Test
    void dropsControlAndFormattingCharacters() {
        assertEquals("credtext", DeviceLabel.clean("§cred\ttext\n"));
    }

    @Test
    void cutsToTheLimitWithoutSplittingAPair() {
        String text = "a".repeat(DeviceLabel.MAX_LENGTH + 10);
        assertEquals(DeviceLabel.MAX_LENGTH, DeviceLabel.clean(text).length());
        // A surrogate pair straddling the limit is dropped whole.
        String pair = "a".repeat(DeviceLabel.MAX_LENGTH - 1) + "😀";
        assertEquals("a".repeat(DeviceLabel.MAX_LENGTH - 1), DeviceLabel.clean(pair));
    }

    @Test
    void keyOfTypedTextMatchesTheLabelsKey() {
        assertEquals(DeviceLabel.of("Smelters", null).key(), DeviceLabel.key(" SMELTERS "));
    }
}
