package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternReturns;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Matches the resolved inputs a job hands over (key to total amount for all crafts) to a pattern's inputs, in order:
 * each input takes the first key that satisfies its ingredient and still has enough left. Called per push, never per
 * tick. Pure; any thread.
 */
public final class InputAssignment {
    private InputAssignment() {
    }

    /**
     * How much of input {@code index} of {@code pattern} {@code crafts} crafts need: once for an input that comes back
     * ({@link PatternReturns}).
     */
    public static long need(CraftingPattern pattern, int index, int crafts) {
        PatternInput input = pattern.inputs().get(index);
        return PatternReturns.returned(pattern, index) ? input.amount() : input.amount() * crafts;
    }

    /**
     * The key for each input of {@code pattern} for {@code crafts} crafts, or null when {@code inputs} cannot cover
     * them all.
     */
    public static StackKey @Nullable [] assign(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs) {
        Object2LongOpenHashMap<StackKey> left = new Object2LongOpenHashMap<>(inputs);
        StackKey[] keys = new StackKey[pattern.inputs().size()];
        for (int i = 0; i < keys.length; i++) {
            PatternInput input = pattern.inputs().get(i);
            long need = need(pattern, i, crafts);
            StackKey found = null;
            if (input.ingredient() instanceof PatternIngredient.Exact(StackKey key)) {
                found = left.getLong(key) >= need ? key : null;
            } else {
                for (Object2LongMap.Entry<StackKey> entry : left.object2LongEntrySet()) {
                    if (entry.getLongValue() >= need && input.ingredient().matches(entry.getKey())) {
                        found = entry.getKey();
                        break;
                    }
                }
            }
            if (found == null) {
                return null;
            }
            left.addTo(found, -need);
            keys[i] = found;
        }
        return keys;
    }

    /** The share of {@code inputs} that {@code crafts} crafts of {@code pattern} take, given {@code keys}. */
    public static Object2LongOpenHashMap<StackKey> share(CraftingPattern pattern, int crafts, StackKey[] keys) {
        Object2LongOpenHashMap<StackKey> share = new Object2LongOpenHashMap<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            share.addTo(keys[i], need(pattern, i, crafts));
        }
        return share;
    }
}
