package dev.silencebound.maxtech.lib.api.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MachineRecipeShapeTest {
    /** Two inputs, one catalyst, two outputs, one tank each way, two modes. */
    private final MachineRecipeShape shape = MachineRecipeShape.items(2, 2).withCatalysts(1).withTanks(1, 1)
            .withModes("fine", "coarse");

    @Test
    void recipeWithinEveryBoundFits() {
        assertEquals(Optional.empty(), shape.check(2, 1, 2, 1, 1, Optional.of("fine")));
        assertEquals(Optional.empty(), shape.check(0, 0, 0, 1, 1, Optional.of("coarse")));
    }

    @Test
    void eachSideRefusesOneTooMany() {
        assertTrue(shape.check(3, 0, 1, 0, 0, Optional.of("fine")).isPresent(), "inputs");
        assertTrue(shape.check(1, 2, 1, 0, 0, Optional.of("fine")).isPresent(), "catalysts");
        assertTrue(shape.check(1, 0, 3, 0, 0, Optional.of("fine")).isPresent(), "outputs");
        assertTrue(shape.check(1, 0, 1, 2, 0, Optional.of("fine")).isPresent(), "tank inputs");
        assertTrue(shape.check(1, 0, 1, 0, 2, Optional.of("fine")).isPresent(), "tank outputs");
    }

    @Test
    void recipeNeedsAnInputAndAnOutput() {
        assertEquals(Optional.of("Recipe has no inputs"), shape.check(0, 0, 1, 0, 0, Optional.of("fine")));
        assertEquals(Optional.of("Recipe has no outputs"), shape.check(1, 0, 0, 0, 0, Optional.of("fine")));
        // A catalyst alone is an input.
        assertEquals(Optional.empty(), shape.check(0, 1, 1, 0, 0, Optional.of("fine")));
    }

    @Test
    void modesMustMatchTheMachine() {
        assertTrue(shape.check(1, 0, 1, 0, 0, Optional.empty()).isPresent(), "missing mode");
        assertTrue(shape.check(1, 0, 1, 0, 0, Optional.of("rough")).isPresent(), "unknown mode");
        MachineRecipeShape modeless = MachineRecipeShape.items(1, 1);
        assertEquals(Optional.empty(), modeless.check(1, 0, 1, 0, 0, Optional.empty()));
        assertTrue(modeless.check(1, 0, 1, 0, 0, Optional.of("fine")).isPresent(), "mode on a modeless machine");
    }

    @Test
    void shapeRefusesNegativeCountsAndBadModes() {
        assertThrows(IllegalArgumentException.class, () -> MachineRecipeShape.items(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> MachineRecipeShape.items(1, 1).withTanks(0, -1));
        assertThrows(IllegalArgumentException.class, () -> MachineRecipeShape.items(1, 1).withModes("a", "a"));
        assertThrows(IllegalArgumentException.class, () -> MachineRecipeShape.items(1, 1).withModes(" "));
        assertEquals(List.of("fine", "coarse"), shape.modes());
    }
}
