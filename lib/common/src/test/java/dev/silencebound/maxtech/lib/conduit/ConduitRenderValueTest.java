package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;

/**
 * The render value in a conduit's saved data and update tag: written only when non-zero, so conduits that never set one
 * save and sync exactly as before it existed.
 */
class ConduitRenderValueTest {
    @Test
    void nonZeroValuesRoundTrip() {
        for (int value : new int[]{1, -1, 0x3366CC, Integer.MIN_VALUE}) {
            CompoundTag tag = write(value);
            assertEquals(value, tag.getIntOr("render_value", 0), "saved key");
            assertEquals(value, read(tag));
        }
    }

    @Test
    void zeroWritesNoKey() {
        CompoundTag tag = write(0);
        assertFalse(tag.contains("render_value"), "render_value written for 0");
        assertEquals(0, tag.size(), "anything written for 0");
    }

    @Test
    void dataWithoutTheKeyLoadsAsZero() {
        assertEquals(0, read(new CompoundTag()));
        assertEquals(0, read(write(0)));
    }

    private static CompoundTag write(int value) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        ConduitRenderValue.write(output, value);
        return output.buildResult();
    }

    private static int read(CompoundTag tag) {
        HolderLookup.Provider lookup = HolderLookup.Provider.create(Stream.empty());
        return ConduitRenderValue.read(TagValueInput.create(ProblemReporter.DISCARDING, lookup, tag));
    }
}
