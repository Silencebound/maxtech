package dev.silencebound.maxtech.lib.api.recipe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

class ChancesTest {
    @Test
    void chanceMustBeWithinOneAndAlways() {
        assertThrows(IllegalArgumentException.class, () -> Chances.check(0));
        assertThrows(IllegalArgumentException.class, () -> Chances.check(ItemOutput.ALWAYS + 1));
        assertDoesNotThrow(() -> Chances.check(1));
        assertDoesNotThrow(() -> Chances.check(ItemOutput.ALWAYS));
    }

    @Test
    void alwaysMakesEveryCopy() {
        RandomSource random = RandomSource.create(1);
        for (int run = 0; run < 100; run++) {
            assertEquals(4, Chances.copies(4, ItemOutput.ALWAYS, random));
        }
    }

    @Test
    void eachCopyRollsOnItsOwn() {
        RandomSource random = RandomSource.create(7);
        int made = 0;
        boolean partial = false;
        for (int run = 0; run < 2000; run++) {
            int copies = Chances.copies(2, 5000, random);
            assertTrue(copies >= 0 && copies <= 2);
            partial |= copies == 1;
            made += copies;
        }
        // Half of 4000 copies, within a wide margin; a run with one copy shows they roll apart.
        assertTrue(made > 1800 && made < 2200, "made " + made);
        assertTrue(partial);
        int rare = 0;
        for (int run = 0; run < 2000; run++) {
            rare += Chances.copies(1, 1, random);
        }
        assertTrue(rare < 10, "a 0.01 % chance came up " + rare + " times");
    }
}
