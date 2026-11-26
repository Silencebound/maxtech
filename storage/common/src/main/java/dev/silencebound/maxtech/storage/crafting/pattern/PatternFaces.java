package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Which face of a known machine each pattern input goes into and where its outputs come out (M7 C12, C23): vanilla's
 * furnace, smoker and blast furnace take the item on top and fuel from the side and give results at the bottom; the
 * brewing stand takes the ingredient on top and bottles and blaze powder from the side; Storage's own machines take
 * everything on any face. Looked up by recipe type when a pattern is encoded and by block when a provider dispatches.
 * Inputs a rule leaves open, and every input for an unknown machine, go into the face the provider touches. Pure; any
 * thread.
 */
public final class PatternFaces {
    /** JEI's brewing page id; brewing has no vanilla recipe type. */
    public static final Identifier BREWING = Identifier.withDefaultNamespace("brewing");

    /** The faces of one machine kind. */
    public enum Rule {
        /** Item on top, fuel (every further input) on the side, results at the bottom. */
        FURNACE(Direction.DOWN),
        /** Ingredient on top, bottles and blaze powder on the side, results at the bottom. */
        BREWING_STAND(Direction.DOWN),
        /** Any face for inputs and outputs, as Storage's machines take them. */
        ANY_FACE(null);

        private final @Nullable Direction output;

        Rule(@Nullable Direction output) {
            this.output = output;
        }

        /** The face input {@code index} goes into; empty means the provider's own face. */
        public Optional<Direction> inputFace(int index, PatternIngredient ingredient) {
            return switch (this) {
                case FURNACE -> Optional.of(index == 0 ? Direction.UP : Direction.NORTH);
                case BREWING_STAND -> Optional.of(sideInput(ingredient) ? Direction.NORTH : Direction.UP);
                case ANY_FACE -> Optional.empty();
            };
        }

        /** The face outputs are pulled from; empty means the provider's own face. */
        public Optional<Direction> outputFace() {
            return Optional.ofNullable(output);
        }
    }

    private PatternFaces() {
    }

    /** The rule for patterns from recipe type {@code recipeType}, or null when it is not a known machine's. */
    public static @Nullable Rule forRecipeType(Identifier recipeType) {
        if (recipeType.getNamespace().equals(MaxtechStorage.MOD_ID)) {
            return Rule.ANY_FACE;
        }
        if (!recipeType.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return null;
        }
        return switch (recipeType.getPath()) {
            case "smelting", "smoking", "blasting" -> Rule.FURNACE;
            case "brewing" -> Rule.BREWING_STAND;
            default -> null;
        };
    }

    /** The rule for machine block {@code block}, or null when it is not a known machine. */
    public static @Nullable Rule forBlock(Block block) {
        if (block.equals(Blocks.FURNACE) || block.equals(Blocks.SMOKER) || block.equals(Blocks.BLAST_FURNACE)) {
            return Rule.FURNACE;
        }
        if (block.equals(Blocks.BREWING_STAND)) {
            return Rule.BREWING_STAND;
        }
        return BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(MaxtechStorage.MOD_ID)
                ? Rule.ANY_FACE
                : null;
    }

    /**
     * {@code pattern} with every input that has no face yet given the face {@code rule} picks; faces the player set
     * stay. Returns the pattern itself when nothing changes.
     */
    public static CraftingPattern fill(CraftingPattern pattern, @Nullable Rule rule) {
        if (rule == null || pattern.kind() != CraftingPattern.Kind.PROCESSING) {
            return pattern;
        }
        List<PatternInput> inputs = new ArrayList<>(pattern.inputs().size());
        boolean changed = false;
        for (int i = 0; i < pattern.inputs().size(); i++) {
            PatternInput input = pattern.inputs().get(i);
            Optional<Direction> face = input.face().isPresent() ? input.face() : rule.inputFace(i, input.ingredient());
            changed |= !face.equals(input.face());
            inputs.add(new PatternInput(input.ingredient(), input.amount(), face, input.returned()));
        }
        return changed
                ? new CraftingPattern(pattern.kind(), inputs, pattern.outputs(), pattern.recipeType(), pattern.recipe())
                : pattern;
    }

    private static boolean sideInput(PatternIngredient ingredient) {
        if (ingredient instanceof PatternIngredient.Exact(ItemKey key)) {
            Item item = key.item();
            return item instanceof PotionItem || item.equals(Items.GLASS_BOTTLE) || item.equals(Items.BLAZE_POWDER);
        }
        return false;
    }
}
