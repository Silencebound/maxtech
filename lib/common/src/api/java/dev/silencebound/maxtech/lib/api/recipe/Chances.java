package dev.silencebound.maxtech.lib.api.recipe;

import net.minecraft.util.RandomSource;

/** Chance rolls of item outputs, apart from {@link ItemOutput} so unit tests reach them without items. */
final class Chances {
    private Chances() {
    }

    /** Throws {@link IllegalArgumentException} if {@code chance} is outside 1..{@link ItemOutput#ALWAYS}. */
    static void check(int chance) {
        if (chance < 1 || chance > ItemOutput.ALWAYS) {
            throw new IllegalArgumentException("Output chance must be in 1.." + ItemOutput.ALWAYS + ", was " + chance);
        }
    }

    /** How many of {@code count} copies come out, each rolled on its own at {@code chance}; all of them for always. */
    static int copies(int count, int chance, RandomSource random) {
        if (chance >= ItemOutput.ALWAYS) {
            return count;
        }
        int made = 0;
        for (int i = 0; i < count; i++) {
            if (random.nextInt(ItemOutput.ALWAYS) < chance) {
                made++;
            }
        }
        return made;
    }
}
